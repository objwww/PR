#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# flagd defaultVariant 单键改写侧车（AM3 M3-17/M3-30；BA-19 纪律落码面，S1/S2 注入执行器）
#
# POST /flags  body {"flag": ..., "variant": ...}
#   → 只改 demo.flagd.json 中目标 flag 的 defaultVariant 单键（未知 flag 404、
#     未知 variant 400，fail-closed；写盘 = 同目录 tmp + os.replace 原子替换；
#     flagd file 源 inotify 热加载，无需重启 flagd）。
# GET /health → 200 {"ok": true}
#
# 纪律：本侧车不含任何密钥；仅 eval-mgmt 私网可达；访问日志静默（防 token 类查询串）。
import json
import os
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FLAGD_FILE = os.environ.get("FLAGD_FILE", "/flags/demo.flagd.json")


def set_default_variant(flag, variant):
    """精确单键改写：加载 → 校验 flag/variant → 只改 defaultVariant → 原子写回。"""
    with open(FLAGD_FILE, encoding="utf-8") as f:
        doc = json.load(f)
    flags = doc.get("flags")
    if not isinstance(flags, dict) or flag not in flags:
        raise KeyError("unknown flag: %s" % flag)
    entry = flags[flag]
    variants = entry.get("variants")
    if isinstance(variants, dict) and variant not in variants:
        raise ValueError("flag %s 无此变体: %s" % (flag, variant))
    previous = entry.get("defaultVariant")
    entry["defaultVariant"] = variant
    directory = os.path.dirname(FLAGD_FILE) or "."
    fd, tmp = tempfile.mkstemp(dir=directory, suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            # mkstemp 固定 0600；flagd 以非 root 用户 inotify 重读本文件，必须放开读权限
            # （2026-09-05 E2E-M3-02 实测：0600 落盘后 flagd os.Open permission denied，热加载失效）
            os.fchmod(f.fileno(), 0o644)
        os.replace(tmp, FLAGD_FILE)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    return previous


class Handler(BaseHTTPRequestHandler):

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True})
        else:
            self._json(404, {"error": "not found"})

    def _read_body(self):
        # RFC 7230：java RestClient（JdkClientHttpRequestFactory 链）实测以 chunked 发送
        # 无 Content-Length 的请求体——python http.server 不自动解码，必须自己读
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()          # 尾部 CRLF
                    return b"".join(chunks)
                chunks.append(self.rfile.read(size))
                self.rfile.readline()              # 每块后的 CRLF
        length = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(length)

    def do_POST(self):
        if self.path != "/flags":
            self._json(404, {"error": "not found"})
            return
        try:
            req = json.loads(self._read_body() or b"{}")
            flag = req["flag"]
            variant = str(req["variant"])
        except Exception:
            self._json(400, {"error": "bad json body"})
            return
        try:
            previous = set_default_variant(flag, variant)
        except KeyError as e:
            self._json(404, {"error": str(e)})
            return
        except ValueError as e:
            self._json(400, {"error": str(e)})
            return
        self._json(200, {"flag": flag, "applied": variant, "previous": previous})

    def log_message(self, fmt, *args):
        pass  # 访问日志静默


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
