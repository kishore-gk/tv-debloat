# Shared by every script: finds the repo, loads config.sh, connects to the TV.
# Not run on its own; the scripts source it.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -f "$ROOT/config.sh" ] && . "$ROOT/config.sh"
REPORTS="$ROOT/_local/reports"
STATE="$ROOT/_local/state"
mkdir -p "$REPORTS" "$STATE"

if [ -z "$TV" ]; then
  echo "No TV set. Copy config.example.sh to config.sh and set TV (or run scripts/01_connect.sh)."
  exit 1
fi
A="adb -s $TV shell"

# Connects to the TV, or stops with a hint.
connect() {
  adb connect "$TV" >/dev/null 2>&1
  if ! adb -s "$TV" get-state >/dev/null 2>&1; then
    echo "Can't reach the TV at $TV."
    echo "After the TV restarts, the fixed port 5555 is gone: run scripts/01_connect.sh again."
    exit 1
  fi
}
