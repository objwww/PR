#!/usr/bin/env python3
# rr2224-mock.py —— RR22/23/24 受控故障端点（路径分发；HTTP:18100 / 自签 TLS:18443）
# 每请求落一行访问日志（ts path peer）——与 rca_model_call 物理行对拍的真值源
import http.server, ssl, socket, struct, threading, time, json, sys, socketserver

LOG = '/opt/build/pr-logs/rr2224/mock-access.log'
CHAT_OK = json.dumps({"id":"x","object":"chat.completion","model":"mock",
  "choices":[{"index":0,"message":{"role":"assistant","content":"mock ok"},
  "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}).encode()

def log(path, peer):
    with open(LOG,'a') as f:
        f.write('%.3f %s %s\n' % (time.time(), path, peer))

def ok(h, body):
    h.send_response(200); h.send_header('Content-Type','application/json')
    h.send_header('Content-Length', str(len(body))); h.end_headers(); h.wfile.write(body)

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def _body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n) if n else b''
    def _route(self):
        p = self.path
        log(p, self.client_address[0])
        if p.startswith('/429'):
            self.send_response(429); self.send_header('Retry-After','1')
            self.send_header('Content-Type','application/json')
            self.send_header('Content-Length','2'); self.end_headers(); self.wfile.write(b'{}')
        elif p.startswith('/slowfirst'):
            time.sleep(30); ok(self, CHAT_OK)          # > 测试旋钮 8s：首字节超时
        elif p.startswith('/slowbody'):
            self.send_response(200); self.send_header('Content-Type','application/json')
            self.send_header('Content-Length','500'); self.end_headers()   # 声明 500 只给 ~300：读超时
            for _ in range(12):
                try: self.wfile.write(b'A'*10); self.wfile.flush()
                except Exception: return
                time.sleep(2)
        elif p.startswith('/reset'):
            try:   # RST：accept+读请求后立刻 SO_LINGER(0) 断
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii',1,0))
                self.connection.close()
            except Exception: pass
        elif p.startswith('/badproto'):
            raw = b'not-json{garbage'
            self.send_response(200); self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
        elif p.startswith('/tls'):
            ok(self, CHAT_OK)
        elif '/loki/api/v1/' in p:
            if p.startswith('/slow-loki'):
                time.sleep(30)
                j = json.dumps({"status":"success","data":{"resultType":"vector","result":[]}}).encode()
                ok(self, j)
            elif p.startswith('/trunc-loki'):
                half = b'{"status":"success","data":{"resultType":"vector","res'
                self.send_response(200); self.send_header('Content-Type','application/json')
                self.send_header('Content-Length','200'); self.end_headers()
                self.wfile.write(half); self.connection.close()
            elif p.startswith('/empty-loki') or p.startswith('/dead-loki'):
                if 'query_range' in p:
                    j = json.dumps({"status":"success","data":{"resultType":"streams","result":[]}}).encode()
                else:
                    j = json.dumps({"status":"success","data":{"resultType":"vector","result":[]}}).encode()
                ok(self, j)
        else:
            self.send_response(404); self.send_header('Content-Length','0'); self.end_headers()
    def do_POST(self):
        self._body(); self._route()
    def do_GET(self):
        self._route()
    def log_message(self, *a): pass

class TS(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

httpd = TS(('0.0.0.0', 18100), H)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain('/opt/build/pr-logs/rr2224/mock.crt', '/opt/build/pr-logs/rr2224/mock.key')
tlsd = TS(('0.0.0.0', 18443), H)
tlsd.socket = ctx.wrap_socket(tlsd.socket, server_side=True)
log('MOCK-START http=18100 tls=18443', '-')
tlsd.serve_forever()
