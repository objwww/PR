// UI-EV07 前端（对比工作台接线 GET /api/eval/compare + POST /api/eval/comparisons）取证：
// Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678；窗口 1366×768。
// 复用 docs/测试证据/UI-EV05前端-20260911/ev05-screenshot-cdp.mjs 方法。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ev07-screenshot-cdp.mjs
// 目的：195 后端 EV-07 未部署（/api/eval/compare 403/404）时——
//   s01 引导空态仍正常；s02 真实 run 对走降级文案“后端 EV-07 未部署”（计数“—”、
//   逐例表诚实空态，不伪造对比数据）；s03 落档按钮显式提示“落档接口依赖后端 EV-07，当前未部署”。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9229',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev07-edge', '--window-size=1366,768',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9229/json')).json()
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
await send('Emulation.setDeviceMetricsOverride', { width: 1366, height: 768, deviceScaleFactor: 1, mobile: false })
const evalJs = async expr =>
  (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result.value

async function saveShot(file) {
  const { data } = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(OUT + '/' + file, Buffer.from(data, 'base64'))
  console.log('saved', file)
}
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function nav(url, wait = 3000) {
  await send('Page.navigate', { url })
  await sleep(wait)
}

// ---- 真登录 ----
await nav(BASE + '/login', 2500)
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

// ---- 取真实 runId（两条已结束实验作基线/候选） ----
const pair = JSON.parse(await evalJs(`(async () => {
  const d = await (await fetch('/api/eval/runs?limit=20', { credentials: 'same-origin' })).json()
  const items = d.items ?? []
  const done = items.filter(x => x.state === 'SUCCEEDED')
  const pick = done.length >= 2 ? done.slice(0, 2) : items.slice(0, 2)
  return JSON.stringify({ total: items.length, baseline: pick[0]?.runId, candidate: pick[1]?.runId })
})()`))
console.log('pair =', JSON.stringify(pair))

// ---- 直连确认降级前提：/api/eval/compare 在 195 旧后端上非 200 ----
const apiStatus = await evalJs(`(async () => {
  const q = 'baseline=${pair.baseline}&candidate=${pair.candidate}'
  const g = await fetch('/api/eval/compare?' + q, { credentials: 'same-origin' })
  const p = await fetch('/api/eval/comparisons', { method: 'POST', credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ baselineRunId: '${pair.baseline}', candidateRunId: '${pair.candidate}' }) })
  return JSON.stringify({ get: g.status, post: p.status })
})()`)
console.log('api status (get/post) =', apiStatus)

// ---- s01 引导空态：无 query 参数 ----
await nav(BASE + '/eval/compare', 4000)
console.log('s01 guide =', await evalJs(`document.querySelector('.empty-state .el-empty__description')?.textContent?.trim()`))
await saveShot('s01-compare-guide-empty.png')

// ---- s02 真实 run 对：降级文案“后端 EV-07 未部署” ----
if (pair.baseline && pair.candidate) {
  await nav(`${BASE}/eval/compare?baseline=${encodeURIComponent(pair.baseline)}&candidate=${encodeURIComponent(pair.candidate)}`, 5000)
  console.log('s02 degraded =', await evalJs(`JSON.stringify({
    cmpNote: document.querySelector('.cmp-note')?.textContent?.trim(),
    tabs: [...document.querySelectorAll('.gt')].map(t => t.textContent.trim()),
    gtNote: document.querySelector('.gt-note')?.textContent?.trim(),
    tableEmpty: document.querySelector('.cmp-table .el-empty__description')?.textContent?.trim(),
  })`))
  await saveShot('s02-compare-pair-ev07-undeployed.png')

  // ---- s03 落档按钮：显式提示“落档接口依赖后端 EV-07，当前未部署” ----
  await evalJs(`[...document.querySelectorAll('.ops-row button')].find(b => b.textContent.includes('保存本次对比'))?.click(); 'go'`)
  await sleep(2500)
  console.log('s03 save tip =', await evalJs(`document.querySelector('.el-message')?.textContent?.trim()`))
  await saveShot('s03-save-btn-undeployed-tip.png')
}

ws.close(); edge.kill()
console.log('DONE')
