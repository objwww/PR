"""Spike P1 echo server: stand-in OpenAI-compatible endpoint.

Logs every /v1/chat/completions request (path, headers, body) to stdout so the
test harness can capture exactly what HolmesGPT's litellm client sends on the
wire. Returns scripted fake responses (plain text or looping tool_calls) so
experiments run offline with zero token cost.

Env:
  FAKE_MODE   plain            -> always a plain assistant message (stop)
               toolloop        -> always a tool_calls response (drives iterations)
               toolN           -> first N requests return tool_calls, then plain
"""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

MODE = os.environ.get("FAKE_MODE", "plain")
TOOL_BUDGET = int(MODE[4:]) if MODE.startswith("tool") and MODE[4:].isdigit() else None
_req_no = 0

TOOL_CALLS_RESP = {
    "id": "chatcmpl-spike-tool",
    "object": "chat.completions",
    "created": 1700000000,
    "model": "spike",
    "choices": [{
        "index": 0,
        "message": {
            "role": "assistant",
            "content": None,
            "tool_calls": [{
                "id": "call_spike_1",
                "type": "function",
                "function": {
                    "name": "no_such_tool_spike",
                    "arguments": "{\"fake_param\": \"x\"}",
                },
            }],
        },
        "finish_reason": "tool_calls",
    }],
    "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
}

PLAIN_RESP = {
    "id": "chatcmpl-spike-plain",
    "object": "chat.completions",
    "created": 1700000000,
    "model": "spike",
    "choices": [{
        "index": 0,
        "message": {"role": "assistant", "content": "SPIKE-PLAIN-ANSWER"},
        "finish_reason": "stop",
    }],
    "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
}


def decide_toolcall() -> bool:
    if MODE == "toolloop":
        return True
    if TOOL_BUDGET is not None:
        return _req_no <= TOOL_BUDGET
    return False


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # default access log off; we log structured lines ourselves

    def _respond(self, code, payload):
        data = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/healthz", "/v1/healthz"):
            self._respond(200, {"status": "ok"})
        else:
            self._respond(404, {"error": "not found"})

    def do_POST(self):
        global _req_no
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        _req_no += 1

        hdrs = {}
        for k, v in self.headers.items():
            if k.lower() == "authorization":
                hdrs[k] = "Bearer <PRESENT-REDACTED>" if v else "<EMPTY>"
            else:
                hdrs[k] = v
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {"_unparsed": raw.decode("utf-8", "replace")[:2000]}

        log_line = json.dumps({
            "echo_req_no": _req_no,
            "path": self.path,
            "headers": hdrs,
            "body": body,
        }, ensure_ascii=False)
        print("ECHO_REQUEST " + log_line, flush=True)

        if "/chat/completions" not in self.path:
            self._respond(404, {"error": "not found"})
            return
        # echo the requested model back so holmes sees a consistent name
        resp = dict(TOOL_CALLS_RESP if decide_toolcall() else PLAIN_RESP)
        if isinstance(body, dict) and body.get("model"):
            resp["model"] = body["model"]
        self._respond(200, resp)


if __name__ == "__main__":
    port = int(os.environ.get("ECHO_PORT", "8000"))
    print("echo server listening on %d, mode=%s" % (port, MODE), flush=True)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
