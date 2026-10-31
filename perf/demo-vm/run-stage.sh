#!/usr/bin/env bash
# Runs one load stage on the demo VM (perf/demo-vm/README.md). Run on the VM: sudo bash run-stage.sh <rate> <dir>
# <dir> holds collect.js and templates.jsonl; results go to <dir>/rate-<rate>/.
# k6 runs in its own container (--cpus 0.5 --memory 256m) on the host network against the server's 127.0.0.1:8080,
# so nginx and its rate limit are not in the path. docker stats samples every container every 5 s during the run.
set -euo pipefail
RATE="$1"; DIR="$2"
K6_IMAGE="${K6_IMAGE:?set K6_IMAGE to grafana/k6@sha256:...}"
OUT="$DIR/rate-$RATE"; mkdir -p "$OUT"
# The key goes to k6 through the environment (`-e COLLECTION_KEY` without a value), so it is not in the docker argv.
COLLECTION_KEY=$(grep '^SIGNALDOCK_COLLECTION_KEY=' /opt/signaldock/.env | cut -d= -f2-)
export COLLECTION_KEY
SALT=$(printf '%08x' $(( (RANDOM << 15 | RANDOM) & 0x7fffffff )))
echo "$SALT" > "$OUT/salt"

( while :; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' | sed "s/^/$(date +%s),/"; sleep 5; done ) \
  > "$OUT/stats.csv" 2>/dev/null &
STATS=$!
trap 'kill $STATS 2>/dev/null || true' EXIT

K6_RC=0
docker run --rm --name sd-k6 --network host --cpus 0.5 --memory 256m \
  -v "$DIR:/work:ro" -v "$OUT:/out" -u "$(id -u):$(id -g)" \
  -e RATE="$RATE" -e SALT="$SALT" -e WARMUP_S="${WARMUP_S:-30}" -e MEASURE_S="${MEASURE_S:-180}" \
  -e TEMPLATES=/work/templates.jsonl -e BASE_URL=http://127.0.0.1:8080 -e COLLECTION_KEY \
  "$K6_IMAGE" run --quiet --summary-export /out/summary.json --out json=/out/raw.json /work/collect.js \
  > "$OUT/k6.log" 2>&1 || K6_RC=$?
if [ "$K6_RC" -ne 0 ]; then
  echo "error: k6 failed with exit status $K6_RC for rate=$RATE; do not summarize this stage. Log: $OUT/k6.log" >&2
  exit "$K6_RC"
fi
gzip -f "$OUT/raw.json"
echo "done rate=$RATE salt=$SALT"
