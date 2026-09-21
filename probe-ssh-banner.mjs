import net from 'node:net';

const proxy = net.connect(7890, '127.0.0.1');
let buf = Buffer.alloc(0);
let established = false;

const timer = setTimeout(() => {
  console.log('TIMEOUT: no SSH banner within 12s. established=' + established + ' bytesAfterHead=' + buf.length);
  process.exit(2);
}, 12000);

proxy.on('connect', () => {
  proxy.write('CONNECT 195.133.33.195:22 HTTP/1.1\r\nHost: 195.133.33.195:22\r\n\r\n');
});

proxy.on('data', (chunk) => {
  if (established) {
    console.log('BANNER: ' + JSON.stringify(chunk.toString('latin1').slice(0, 80)));
    clearTimeout(timer);
    process.exit(0);
    return;
  }
  buf = Buffer.concat([buf, chunk]);
  const idx = buf.indexOf('\r\n\r\n');
  if (idx === -1) return;
  const head = buf.slice(0, idx).toString('latin1');
  console.log('PROXY HEAD: ' + head.split('\r\n')[0]);
  if (!/^HTTP\/1\.[01] 200/.test(head)) { clearTimeout(timer); process.exit(1); }
  established = true;
  const rest = buf.slice(idx + 4);
  if (rest.length) {
    console.log('BANNER: ' + JSON.stringify(rest.toString('latin1').slice(0, 80)));
    clearTimeout(timer);
    process.exit(0);
  }
});

proxy.on('error', (e) => { console.log('ERR: ' + e.message); process.exit(1); });
proxy.on('close', () => { if (!established) { console.log('CLOSED before establish'); process.exit(1); } });
