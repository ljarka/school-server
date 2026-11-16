#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

FAIL=0

if [ -d model/src ] && grep -rnE '^\s*import\s+school\.(journal|server)\b' model/src; then
    echo "Boundary violation: :model must not import :journal or :server" >&2
    FAIL=1
fi

if [ -d journal/src ] && grep -rnE '^\s*import\s+school\.server\b' journal/src; then
    echo "Boundary violation: :journal must not import :server" >&2
    FAIL=1
fi

if [ -d server/src ]; then
    while IFS= read -r match; do
        file="${match%%:*}"
        if [ "$(basename "$file")" != "Main.kt" ]; then
            echo "Boundary violation ($match): only Main.kt in :server may import school.journal" >&2
            FAIL=1
        fi
    done < <(grep -rnE '^\s*import\s+school\.journal\b' server/src || true)
fi

if [ "$FAIL" -ne 0 ]; then
    exit 1
fi
