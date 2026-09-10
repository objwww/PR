// UX-01 前端接线取证：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678
// 复用 docs/测试证据/UI-EV03接线-20260911/ev03-screenshot-cdp.mjs 方法；窗口 1366×768。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ux01-screenshot-cdp.mjs
// 目的：195 已部署后端为旧契约（行无 category/categorySource、详情无 categoryDetail、facets 无 category 维），
// 证明分类列降级 '—'、过滤下拉禁用注明、详情分类区块'未统计'、修正提交显式报"依赖后端 UX-01，当前未部署"——不报错不伪造。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9229',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ux01-edge', '--window-size=1366,768',
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

// ---- 旧契约证据：列表行/详情/facets 无 UX-01 新键 ----
const contract = await evalJs(`(async () => {
  const d = await (await fetch('/api/v1/incidents?limit=5', { credentials: 'same-origin' })).json()
  const first = d.items[0] ?? {}
  const det = first.incidentId
    ? await (await fetch('/api/v1/incidents/' + first.incidentId, { credentials: 'same-origin' })).json() : {}
  const facets = await (await fetch('/api/v1/incidents/facets', { credentials: 'same-origin' })).json()
  return JSON.stringify({
    rowCategoryKeysPresent: ['category', 'categorySource'].filter(k => k in first),
    detailCategoryKeysPresent: ['category', 'categorySource', 'categoryDetail'].filter(k => k in det),
    facetCategoryPresent: 'category' in facets,
    firstIncidentId: first.incidentId ?? null,
  })
})()`)
console.log('old-contract probe =', contract)
const { firstIncidentId: INCIDENT_ID } = JSON.parse(contract)

// ---- s01 告警列表：分类列旧后端降级 '—'，分类下拉禁用并注明，facet 无分类组 ----
await nav(BASE + '/alerts', 4000)
const listInfo = await evalJs(`JSON.stringify({
  rows: document.querySelectorAll('.el-table__body .el-table__row').length,
  catHeader: [...document.querySelectorAll('.el-table__header th')].map(t => t.textContent.trim()),
  firstRowCatCell: document.querySelector('.el-table__body .el-table__row .cat-none')?.textContent,
  catSelectDisabled: document.querySelector('.w-category')?.classList?.contains('is-disabled'),
  catNote: document.querySelector('.cat-note')?.textContent,
  facetTitles: [...document.querySelectorAll('.facet-rail h4')].map(h => h.textContent.trim()),
})`)
console.log('list =', listInfo)
await saveShot('s01-alerts-list-old-backend-degraded.png')

// ---- s02 详情页：分类区块缺席态（生效分类 '—'、命中依据'未统计'、无 override 快照），页面不崩 ----
await nav(`${BASE}/alerts/${INCIDENT_ID}`, 4000)
const detailInfo = await evalJs(`JSON.stringify({
  catEffective: document.querySelector('.cat-effective')?.textContent?.trim(),
  catCells: [...document.querySelectorAll('.el-descriptions__cell')].map(c => c.textContent.trim()).filter(t => /规则|分类时间/.test(t)),
  overrideSnapshotShown: !!document.querySelector('.cat-sub'),
})`)
console.log('detail =', detailInfo)
await saveShot('s02-incident-detail-category-absent.png')

// ---- s03 修正对话框打开态（目标分类下拉 + 理由必填）----
await evalJs(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '人工修正')?.click(); 'open'`)
await sleep(800)
// 展开目标分类下拉，露出静态词表选项
await evalJs(`document.querySelector('.el-dialog .el-select')?.click(); 'expand'`)
await sleep(600)
console.log('dialog =', await evalJs(`JSON.stringify({
  title: document.querySelector('.el-dialog__title')?.textContent,
  options: [...document.querySelectorAll('.el-select-dropdown__item')].map(o => o.textContent.trim()),
})`))
await saveShot('s03-override-dialog-open.png')

// ---- s04 提交后错误提示态：后端未部署 → 显式"修正接口依赖后端 UX-01，当前未部署" ----
await evalJs(`document.querySelector('.el-dialog .el-select')?.click(); 'collapse'`)
await sleep(400)
await evalJs(`document.querySelectorAll('.el-select-dropdown__item')[0]?.click(); 'pick'`)
await sleep(400)
await evalJs(`(() => {
  const ta = document.querySelector('.el-dialog textarea')
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
  setter.call(ta, '取证：验证旧后端下修正提交的降级提示')
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  return ta.value
})()`)
await sleep(300)
await evalJs(`[...document.querySelectorAll('.el-dialog__footer button')].find(b => b.textContent.trim() === '提交')?.click(); 'submit'`)
await sleep(2500)
console.log('error tip =', await evalJs(`document.querySelector('.el-message-box__message')?.textContent`))
await saveShot('s04-override-submit-not-deployed-tip.png')

ws.close(); edge.kill()
console.log('DONE')
