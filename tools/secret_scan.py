#!/usr/bin/env python3
"""Fail closed on high-confidence credential leaks in tracked files and ZIP archives.

This lightweight audit intentionally reports file paths and finding types, never secret values.
It is a guardrail, not a replacement for provider-side secret scanning or manual review.
"""
from __future__ import annotations

import io
import re
import subprocess
import sys
import zipfile
from pathlib import PurePosixPath

MAX_TEXT_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_MEMBER_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_TOTAL_BYTES = 20 * 1024 * 1024
ARCHIVE_SUFFIXES = (".zip", ".apk", ".aar", ".jar")
HISTORY_TEXT_SUFFIXES = (".kt", ".java", ".kts", ".gradle", ".xml", ".json", ".properties", ".yml", ".yaml", ".toml", ".md", ".sh", ".py", ".txt", ".conf", ".ini", ".env")

PATTERNS: tuple[tuple[str, re.Pattern[bytes]], ...] = (
    ("GitHub token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})\b")),
    ("AWS access key", re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("Google API key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("Slack token", re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("OpenAI-style API key", re.compile(rb"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b")),
    ("Private key block", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    (
        "Credential assignment",
        re.compile(
            rb"""(?im)\b(?:GEMINI_API_KEY|SERPAPI_API_KEY|SERPAPI_KEY|OPENAI_API_KEY|"""
            rb"""ANTHROPIC_API_KEY|AWS_SECRET_ACCESS_KEY|CLIENT_SECRET)\s*[:=]\s*["']?"""
            rb"""([A-Za-z0-9/+_=-]{20,})"""
        ),
    ),
    (
        "Hard-coded credential value",
        re.compile(
            rb"""(?i)(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)"""
            rb"""\s*[:=]\s*["']([A-Za-z0-9/+_.=-]{24,})["']"""
        ),
    ),
)

SENSITIVE_BASENAMES = {
    ".env",
    "google-services.json",
    "local.properties",
    "debug.keystore",
    "key.properties",
    "secrets.properties",
    "credentials.json",
    "service-account.json",
    "firebase-adminsdk.json",
    ".npmrc",
    ".pypirc",
    "id_rsa",
    "id_ed25519",
}
SENSITIVE_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".pkey", ".key", ".p8", ".mobileprovision", ".asc")


def is_sensitive_name(name: str) -> bool:
    base = PurePosixPath(name).name.lower()
    if base == ".env.example":
        return False
    return (
        base in SENSITIVE_BASENAMES
        or (base.startswith(".env.") and base != ".env.example")
        or base.endswith(SENSITIVE_SUFFIXES)
    )


def scan_bytes(label: str, data: bytes, findings: list[tuple[str, str]]) -> None:
    for finding_type, pattern in PATTERNS:
        if pattern.search(data):
            findings.append((label, finding_type))


def scan_zip(path: str, data: bytes, findings: list[tuple[str, str]]) -> None:
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
        infos = archive.infolist()
    except (OSError, zipfile.BadZipFile):
        findings.append((path, "Unable to inspect ZIP archive"))
        return

    total = 0
    with archive:
        for info in infos:
            if info.is_dir():
                continue
            member = info.filename
            if is_sensitive_name(member):
                findings.append((f"{path}!/{member}", "Credential or local configuration file inside archive"))
                continue
            total += info.file_size
            if info.file_size > MAX_ARCHIVE_MEMBER_BYTES or total > MAX_ARCHIVE_TOTAL_BYTES:
                findings.append((path, "ZIP scan limit exceeded; archive requires manual review"))
                break
            try:
                member_bytes = archive.read(info)
            except (OSError, RuntimeError, zipfile.BadZipFile):
                findings.append((f"{path}!/{member}", "Unable to read archive member"))
                continue
            # High-confidence patterns are ASCII; scan binary members too so DEX strings are not skipped.
            scan_bytes(f"{path}!/{member}", member_bytes, findings)



def scan_git_history(findings: list[tuple[str, str]]) -> None:
    """Inspect historical text blobs and archives so deleting a leaked file is not enough to pass."""
    try:
        listing = subprocess.check_output(["git", "rev-list", "--objects", "--all"], stderr=subprocess.STDOUT)
    except (OSError, subprocess.CalledProcessError):
        findings.append(("git-history", "Unable to enumerate repository history"))
        return

    seen_blobs: set[str] = set()
    for raw_line in listing.splitlines():
        parts = raw_line.split(b" ", 1)
        if len(parts) != 2:
            continue
        object_id = parts[0].decode("ascii", errors="ignore")
        path = parts[1].decode("utf-8", errors="replace")
        lowered = path.lower()

        if is_sensitive_name(path):
            findings.append((f"git-history:{path}", "Sensitive credential/configuration filename exists in history"))
            continue

        if not (lowered.endswith(ARCHIVE_SUFFIXES) or lowered.endswith(HISTORY_TEXT_SUFFIXES)):
            continue
        if object_id in seen_blobs:
            continue
        seen_blobs.add(object_id)

        try:
            kind = subprocess.check_output(["git", "cat-file", "-t", object_id], text=True, stderr=subprocess.DEVNULL).strip()
            if kind != "blob":
                continue
            object_size = int(subprocess.check_output(["git", "cat-file", "-s", object_id], text=True, stderr=subprocess.DEVNULL).strip())
            if lowered.endswith(ARCHIVE_SUFFIXES) and object_size > MAX_ARCHIVE_TOTAL_BYTES:
                findings.append((f"git-history:{path}", "Large historical archive requires manual review"))
                continue
            if not lowered.endswith(ARCHIVE_SUFFIXES) and object_size > MAX_TEXT_BYTES:
                findings.append((f"git-history:{path}", "Large historical text file requires manual review"))
                continue
            data = subprocess.check_output(["git", "cat-file", "blob", object_id], stderr=subprocess.DEVNULL)
        except (OSError, subprocess.CalledProcessError):
            findings.append((f"git-history:{path}", "Unable to inspect historical file"))
            continue

        historical_label = f"git-history:{path}@{object_id[:12]}"
        if lowered.endswith(ARCHIVE_SUFFIXES):
            scan_zip(historical_label, data, findings)
        else:
            scan_bytes(historical_label, data, findings)


def main() -> int:
    try:
        raw = subprocess.check_output(["git", "ls-files", "-z"], stderr=subprocess.STDOUT)
    except (OSError, subprocess.CalledProcessError):
        print("ERROR: no se pudieron enumerar los archivos versionados.", file=sys.stderr)
        return 2

    findings: list[tuple[str, str]] = []
    for encoded_path in raw.split(b"\x00"):
        if not encoded_path:
            continue
        path = encoded_path.decode("utf-8", errors="replace")
        if is_sensitive_name(path):
            findings.append((path, "Sensitive local credential/configuration file is tracked"))
            continue
        try:
            with open(path, "rb") as handle:
                data = handle.read(MAX_TEXT_BYTES + 1)
        except OSError:
            findings.append((path, "Unable to read tracked file"))
            continue

        if path.lower().endswith(ARCHIVE_SUFFIXES):
            if len(data) > MAX_ARCHIVE_TOTAL_BYTES:
                findings.append((path, "Large archive requires manual review"))
                continue
            if len(data) > MAX_TEXT_BYTES:
                # Read only bounded-size archives as binary data for ZIP member inspection.
                with open(path, "rb") as handle:
                    data = handle.read()
            scan_zip(path, data, findings)
        elif len(data) <= MAX_TEXT_BYTES and b"\x00" not in data:
            scan_bytes(path, data, findings)

    scan_git_history(findings)

    if findings:
        print("FALLO: se han encontrado posibles credenciales o archivos locales sensibles.")
        for path, kind in sorted(set(findings)):
            print(f"- {path}: {kind}")
        print("No se muestran valores detectados. Revoca cualquier credencial real que haya sido expuesta.")
        return 1

    print("OK: no se han detectado patrones de credenciales conocidos en los archivos versionados examinados.")
    print("Este resultado no sustituye la revisión del historial completo ni una auditoría profesional.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
