import http from 'http'
import { readFile } from 'fs/promises'
import { extname, join } from 'path'
const ROOT = 'E:/kimiCode/alert-web/dist'
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const now = Date.now()
const iso = ms => new Date(ms).toISOString()
// API 契约=created_at 降序（新在前）——GATUS（2min 前）在前，P1（32min 前）在后
const feed = { messages: [
  { id: 'a2222222-2222-2222-2222-222222222222', source: 'GATUS', episodeId: 'ep-demo-2',
    eventStatus: 'resolved', severity: null, title: '探针告警 control_health [resolved]',
    body: 'group=control-plane 已恢复', status: 'UNREAD', createdAt: iso(now - 2 * 60000),
    deliveries: [] },
  { id: 'a1111111-1111-1111-1111-111111111111', source: 'RCA_SYSTEM', episodeId: 'ep-demo-1',
    eventStatus: 'firing', severity: 'P1', title: '支付下单成功率骤降',
    body: '### 【P1】支付下单成功率骤降\n> 服务：order-arena\n> 级别：<font color="warning">P1</font>\nRCA 结论：**数据库连接池耗尽**（置信 0.87）\n<font color="comment">已排除：发布变更、流量突增</font>\n@zhangwei 请值班同事关注',
    status: 'UNREAD', createdAt: iso(now - 32 * 60000),
    deliveries: [
      { channel: 'dead-bot', platform: 'WECOM', priority: 1, state: 'DEAD', attempts: 5, sentAt: null, lastError: '{"error":"http_500"}', drill: false },
      { channel: 'echo-bot', platform: 'WECOM', priority: 2, state: 'SENT', attempts: 0, sentAt: iso(now - 25 * 60000), lastError: null, drill: true } ] } ] }
http.createServer(async (req, res) => {
  const url = req.url.split('?')[0]
  if (url === '/api/duty/notifications/feed') {
    res.writeHead(200, { 'content-type': 'application/json' }); return res.end(JSON.stringify(feed))
  }
  try {
    const file = url === '/' ? '/index.html' : url
    const data = await readFile(join(ROOT, file))
    res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' })
    res.end(data)
  } catch {
    const data = await readFile(join(ROOT, 'index.html'))
    res.writeHead(200, { 'content-type': 'text/html' }); res.end(data)
  }
}).listen(18099, () => console.log('serve up :18099'))
