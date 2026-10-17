"""Unit tests for the credential-scanning guardrail; all token fixtures are synthetic."""
from __future__ import annotations

import io
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import secret_scan as scanner


class SecretScanTests(unittest.TestCase):
    def test_documented_placeholder_is_not_treated_as_a_live_key(self) -> None:
        findings: list[tuple[str, str]] = []
        scanner.scan_bytes("example.env", b"# GEMINI_API_KEY=MY_GEMINI_API_KEY", findings)
        self.assertEqual(findings, [])

    def test_synthetic_github_token_is_detected_without_printing_its_value(self) -> None:
        synthetic = b"ghp_" + (b"A" * 40)
        findings: list[tuple[str, str]] = []
        scanner.scan_bytes("fixture.txt", synthetic, findings)
        self.assertIn(("fixture.txt", "GitHub token"), findings)

    def test_synthetic_google_api_key_is_detected(self) -> None:
        synthetic = b"AIza" + (b"B" * 35)
        findings: list[tuple[str, str]] = []
        scanner.scan_bytes("fixture.txt", synthetic, findings)
        self.assertIn(("fixture.txt", "Google API key"), findings)

    def test_local_secrets_are_flagged_but_env_example_is_allowed(self) -> None:
        self.assertTrue(scanner.is_sensitive_name("app/.env"))
        self.assertTrue(scanner.is_sensitive_name("signing/upload-key.jks"))
        self.assertFalse(scanner.is_sensitive_name(".env.example"))

    def test_zip_containing_env_file_is_flagged_without_reading_values(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("project/.env", "A_SYNTHETIC_VALUE=example")
        findings: list[tuple[str, str]] = []
        scanner.scan_zip("project.zip", buffer.getvalue(), findings)
        self.assertIn(
            ("project.zip!/project/.env", "Credential or local configuration file inside archive"),
            findings,
        )


if __name__ == "__main__":
    unittest.main()
