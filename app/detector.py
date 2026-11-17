"""
ScreenGuard Word Detector
Handles fast text normalization, exact word boundary regex, phrase matching,
case-insensitivity, unicode support, and consecutive-frame confirmation logic.
"""

import re
import unicodedata
from typing import List, Set, Optional, Dict, Tuple


def normalize_text(text: str) -> str:
    """Normalize unicode and clean excess whitespace while preserving word contents."""
    if not text:
        return ""
    # Normalize unicode (NFKC)
    normalized = unicodedata.normalize("NFKC", text)
    # Collapse multiple whitespace to single space
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized


def compile_pattern(target: str, match_mode: str = "exact_word") -> re.Pattern:
    """
    Compiles a search pattern for a blocked word or phrase.
    - exact_word: uses word boundary checks to avoid false positives (e.g. 'cat' in 'education')
    - substring: matches any occurrence
    """
    target = normalize_text(target).strip()
    if not target:
        return re.compile(r"$^")  # Matches nothing

    # If it's a multi-word phrase, allow flexible whitespace between words
    escaped_parts = [re.escape(part) for part in target.split()]
    joined_phrase = r"\s+".join(escaped_parts)

    if match_mode == "exact_word":
        # Match with word boundaries, accounting for symbols/punctuation or string edges
        pattern_str = r"(?<!\w)" + joined_phrase + r"(?!\w)"
        return re.compile(pattern_str, re.IGNORECASE | re.UNICODE)
    else:
        # Substring match
        return re.compile(joined_phrase, re.IGNORECASE | re.UNICODE)


class Detector:
    """Detects blocked words in OCR text streams with stateful tracking for consecutive scan checks."""

    def __init__(self):
        self._pattern_cache: Dict[Tuple[str, str], re.Pattern] = {}
        self._consecutive_hits: Dict[str, int] = {}  # {app_key: hit_count}

    def _get_pattern(self, word: str, match_mode: str) -> re.Pattern:
        key = (word, match_mode)
        if key not in self._pattern_cache:
            self._pattern_cache[key] = compile_pattern(word, match_mode)
        return self._pattern_cache[key]

    def find_matches(
        self,
        full_text: str,
        blocked_words: List[str],
        match_mode: str = "exact_word"
    ) -> List[str]:
        """
        Scans normalized text against the blocked words list.
        Returns list of matched blocked words/phrases.
        """
        if not full_text or not blocked_words:
            return []

        clean_text = normalize_text(full_text)
        matches: List[str] = []

        for word in blocked_words:
            word_clean = word.strip()
            if not word_clean:
                continue

            pattern = self._get_pattern(word_clean, match_mode)
            if pattern.search(clean_text):
                matches.append(word_clean)

        return matches

    def check_consecutive_confirmation(
        self,
        app_key: str,
        is_detected: bool,
        required_consecutive: int = 2
    ) -> bool:
        """
        Checks if detection has occurred for required consecutive frames for the same application.
        If required_consecutive <= 1, returns is_detected immediately.
        """
        if required_consecutive <= 1:
            return is_detected

        if is_detected:
            count = self._consecutive_hits.get(app_key, 0) + 1
            self._consecutive_hits[app_key] = count
            if count >= required_consecutive:
                # Reset counter after triggering
                self._consecutive_hits[app_key] = 0
                return True
            return False
        else:
            # Reset if no detection on this frame
            if app_key in self._consecutive_hits:
                self._consecutive_hits[app_key] = 0
            return False

    def clear_state(self) -> None:
        """Resets consecutive hits state."""
        self._consecutive_hits.clear()
