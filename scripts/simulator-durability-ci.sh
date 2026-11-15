#!/bin/bash
# Nightly entry for the iOS Simulator durability cases (ADR-0049 gate I2; ADR-0047 D15 and ticket 15 amendment).
# Usage: scripts/simulator-durability-ci.sh [case ...]    (no case: all, see scripts/simulator-durability.sh)
#
# A GitHub macOS runner cannot run containers (no nested virtualization), so this script does not use docker compose.
# It starts a throwaway verification stack on the Mac: PostgreSQL 17 from Homebrew in a temporary data directory, and the
# server that Gradle builds (`:app:installDist`, the same task as server/Dockerfile) run on the host. Then it runs
# scripts/simulator-durability.sh with all cases on the Simulator that scripts/verify-ios.sh picks for the tests, and it
# stops everything at exit. The collection key, query key, and database password are random for each run and never
# written to a file. No retry: a failed case fails the run (ADR-0049 decision 5).
#
# Needs: macOS with full Xcode and an iOS Simulator runtime, Homebrew, JDK 21 (JAVA_HOME; default .tools/ from
# scripts/setup-toolchain.sh), curl, nc. With CI=true a missing PostgreSQL is installed with `brew install postgresql@17`;
# on a developer Mac it is an error (install it yourself, or set SD_PG_BIN to a directory with initdb, pg_ctl, createdb).
# Environment (all optional):
#   SIMULATOR_UDID                      the Simulator (default: scripts/verify-ios.sh simulator)
#   SIMULATOR_DURABILITY_DB_PORT        default 15442
#   SIMULATOR_DURABILITY_SERVER_PORT    default 18092
#   SIMULATOR_DURABILITY_LOG_DIR        initdb.log, postgres.log and server.log (default build/simulator-durability, ignored by git)
#   SIMULATOR_DURABILITY_RESULT_JSON, SIMULATOR_BOOT_TIMEOUT, PHASE_TIMEOUT, IOS_WERROR, SIMULATOR_DURABILITY_SKIP_BUILD:
#                                       passed to scripts/simulator-durability.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[ "$(uname -s)" = Darwin ] || { echo "error: needs macOS (the iOS Simulator)" >&2; exit 1; }

DB_PORT="${SIMULATOR_DURABILITY_DB_PORT:-15442}"
SERVER_PORT="${SIMULATOR_DURABILITY_SERVER_PORT:-18092}"
LOG_DIR="${SIMULATOR_DURABILITY_LOG_DIR:-$ROOT/build/simulator-durability}"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1}"
java_version="$("$JAVA_HOME/bin/java" -version 2>&1 || true)" # not a pipe into grep -q: SIGPIPE would fail under pipefail
case "$java_version" in
  *'version "21.'*) ;;
  *) echo "error: JAVA_HOME ($JAVA_HOME) is not a JDK 21; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2; exit 1 ;;
esac
for port in "$DB_PORT" "$SERVER_PORT"; do
  if nc -z 127.0.0.1 "$port" 2>/dev/null; then echo "error: port $port is in use" >&2; exit 1; fi
done

PG_BIN="${SD_PG_BIN:-}"
if [ -z "$PG_BIN" ]; then
  PG_BIN="$(brew --prefix postgresql@17)/bin"
  if [ ! -x "$PG_BIN/initdb" ]; then
    [ "${CI:-}" = true ] || { echo "error: PostgreSQL 17 is missing; run: brew install postgresql@17" >&2; exit 1; }
    HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install postgresql@17
  fi
fi
for tool in initdb pg_ctl createdb; do
  [ -x "$PG_BIN/$tool" ] || { echo "error: $PG_BIN/$tool not found" >&2; exit 1; }
done

work="$(mktemp -d)"
mkdir -p "$LOG_DIR"
rm -f "$LOG_DIR/initdb.log" "$LOG_DIR/postgres.log" "$LOG_DIR/server.log"
server_pid=""
cleanup() {
  local status=$?
  if [ -n "$server_pid" ]; then
    kill "$server_pid" 2>/dev/null || true
    for _ in $(seq 20); do kill -0 "$server_pid" 2>/dev/null || break; sleep 0.5; done # 10 s, then SIGKILL
    kill -9 "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  "$PG_BIN/pg_ctl" -D "$work/pgdata" -m fast stop >/dev/null 2>&1 || true
  if [ "$status" -ne 0 ]; then
    echo "--- last lines of server.log ($LOG_DIR) ---" >&2
    tail -40 "$LOG_DIR/server.log" 2>/dev/null >&2 || true
  fi
  rm -rf "$work"
  return "$status"
}
trap cleanup EXIT

DB_PASSWORD="$(openssl rand -hex 16)"
SIGNALDOCK_COLLECTION_KEY="$(openssl rand -hex 16)"
SIGNALDOCK_QUERY_KEY="$(openssl rand -hex 16)"
export SIGNALDOCK_COLLECTION_KEY SIGNALDOCK_QUERY_KEY
export SIGNALDOCK_ENDPOINT="http://127.0.0.1:$SERVER_PORT"

echo "=== 1. PostgreSQL ($("$PG_BIN/pg_ctl" --version)) on 127.0.0.1:$DB_PORT ==="
# LC_ALL: PostgreSQL 17 on macOS stops with "postmaster became multithreaded" when the locale is not set (LANG empty).
# It also keeps the log in English. en_US.UTF-8 is the locale of the compose image.
printf '%s\n' "$DB_PASSWORD" >"$work/pgpass"
LC_ALL=en_US.UTF-8 "$PG_BIN/initdb" -D "$work/pgdata" -U signaldock --auth=scram-sha-256 --pwfile="$work/pgpass" -E UTF8 >"$LOG_DIR/initdb.log" 2>&1
rm -f "$work/pgpass"
LC_ALL=en_US.UTF-8 "$PG_BIN/pg_ctl" -D "$work/pgdata" -l "$LOG_DIR/postgres.log" -w -t 60 \
  -o "-p $DB_PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=" start >/dev/null
PGPASSWORD="$DB_PASSWORD" "$PG_BIN/createdb" -h 127.0.0.1 -p "$DB_PORT" -U signaldock signaldock

echo "=== 2. server: gradle :app:installDist, then run on 127.0.0.1:$SERVER_PORT ==="
(cd server && ./gradlew :app:installDist --no-daemon --console=plain)
SIGNALDOCK_JDBC_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/signaldock" SIGNALDOCK_DB_USER=signaldock \
  SIGNALDOCK_DB_PASSWORD="$DB_PASSWORD" PORT="$SERVER_PORT" SIGNALDOCK_TRACKING_PLAN="$ROOT/tracking-plan/plan.json" \
  server/app/build/install/app/bin/app >"$LOG_DIR/server.log" 2>&1 &
server_pid=$!
deadline=$((SECONDS + 120))
until curl -fsS --max-time 5 -o /dev/null "$SIGNALDOCK_ENDPOINT/ready" 2>/dev/null; do
  kill -0 "$server_pid" 2>/dev/null || { echo "error: the server exited before it was ready" >&2; exit 1; }
  [ $SECONDS -lt "$deadline" ] || { echo "error: the server was not ready within 120 s" >&2; exit 1; }
  sleep 1
done
echo "server ready at $SIGNALDOCK_ENDPOINT"

echo "=== 3. Simulator durability cases ==="
udid="${SIMULATOR_UDID:-$(scripts/verify-ios.sh simulator)}"
scripts/simulator-durability.sh "$udid" "$@"
