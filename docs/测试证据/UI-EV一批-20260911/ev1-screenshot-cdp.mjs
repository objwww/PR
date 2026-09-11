// EV 一批（EV-01/EV-02）取证：Edge headless + CDP + 真登录 test，截实验列表/新建向导/详情首屏/对比骨架/EU 场景
// 复用 docs/测试证据/UI-侧边栏-20260911/sidebar-screenshot-cdp.mjs 的注入方法
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9227',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev1-edge', '--window-size=1440,900',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9227/json')).json()
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

// ---- s01 实验列表：页头/三过滤项/筛选栏/七列/“已加载 N 条”（EU07） ----
await nav(BASE + '/eval/runs', 4000)
const listInfo = await evalJs(`JSON.stringify({
  pager: document.querySelector('.pager .muted')?.textContent,
  cols: [...document.querySelectorAll('.el-table__header th')].map(t => t.textContent.trim()).filter(Boolean),
  filters: [...document.querySelectorAll('.qf')].map(b => b.textContent.trim()),
  rows: document.querySelectorAll('.el-table__body .el-table__row').length,
})`)
console.log('list info =', listInfo)
await saveShot('s01-eval-list.png')

// ---- s02 上下文操作条：选中两条（EU: 不常驻，选中即现） ----
await evalJs(`(() => {
  const boxes = document.querySelectorAll('.el-table__body .el-table__row .el-checkbox')
  boxes[0]?.querySelector('input')?.dispatchEvent(new Event('click', {bubbles: true}))
  boxes[0]?.click()
  return 'clicked1'
})()`)
await sleep(400)
await evalJs(`(() => {
  const boxes = document.querySelectorAll('.el-table__body .el-table__row .el-checkbox')
  boxes[1]?.click()
  return 'clicked2'
})()`)
await sleep(800)
const ctxInfo = await evalJs(`document.querySelector('.ctx-bar')?.textContent?.trim() ?? 'NO_CTX_BAR'`)
console.log('ctx bar =', ctxInfo)
await saveShot('s02-eval-list-ctx-bar.png')
// 点“设为基线”再截一张带基线/候选身份的
await evalJs(`[...document.querySelectorAll('.ctx-bar button')].find(b => b.textContent.includes('设为基线'))?.click(); 'ok'`)
await sleep(500)
console.log('ctx bar marked =', await evalJs(`document.querySelector('.ctx-bar')?.textContent?.trim()`))
await saveShot('s03-eval-list-ctx-baseline.png')

// ---- s04 “比较”跳转对比骨架页（EV-02.4） ----
await evalJs(`[...document.querySelectorAll('.ctx-bar button')].find(b => b.textContent.includes('比较'))?.click(); 'ok'`)
await sleep(1500)
console.log('compare url =', await evalJs('location.href'))
await saveShot('s04-eval-compare-skeleton.png')

// ---- s05 执行中过滤：RUNNING 行 null 指标 → 未统计（EU01 未知态） ----
await nav(BASE + '/eval/runs', 3500)
await evalJs(`[...document.querySelectorAll('.qf')].find(b => b.textContent.trim() === '执行中')?.click(); 'ok'`)
await sleep(2000)
console.log('running url =', await evalJs('location.href'))
console.log('running cells sample =', await evalJs(`JSON.stringify([...document.querySelectorAll('.el-table__body .el-table__row')].slice(0,2).map(r => r.textContent.slice(0, 120)))`))
await saveShot('s05-eval-list-running-null.png')

// ---- s06 已结束过滤：真实 0 → 显示 0%（EU01 真实 0 态）+ 待处理过滤 ----
await evalJs(`[...document.querySelectorAll('.qf')].find(b => b.textContent.trim() === '已结束')?.click(); 'ok'`)
await sleep(2000)
await saveShot('s06-eval-list-succeeded-zero.png')
await evalJs(`[...document.querySelectorAll('.qf')].find(b => b.textContent.trim() === '待处理')?.click(); 'ok'`)
await sleep(2000)
console.log('failed count =', await evalJs(`document.querySelectorAll('.el-table__body .el-table__row').length`))
await saveShot('s07-eval-list-failed.png')

// ---- EU05 浏览器后退恢复筛选 ----
await evalJs('history.back(); "back"')
await sleep(1500)
console.log('EU05 after back url =', await evalJs('location.href'),
  '| cur filter =', await evalJs(`document.querySelector('.qf.cur')?.textContent?.trim()`))

// ---- EU04 竞态：快速连切过滤项，最终列表必须与 URL state 一致 ----
const eu04 = await evalJs(`(async () => {
  const q = f => [...document.querySelectorAll('.qf')].find(b => b.textContent.trim() === f)
  q('全部')?.click(); q('执行中')?.click(); q('待处理')?.click(); q('执行中')?.click()
  await new Promise(r => setTimeout(r, 3000))
  const url = new URL(location.href)
  const badges = [...document.querySelectorAll('.el-table__body .el-table__row .el-tag')].map(t => t.textContent.trim())
  return JSON.stringify({ state: url.searchParams.get('state'), rows: badges.length,
    allRunning: badges.every(b => b === '执行中'),
    loaded: document.querySelector('.pager .muted')?.textContent })
})()`)
console.log('EU04 race result =', eu04)

