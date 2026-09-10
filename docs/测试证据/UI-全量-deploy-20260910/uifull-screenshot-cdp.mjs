// UI 全量部署取证：Edge headless + CDP，/login 先截（未登录态），再真登录 test 截 14 页
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'

const OUT = process.argv[2]
const INCIDENT_ID = '2c88e64b-0cb0-42e4-8133-16d61852ee5a'
const RUN_ID = '2a41f73a-9eb3-45d7-bc24-0599cd1cc697'
const EVAL_RUN_ID = '439f2cb2-5755-4068-8519-3324e44c2914'
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9225',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/uifull-edge', '--window-size=1440,900',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9225/json')).json()
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
const evalJs = async expr =>
  (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result.value

async function shot(path, file, waitMs = 4000) {
  await send('Page.navigate', { url: BASE + path })
  await new Promise(r => setTimeout(r, waitMs))
  const info = await evalJs(`JSON.stringify({ url: location.pathname,
    bodyLen: document.body ? document.body.innerText.length : 0,
    head: (document.body ? document.body.innerText : '').replace(/\\n+/g,'|').slice(0, 100) })`)
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(OUT + '/' + file, Buffer.from(data, 'base64'))
  console.log(file, info)
}

// 1. /login 未登录态
await shot('/login', 'p01-login.png', 2500)

// 真登录（与 LoginView 同路径）
const loginResult = await evalJs(`(async () => {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/)
  const form = new URLSearchParams({ username: 'test', password: '12345678' })
  const res = await fetch('/api/auth/login', { method: 'POST', credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': m ? decodeURIComponent(m[1]) : '',
      'Content-Type': 'application/x-www-form-urlencoded' }, body: form })
  return res.status
})()`)
console.log('login status =', loginResult)
await send('Page.addScriptToEvaluateOnNewDocument',
  { source: "sessionStorage.setItem('am7.session', JSON.stringify({user:'test'}))" })

await shot('/overview', 'p02-overview.png', 5000)
await shot('/alerts', 'p03-alerts.png', 5000)
await shot('/alerts/' + INCIDENT_ID, 'p04-incident-detail.png', 5000)
await shot('/history', 'p05-history.png', 5000)
await shot('/runs', 'p06-runs.png', 5000)
await shot('/runs/' + RUN_ID, 'p07-run-detail.png', 5000)
await shot('/cases', 'p08-cases.png', 5000)
await shot('/duty', 'p09-duty.png', 5000)
await shot('/duty/chat', 'p10-duty-chat.png', 5000)
await shot('/notifications', 'p11-notifications.png', 5000)
await shot('/monitor', 'p12-monitor.png', 6000)
await shot('/eval/runs', 'p13-eval-runs.png', 5000)
await shot('/eval/runs/' + EVAL_RUN_ID, 'p14-eval-run-detail.png', 5000)
await shot('/eval/datasets', 'p15-eval-datasets.png', 5000)
ws.close(); edge.kill()
