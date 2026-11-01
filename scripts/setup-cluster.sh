#!/bin/bash
# A local Valkey cluster from the valkey-server on the PATH, for running the proxy jar against:
# 3 primaries on ports 7000-7002, each with a replica on 7003-7005, 16 databases (for SELECT).
#
# Usage: scripts/setup-cluster.sh          start (replacing a cluster this script started)
#        scripts/setup-cluster.sh stop     stop it and delete its data
#
# For the cluster and the proxy together in containers, use docker-compose.dev.yml instead.
set -euo pipefail

BASE_DIR=${CLUSTER_DIR:-/tmp/valkey-cluster-test}
PORTS=(7000 7001 7002 7003 7004 7005)

stop() {
    for port in "${PORTS[@]}"; do
        valkey-cli -p "$port" shutdown nosave >/dev/null 2>&1 || true
    done
    rm -rf "$BASE_DIR"
}

if [ "${1:-}" = "stop" ]; then
    stop
    echo "Cluster stopped."
    exit 0
fi

stop
for port in "${PORTS[@]}"; do
    dir="$BASE_DIR/node-$port"
    mkdir -p "$dir"
    valkey-server --port "$port" --dir "$dir" --daemonize yes --logfile "$dir/valkey.log" \
        --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 \
        --cluster-databases 16 --appendonly no --save ""
done

for port in "${PORTS[@]}"; do
    until valkey-cli -p "$port" ping >/dev/null 2>&1; do sleep 0.2; done
done
valkey-cli --cluster create \
    127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 127.0.0.1:7003 127.0.0.1:7004 127.0.0.1:7005 \
    --cluster-replicas 1 --cluster-yes >/dev/null
until valkey-cli -p 7000 cluster info | grep -q cluster_state:ok; do sleep 0.5; done

echo "Cluster ready on 127.0.0.1:7000-7005 (primaries 7000-7002)."
echo "Run the proxy: java -jar target/valkeyway-*.jar"
echo "Stop:          $0 stop"
