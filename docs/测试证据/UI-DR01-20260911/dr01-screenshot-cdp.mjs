// DR-01 取证：Edge headless + CDP + 真登录 test，截故障演练导航项/列表接口未就绪/新建三步/详情骨架/评测互链
// 方法复用 docs/测试证据/UI-EV一批-20260911/ev1-screenshot-cdp.mjs
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9228',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/dr01-edge', '--window-size=1366,768',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9228/json')).json()
const page = targets.find(t => t.type === 'page')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let id = 0
const pending = new Map()
const send = (method, params = {}) => new Promise((res) => {
  const mid = ++id; pending.set(mid, { res })
  ws.send(JSON.stringify({ id: mid, method, params }))
})
ws.onmessage = e => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) { pending.get(m.id).res(m.result); pending.delete(m.id) }
}
await new Promise(r => { ws.onopen = r })
await send('Page.enable')
const evalJs = async expr =>
  (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.value

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

// ---- s01 导航含「故障演练」项 + 列表页：接口未就绪（404 诚实呈现，非「暂无数据」） ----
await nav(BASE + '/drills', 4000)
console.log('nav has 故障演练 =', await evalJs(`[...document.querySelectorAll('.nav-it')].some(a => a.textContent.includes('故障演练'))`))
console.log('list state =', await evalJs(`document.querySelector('.table-zone')?.textContent?.slice(0, 200)`))
await saveShot('s01-drills-list-api-not-ready.png')

// ---- s02 新建第一步：场景静态说明（标注目录接口依赖 DR-02，不可选） ----
await nav(BASE + '/drills/new', 3500)
console.log('step1 alert =', await evalJs(`document.querySelector('.step-alert')?.textContent?.slice(0, 120)`))
console.log('scenario cards =', await evalJs(`document.querySelectorAll('.scenario-card').length`))
await saveShot('s02-drill-new-step1-scenarios.png')

// ---- s03 新建第二步：受限参数（禁用） ----
await evalJs(`[...document.querySelectorAll('.step-nav button')].find(b => b.textContent.includes('下一步'))?.click(); 'ok'`)
await sleep(600)
await saveShot('s03-drill-new-step2-params.png')

// ---- s04 新建第三步：预览骨架 + 开始演练禁用 ----
await evalJs(`[...document.querySelectorAll('.step-nav button')].find(b => b.textContent.includes('下一步'))?.click(); 'ok'`)
await sleep(600)
const submitDisabled = await evalJs(`[...document.querySelectorAll('.submit-row button')].find(b => b.textContent.includes('开始演练'))?.disabled`)
console.log('submit disabled =', submitDisabled)
await saveShot('s04-drill-new-step3-submit-disabled.png')

// ---- s05 详情页直达：作业不存在或接口未就绪 + 八阶段时间线骨架 ----
await nav(BASE + '/drills/dr01-probe-00000000', 4000)
console.log('detail state =', await evalJs(`document.querySelector('.el-result')?.textContent?.slice(0, 150)`))
console.log('timeline stages =', await evalJs(`[...document.querySelectorAll('.tl-name')].map(e => e.textContent).join(' | ')`))
console.log('stop disabled =', await evalJs(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('停止并恢复'))?.disabled`))
await saveShot('s05-drill-detail-skeleton.png')

// ---- s06 评测页互链「去故障演练」 ----
await nav(BASE + '/eval/runs', 4000)
console.log('eval link =', await evalJs(`document.querySelector('.drill-link')?.textContent + ' -> ' + document.querySelector('.drill-link')?.getAttribute('href')`))
await saveShot('s06-eval-link-to-drills.png')

edge.kill()
console.log('done')
process.exit(0)
