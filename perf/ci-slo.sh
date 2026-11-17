#!/usr/bin/env bash
# Gate B3 (ADR-0049 amendment, ticket 08): ingest load SLO on the runner. Runs from .github/workflows/nightly.yml.
#   perf/ci-slo.sh           fails on any bad request, or when p95 > 2 x the committed baseline of the same runner class
#   perf/ci-slo.sh measure   no p95 rule; writes the numbers to measure a new baseline (decision 2: from a real CI run)
# Compose project signaldock-perf (perf/compose.perf.yml: DB 2 CPUs / 2 GiB, server 2 CPUs / 1 GiB; perf/compose.ci-slo.yml: DB volume in RAM), an empty database,
# 20 req/s x 20 events for 120 s (after a 15 s warm-up that p95 ignores) with perf/ci-slo.js. Needs docker and k6; without k6 on PATH it downloads the pinned
# linux-amd64 release and checks its SHA-256.
# Inputs: SLO_RUNNER_CLASS (required: the `runs-on` label of the job), SLO_BASELINE (default perf/ci-slo-baseline.json),
# SLO_OUT (default perf/work/ci-slo). Output: $SLO_OUT/{k6-summary.json,result.json,cases.json,compose.log,server-gc.log,vmstat.txt}. No retry.
set -euo pipefail
cd "$(dirname "$0")/.."

K6_VERSION=v2.3.0
K6_SHA=39c3117b6af817592dcd0ce4242105c0a7af10948c2a425306f0be8f7a8a8ab1 # k6-v2.3.0-linux-amd64.tar.gz, from k6-v2.3.0-checksums.txt of the release
RATE=20
DURATION_S=120
WARMUP_S=15

mode=check
if [[ ${1:-} == measure ]]; then mode=measure; elif [[ $# -gt 0 ]]; then echo "usage: $0 [measure]" >&2; exit 2; fi
: "${SLO_RUNNER_CLASS:?set SLO_RUNNER_CLASS to the runs-on label of the job}"
baseline=${SLO_BASELINE:-perf/ci-slo-baseline.json}
out=${SLO_OUT:-perf/work/ci-slo}
rm -rf "$out"
mkdir -p "$out" perf/work

if ! command -v k6 >/dev/null; then
  [[ $(uname -sm) == "Linux x86_64" ]] || { echo "error: k6 not found; install it, or run on linux x86_64" >&2; exit 1; }
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/k6.tgz" "https://github.com/grafana/k6/releases/download/$K6_VERSION/k6-$K6_VERSION-linux-amd64.tar.gz"
  echo "$K6_SHA  $tmp/k6.tgz" | sha256sum -c -
  tar -xzf "$tmp/k6.tgz" -C "$tmp" --strip-components=1 "k6-$K6_VERSION-linux-amd64/k6"
  PATH="$tmp:$PATH"
fi

# Random keys for this run only; same file and ports as perf/common.py ensure_env, but never reused (the volume is new).
rand() { od -An -N12 -tx1 /dev/urandom | tr -d ' \n'; }
ckey=perf-c-$(rand)
envfile=perf/work/env
if [[ -e $envfile ]]; then echo "error: $envfile exists (a perf/*.py run); remove it and its volumes first" >&2; exit 1; fi
# Same refusal as perf/common.py ensure_env(): the cleanup below runs `down --volumes`, which would delete a seeded perf volume.
if [[ -n $(docker volume ls -q --filter label=com.docker.compose.project=signaldock-perf) ]]; then
  echo "error: signaldock-perf volumes exist (a seeded perf/*.py run); remove them yourself first: docker compose -p signaldock-perf down --volumes" >&2; exit 1
fi
cat > "$envfile" <<ENV
SIGNALDOCK_COLLECTION_KEY=$ckey
SIGNALDOCK_QUERY_KEY=perf-q-$(rand)
SIGNALDOCK_DB_NAME=bench
SIGNALDOCK_DB_USER=perf
SIGNALDOCK_DB_PASSWORD=perf-$(rand)
SIGNALDOCK_DB_PORT=15432
SIGNALDOCK_SERVER_PORT=18080
SIGNALDOCK_TIME_ZONE=Asia/Seoul
ENV
chmod 600 "$envfile"
dc() { docker compose -p signaldock-perf --env-file "$envfile" -f docker-compose.yml -f perf/compose.perf.yml -f perf/compose.ci-slo.yml "$@"; }
cleanup() {
  docker cp "$(dc ps -q server)":/tmp/gc.log "$out/server-gc.log" >/dev/null 2>&1 || true
  dc logs --no-color --tail=300 > "$out/compose.log" 2>&1 || true
  dc down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$envfile"
  kill "${vmstat_pid:-}" 2>/dev/null || true
  rm -rf "${tmp:-}"
}
trap cleanup EXIT

dc up -d --build --wait db server

# Evidence for a later noisy run: CPU, steal and io-wait of the runner once per second (vmstat -t). Not a gate input.
vmstat -t 1 > "$out/vmstat.txt" 2>&1 &
vmstat_pid=$!

k6_exit=0
k6_start=$SECONDS
BASE_URL=http://127.0.0.1:18080 COLLECTION_KEY=$ckey RATE=$RATE DURATION_S=$DURATION_S WARMUP_S=$WARMUP_S SALT=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n') \
  k6 run --quiet --no-usage-report --summary-export "$out/k6-summary.json" perf/ci-slo.js || k6_exit=$?
# 0 = no threshold crossed, 99 = a threshold crossed (ci_slo.py names the reason). Anything else: k6 did not finish.
if [[ $k6_exit -ne 0 && $k6_exit -ne 99 ]]; then echo "error: k6 exit code $k6_exit" >&2; exit 1; fi

k6_seconds=$((SECONDS - k6_start))
if [[ $mode == measure ]]; then
  python3 -I perf/ci_slo.py --summary "$out/k6-summary.json" --out "$out/result.json" --measure --runner-class "$SLO_RUNNER_CLASS" --seconds "$k6_seconds" --expected-requests $((RATE * DURATION_S))
else
  python3 -I perf/ci_slo.py --summary "$out/k6-summary.json" --out "$out/result.json" --baseline "$baseline" --runner-class "$SLO_RUNNER_CLASS" --seconds "$k6_seconds" --expected-requests $((RATE * DURATION_S))
fi
