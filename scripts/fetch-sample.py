#!/usr/bin/env python3
"""Download one sample from MalwareBazaar by SHA-256 and unpack it into its own directory.

Usage:  MB_AUTH_KEY=<key> scripts/fetch-sample.py <sha256> [target-dir]

The sample is LIVE MALWARE. This script only stores it; nothing here executes it, and JavaByte
itself reads samples as data. Keep the target directory out of version control (the repository's
.gitignore already excludes "samples/").

Requires: pip install pyzipper   (MalwareBazaar archives are AES-encrypted, password "infected")
"""
import hashlib
import io
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request

import pyzipper

API = "https://mb-api.abuse.ch/api/v1/"


def main() -> int:
    if len(sys.argv) < 2 or re.fullmatch(r"[0-9a-fA-F]{64}", sys.argv[1]) is None:
        print(__doc__, file=sys.stderr)
        return 2
    sha256 = sys.argv[1].lower()
    # One directory per sample keeps unrelated files from ever sitting next to each other.
    target = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "samples") / sha256
    target.mkdir(parents=True, exist_ok=True)

    request = urllib.request.Request(
        API, data=urllib.parse.urlencode({"query": "get_file", "sha256_hash": sha256}).encode())
    key = os.environ.get("MB_AUTH_KEY")
    if key:
        request.add_header("Auth-Key", key)
    with urllib.request.urlopen(request, timeout=120) as response:
        body = response.read()
    if not body.startswith(b"PK"):
        print("MalwareBazaar did not return an archive: " + body[:200].decode(errors="replace"), file=sys.stderr)
        return 1

    written = 0
    with pyzipper.AESZipFile(io.BytesIO(body)) as archive:
        archive.setpassword(b"infected")
        for name in archive.namelist():
            # Use only the base name: archive entries must not choose where they are written.
            # Both separators count, so that "..\\..\\x" is no way out on Windows either.
            base = re.split(r"[/\\]", name)[-1]
            if base in ("", ".", "..") or ":" in base:
                print("skipped an archive entry with an unusable name: " + repr(name), file=sys.stderr)
                continue
            data = archive.read(name)
            # The download is live malware asked for by hash; anything else is not written.
            if hashlib.sha256(data).hexdigest() != sha256:
                print("skipped " + repr(base) + ": its SHA-256 is not the one asked for", file=sys.stderr)
                continue
            out = target / base
            out.write_bytes(data)
            print(out)
            written += 1
    if written == 0:
        print("the archive held no file with SHA-256 " + sha256, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
