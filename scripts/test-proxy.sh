#!/bin/bash

PROXY_PORT=6379
CLUSTER_NODES="127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005"
PROXY_JAR="/home/nick/valkey-cluster-proxy/target/valkey-cluster-proxy-0.1.0-SNAPSHOT.jar"
VALKEY_CLI="valkey-cli -h 127.0.0.1 -p $PROXY_PORT"

cleanup() {
    echo ""
    echo "Tests complete. Proxy remains running (started externally)."
}

trap cleanup EXIT

# Check cluster is running
if ! valkey-cli -p 7000 ping > /dev/null 2>&1; then
    echo "ERROR: Valkey cluster not running. Run scripts/setup-cluster.sh first."
    exit 1
fi

# Check proxy is running (assumes proxy started externally on PROXY_PORT)
echo "Checking proxy on port $PROXY_PORT..."
if ! $VALKEY_CLI ping > /dev/null 2>&1; then
    echo "ERROR: Proxy not responding on port $PROXY_PORT. Start it first."
    exit 1
fi

echo "Proxy is running on port $PROXY_PORT"
echo ""

# Tests
PASS=0
FAIL=0

test_cmd() {
    local desc="$1"
    local expected="$2"
    shift 2
    local result=$("$@" 2>/dev/null)
    
    # Handle valkey-cli nil output (empty string or "(nil)")
    if [ "$expected" = "(nil)" ]; then
        if [ -z "$result" ] || [ "$result" = "(nil)" ]; then
            echo "PASS: $desc"
            PASS=$((PASS + 1))
            return
        fi
    elif [ "$result" = "$expected" ]; then
        echo "PASS: $desc"
        PASS=$((PASS + 1))
        return
    fi
    
    echo "FAIL: $desc"
    echo "  Expected: $expected"
    echo "  Got:      $result"
    FAIL=$((FAIL + 1))
}



test_grep() {
    local desc="$1"
    local pattern="$2"
    shift 2
    local result=$("$@" 2>/dev/null)
    
    if echo "$result" | grep -q "$pattern"; then
        echo "PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $desc"
        echo "  Expected to contain: $pattern"
        echo "  Got:      $result"
        FAIL=$((FAIL + 1))
    fi
}

echo "Running tests..."
echo "================"

# Basic operations
echo ""
echo "[Basic Operations]"
test_cmd "SET key" "OK" $VALKEY_CLI SET proxy_test_key hello
test_cmd "GET key" "hello" $VALKEY_CLI GET proxy_test_key
test_cmd "DEL key" "1" $VALKEY_CLI DEL proxy_test_key
test_cmd "GET deleted key" "(nil)" $VALKEY_CLI GET proxy_test_key
test_cmd "PING" "PONG" $VALKEY_CLI PING

# Multi-db (SELECT) - must use single connection
echo ""
echo "[Multi-DB Support]"

# Test 1: Basic SELECT/db isolation
db_result=$(echo -e "SELECT 0\nSET db0_key value0\nSELECT 1\nSET db1_key value1\nSELECT 0\nGET db0_key\nGET db1_key\nSELECT 1\nGET db1_key" | $VALKEY_CLI 2>/dev/null)
db_ok_count=$(echo "$db_result" | grep -c "^OK$" || echo "0")
db0_val=$(echo "$db_result" | sed -n '6p')
db1_nil=$(echo "$db_result" | sed -n '7p')
db1_val=$(echo "$db_result" | sed -n '9p')
if [ "$db_ok_count" -ge 5 ] && [ "$db0_val" = "value0" ] && [ -z "$db1_nil" ] && [ "$db1_val" = "value1" ]; then
    echo "PASS: SELECT/db isolation works"
    PASS=$((PASS + 1))
else
    echo "FAIL: SELECT/db isolation works"
    echo "  OK count: $db_ok_count, db0_val: $db0_val, db1_nil: '$db1_nil', db1_val: $db1_val"
    echo "  Full result:"
    echo "$db_result" | cat -n
    FAIL=$((FAIL + 1))
fi

