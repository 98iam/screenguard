"""
Unit tests for ScreenGuard Config Manager
"""

import os
import tempfile
import pytest
from app.config import ConfigManager


def test_config_defaults():
    with tempfile.TemporaryDirectory() as tmpdir:
        cfg_path = os.path.join(tmpdir, "settings.json")
        cfg = ConfigManager(cfg_path)

        assert cfg.is_enabled is True
        assert cfg.scan_interval_ms == 400
        assert "TEST_BLOCKED_WORD" in cfg.blocked_words
        assert "ScreenGuard.exe" in cfg.whitelist


def test_add_remove_blocked_word():
    with tempfile.TemporaryDirectory() as tmpdir:
        cfg_path = os.path.join(tmpdir, "settings.json")
        cfg = ConfigManager(cfg_path)

        assert cfg.add_blocked_word("new_bad_word") is True
        assert "new_bad_word" in cfg.blocked_words

        # Duplicate should return False
        assert cfg.add_blocked_word("new_bad_word") is False

        # Remove
        assert cfg.remove_blocked_word("new_bad_word") is True
        assert "new_bad_word" not in cfg.blocked_words


def test_edit_blocked_word():
    with tempfile.TemporaryDirectory() as tmpdir:
        cfg_path = os.path.join(tmpdir, "settings.json")
        cfg = ConfigManager(cfg_path)

        cfg.add_blocked_word("typo_word")
        assert cfg.edit_blocked_word("typo_word", "fixed_word") is True
        assert "fixed_word" in cfg.blocked_words
        assert "typo_word" not in cfg.blocked_words


def test_whitelist_management():
    with tempfile.TemporaryDirectory() as tmpdir:
        cfg_path = os.path.join(tmpdir, "settings.json")
        cfg = ConfigManager(cfg_path)

        assert cfg.add_whitelist_app("Notepad.exe") is True
        assert "Notepad.exe" in cfg.whitelist

        assert cfg.remove_whitelist_app("notepad.exe") is True
        assert "Notepad.exe" not in cfg.whitelist
