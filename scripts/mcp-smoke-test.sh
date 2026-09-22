#!/bin/bash
# Smoke test for the MCP stdio server: runs offline protocol round trips
# against the shaded jar and asserts valid JSON-RPC responses.
# Usage: scripts/mcp-smoke-test.sh [path-to-jar] [secrets-path]
set -e
JAR=${1:-target/bibliographic-api-client-master-SNAPSHOT.jar}
SECRETS=${2:-$HOME/.private/apis/secrets.prop}
JAR=$(realpath "$JAR")
cd "$(dirname "$0")/.."

printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"citation_styles","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"session_status","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":5,"method":"bogus"}' \
| timeout 120 java -jar "$JAR" "$SECRETS" 2>/dev/null \
| python3 -c '
import sys, json
responses = [json.loads(l) for l in sys.stdin if l.strip()]
by_id = {r.get("id"): r for r in responses}
assert 1 in by_id and "result" in by_id[1], "initialize failed"
assert by_id[1]["result"]["serverInfo"]["name"] == "bibliographic-api-client", "bad serverInfo"
assert 2 in by_id and "tools" in by_id[2]["result"], "tools/list failed"
names = [t["name"] for t in by_id[2]["result"]["tools"]]
assert "search" in names and "page_rank" in names and "topic_model" in names, f"missing tools: {names}"
assert 3 in by_id and by_id[3]["result"]["isError"] is False, "citation_styles failed"
assert 4 in by_id and "Records:" in by_id[4]["result"]["content"][0]["text"], "session_status failed"
assert 5 in by_id and by_id[5]["error"]["code"] == -32601, "unknown method not handled"
print(f"MCP smoke test PASSED: {len(names)} tools, protocol round trips OK")
'
