// UI-EV05 前端（对比工作台打磨）取证：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678
// 复用 docs/测试证据/UI-EV03接线-20260911/ev03-screenshot-cdp.mjs 方法；窗口 1366×768。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ev05-screenshot-cdp.mjs
// 目的：证明 /eval/compare 引导空态、真实 query 参数对比骨架、实验列表互链跳转、非法 query 引导。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9229',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev05-edge', '--window-size=1366,768',
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

// ---- s01 引导空态：无 query 参数 ----
await nav(BASE + '/eval/compare', 4000)
console.log('s01 guide =', await evalJs(`document.querySelector('.empty-state .el-empty__description')?.textContent?.trim()`))
await saveShot('s01-compare-guide-empty.png')

// ---- s02 非法 query 参数：引导提示而非报错 ----
await nav(BASE + '/eval/compare?baseline=not-exist&candidate=also-not-exist', 4000)
console.log('s02 invalid =', await evalJs(`JSON.stringify({
  note: document.querySelector('.query-note')?.textContent?.trim(),
  guide: document.querySelector('.empty-state .el-empty__description')?.textContent?.trim(),
})`))
await saveShot('s02-compare-invalid-query.png')

// ---- s03 真实 query 参数：对比骨架（分组计数“—” + 逐例表诚实空态） ----
if (pair.baseline && pair.candidate) {
  await nav(`${BASE}/eval/compare?baseline=${encodeURIComponent(pair.baseline)}&candidate=${encodeURIComponent(pair.candidate)}`, 4000)
  console.log('s03 compare =', await evalJs(`JSON.stringify({
    pinned: document.querySelector('.pi-note')?.textContent?.trim(),
    sums: [...document.querySelectorAll('.sum-item')].map(s => s.textContent.trim().slice(0, 120)),
    tabs: [...document.querySelectorAll('.gt')].map(t => t.textContent.trim()),
    tableEmpty: document.querySelector('.cmp-table .el-empty__description')?.textContent?.trim(),
  })`))
  await saveShot('s03-compare-real-pair-skeleton.png')

  // ---- s04 互链：从对比页回链基线详情 ----
  await evalJs(`[...document.querySelectorAll('.sum-link')].find(a => a.textContent.includes('基线'))?.click(); 'go'`)
  await sleep(3000)
  console.log('s04 detail url =', await evalJs('location.pathname'))
  await saveShot('s04-compare-to-run-detail.png')
}

// ---- s05 互链：实验列表勾选两条 → 上下文条“比较”带入 query ----
await nav(BASE + '/eval/runs', 4000)
await evalJs(`(() => {
  const cbs = [...document.querySelectorAll('.el-table__body .el-table__row .el-checkbox')]
  cbs[0]?.click(); cbs[1]?.click()
  return cbs.length
})()`)
await sleep(800)
console.log('s05 ctx-bar =', await evalJs(`document.querySelector('.ctx-bar .ctx-text')?.textContent?.trim()`))
await saveShot('s05-runs-ctx-bar-pair.png')
await evalJs(`[...document.querySelectorAll('.ctx-bar button')].find(b => b.textContent.includes('比较'))?.click(); 'go'`)
await sleep(3500)
console.log('s05 landed =', await evalJs('JSON.stringify({ url: location.pathname + location.search, sums: document.querySelectorAll(".sum-item").length })'))
await saveShot('s05-runs-to-compare.png')

ws.close(); edge.kill()
console.log('DONE')
