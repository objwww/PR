// UI-1/AUTH-1 部署取证：Edge headless + CDP，真登录 test 后截四页
// 用法：node ui1-screenshot-cdp.mjs <outDir> [incidentId]
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'

const OUT = process.argv[2]
const INCIDENT_ID = process.argv[3] || ''
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9224',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ui1-edge', '--window-size=1440,900',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9224/json')).json()
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

// 真登录：GET csrf 落 XSRF-TOKEN cookie → POST 表单（与 LoginView 同路径）
await send('Page.navigate', { url: BASE + '/login' })
await new Promise(r => setTimeout(r, 2500))
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
// 前端路由守卫读 sessionStorage am7.session（pinia store 初始化时）——API 登录后补标记
await send('Page.addScriptToEvaluateOnNewDocument',
  { source: "sessionStorage.setItem('am7.session', JSON.stringify({user:'test'}))" })

async function shot(path, file, waitMs = 3500) {
  await send('Page.navigate', { url: BASE + path })
  await new Promise(r => setTimeout(r, waitMs))
  const info = await evalJs(`JSON.stringify({ url: location.pathname,
    title: document.title,
    bodyLen: document.body ? document.body.innerText.length : 0,
    head: document.body ? document.body.innerText.slice(0, 120) : '' })`)
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(OUT + '/' + file, Buffer.from(data, 'base64'))
  console.log(file, info)
}

await shot('/overview', 'shot-1-overview.png', 4500)
await shot('/alerts', 'shot-2-alerts.png', 4500)
await shot('/history', 'shot-3-history.png', 4500)
if (INCIDENT_ID) {
  await shot('/alerts/' + INCIDENT_ID, 'shot-4-incident-detail.png', 4500)
}
ws.close(); edge.kill()
