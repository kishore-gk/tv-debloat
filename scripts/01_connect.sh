#!/bin/bash
# Connects adb to the TV over Wi-Fi and switches it to the fixed port 5555, then saves TV=ip:5555 in config.sh.
#
# On the TV: Settings > System > Developer options > Wireless debugging.
#   First time (pairing): choose "Pair device with pairing code". It shows an IP:port and a 6-digit code.
#     bash scripts/01_connect.sh <ip> <connect-port> <pairing-port> <code>
#   Already paired (e.g. after the TV restarts): only the port on the main Wireless debugging screen.
#     bash scripts/01_connect.sh <ip> <connect-port>
#
# The pairing port and the connect port are different numbers; both change every time.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IP="$1"; PORT="$2"; PAIRPORT="$3"; CODE="$4"
if [ -z "$IP" ] || [ -z "$PORT" ]; then
  sed -n '2,10p' "$0"; exit 1
fi
if [ -n "$PAIRPORT" ]; then
  echo "Pairing with $IP:$PAIRPORT..."
  adb pair "$IP:$PAIRPORT" "$CODE" || exit 1
fi
echo "Connecting to $IP:$PORT..."
adb connect "$IP:$PORT"
adb -s "$IP:$PORT" get-state >/dev/null 2>&1 || { echo "Not connected. Check the IP and port on the TV's screen."; exit 1; }
echo "Switching to the fixed port 5555 (until the TV restarts)..."
adb -s "$IP:$PORT" tcpip 5555 >/dev/null
sleep 3
adb connect "$IP:5555"
adb -s "$IP:5555" get-state >/dev/null 2>&1 || { echo "Port 5555 didn't come up; use $IP:$PORT for now."; exit 1; }
CONFIG="$ROOT/config.sh"
[ -f "$CONFIG" ] || cp "$ROOT/config.example.sh" "$CONFIG"
grep -v '^TV=' "$CONFIG" > "$CONFIG.tmp"; mv "$CONFIG.tmp" "$CONFIG"
echo "TV=\"$IP:5555\"" >> "$CONFIG"
echo "Connected. Saved TV=$IP:5555 in config.sh."
