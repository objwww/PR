#!/usr/bin/env python3
# E2E-M3-03 badmodel stub：OpenAI chat.completion 形状、内容 = 非 JSON 散文。
# 用途：经 litellm badmodel 路由喂给 holmes，触发 control-app 结构验证 REJECTED_MALFORMED
#       （INV-AM3-7 同权落档）→ eval STRUCTURE_REJECTED 进分母。测试脚手架，非产品代码。
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

GARBAGE = "THIS IS NOT A VALID REPORT: {{{ broken json, no analysis field at all"


class Handler(BaseHTTPRequestHandler):

    def _reply(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._reply({"ok": True})

    def do_POST(self):
        # 兼容 chunked（java/okhttp 链可能不发 Content-Length）
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                self.rfile.read(size)
                self.rfile.readline()
        else:
            length = int(self.headers.get("Content-Length", "0") or 0)
            self.rfile.read(length)
        self._reply({
            "id": "stub-badmodel", "object": "chat.completion", "created": 1,
            "model": "badmodel",
            "choices": [{"index": 0,
                         "message": {"role": "assistant", "content": GARBAGE},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
        })

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
