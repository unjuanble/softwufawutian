#!/usr/bin/env bash
# apk-tool 的 MCP 验收脚本：重启 App 与 MCP 服务、取出令牌、直接调用工具。
#
# 用法：
#   source apk-tool/scripts/mcp.sh          # 载入 mcpcall / mcpstart
#   mcpstart                                # 重启 App + MCP 服务 + adb forward
#   mcpcall apk_info '{"path":"/sdcard/Download/mt.apk"}'
#   mcpcall apk_resources '{"path":"/sdcard/Download/mt.apk","type":"string","limit":5}'
#
# 依赖：先 `source ../env.sh`（提供 adb / JAVA_HOME 等）。
set -u

AT_PKG=com.example.apktool
AT_APK="${AT_APK:-/Users/wenxintan/Desktop/Code/apk-tool/app/build/outputs/apk/debug/app-debug.apk}"
AT_PORT="${AT_PORT:-8790}"
AT_SERIAL="${AT_SERIAL:-emulator-5554}"

at_adb() { adb -s "$AT_SERIAL" "$@"; }

at_token() {
  at_adb shell "run-as $AT_PKG cat shared_prefs/mcp.xml" 2>/dev/null \
    | tr -d '\r' | sed -n 's/.*name="token">\([^<]*\)<.*/\1/p'
}

# 装包 → 授「所有文件访问」→ 启动 App → 点开 MCP 页并打开开关 → adb forward
mcpstart() {
  [ -f "$AT_APK" ] || { echo "找不到 APK：$AT_APK" >&2; return 1; }
  at_adb install -r "$AT_APK" 2>&1 | tail -1
  at_adb shell appops set "$AT_PKG" MANAGE_EXTERNAL_STORAGE allow
  at_adb shell am start -n "$AT_PKG/.MainActivity" >/dev/null 2>&1
  sleep 2
  at_adb shell input tap 2030 954 >/dev/null 2>&1   # 底部「MCP」页签
  sleep 1
  at_adb shell input tap 236 260 >/dev/null 2>&1    # 左上开关
  sleep 3
  at_adb forward "tcp:$AT_PORT" "tcp:$AT_PORT" >/dev/null
  curl -s -m 5 "http://127.0.0.1:$AT_PORT/health"; echo
}

# 只重启服务（不重新装包）：关闭再打开开关
mcpstop() {
  at_adb shell input tap 236 260 >/dev/null 2>&1
  sleep 1
  echo "stopped"
}

# mcpcall <tool> <json-args>  → 打印工具返回的 text
mcpcall() {
  local tool="$1" args="$2" tok
  tok=$(at_token)
  [ -n "$tok" ] || { echo "取不到令牌" >&2; return 1; }
  python3 - "$AT_PORT" "$tok" "$tool" "$args" <<'PY'
import json, os, sys, urllib.request
port, tok, tool, args = sys.argv[1], sys.argv[2], sys.argv[3], json.loads(sys.argv[4])
body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                   "params": {"name": tool, "arguments": args}}).encode()
req = urllib.request.Request(f"http://127.0.0.1:{port}/mcp", data=body,
                             headers={"Authorization": f"Bearer {tok}",
                                      "Content-Type": "application/json"})
try:
    r = json.load(urllib.request.urlopen(req, timeout=300))
except Exception as e:                     # 网络层失败（服务没起来 / 端口没转发）
    print("调用失败:", e); sys.exit(1)
res = r.get("result", r.get("error"))
cap = int(os.environ.get("AT_PRINT_CAP", "4000"))
if isinstance(res, dict) and "content" in res:
    print(res["content"][0]["text"][:cap])
    if res.get("isError"):
        print("[isError=true]")
else:
    print(json.dumps(res, ensure_ascii=False)[:cap])
PY
}

# mcprpc <method> <json-params>：直接打 JSON-RPC（initialize / tools/list 等）
mcprpc() {
  local method="$1" params="$2" tok
  tok=$(at_token)
  python3 - "$AT_PORT" "$tok" "$method" "$params" <<'PY'
import json, os, sys, urllib.request
port, tok, method, params = sys.argv[1], sys.argv[2], sys.argv[3], json.loads(sys.argv[4])
body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
req = urllib.request.Request(f"http://127.0.0.1:{port}/mcp", data=body,
                             headers={"Authorization": f"Bearer {tok}",
                                      "Content-Type": "application/json"})
r = json.load(urllib.request.urlopen(req, timeout=60))
print(json.dumps(r, ensure_ascii=False)[:int(os.environ.get("AT_PRINT_CAP", "4000"))])
PY
}
