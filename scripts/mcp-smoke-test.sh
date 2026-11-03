#!/bin/bash
# [TESTS](/design/test-scripts/test-001-mcp-protocol-smoke-test.md)
# Smoke test for the MCP server: starts the built jar on an ephemeral port, drives
# a real streamable-HTTP handshake against it, and checks the packaging can still
# render a citation (issue-022).
#
# Offline: the tools it calls read the repository and never an API. The server is
# given a temporary repository and Zotero off, so it never touches the real ones.
# Usage: mcp-smoke-test.sh [path-to-jar]
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=${1:-$(ls target/bibliographic-api-client-*.jar 2>/dev/null | head -1)}
JAR=$(realpath "$JAR")

WORK=$(mktemp -d)
PORT=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
cat > "$WORK/smoke.prop" <<EOF
zotero-mode=off
mcp-port=$PORT
repository-directory=$WORK/repositories
EOF

java -jar "$JAR" "$WORK/smoke.prop" > "$WORK/server.log" 2>&1 &
SERVER=$!
cleanup() { kill "$SERVER" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

URL="http://127.0.0.1:$PORT/mcp"
for _ in $(seq 1 50); do
  if curl -sS -o /dev/null --max-time 1 -X POST "$URL" 2>/dev/null; then break; fi
  if ! kill -0 "$SERVER" 2>/dev/null; then echo "server exited:"; cat "$WORK/server.log"; exit 1; fi
  python3 -c 'import time;time.sleep(0.2)'
done

H_CT="Content-Type: application/json"
H_ACC="Accept: application/json, text/event-stream"
post() { local body="$1"; shift; curl -sS -D "$WORK/headers" -H "$H_CT" -H "$H_ACC" "$@" -d "$body" "$URL"; }

INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"1"}}}'
INIT_OUT=$(post "$INIT")
SESSION=$(grep -i '^mcp-session-id:' "$WORK/headers" | tr -d '\r' | awk '{print $2}')
H_SESS="Mcp-Session-Id: ${SESSION:-none}"
post '{"jsonrpc":"2.0","method":"notifications/initialized"}' -H "$H_SESS" -o /dev/null
TOOLS_OUT=$(post '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' -H "$H_SESS")
LIST_OUT=$(post '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_records","arguments":{}}}' -H "$H_SESS")
CRAWL_OUT=$(post '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"crawl","arguments":{"action":"status"}}}' -H "$H_SESS")

export INIT_OUT TOOLS_OUT LIST_OUT CRAWL_OUT
python3 - <<'PY'
import json, os, sys

def payload(raw):
    # a streamable-HTTP response is either a json body or an SSE frame carrying one
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith("data:"):
            return json.loads(line[5:].strip())
        if line.startswith("{"):
            return json.loads(line)
    raise AssertionError("no JSON-RPC payload in: " + raw[:200])

init = payload(os.environ["INIT_OUT"])
assert init["result"]["serverInfo"]["name"] == "bibliographic-api-client", init
assert "protocolVersion" in init["result"], init

tools = sorted(t["name"] for t in payload(os.environ["TOOLS_OUT"])["result"]["tools"])
expected = ["crawl", "list_records", "rank", "search", "select", "zotero_sync"]
assert tools == expected, f"tool surface drifted: {tools}"

listed = payload(os.environ["LIST_OUT"])["result"]
assert listed["isError"] is False, listed
assert json.loads(listed["content"][0]["text"]) == [], listed

crawl = payload(os.environ["CRAWL_OUT"])["result"]
assert crawl["isError"] is False, crawl
assert "no crawl has been run" in crawl["content"][0]["text"], crawl

print(f"MCP smoke test PASSED: {len(tools)} tools over HTTP, protocol round trips OK")
PY

# Rendering a citation goes through a javascript engine whose language registration
# lives in a dependency's resources, so it is the packaging rather than the code that
# decides whether it works — and the test suite cannot tell (issue-022).
java -cp "$JAR" scripts/RenderCheck.java
