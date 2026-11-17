#!/usr/bin/env python3
"""Download every sample of the real-malware test corpus from MalwareBazaar.

Usage:  MB_AUTH_KEY=<key> scripts/fetch-corpus.py [target-dir]

The list of samples is read from the scenario files in src/test/resources/corpus/real, so
adding a scenario there is all it takes to add a sample here. Samples already present are
skipped. Each one goes into its own directory, <target-dir>/<sha256>/, through fetch-sample.py.

The samples are LIVE MALWARE. This script only stores them. Keep the target directory out of
version control ("samples/", the default, is already ignored) and run the tests that use them
on a machine you can throw away:

    JAVABYTE_SAMPLES=samples mvn verify -Dtest=RealCorpusTest

Requires: pip install pyzipper
"""
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCENARIOS = ROOT / "src" / "test" / "resources" / "corpus" / "real"


def corpus_hashes() -> list[str]:
    """SHA-256 of every scenario's sample, without needing a YAML library."""
    hashes = []
    for scenario in sorted(SCENARIOS.glob("*.yaml")):
        match = re.search(r"^sha256:\s*([0-9a-f]{64})\s*$", scenario.read_text(encoding="utf-8"), re.MULTILINE)
        if match is None:
            print(f"warning: {scenario.name} names no sha256", file=sys.stderr)
        else:
            hashes.append(match.group(1))
    return hashes


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    target = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "samples")
    failed = []
    for sha256 in corpus_hashes():
        own = target / sha256
        if own.is_dir() and any(own.iterdir()):
            print(f"present  {sha256}")
            continue
        # A separate process per sample: one failed download does not stop the rest.
        result = subprocess.run([sys.executable, str(ROOT / "scripts" / "fetch-sample.py"), sha256, str(target)])
        if result.returncode != 0:
            failed.append(sha256)
    if failed:
        print("could not fetch: " + ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
