"""
ScreenGuard Local OCR Engine
Uses Windows 10/11 built-in hardware-accelerated Windows.Media.Ocr via winocr.
Completely offline, fast, and lightweight.
"""

import sys
import threading
from typing import Optional, List, Dict, Any
from PIL import Image

try:
    import winocr
    HAS_WINOCR = True
except ImportError:
    HAS_WINOCR = False


class OCRResult:
    """Standardized OCR Result structure."""
    def __init__(self, full_text: str, words: List[Dict[str, Any]]):
        self.full_text = full_text
        self.words = words  # Each dict has 'text', 'rect', 'confidence'

    def __repr__(self) -> str:
        return f"<OCRResult words_count={len(self.words)} text='{self.full_text[:40]}...'>"


class OCREngine:
    """Local Windows OCR Engine."""

    def __init__(self, lang: str = "en"):
        self.lang = lang
        self._lock = threading.Lock()
        self._available = HAS_WINOCR

    @property
    def is_available(self) -> bool:
        return self._available

    def recognize(self, image: Image.Image) -> OCRResult:
        """
        Runs OCR on the given PIL Image.
        Returns OCRResult with text and word-level details.
        """
        if not self._available or image is None:
            return OCRResult(full_text="", words=[])

        with self._lock:
            try:
                # Use winocr synchronous PIL recognition
                res = winocr.recognize_pil_sync(image, self.lang)
                if not res:
                    return OCRResult(full_text="", words=[])

                full_text = res.get("text", "")
                words_list = []
                
                # Extract line and word level structures
                lines = res.get("lines", [])
                for line in lines:
                    for word_obj in line.get("words", []):
                        w_text = word_obj.get("text", "")
                        b_rect = word_obj.get("bounding_rect", {})
                        if w_text:
                            words_list.append({
                                "text": w_text,
                                "rect": (
                                    b_rect.get("x", 0),
                                    b_rect.get("y", 0),
                                    b_rect.get("width", 0),
                                    b_rect.get("height", 0)
                                ),
                                "confidence": 100  # Windows OCR returns high-quality confident matches
                            })

                return OCRResult(full_text=full_text, words=words_list)
            except Exception as e:
                # Handle possible WinRT or thread apartment exceptions
                return OCRResult(full_text="", words=[])
