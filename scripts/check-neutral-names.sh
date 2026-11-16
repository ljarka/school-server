#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENCODED_WORDS=(
    "bGlicnVz"
    "c3luZXJnaWE="
    "d2lhZG9tb3NjaS4="
    "a29udG8tbGlicnVz"
    "U3luZXJnaWFBY2NvdW50cw=="
    "bWF0aTM2NQ=="
    "YW5kcmV3a29sdHNvdg=="
)

decode_base64() {
    if base64 --decode </dev/null >/dev/null 2>&1; then
        printf '%s' "$1" | base64 --decode
    else
        printf '%s' "$1" | base64 -D
    fi
}

PATTERN=""
for enc in "${ENCODED_WORDS[@]}"; do
    word="$(decode_base64 "$enc")"
    escaped="$(printf '%s' "$word" | sed 's/[.[\*^$()+?{|]/\\&/g')"
    if [ -z "$PATTERN" ]; then
        PATTERN="$escaped"
    else
        PATTERN="$PATTERN|$escaped"
    fi
done

if [ "${1:-}" = "--message" ]; then
    MSG_FILE="${2:-}"
    if [ -z "$MSG_FILE" ] || [ ! -f "$MSG_FILE" ]; then
        echo "Usage: $0 --message <commit-msg-file>" >&2
        exit 2
    fi
    if grep -inE "$PATTERN" "$MSG_FILE" >/dev/null; then
        echo "Commit message contains a forbidden provider-specific name:" >&2
        grep -inE "$PATTERN" "$MSG_FILE" >&2
        exit 1
    fi
    exit 0
fi

HITS=0
while IFS= read -r -d '' file; do
    case "$file" in
        journal/src/main/kotlin/*/ProtocolPaths.kt)
            continue
            ;;
    esac
    if [ -f "$file" ]; then
        if grep -InE "$PATTERN" "$file" >/dev/null 2>&1; then
            grep -InE "$PATTERN" "$file" | sed "s|^|$file:|" >&2
            HITS=1
        fi
    fi
done < <(git ls-files -z)

if [ "$HITS" -ne 0 ]; then
    echo "Forbidden provider-specific name found in tracked files (ADR 0006)." >&2
    exit 1
fi