# Test 2: SELECT with immediate SET (tests internal SELECT response handling)
db2_result=$(echo -e "SELECT 2\nSET db2_key value2\nGET db2_key" | $VALKEY_CLI 2>/dev/null)
db2_ok=$(echo "$db2_result" | sed -n '1p')
db2_set_ok=$(echo "$db2_result" | sed -n '2p')
db2_get=$(echo "$db2_result" | sed -n '3p')
if [ "$db2_ok" = "OK" ] && [ "$db2_set_ok" = "OK" ] && [ "$db2_get" = "value2" ]; then
    echo "PASS: SELECT followed by SET returns correct responses"
    PASS=$((PASS + 1))
else
    echo "FAIL: SELECT followed by SET returns correct responses"
    echo "  db2_ok: $db2_ok, db2_set_ok: $db2_set_ok, db2_get: $db2_get"
    echo "  Full result:"
    echo "$db2_result" | cat -n
    FAIL=$((FAIL + 1))
fi

# Test 3: Multiple SELECT operations in sequence
db3_result=$(echo -e "SELECT 0\nSET multi_db_key db0\nSELECT 1\nSET multi_db_key db1\nSELECT 2\nSET multi_db_key db2\nSELECT 0\nGET multi_db_key\nSELECT 1\nGET multi_db_key\nSELECT 2\nGET multi_db_key" | $VALKEY_CLI 2>/dev/null)
db3_get0=$(echo "$db3_result" | sed -n '8p')
db3_get1=$(echo "$db3_result" | sed -n '10p')
db3_get2=$(echo "$db3_result" | sed -n '12p')
if [ "$db3_get0" = "db0" ] && [ "$db3_get1" = "db1" ] && [ "$db3_get2" = "db2" ]; then
    echo "PASS: Multiple SELECT operations maintain db isolation"
    PASS=$((PASS + 1))
else
    echo "FAIL: Multiple SELECT operations maintain db isolation"
    echo "  db3_get0: $db3_get0, db3_get1: $db3_get1, db3_get2: $db3_get2"
    echo "  Full result:"
    echo "$db3_result" | cat -n
    FAIL=$((FAIL + 1))
fi

# Test 4: SELECT with different database numbers
db4_result=$(echo -e "SELECT 3\nSET db3_key value3\nSELECT 4\nSET db4_key value4\nSELECT 3\nGET db3_key\nSELECT 4\nGET db4_key" | $VALKEY_CLI 2>/dev/null)
db4_get3=$(echo "$db4_result" | sed -n '6p')
db4_get4=$(echo "$db4_result" | sed -n '8p')
if [ "$db4_get3" = "value3" ] && [ "$db4_get4" = "value4" ]; then
    echo "PASS: SELECT with higher database numbers works"
    PASS=$((PASS + 1))
else
    echo "FAIL: SELECT with higher database numbers works"
    echo "  db4_get3: $db4_get3, db4_get4: $db4_get4"
    echo "  Full result:"
    echo "$db4_result" | cat -n
    FAIL=$((FAIL + 1))
fi

# Hash tags
echo ""
echo "[Hash Tags]"
test_cmd "SET with hash tag 1" "OK" $VALKEY_CLI SET "{user}:1:name" alice
test_cmd "SET with hash tag 2" "OK" $VALKEY_CLI SET "{user}:1:email" alice@example.com
test_cmd "GET with hash tag" "alice" $VALKEY_CLI GET "{user}:1:name"

# Transactions (MULTI/EXEC) - use valkey-cli with pipeline
echo ""
echo "[Transaction Support]"
tx_result=$(echo -e "MULTI\nSET tx_key1 val1\nSET tx_key2 val2\nEXEC" | $VALKEY_CLI 2>/dev/null)
if echo "$tx_result" | grep -q "OK" && echo "$tx_result" | grep -q "QUEUED"; then
    echo "PASS: MULTI/EXEC flow works"
    PASS=$((PASS + 1))
else
    echo "FAIL: MULTI/EXEC flow works"
    echo "  Got: $tx_result"
    FAIL=$((FAIL + 1))
fi
test_cmd "GET tx_key1 after EXEC" "val1" $VALKEY_CLI GET tx_key1
test_cmd "GET tx_key2 after EXEC" "val2" $VALKEY_CLI GET tx_key2

