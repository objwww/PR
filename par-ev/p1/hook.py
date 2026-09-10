# 捕获监听（127 测试专用）：记录收到的 GET/POST 到 /tmp/gatus-hook.log，一律回 200
# python3.6 兼容；post 200 是关键——custom 告警 Send 收到 >399 视为失败并重试
import http.server
import datetime

LOG = "/tmp/gatus-hook.log"


class Handler(http.server.BaseHTTPRequestHandler):
    def _handle(self, method):
        length = int(self.headers.get('Content-Length') or 0)
        body = ''
        if length > 0:
            body = self.rfile.read(length).decode('utf-8', 'replace')
        with open(LOG, 'a') as f:
            f.write('=== %s | %s %s | from %s\n' % (
                datetime.datetime.utcnow().isoformat(),
                method, self.path, self.client_address[0]))
            if body:
                f.write(body + '\n')
        payload = b'ok'
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._handle('GET')

    def do_POST(self):
        self._handle('POST')

    def log_message(self, fmt, *args):
        pass


if __name__ == '__main__':
    server = http.server.HTTPServer(('0.0.0.0', 18080), Handler)
    server.serve_forever()
