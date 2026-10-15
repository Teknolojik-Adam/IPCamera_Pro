#!/usr/bin/env python3
"""Regression tests for Android MJPEG URL repair and pairing input validation."""
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
from phone_url import normalize_phone_url, PhoneUrlError

IP = "192.168.111.10"
TOKEN = "A" * 32
URL = f"http://{IP}:8787/video?token={TOKEN}"


class PhoneUrlTests(unittest.TestCase):
    def test_normal_url(self):
        self.assertEqual(normalize_phone_url(URL, sender_ip=IP), (URL, False))

    def test_percent_encoded_space_in_token(self):
        value = f"http://{IP}:8787/video?token={'A'*23}%20{'A'*9}"
        self.assertEqual(normalize_phone_url(value, sender_ip=IP), (URL, True))

    def test_literal_space_in_token(self):
        value = f"http://{IP}:8787/video?token={'A'*23} {'A'*9}"
        self.assertEqual(normalize_phone_url(value, sender_ip=IP), (URL, True))

    def test_plus_in_token(self):
        value = f"http://{IP}:8787/video?token={'A'*23}+{'A'*9}"
        self.assertEqual(normalize_phone_url(value, sender_ip=IP), (URL, True))

    def test_short_token_not_guessed(self):
        with self.assertRaisesRegex(PhoneUrlError, "32 karakter"):
            normalize_phone_url(
                f"http://{IP}:8787/video?token={'A'*23}%20{'A'*8}",
                sender_ip=IP)

    def test_wrong_sender_ip_rejected(self):
        with self.assertRaisesRegex(PhoneUrlError, "IP'si"):
            normalize_phone_url(URL, sender_ip="192.168.111.99")

    def test_external_ip_rejected(self):
        with self.assertRaises(PhoneUrlError):
            normalize_phone_url(
                "http://8.8.8.8:8787/video?token=" + TOKEN,
                sender_ip="8.8.8.8")

    def test_wrong_path_or_port(self):
        for changed in (URL.replace(":8787", ":8080"),
                        URL.replace("/video?", "/admin?")):
            with self.subTest(changed=changed):
                with self.assertRaises(PhoneUrlError):
                    normalize_phone_url(changed, sender_ip=IP)

    def test_multiple_tokens_rejected(self):
        with self.assertRaises(PhoneUrlError):
            normalize_phone_url(URL + "&token=" + TOKEN, sender_ip=IP)

    def test_invalid_token_character(self):
        with self.assertRaises(PhoneUrlError):
            normalize_phone_url(URL.replace("A", "%2F", 1), sender_ip=IP)

    def test_prefix_or_trailing_space_rejected(self):
        for changed in (" " + URL, URL + " "):
            with self.subTest(changed=changed):
                with self.assertRaises(PhoneUrlError):
                    normalize_phone_url(changed, sender_ip=IP)


if __name__ == "__main__":
    unittest.main(verbosity=2)
