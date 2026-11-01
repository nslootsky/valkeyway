#!/bin/bash
# Measures how a blocking command (BLPOP) affects *other* clients.
#
# 1. Thread stall: one client blocks in BLPOP for 5s while 96 new connections each send PING.
#    Any PING slower than 1s was stuck behind the BLPOP.
# 2. Node stall: one client blocks in BLPOP while another client GETs a key in the same slot
#    and a key on a different node. A healthy proxy answers both immediately.
#
# Usage: scripts/bench-blocking-stall.sh [port]
set -uo pipefail

port=${1:-6379}
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

ms_since() { echo $(( ($(date +%s%N) - $1) / 1000000 )); }

echo "== thread stall (port $port)"
valkey-cli -p "$port" del '{hol}list' >/dev/null
valkey-cli -p "$port" blpop '{hol}list' 5 >/dev/null &
sleep 0.3
for _ in $(seq 1 96); do
  ( s=$(date +%s%N); valkey-cli -p "$port" ping >/dev/null; ms_since "$s" ) &
done | sort -n >"$tmp"
wait
echo "PING ms over 96 connections: min=$(head -1 "$tmp") median=$(sed -n 48p "$tmp") max=$(tail -1 "$tmp") stalled(>1s)=$(awk '$1>1000' "$tmp" | wc -l)"

echo "== node stall (port $port)"
valkey-cli -p "$port" set '{hol}k' v >/dev/null
valkey-cli -p "$port" blpop '{hol}list' 4 >/dev/null &
sleep 0.3
# "foo" hashes to slot 12182, "{hol}k" to slot 1268; on a 3-primary cluster they are on different nodes.
( s=$(date +%s%N); r=$(valkey-cli -p "$port" get '{hol}k' 2>&1); echo "same-slot GET:  $(ms_since "$s")ms -> $r" ) &
( s=$(date +%s%N); r=$(valkey-cli -p "$port" get foo 2>&1); echo "other-node GET: $(ms_since "$s")ms -> ${r:-(nil)}" ) &
wait
