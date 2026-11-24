#!/usr/bin/env bash
# Fails if a workflow uses an action by tag or branch instead of a full commit SHA. A tag can be
# moved to different code after it was reviewed; a commit cannot. Workflows in this repository
# (uses: ./...) are exempt. CI runs this; docs/gui/RELEASING.md says how pins are refreshed.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
shopt -s nullglob
workflows=("$repo"/.github/workflows/*.yml "$repo"/.github/workflows/*.yaml)
# Every line that has a "uses" key, however it is spelled: at the start of a step or inside
# "{ ... }", with or without quotes, with or without a space before the colon. A line passes
# only in one of two forms: a workflow of this repository, or owner/repo@<40 hex digits>
# followed by a comment naming the version.
bad="$(grep -HnE "(^|[[:space:]{,-])[\"']?uses[\"']?[[:space:]]*:" "${workflows[@]}" \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+\./[^[:space:]]+[[:space:]]*$" \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[A-Za-z0-9._/-]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*[^[:space:]]+" || true)"
if [[ -n "$bad" ]]; then
    echo "error: these actions are not pinned to a full commit SHA with the version in a comment:" >&2
    echo "$bad" >&2
    exit 1
fi
echo "Every action is pinned to a commit."
