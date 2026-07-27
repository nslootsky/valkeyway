#!/bin/bash
set -e

BASE_DIR="/tmp/valkey-cluster-test"
PORTS=(7000 7001 7002 7003 7004 7005)

cleanup() {
    echo "Cleaning up..."
    for port in "${PORTS[@]}"; do
        pidfile="$BASE_DIR/node-${port}/pid"
        if [ -f "$pidfile" ]; then
            kill "$(cat "$pidfile")" 2>/dev/null || true
            rm -f "$pidfile"
        fi
    done
    rm -rf "$BASE_DIR"
}

if [ "$1" = "stop" ]; then
    cleanup
    echo "Cluster stopped."
    exit 0
fi

# Create directories
rm -rf "$BASE_DIR"
for port in "${PORTS[@]}"; do
    mkdir -p "$BASE_DIR/node-${port}"
done

# Start nodes
for port in "${PORTS[@]}"; do
    cat > "$BASE_DIR/node-${port}/valkey.conf" <<EOF
port $port
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 5000
appendonly yes
daemonize yes
pidfile pid
logfile valkey.log
dir $BASE_DIR/node-${port}
EOF

    echo "Starting node on port $port..."
    valkey-server "$BASE_DIR/node-${port}/valkey.conf"
done

sleep 2

# Create cluster (3 masters + 3 replicas)
echo "Creating cluster..."
valkey-cli --cluster create \
    127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 \
    127.0.0.1:7003 127.0.0.1:7004 127.0.0.1:7005 \
    --cluster-replicas 1 --cluster-yes

echo ""
echo "Cluster created successfully!"
echo "Nodes:"
valkey-cli -p 7000 cluster nodes | awk '{print $2, $7, $1}' | head -6

echo ""
echo "Test with valkey-cli:"
echo "  valkey-cli -p 7000 CLUSTER SLOTS"
echo "  valkey-cli -p 7000 SET testkey hello"
echo ""
echo "Stop cluster:"
echo "  $0 stop"
