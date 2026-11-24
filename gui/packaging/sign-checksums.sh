#!/usr/bin/env bash
# Signs a release's checksum file with the project's GPG key, or stops the release.
#
#   packaging/sign-checksums.sh check         the key is stored, usable and the documented one
#   packaging/sign-checksums.sh sign FILE     the same checks, then writes FILE.asc
#
# The private key and its passphrase come from the environment (GPG_PRIVATE_KEY, an armoured
# private key, and GPG_PASSPHRASE), which the release workflow fills from repository secrets.
# Neither is ever put on a command line, where other processes could read it.
#
# The public key is NOT taken from the secret. It is docs/javabyte-signing-key.asc, committed
# to the repository, and its fingerprint must be written in docs/INSTALL.md. The secret key has
# to be that key, and the signature is verified with the committed file alone. So a release
# cannot be signed with a key other than the one people were told to expect, and the key is
# published apart from the release it signs.
#
# Every failure ends with a non-zero exit code: a release is signed or it is not made.
set -euo pipefail

mode="${1:-}"
file="${2:-}"
if [[ "$mode" != check && ! ( "$mode" == sign && -f "$file" ) ]]; then
    echo "usage: sign-checksums.sh check | sign FILE" >&2
    exit 2
fi
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Fixed paths, not settable from the environment: the documented key is the one in this
# repository at this commit, and nothing else may be put in its place.
public="$repo/docs/javabyte-signing-key.asc"
install_doc="$repo/docs/INSTALL.md"

fail() {
    echo "error: $1" >&2
    echo "The release cannot be signed, so it is not made. See docs/gui/RELEASING.md, \"Signing releases\"." >&2
    exit 1
}

[[ -n "${GPG_PRIVATE_KEY:-}" ]] || fail "the secret GPG_PRIVATE_KEY is not set"
[[ -n "${GPG_PASSPHRASE:-}" ]] || fail "the secret GPG_PASSPHRASE is not set"
[[ -f "$public" ]] || fail "the public key ${public#"$repo/"} is not in the repository"

work="$(mktemp -d)"
cleanup() {
    for home in "$work/signer" "$work/verifier"; do
        GNUPGHOME="$home" gpgconf --kill all 2> /dev/null || true
    done
    rm -rf "$work"
}
trap cleanup EXIT
mkdir -m 700 "$work/signer" "$work/verifier"

# Field 10 of the first "fpr" record: the primary key's fingerprint.
fingerprint_of() { awk -F: '$1 == "fpr" {print $10; exit}'; }

# The documented key: the committed file, holding one key, whose fingerprint INSTALL.md shows.
listing="$(GNUPGHOME="$work/verifier" gpg --batch --quiet --with-colons --show-keys "$public")"
[[ "$(grep -c '^pub:' <<< "$listing")" == 1 ]] || fail "${public#"$repo/"} must hold exactly one public key"
documented="$(fingerprint_of <<< "$listing")"
[[ "$documented" =~ ^[0-9A-F]{40}$ ]] || fail "could not read a fingerprint from ${public#"$repo/"}"
tr -d ' \n' < "$install_doc" | grep -q "$documented" \
    || fail "the fingerprint $documented of ${public#"$repo/"} is not written in ${install_doc#"$repo/"}"

# The stored key must be that key.
export GNUPGHOME="$work/signer"
printf '%s' "$GPG_PRIVATE_KEY" | gpg --batch --quiet --import || fail "GPG_PRIVATE_KEY could not be imported"
stored="$(gpg --batch --with-colons --list-secret-keys | fingerprint_of)"
[[ "$stored" == "$documented" ]] \
    || fail "the stored private key (${stored:-none}) is not the documented key ($documented)"

if [[ "$mode" == check ]]; then
    file="$work/probe"
    echo "signing check" > "$file"
fi
# The passphrase goes to gpg through a pipe (--passphrase-fd), not as an argument.
printf '%s' "$GPG_PASSPHRASE" | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-fd 0 \
    --local-user "$documented" --armor --detach-sign --output "$file.asc" "$file" \
    || fail "signing failed (wrong passphrase, or the key has expired)"

# Verified as a downloader would: with nothing but the committed public key.
export GNUPGHOME="$work/verifier"
gpg --batch --quiet --import "$public"
status="$(gpg --batch --status-fd 1 --verify "$file.asc" "$file" 2> /dev/null)" \
    || fail "the signature does not verify with ${public#"$repo/"}"
# VALIDSIG's last field is the primary key's fingerprint, also when a subkey signed.
grep -q "^\[GNUPG:\] VALIDSIG .* $documented\$" <<< "$status" \
    || fail "the signature was not made by the documented key ($documented)"

if [[ "$mode" == check ]]; then
    echo "The signing key is stored, usable and the documented one: $documented"
else
    echo "Signed $(basename "$file") with $documented"
fi
