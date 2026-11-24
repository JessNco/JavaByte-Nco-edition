#!/usr/bin/env bash
# Fails if a workflow uses an action by tag or branch instead of a full commit SHA. A tag can be
# moved to different code after it was reviewed; a commit cannot. Workflows in this repository
# (uses: ./...) are exempt. CI runs this; docs/gui/RELEASING.md says how pins are refreshed.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bad="$(grep -nE '^[[:space:]]*(-[[:space:]]+)?uses:' "$repo"/.github/workflows/*.yml \
    | grep -vE 'uses:[[:space:]]+\./' \
    | grep -vE 'uses:[[:space:]]+[A-Za-z0-9._/-]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*[^[:space:]]+' || true)"
if [[ -n "$bad" ]]; then
    echo "error: these actions are not pinned to a full commit SHA with the version in a comment:" >&2
    echo "$bad" >&2
    exit 1
fi
echo "Every action is pinned to a commit."
