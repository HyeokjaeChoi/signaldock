#!/bin/bash
# Issue 21: delivery failure E2E against the Compose stack and a real SDK build (R8 consumer app on an
# emulator). Cases: 1 normal E2E, 2 ACK loss, 3 partial rejection, 4 content conflict. Local only; not in CI.
#
# Needs (started by you, never by this script):
#   - .env (cp .env.example .env) and `docker compose up -d --build`
#   - a booted emulator, for example:
#       $ANDROID_HOME/emulator/emulator -avd signaldock-api37 -no-window -no-audio -no-boot-anim &
#       adb wait-for-device
#   - JDK 21 (scripts/setup-toolchain.sh), ANDROID_HOME
# Usage: scripts/e2e-delivery.sh [case numbers, e.g. 2 3]. E2E_SKIP_BUILD=1 reuses the installed app.
# It starts the fault proxy (scripts/e2e/fault-proxy.py) on 127.0.0.1:${E2E_PROXY_PORT:-8081} and stops it at exit.
# It never runs `docker compose down` and never deletes volumes. Events stay in the DB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROXY_PORT="${E2E_PROXY_PORT:-8081}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1}"
ADB="$ANDROID_HOME/platform-tools/adb"
cd "$ROOT"

[ -f .env ] || { echo "error: no .env; run: cp .env.example .env && docker compose up -d --build" >&2; exit 1; }
set -a; . ./.env; set +a
curl -fsS -o /dev/null "http://127.0.0.1:${SIGNALDOCK_DASHBOARD_PORT:-5173}/" || { echo "error: Dashboard not reachable; run: docker compose up -d --build" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "error: no emulator; start signaldock-api37 (see the header of this script)" >&2; exit 1; }
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done

if [ "${E2E_SKIP_BUILD:-0}" != "1" ]; then
  echo "=== build: Maven ZIP, then the consumer app aimed at the fault proxy (10.0.2.2:$PROXY_PORT) ==="
  REPO="$(mktemp -d)/maven-repo"
  SIGNALDOCK_REPO_DIR="$REPO" scripts/verify-consumer.sh >/dev/null
  compatibility/consumer-android/gradlew -p compatibility/consumer-android --no-daemon \
    -Psignaldock.repo="$REPO" -Psignaldock.endpoint="http://10.0.2.2:$PROXY_PORT" \
    -Psignaldock.collectionKey="$SIGNALDOCK_COLLECTION_KEY" :app:assembleRelease >/dev/null
  "$ADB" install -r compatibility/consumer-android/app/build/outputs/apk/release/app-release.apk
fi

LOG="$(mktemp -d)/proxy.jsonl"
python3 scripts/e2e/fault-proxy.py --listen "127.0.0.1:$PROXY_PORT" --upstream "127.0.0.1:${SIGNALDOCK_SERVER_PORT:-8080}" --log "$LOG" &
PROXY_PID=$!
trap 'kill "$PROXY_PID" 2>/dev/null || true; echo "proxy log: $LOG"' EXIT
until curl -fsS -o /dev/null "http://127.0.0.1:$PROXY_PORT/__fault/mode"; do sleep 0.2; done

E2E_PROXY_PORT="$PROXY_PORT" python3 scripts/e2e/e2e_cases.py "$@"
