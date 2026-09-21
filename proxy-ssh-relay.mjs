import net from 'node:net';

const PROXY_HOST = '127.0.0.1';
const PROXY_PORT = 7890;
const TARGET_HOST = process.argv[2] || '195.133.33.195';
const TARGET_PORT = Number(process.argv[3] || 22);
const LISTEN_PORT = Number(process.argv[4] || 2222);

const server = net.createServer((client) => {
  const proxy = net.connect(PROXY_PORT, PROXY_HOST);
  let buf = Buffer.alloc(0);
  let established = false;

  proxy.on('connect', () => {
    proxy.write(
      `CONNECT ${TARGET_HOST}:${TARGET_PORT} HTTP/1.1\r\n` +
      `Host: ${TARGET_HOST}:${TARGET_PORT}\r\n` +
      `Proxy-Connection: keep-alive\r\n\r\n`);
  });

  proxy.on('data', (chunk) => {
    if (established) return;
    buf = Buffer.concat([buf, chunk]);
    const idx = buf.indexOf('\r\n\r\n');
    if (idx === -1) return;
    const head = buf.slice(0, idx).toString('latin1');
    if (!/^HTTP\/1\.[01] 200/.test(head)) {
      client.destroy();
      proxy.destroy();
      console.error(`[${new Date().toISOString()}] proxy refused: ${head.split('\r\n')[0]}`);
      return;
    }
    established = true;
    const rest = buf.slice(idx + 4);
    if (rest.length) client.write(rest);
    client.pipe(proxy);
    proxy.pipe(client);
    console.log(`[${new Date().toISOString()}] tunnel established -> ${TARGET_HOST}:${TARGET_PORT}`);
  });

  const bye = () => { client.destroy(); proxy.destroy(); };
  client.on('error', bye);
  proxy.on('error', (e) => { console.error(`[${new Date().toISOString()}] proxy err: ${e.message}`); bye(); });
  client.on('close', bye);
  proxy.on('close', bye);
});

server.listen(LISTEN_PORT, '127.0.0.1', () => {
  console.log(`relay listening 127.0.0.1:${LISTEN_PORT} -> (proxy ${PROXY_HOST}:${PROXY_PORT}) -> ${TARGET_HOST}:${TARGET_PORT}`);
});
