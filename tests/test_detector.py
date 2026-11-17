"""
Unit tests for ScreenGuard Detector
"""

import pytest
from app.detector import Detector, normalize_text, compile_pattern


def test_exact_word_matching():
    detector = Detector()
    blocked = ["cat", "dog"]

    # Should match standalone words
    assert detector.find_matches("I have a cat.", blocked) == ["cat"]
    assert detector.find_matches("Look at that dog!", blocked) == ["dog"]
    assert set(detector.find_matches("Both cat and dog are here", blocked)) == {"cat", "dog"}

    # Should NOT match substring inside words
    assert detector.find_matches("The education system is fine", blocked) == []
    assert detector.find_matches("Concatenate string", blocked) == []
    assert detector.find_matches("Cathedral ceiling", blocked) == []
    assert detector.find_matches("Underdog story", blocked) == []


def test_case_insensitivity():
    detector = Detector()
    blocked = ["Secret_Token", "PASSWORD"]

    assert detector.find_matches("secret_token leaked", blocked) == ["Secret_Token"]
    assert detector.find_matches("SECRET_TOKEN leaked", blocked) == ["Secret_Token"]
    assert detector.find_matches("My password is 123", blocked) == ["PASSWORD"]


def test_phrase_matching():
    detector = Detector()
    blocked = ["confidential leak", "top secret document"]

    assert detector.find_matches("This is a confidential leak right here", blocked) == ["confidential leak"]
    assert detector.find_matches("This is a confidential\nleak right here", blocked) == ["confidential leak"]
    assert detector.find_matches("This is a confidential   leak", blocked) == ["confidential leak"]
    assert detector.find_matches("TOP SECRET DOCUMENT", blocked) == ["top secret document"]


def test_punctuation_and_symbols():
    detector = Detector()
    blocked = ["TEST_BLOCKED_WORD", "api_key", "danger"]

    assert detector.find_matches("Found TEST_BLOCKED_WORD in file.", blocked) == ["TEST_BLOCKED_WORD"]
    assert detector.find_matches("(danger) ahead!", blocked) == ["danger"]
    assert detector.find_matches("api_key = 'xyz';", blocked) == ["api_key"]
    assert detector.find_matches('"TEST_BLOCKED_WORD"', blocked) == ["TEST_BLOCKED_WORD"]


def test_unicode_normalization():
    detector = Detector()
    blocked = ["café", "über"]

    assert detector.find_matches("Welcome to the café today", blocked) == ["café"]
    assert detector.find_matches("Drive with über now", blocked) == ["über"]


def test_substring_mode():
    detector = Detector()
    blocked = ["cat"]

    # Substring mode enabled
    assert detector.find_matches("education", blocked, match_mode="substring") == ["cat"]
    assert detector.find_matches("concatenate", blocked, match_mode="substring") == ["cat"]


def test_consecutive_detection():
    detector = Detector()
    app_key = "chrome.exe:1234"

    # Require 2 consecutive frames
    assert detector.check_consecutive_confirmation(app_key, True, required_consecutive=2) is False
    assert detector.check_consecutive_confirmation(app_key, True, required_consecutive=2) is True

    # After trigger, resets
    assert detector.check_consecutive_confirmation(app_key, False, required_consecutive=2) is False
    assert detector.check_consecutive_confirmation(app_key, True, required_consecutive=2) is False
    assert detector.check_consecutive_confirmation(app_key, True, required_consecutive=2) is True
