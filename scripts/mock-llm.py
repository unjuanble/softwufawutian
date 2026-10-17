#!/usr/bin/env python3
"""极简 OpenAI 兼容 mock 服务：验证 APK 工具箱 Agent 的 function calling 与确认弹窗。
   脚本化：第一次 → file_list；看到 file_list 结果且用户提到 write → file_write_text；之后 → 总结文本。"""
import json, time
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = "/tmp/mock-llm.log"

def log(s):
    with open(LOG, "a") as f:
        f.write(time.strftime("%H:%M:%S ") + s + "\n")

def tool_call(cid, name, args):
    return {"id": cid, "type": "function",
            "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/models"):
            self._json({"object": "list", "data": [{"id": "mock", "object": "model"}]})
        else:
            self._json({"ok": True})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            req = json.loads(raw)
        except Exception:
            req = {}
        msgs = req.get("messages") or []
        log("REQ messages=%d tools=%d" % (len(msgs), len(req.get("tools") or [])))
        for m in msgs[-3:]:
            log("   %s: %s" % (m.get("role"), (str(m.get("content")) or "")[:90].replace("\n", " ")))

        user_text = ""
        for m in reversed(msgs):
            if m.get("role") == "user":
                user_text = str(m.get("content") or "")
                break
        # 按「assistant 已经发出过哪些 tool_calls」推进脚本，避免无限重试
        called = [c["function"]["name"] for m in msgs if m.get("role") == "assistant"
                  for c in (m.get("tool_calls") or [])]
        has_list = "file_list" in called
        has_write = "file_write_text" in called

        base = {"id": "mock-%d" % int(time.time()), "object": "chat.completion", "created": int(time.time()), "model": "mock"}

        if not has_list:
            msg = {"role": "assistant", "content": "",
                   "tool_calls": [tool_call("call_1", "file_list", {"path": "/sdcard/Download", "limit": 20})]}
            finish = "tool_calls"
        elif "write" in user_text.lower() and not has_write:
            msg = {"role": "assistant", "content": "",
                   "tool_calls": [tool_call("call_2", "file_write_text",
                                            {"path": "/sdcard/Download/agent-test.txt",
                                             "text": "hello from mock llm\n第二行\n"})]}
            finish = "tool_calls"
        else:
            msg = {"role": "assistant", "content": "完成：已列出 /sdcard/Download 的内容。"}
            finish = "stop"

        log("RESP finish=%s" % finish)
        self._json({**base, "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}})

if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8899), H).serve_forever()
