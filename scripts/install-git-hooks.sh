#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

mkdir -p .git/hooks

cat > .git/hooks/commit-msg <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
./scripts/check-neutral-names.sh --message "$1"
EOF

cat > .git/hooks/pre-push <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
./scripts/check.sh
EOF

chmod +x .git/hooks/commit-msg .git/hooks/pre-push
echo "Installed commit-msg and pre-push hooks."
