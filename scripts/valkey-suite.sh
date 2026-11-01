#!/bin/bash
# Runs Valkey's own test suite against the proxy and, for comparison, against a standalone server,
# and lists the tests that pass on the server but not through the proxy.
# See docs/VALKEY_TEST_SUITE.md.
#
# Usage: scripts/valkey-suite.sh [unit ...]      e.g. scripts/valkey-suite.sh unit/type/list unit/multi
#
# Needs: valkey-server and valkey-cli on the PATH (their version picks the test suite's version),
# tclsh 8.6, git, java, and the proxy built (./mvnw package -DskipTests).
# Uses ports 7100-7102 (cluster), 7199 (standalone) and 6500 (proxy).
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${VALKEY_SUITE_DIR:-$ROOT/target/valkey-suite}
VERSION=${VALKEY_VERSION:-$(valkey-server --version | sed -E 's/.*v=([0-9.]+).*/\1/')}
SRC=$WORK/valkey-$VERSION
JAR=$(ls "$ROOT"/target/valkeyway-*.jar 2>/dev/null | head -1)
CLUSTER_PORTS="7100 7101 7102"
STANDALONE=7199
PROXY=6500
# Tests of a server's internals, which can't work through a proxy: replication streams, DEBUG,
# persistence. Excluded for both targets, so the comparison stays fair.
DENY_TAGS="-needs:repl -needs:debug -needs:save -needs:pfdebug -needs:local-process"
DEFAULT_UNITS="unit/type/string unit/type/incr unit/type/hash unit/type/set unit/type/list unit/type/zset
  unit/type/stream unit/type/stream-cgroups unit/expire unit/keyspace unit/multi unit/pubsub unit/protocol
  unit/scan unit/bitops unit/hyperloglog unit/geo unit/sort unit/dump unit/info-command unit/quit"

[ -n "$JAR" ] || { echo "Build the proxy first: ./mvnw package -DskipTests"; exit 1; }
mkdir -p "$WORK/out"
if [ ! -d "$SRC" ]; then
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$VERSION" https://github.com/valkey-io/valkey.git "$SRC" || exit 1
    mkdir -p "$SRC/src"
    ln -sf "$(command -v valkey-server)" "$SRC/src/valkey-server"
    ln -sf "$(command -v valkey-cli)" "$SRC/src/valkey-cli"
fi

stop() {
    [ -f "$WORK/proxy.pid" ] && kill "$(cat "$WORK/proxy.pid")" 2>/dev/null
    for p in $CLUSTER_PORTS $STANDALONE; do valkey-cli -p "$p" shutdown nosave >/dev/null 2>&1; done
    for _ in $(seq 1 20); do ss -ltn | grep -qE ":(710[0-2]|$STANDALONE|$PROXY) " || break; sleep 0.5; done
    if ss -ltn | grep -qE ":(710[0-2]|$STANDALONE|$PROXY) "; then
        # Something else holds a port: results would come from it, not from this run's servers.
        echo "Ports still in use (7100-7102, $STANDALONE, $PROXY):" >&2
        ss -ltnp 2>/dev/null | grep -E ":(710[0-2]|$STANDALONE|$PROXY) " >&2
        exit 1
    fi
}

# A fresh standalone server, cluster and proxy: tests that change a server's settings and then fail
# leave them changed, which would skew every later unit.
start() {
    stop
    for p in $CLUSTER_PORTS; do
        rm -rf "$WORK/n$p" && mkdir -p "$WORK/n$p"
        valkey-server --port "$p" --cluster-enabled yes --cluster-config-file nodes.conf --cluster-databases 16 \
            --dir "$WORK/n$p" --daemonize yes --save "" --logfile "$WORK/n$p/log" --enable-debug-command yes
    done
    rm -rf "$WORK/sa" && mkdir -p "$WORK/sa"
    valkey-server --port $STANDALONE --dir "$WORK/sa" --daemonize yes --save "" --logfile "$WORK/sa/log" \
        --enable-debug-command yes
    sleep 1
    yes yes | valkey-cli --cluster create $(for p in $CLUSTER_PORTS; do printf '127.0.0.1:%s ' "$p"; done) \
        --cluster-replicas 0 >/dev/null
    for _ in $(seq 1 30); do valkey-cli -p 7100 cluster info | grep -q cluster_state:ok && break; sleep 0.5; done
    # node-admin-commands=forward: the suite sets server options with CONFIG SET (Glide sends it to every node).
    nohup java -jar "$JAR" --proxy.port=$PROXY --proxy.cluster-nodes=127.0.0.1:7100,127.0.0.1:7101,127.0.0.1:7102 \
        --proxy.node-admin-commands=forward --server.port=0 --management.server.port=0 \
        --logging.level.io.github.nslootsky.valkeyway=WARN > "$WORK/proxy.log" 2>&1 &
    echo $! > "$WORK/proxy.pid"
    for _ in $(seq 1 60); do valkey-cli -p $PROXY ping 2>/dev/null | grep -q PONG && break; sleep 0.5; done
    kill -0 "$(cat "$WORK/proxy.pid")" 2>/dev/null || { echo "The proxy didn't start; see $WORK/proxy.log" >&2; exit 1; }
}

trap stop EXIT
cd "$SRC" || exit 1
printf "%-28s %8s %8s %12s\n" unit server proxy "proxy fails"
for unit in ${*:-$DEFAULT_UNITS}; do
    name=${unit//\//_}
    start
    for target in server:$STANDALONE proxy:$PROXY; do
        t=${target%%:*}; port=${target##*:}
        timeout 900 ./runtest --host 127.0.0.1 --port "$port" --cluster-mode --single "$unit" --timeout 60 \
            --tags "$DENY_TAGS" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' > "$WORK/out/$t-$name.log"
        grep -oE '^\[ok\]: .*' "$WORK/out/$t-$name.log" | sed -E 's/^\[ok\]: //; s/ \([0-9]+ ms\)$//' | sort -u \
            > "$WORK/out/$t-$name.ok"
    done
    comm -23 "$WORK/out/server-$name.ok" "$WORK/out/proxy-$name.ok" > "$WORK/out/proxy-only-$name.txt"
    printf "%-28s %8s %8s %12s\n" "$unit" "$(wc -l < "$WORK/out/server-$name.ok")" \
        "$(wc -l < "$WORK/out/proxy-$name.ok")" "$(wc -l < "$WORK/out/proxy-only-$name.txt")"
    grep -m1 -E '^\[exception\]' "$WORK/out/proxy-$name.log" | cut -c1-160 | sed 's/^/    /'
done
echo
echo "server, proxy: tests passed. proxy fails: tests that pass on the server but not through the proxy;"
echo "they're listed in $WORK/out/proxy-only-<unit>.txt, with details in $WORK/out/proxy-<unit>.log."
