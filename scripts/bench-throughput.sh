#!/bin/bash
# Throughput/latency matrix for SET/GET, unpipelined and pipelined.
#
# Usage:
#   scripts/bench-throughput.sh <label> <valkey-benchmark target args...>
# Examples:
#   scripts/bench-throughput.sh proxy  -p 6379
#   scripts/bench-throughput.sh direct --cluster -p 7000
#
# Output: one line per test with rps and p50/p99/max latency (ms).
set -euo pipefail

label=$1
shift
requests=${REQUESTS:-300000}

# Warm up the JIT before measuring.
valkey-benchmark "$@" -t set,get -n "$requests" -c 50 -r 100000 -q >/dev/null 2>&1

for P in 1 16; do
  for c in 1 50 200; do
    out=$(valkey-benchmark "$@" -t set,get -n "$requests" -c "$c" -P "$P" -r 100000 --csv 2>&1 || true)
    if ! grep -qE '^"(SET|GET)"' <<<"$out"; then
      printf '%-7s c=%-4s P=%-3s FAILED: %s\n' "$label" "$c" "$P" "$(tail -c 300 <<<"$out" | tr '\n\r' '  ')"
      continue
    fi
    grep -E '^"(SET|GET)"' <<<"$out" | tr -d '"' |
      awk -F, -v l="$label" -v c="$c" -v p="$P" \
        '{printf "%-7s c=%-4s P=%-3s %-4s rps=%-11s p50=%-7s p99=%-7s max=%s\n", l, c, p, $1, $2, $5, $7, $8}'
  done
done
