// UI-EV05 案例前端取证：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678
// 复用 docs/测试证据/UI-EV05前端-20260911/ev05-screenshot-cdp.mjs 方法；窗口 1366×768。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ev05-case-screenshot-cdp.mjs
//
// 195 实测契约状态（2026-09-11）：
//   GET /eval/runs/{id}/cases 列表项无 caseExecutionId（EV-01 旧契约）→ 行内“详情”按钮走“禁用+注明”路径；
//   GET /eval/runs/{id}/evidence-summary → 403（EV-05 未部署）→ 证据汇总区诚实空态；
//   GET /eval/runs/{id}/cases/{cid} → 403/404 → 抽屉降级提示。
// s03 为预览抽屉降级提示态：经 CDP Fetch 拦载给案例列表行注入占位 caseExecutionId 使按钮可点，
//   案例详情请求本身为对后端的真实调用（403 来自真实后端，非桩）。仅此一处注入，已在截图说明中注明。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'
const FAKE_CASE_ID = '00000000-0000-4000-8000-0000000000d5'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9230',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev05case-edge', '--window-size=1366,768',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9230/json')).json()
const page = targets.find(t => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((res, rej) => {
  const mid = ++id; pending.set(mid, { res, rej })
  ws.send(JSON.stringify({ id: mid, method, params }))
})

let interceptCases = false // s03 起才注入 caseExecutionId
let pageCookie = ''
ws.onmessage = async e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id).res(m.result); pending.delete(m.id); return }
  if (m.method === 'Fetch.requestPaused') {
    const { requestId, request } = m.params
    // 只拦案例“列表”（带 query）；案例“详情”（/cases/{cid}）与 evidence-summary 放行打真后端
    if (interceptCases && /\/api\/eval\/runs\/[^/]+\/cases\?/.test(request.url)) {
      const r = await fetch(request.url, { headers: { cookie: pageCookie } })
      const body = await r.json()
      for (const it of body.items ?? []) it.caseExecutionId = it.caseExecutionId ?? FAKE_CASE_ID
      await send('Fetch.fulfillRequest', { requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
        body: Buffer.from(JSON.stringify(body)).toString('base64') })
    } else {
      await send('Fetch.continueRequest', { requestId })
    }
  }
}
await new Promise(r => { ws.onopen = r })
await send('Page.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/eval/*' }] })
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

// ---- 取真实 runId（优先有案例的实验） ----
const pick = JSON.parse(await evalJs(`(async () => {
  const d = await (await fetch('/api/eval/runs?limit=20', { credentials: 'same-origin' })).json()
  const items = d.items ?? []
  const withCases = items.filter(x => (x.caseCount ?? 0) > 0)
  const chosen = withCases[0] ?? items[0]
  return JSON.stringify({ total: items.length, runId: chosen?.runId, caseCount: chosen?.caseCount })
})()`))
console.log('pick =', JSON.stringify(pick))
if (!pick.runId) { console.log('NO RUN AVAILABLE'); ws.close(); edge.kill(); process.exit(1) }
pageCookie = await evalJs('document.cookie')

// ---- s01 run 详情页案例 tab：证据汇总诚实空态（EV-05 未部署 403） ----
await nav(`${BASE}/eval/runs/${encodeURIComponent(pick.runId)}`, 4500)
console.log('s01 =', await evalJs(`JSON.stringify({
  summaryEmpty: document.querySelector('.ev-summary .el-empty__description')?.textContent?.trim(),
  cols: [...document.querySelectorAll('.el-table__header th')].map(t => t.textContent.trim()).filter(Boolean).slice(-2),
})`))
await evalJs(`document.querySelector('.ev-summary')?.scrollIntoView({ block: 'center' }); 'ok'`)
await sleep(500)
await saveShot('s01-run-detail-cases-summary-unavailable.png')

// ---- s02 案例列表“详情”禁用+注明态：195 旧契约列表无 caseExecutionId，按钮禁用 ----
console.log('s02 =', await evalJs(`JSON.stringify({
  detailBtnStates: [...document.querySelectorAll('.el-table__body button')]
    .filter(b => b.textContent.trim() === '详情')
    .map(b => ({ disabled: b.disabled, title: b.title })).slice(0, 2),
})`))
await evalJs(`(() => {
  const b = [...document.querySelectorAll('.el-table__body button')].find(b => b.textContent.trim() === '详情')
  b?.closest('tr')?.scrollIntoView({ block: 'center' })
  return 'ok'
})()`)
await sleep(500)
await saveShot('s02-case-detail-button-disabled-no-caseid.png')

// ---- s03 抽屉降级提示态：注入占位 caseExecutionId（仅此一处）→ 点“详情”→ 详情请求真打后端 403 ----
interceptCases = true
await evalJs(`[...document.querySelectorAll('.case-filter button')].find(b => b.textContent.includes('刷新'))?.click(); 'reload'`)
await sleep(3000)
console.log('s03 click =', await evalJs(`(() => {
  const btn = [...document.querySelectorAll('.el-table__body button')]
    .find(b => b.textContent.trim() === '详情' && !b.disabled)
  btn?.click()
  return btn ? 'clicked' : 'none'
})()`))
await sleep(3500)
console.log('s03 drawer =', await evalJs(`JSON.stringify({
  drawerTitle: document.querySelector('.el-drawer__title')?.textContent?.trim(),
  drawerEmpty: document.querySelector('.el-drawer .el-empty__description')?.textContent?.trim(),
})`))
await saveShot('s03-case-detail-drawer-unavailable.png')

// ---- s04 证据汇总“刷新”后仍是诚实空态（不伪造计数、不成错误重试环） ----
await evalJs(`document.querySelector('.el-drawer__close-btn')?.click(); 'closed'`)
await sleep(1200)
await evalJs(`[...document.querySelectorAll('.ev-summary .es-head button')].find(b => b.textContent.includes('刷新'))?.click(); 'refresh'`)
await sleep(2500)
console.log('s04 after refresh =', await evalJs(`document.querySelector('.ev-summary .el-empty__description')?.textContent?.trim()`))
await evalJs(`document.querySelector('.ev-summary')?.scrollIntoView({ block: 'center' }); 'ok'`)
await sleep(500)
await saveShot('s04-evidence-summary-refresh-still-honest.png')

ws.close(); edge.kill()
console.log('DONE')