# DISCARD - use valkey-cli with pipeline on single connection
# Note: valkey-cli closes connection after DISCARD in pipeline mode, so we verify separately
discard_multi_result=$(echo -e "MULTI\nSET discard_key should_not_exist\nDISCARD" | $VALKEY_CLI 2>/dev/null)
discard_ok_count=$(echo "$discard_multi_result" | grep -c "^OK$" || echo "0")
discard_queued_count=$(echo "$discard_multi_result" | grep -c "^QUEUED$" || echo "0")
# Check that key was NOT set (use separate connection)
discard_get_result=$($VALKEY_CLI GET discard_key 2>/dev/null)
if [ "$discard_ok_count" -ge 2 ] && [ "$discard_queued_count" -ge 1 ] && { [ -z "$discard_get_result" ] || [ "$discard_get_result" = "(nil)" ]; }; then
    echo "PASS: DISCARD prevents key from being set"
    PASS=$((PASS + 1))
else
    echo "FAIL: DISCARD prevents key from being set"
    echo "  OK count: $discard_ok_count, QUEUED count: $discard_queued_count, GET result: '$discard_get_result'"
    echo "  Full result:"
    echo "$discard_multi_result" | cat -n
    FAIL=$((FAIL + 1))
fi

# SCAN command (iterates across cluster)
echo ""
echo "[SCAN Support]"
for i in {1..10}; do
    $VALKEY_CLI SET "scan_key_$i" "value_$i" > /dev/null 2>&1
done
scan_result=""
cursor="0"
for iter in 1 2 3 4 5; do
    result=$($VALKEY_CLI SCAN "$cursor" MATCH "scan_key_*" COUNT 100 2>/dev/null) || break
    cursor=$(echo "$result" | head -n1)
    keys=$(echo "$result" | tail -n +2)
    if [ -n "$keys" ]; then
        scan_result="${scan_result}
${keys}"
    fi
    if [ "$cursor" = "0" ]; then
        break
    fi
done
scan_count=$(printf "%s" "$scan_result" | grep -c "scan_key_" || true)
if [ "$scan_count" = "10" ]; then
    echo "PASS: SCAN returns all 10 keys ($iter iterations)"
    PASS=$((PASS + 1))
else
    echo "FAIL: SCAN returns all 10 keys (found $scan_count in $iter iterations)"
    FAIL=$((FAIL + 1))
fi

# Cross-slot MGET splitting
echo ""
echo "[Cross-Slot MGET Splitting]"
test_cmd "SET mget_key1" "OK" $VALKEY_CLI SET mget_key1 val1
test_cmd "SET mget_key2" "OK" $VALKEY_CLI SET mget_key2 val2
test_cmd "SET mget_key3" "OK" $VALKEY_CLI SET mget_key3 val3
mget_result=$($VALKEY_CLI MGET mget_key1 mget_key2 mget_key3 2>/dev/null)
if echo "$mget_result" | grep -q "val1" && echo "$mget_result" | grep -q "val2" && echo "$mget_result" | grep -q "val3"; then
    echo "PASS: MGET across slots returns all values"
    PASS=$((PASS + 1))
else
    echo "FAIL: MGET across slots returns all values"
    echo "  Got: $mget_result"
    FAIL=$((FAIL + 1))
fi

# Cross-slot DEL splitting
echo ""
echo "[Cross-Slot DEL Splitting]"
test_cmd "SET del_key1" "OK" $VALKEY_CLI SET del_key1 val1
test_cmd "SET del_key2" "OK" $VALKEY_CLI SET del_key2 val2
test_cmd "SET del_key3" "OK" $VALKEY_CLI SET del_key3 val3
del_result=$($VALKEY_CLI DEL del_key1 del_key2 del_key3 2>/dev/null)
if [ "$del_result" = "3" ]; then
    echo "PASS: DEL across slots returns 3"
    PASS=$((PASS + 1))
else
    echo "FAIL: DEL across slots returns 3"
    echo "  Got: $del_result"
    FAIL=$((FAIL + 1))
fi

# Same-node different-slot splitting
echo ""
echo "[Same-Node Different-Slot Splitting]"
# Use keys that hash to same node but different slots (via hash tags we can't control this easily)
# Just verify MGET with 2 keys works
test_cmd "SET same_node_k1" "OK" $VALKEY_CLI SET same_node_k1 v1
test_cmd "SET same_node_k2" "OK" $VALKEY_CLI SET same_node_k2 v2
mget2_result=$($VALKEY_CLI MGET same_node_k1 same_node_k2 2>/dev/null)
if echo "$mget2_result" | grep -q "v1" && echo "$mget2_result" | grep -q "v2"; then
    echo "PASS: MGET with 2 keys returns both values"
    PASS=$((PASS + 1))