// ---- s08 新建实验：第一步（模式 E/B/L 中文说明） ----
await nav(BASE + '/eval/new', 3000)
await saveShot('s08-eval-new-step1-mode.png')
// 走到第四步：下一步 x3
for (let i = 0; i < 3; i++) {
  await evalJs(`[...document.querySelectorAll('.step-actions button')].find(b => b.textContent.includes('下一步'))?.click(); 'next'`)
  await sleep(700)
}
const submitInfo = await evalJs(`JSON.stringify({
  disabled: [...document.querySelectorAll('.step-actions button')].find(b => b.textContent.includes('提交'))?.disabled,
  hint: document.querySelector('.submit-hint')?.textContent?.trim(),
})`)
console.log('new wizard step4 =', submitInfo)
await saveShot('s09-eval-new-step4-submit-disabled.png')
// 回看第一步（每步可回看）
await evalJs(`[...document.querySelectorAll('.el-step__title')].find(t => t.textContent.includes('选择模式'))?.click(); 'back-step'`)
await sleep(700)
console.log('EU05b wizard back-to-step1 visible =', await evalJs(`!!document.querySelector('.mode-card')`))

// ---- s10 实验详情首屏：RUNNING 实验（null → 未统计；EU01） ----
const runningId = await evalJs(`(async () => {
  const r = await fetch('/api/eval/runs?limit=50', { credentials: 'same-origin' })
  const d = await r.json()
  const run = d.items.find(x => x.state === 'RUNNING')
  const ok = d.items.find(x => x.state === 'SUCCEEDED' && x.coverage != null)
  return JSON.stringify({ running: run?.runId, done: ok?.runId })
})()`)
const { running: RUNNING_ID, done: DONE_ID } = JSON.parse(runningId)
console.log('ids =', runningId)
await nav(`${BASE}/eval/runs/${RUNNING_ID}`, 3500)
console.log('detail quality =', await evalJs(`JSON.stringify([...document.querySelectorAll('.qs-item')].map(q => q.textContent.trim()))`))
await saveShot('s10-eval-detail-running-first-screen.png')

// ---- s11 详情-案例页签：S1 两轮同场景（EU02）+ 展开不串行 ----
await nav(`${BASE}/eval/runs/${DONE_ID}`, 3500)
const eu02 = await evalJs(`(async () => {
  const rows = [...document.querySelectorAll('.el-table__body .el-table__row')]
  const keys = rows.map(r => r.cells[1]?.textContent + '#' + r.cells[2]?.textContent)
  const s1 = rows.filter(r => r.cells[1]?.textContent === 'S1')
  if (s1.length >= 2) {
    s1[0].querySelector('.el-table__expand-icon')?.click()
    await new Promise(r => setTimeout(r, 600))
  }
  const expanded = document.querySelectorAll('.el-table__expanded-cell').length
  return JSON.stringify({ rowCount: rows.length, s1Rounds: s1.map(r => r.cells[2]?.textContent), expandedCells: expanded })
})()`)
console.log('EU02 =', eu02)
await saveShot('s11-eval-detail-cases-multi-round.png')

// ---- EU03 切实验无旧数据残留：A→B 快速切换，最终上下文必须全部来自 B ----
await nav(`${BASE}/eval/runs/${RUNNING_ID}`, 800) // 不等 A 加载完就切 B
await send('Page.navigate', { url: `${BASE}/eval/runs/${DONE_ID}` })
await sleep(3500)
const eu03 = await evalJs(`JSON.stringify({
  shownId: document.querySelector('.hi-value .mono')?.textContent?.trim(),
  crumb: document.querySelector('.crumb b')?.textContent?.trim(),
  expect: '${DONE_ID}',
  casePager: document.querySelector('.pager .muted')?.textContent,
})`)
console.log('EU03 =', eu03)
await saveShot('s12-eval-detail-switched.png')

// ---- EU06：本批未加轮询——验证页面无定时器轮询证据（代码审查结论，截图无） ----

// ---- s13 对比骨架直达（带 query） ----
await nav(`${BASE}/eval/compare?baseline=${DONE_ID}&candidate=${RUNNING_ID}`, 2500)
await saveShot('s13-eval-compare-direct.png')

// ---- EU08 窄屏 800px ----
await send('Emulation.setDeviceMetricsOverride', { width: 800, height: 900, deviceScaleFactor: 1, mobile: false })
await nav(BASE + '/eval/runs', 3500)
await saveShot('s14-eval-list-narrow-800.png')

// ---- EU08 200% 缩放（等效 deviceScaleFactor=2, 720 逻辑宽） ----
await send('Emulation.setDeviceMetricsOverride', { width: 720, height: 450, deviceScaleFactor: 2, mobile: false })
await nav(`${BASE}/eval/runs/${DONE_ID}`, 3500)
await saveShot('s15-eval-detail-zoom200.png')
await send('Emulation.clearDeviceMetricsOverride')

// ---- EU07 “已加载”计数核对 ----
await nav(BASE + '/eval/runs', 3500)
console.log('EU07 pager text =', await evalJs(`document.querySelector('.pager .muted')?.textContent`))

ws.close(); edge.kill()
console.log('DONE')
