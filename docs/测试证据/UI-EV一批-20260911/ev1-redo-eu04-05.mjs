import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'
const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'
const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9228',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev1b-edge', '--window-size=1440,900',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9228/json')).json()
const page = targets.find(t => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let id = 0; const pending = new Map()
const send = (m, p = {}) => new Promise((res, rej) => { const i = ++id; pending.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method: m, params: p })) })
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id).res(m.result); pending.delete(m.id) } }
await new Promise(r => { ws.onopen = r })
await send('Page.enable')
const evalJs = async x => (await send('Runtime.evaluate', { expression: x, returnByValue: true, awaitPromise: true })).result.value
const sleep = ms => new Promise(r => setTimeout(r, ms))
async function saveShot(f) { const { data } = await send('Page.captureScreenshot', { format: 'png' }); writeFileSync(OUT + '/' + f, Buffer.from(data, 'base64')); console.log('saved', f) }

await send('Page.navigate', { url: BASE + '/login' }); await sleep(2500)
console.log('login =', await evalJs(`(async () => {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/)
  const res = await fetch('/api/auth/login', { method: 'POST', credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': m ? decodeURIComponent(m[1]) : '', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: 'test', password: '12345678' }) })
  return res.status
})()`))
await send('Page.addScriptToEvaluateOnNewDocument', { source: "sessionStorage.setItem('am7.session', JSON.stringify({user:'test'}))" })

// ---- EU04 redo：快速连切过滤项（全部→执行中→待处理→执行中），最终列表与 URL 一致 ----
await send('Page.navigate', { url: BASE + '/eval/runs' }); await sleep(4000)
const eu04 = await evalJs(`(async () => {
  const q = f => [...document.querySelectorAll('.qf')].find(b => b.textContent.trim() === f)
  q('全部').click(); q('执行中').click(); q('待处理').click(); q('执行中').click()
  await new Promise(r => setTimeout(r, 3500))
  const url = new URL(location.href)
  const rows = [...document.querySelectorAll('.el-table__body .el-table__row')]
  const badges = rows.map(r => r.querySelector('.el-tag')?.textContent?.trim())
  return JSON.stringify({ state: url.searchParams.get('state'), rows: rows.length,
    allRunning: badges.length > 0 && badges.every(b => b === '执行中'),
    loaded: document.querySelector('.pager .muted')?.textContent?.trim() })
})()`)
console.log('EU04 redo =', eu04)
await saveShot('s16-eu04-race-final.png')

// ---- EU05 redo：筛选执行中 → 进详情 → 后退，过滤项与 URL 恢复 ----
const eu05a = await evalJs(`location.href`)
await evalJs(`document.querySelector('.el-table__body .el-table__row')?.click(); 'open-detail'`)
await sleep(3000)
console.log('EU05 detail url =', await evalJs('location.pathname'))
await evalJs('history.back(); "back"')
await sleep(2500)
console.log('EU05 after back =', await evalJs(`JSON.stringify({
  url: location.href,
  cur: document.querySelector('.qf.cur')?.textContent?.trim(),
  rows: document.querySelectorAll('.el-table__body .el-table__row').length,
})`))
await saveShot('s17-eu05-back-restored.png')
// 刷新恢复
await send('Page.navigate', { url: BASE + '/eval/runs?state=FAILED' }); await sleep(3000)
console.log('EU05 refresh =', await evalJs(`JSON.stringify({
  cur: document.querySelector('.qf.cur')?.textContent?.trim(),
  rows: document.querySelectorAll('.el-table__body .el-table__row').length,
})`))
// 详情页 tab 恢复：?tab=usage 直达
await send('Page.navigate', { url: BASE + '/eval/runs/439f2cb2-5755-4068-8519-3324e44c2914?tab=usage' }); await sleep(3000)
console.log('EU05 tab restore =', await evalJs(`document.querySelector('.tab.cur')?.textContent?.trim()`))
await saveShot('s18-eu05-tab-usage.png')

// ---- EU06 焦点不抢：列表筛选后手动刷新，活动元素与选中不丢 ----
await send('Page.navigate', { url: BASE + '/eval/runs' }); await sleep(3500)
const eu06 = await evalJs(`(async () => {
  const boxes = document.querySelectorAll('.el-table__body .el-table__row .el-checkbox')
  boxes[0]?.click(); boxes[1]?.click()
  await new Promise(r => setTimeout(r, 500))
  const before = document.querySelector('.ctx-bar')?.textContent?.includes('已选 2 条')
  ;[...document.querySelectorAll('.ph-actions button')].find(b => b.textContent.includes('刷新'))?.click()
  await new Promise(r => setTimeout(r, 2500))
  const after = document.querySelector('.ctx-bar')?.textContent?.includes('已选 2 条')
  const scrollY = window.scrollY
  return JSON.stringify({ selectionKeptBefore: before, selectionKeptAfterRefresh: after, scrollY })
})()`)
console.log('EU06 manual-refresh =', eu06)
await saveShot('s19-eu06-refresh-keeps-selection.png')

ws.close(); edge.kill()
console.log('DONE')