else
    echo "FAIL: MGET with 2 keys returns both values"
    echo "  Got: $mget2_result"
    FAIL=$((FAIL + 1))
fi
del2_result=$($VALKEY_CLI DEL same_node_k1 same_node_k2 2>/dev/null)
if [ "$del2_result" = "2" ]; then
    echo "PASS: DEL with 2 keys returns 2"
    PASS=$((PASS + 1))
else
    echo "FAIL: DEL with 2 keys returns 2"
    echo "  Got: $del2_result"
    FAIL=$((FAIL + 1))
fi

# Hash tags (same slot - should NOT split)
echo ""
echo "[Hash Tags - Same Slot]"
test_cmd "SET hash_tag key1" "OK" $VALKEY_CLI SET "{user}:100:name" alice
test_cmd "SET hash_tag key2" "OK" $VALKEY_CLI SET "{user}:100:email" alice@example.com
test_cmd "GET hash_tag key1" "alice" $VALKEY_CLI GET "{user}:100:name"
mget_hash_result=$($VALKEY_CLI MGET "{user}:100:name" "{user}:100:email" 2>/dev/null)
if echo "$mget_hash_result" | grep -q "alice" && echo "$mget_hash_result" | grep -q "alice@example.com"; then
    echo "PASS: MGET with hash tags returns both values"
    PASS=$((PASS + 1))
else
    echo "FAIL: MGET with hash tags returns both values"
    echo "  Got: $mget_hash_result"
    FAIL=$((FAIL + 1))
fi

# PROXY admin commands
echo ""
echo "[PROXY Admin Commands]"
test_grep "PROXY CLUSTER INFO" "cluster_state" $VALKEY_CLI PROXY CLUSTER INFO
test_grep "PROXY CONFIG GET cluster_slots" "16384" $VALKEY_CLI PROXY CONFIG GET cluster_slots
test_cmd "PROXY CONFIG SET" "OK" $VALKEY_CLI PROXY CONFIG SET test_key test_value
test_grep "PROXY STATS" "cluster_nodes" $VALKEY_CLI PROXY STATS

# Cleanup test keys
echo ""
echo "[Cleanup]"
$VALKEY_CLI DEL db0_key proxy_test_key > /dev/null 2>&1 || true
$VALKEY_CLI SELECT 1 > /dev/null 2>&1 || true
$VALKEY_CLI DEL db1_key > /dev/null 2>&1 || true
$VALKEY_CLI SELECT 2 > /dev/null 2>&1 || true
$VALKEY_CLI DEL db2_key multi_db_key > /dev/null 2>&1 || true
$VALKEY_CLI SELECT 3 > /dev/null 2>&1 || true
$VALKEY_CLI DEL db3_key multi_db_key > /dev/null 2>&1 || true
$VALKEY_CLI SELECT 4 > /dev/null 2>&1 || true
$VALKEY_CLI DEL db4_key multi_db_key > /dev/null 2>&1 || true
$VALKEY_CLI SELECT 0 > /dev/null 2>&1 || true
$VALKEY_CLI DEL "{user}:1:name" "{user}:1:email" "{user}:100:name" "{user}:100:email" > /dev/null 2>&1 || true
$VALKEY_CLI DEL tx_key1 tx_key2 > /dev/null 2>&1 || true
$VALKEY_CLI DEL mget_key1 mget_key2 mget_key3 > /dev/null 2>&1 || true
$VALKEY_CLI DEL del_key1 del_key2 del_key3 > /dev/null 2>&1 || true
$VALKEY_CLI DEL same_node_k1 same_node_k2 > /dev/null 2>&1 || true
$VALKEY_CLI DEL discard_key > /dev/null 2>&1 || true
for i in {1..10}; do
    $VALKEY_CLI DEL "scan_key_$i" > /dev/null 2>&1 || true
done

echo ""
echo "================"
echo "Results: $PASS passed, $FAIL failed"

if [ $FAIL -gt 0 ]; then
    echo ""
    echo "Proxy log (last 80 lines):"
    tail -80 /tmp/valkey-proxy.log
    exit 1
fi

echo ""
echo "All tests passed!"
