// EV-03 前端接线取证：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678
// 复用 docs/测试证据/UI-EV一批-20260911/ev1-screenshot-cdp.mjs 的注入方法；窗口 1366×768。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ev03-screenshot-cdp.mjs
// 目的：已部署 195 后端为旧契约（无 displayName/mode/totalScenarios/quality/facets/asOf），
// 证明新字段区一律降级“未统计”、页面不崩不造假。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9228',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev03-edge', '--window-size=1366,768',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9228/json')).json()
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

// ---- 旧契约证据：列表/详情响应无 EV-03 新键 ----
const contract = await evalJs(`(async () => {
  const d = await (await fetch('/api/eval/runs?limit=5', { credentials: 'same-origin' })).json()
  const first = d.items[0] ?? {}
  const ev03Keys = ['displayName', 'mode', 'totalScenarios', 'quality', 'facets', 'caseCount']
  const det = await (await fetch('/api/eval/runs/' + first.runId, { credentials: 'same-origin' })).json()
  return JSON.stringify({
    listNewKeysPresent: ev03Keys.filter(k => k in first),
    listAsOfPresent: 'asOf' in d,
    detailNewKeysPresent: [...ev03Keys, 'asOf'].filter(k => k in det),
    running: d.items.find(x => x.state === 'RUNNING')?.runId,
    done: d.items.find(x => x.state === 'SUCCEEDED')?.runId,
  })
})()`)
console.log('old-contract probe =', contract)
const { running: RUNNING_ID, done: DONE_ID } = JSON.parse(contract)

// ---- s01 评测列表：名称/模式/样本数/阶段/已结清案例新字段区旧后端下“未统计”，页面不崩 ----
await nav(BASE + '/eval/runs', 4000)
const listInfo = await evalJs(`JSON.stringify({
  rows: document.querySelectorAll('.el-table__body .el-table__row').length,
  firstRow: document.querySelector('.el-table__body .el-table__row')?.textContent?.slice(0, 200),
  pager: document.querySelector('.pager .muted')?.textContent,
})`)
console.log('list =', listInfo)
await saveShot('s01-eval-list-old-backend-degraded.png')

// ---- s02 详情首屏（已完成实验）：六状态分面 + asOf + 质量摘要降级 ----
await nav(`${BASE}/eval/runs/${DONE_ID ?? RUNNING_ID}`, 4000)
const detailInfo = await evalJs(`JSON.stringify({
  facets: [...document.querySelectorAll('.facets .el-descriptions__cell')].map(c => c.textContent.trim()),
  asof: document.querySelector('.asof')?.textContent?.trim(),
  quality: [...document.querySelectorAll('.qs-item')].map(q => q.textContent.trim()),
  versions: document.querySelector('.head-versions')?.textContent?.trim(),
})`)
console.log('detail =', detailInfo)
await saveShot('s02-eval-detail-facets-old-backend.png')

// ---- s03 详情展开态：展开“配置详情”折叠 + 分面区同屏 ----
await evalJs(`document.querySelector('.cfg-collapse .el-collapse-item__header')?.click(); 'expand'`)
await sleep(800)
await evalJs(`document.querySelector('.cfg-collapse')?.scrollIntoView({ block: 'center' }); 'scroll'`)
await sleep(400)
await saveShot('s03-eval-detail-expanded-config.png')

// ---- s04 RUNNING 实验详情：lastProgressAt/phase 未采集 → 未统计 ----
if (RUNNING_ID) {
  await nav(`${BASE}/eval/runs/${RUNNING_ID}`, 4000)
  console.log('running head =', await evalJs(`JSON.stringify([...document.querySelectorAll('.head-item')].map(h => h.textContent.trim()))`))
  await saveShot('s04-eval-detail-running.png')
}

ws.close(); edge.kill()
console.log('DONE')
