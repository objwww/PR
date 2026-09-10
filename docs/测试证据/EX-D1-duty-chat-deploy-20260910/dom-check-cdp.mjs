// Edge headless + CDP：addScriptToEvaluateOnNewDocument 预置会话标记 → /duty/chat → 取 DOM 断言
import { spawn } from 'child_process'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9223',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/exd1-edge', 'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9223/json')).json()
const page = targets.find(t => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((res, rej) => {
  const mid = ++id; pending.set(mid, { res, rej })
  ws.send(JSON.stringify({ id: mid, method, params }))
})
ws.onmessage = e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id).res(m.result); pending.delete(m.id) }
}
await new Promise(r => { ws.onopen = r })
await send('Page.enable')
await send('Page.addScriptToEvaluateOnNewDocument',
  { source: "sessionStorage.setItem('am7.session','1')" })
await send('Page.navigate', { url: 'http://127.0.0.1:18099/duty/chat' })
await new Promise(r => setTimeout(r, 5000))
const evalJs = async expr =>
  (await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result.value
const checks = await evalJs(`JSON.stringify({
  url: location.pathname,
  groupBar: !!document.querySelector('.chat-head .chat-title'),
  groupName: document.querySelector('.chat-head .chat-title')?.textContent,
  avatar: !!document.querySelector('.msg .avatar'),
  nick: document.querySelector('.nick')?.childNodes[0]?.textContent?.trim(),
  botTag: document.querySelector('.bot-tag')?.textContent,
  timeBar: document.querySelector('.time-bar')?.textContent,
  cards: document.querySelectorAll('.msg-card').length,
  cardTitle: document.querySelector('.c-title')?.textContent,
  source: document.querySelector('.c-source')?.textContent,
  quoteBar: !!document.querySelector('.md-q'),
  heading: !!document.querySelector('.md-h'),
  fontWarning: !!document.querySelector('.fc-warning'),
  fontComment: !!document.querySelector('.fc-comment'),
  bold: !!document.querySelector('.c-body b'),
  atMention: document.querySelector('.at')?.textContent,
  jumpRow: document.querySelector('.c-jump')?.textContent?.trim(),
  jumpRowsTotal: document.querySelectorAll('.c-jump').length,
  drillBadge: [...document.querySelectorAll('.tag')].some(t => t.textContent === '演练通道'),
  sentChip: [...document.querySelectorAll('.tag')].some(t => t.textContent.includes('已送达')),
  deadChip: [...document.querySelectorAll('.tag')].some(t => t.textContent.includes('失败')),
  directChip: [...document.querySelectorAll('.tag')].some(t => t.textContent.includes('127 直发')),
  firingTag: [...document.querySelectorAll('.c-sub .tag')].map(t => t.textContent + ':' + t.className),
})`)
console.log(checks)
const dom = await evalJs('document.querySelector(".chat-body")?.innerHTML || ""')
await (await import('fs')).writeFileSync('C:/Users/wangp/AppData/Local/Temp/exd1/dom.html', dom)
ws.close(); edge.kill()
