// DR-02 前端接线取证：Edge headless + CDP + 真登录 test，截三帧降级态（195 未部署 DR-02，403/404 显式降级）
//   s01 新建第一步：场景目录接口未就绪 → 回退「依赖 DR-02」静态说明空态
//   s02 新建第三步：「执行预检」按钮显式禁用 + 注明（预检接口依赖 DR-02 未部署）
//   s03 详情页：「停止并恢复」按钮禁用 + 降级注明（作业状态无法确认，不允许盲停）
// 方法复用 docs/测试证据/UI-DR01-20260911/dr01-screenshot-cdp.mjs（SSH 隧道 localhost:8090 → 195）
// 前置：ssh -i ~/.ssh/id_ed25519 -L 8090:127.0.0.1:8090 -N root@146.56.195.225
// 运行：node dr02-screenshot-cdp.mjs；截后 md5sum 自查三帧互异
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9229',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/dr02-edge', '--window-size=1366,768',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9229/json')).json()
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

// ---- s01 新建第一步：场景目录接口未就绪 → 静态说明降级空态（403/404 如实，不渲染假目录） ----
await nav(BASE + '/drills/new', 4000)
console.log('step1 alert =', await evalJs(`document.querySelector('.step-alert')?.textContent?.slice(0, 130)`))
console.log('scenario cards =', await evalJs(`document.querySelectorAll('.scenario-card.disabled').length`))
await saveShot('s01-drill-new-step1-templates-degraded.png')

// ---- s02 新建第三步：「执行预检」显式禁用 + 注明（预检接口依赖 DR-02 未部署） ----
await evalJs(`[...document.querySelectorAll('.step-nav button')].find(b => b.textContent.includes('下一步'))?.click(); 'ok'`)
await sleep(500)
await evalJs(`[...document.querySelectorAll('.step-nav button')].find(b => b.textContent.includes('下一步'))?.click(); 'ok'`)
await sleep(800)
console.log('preview disabled =', await evalJs(`[...document.querySelectorAll('.preview-actions button')].find(b => b.textContent.includes('执行预检'))?.disabled`))
console.log('preview note =', await evalJs(`document.querySelector('.preview-actions .submit-note')?.textContent?.slice(0, 120)`))
console.log('submit disabled =', await evalJs(`[...document.querySelectorAll('.submit-row button')].find(b => b.textContent.includes('开始演练'))?.disabled`))
await saveShot('s02-drill-new-step3-preview-disabled.png')

// ---- s03 详情页直达：接口未就绪 + 「停止并恢复」禁用（降级注明，不允许盲停） ----
await nav(BASE + '/drills/dr02-probe-00000000', 4000)
console.log('detail result =', await evalJs(`document.querySelector('.el-result')?.textContent?.slice(0, 120)`))
console.log('stop disabled =', await evalJs(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('停止并恢复'))?.disabled`))
console.log('stop title =', await evalJs(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('停止并恢复'))?.getAttribute('title')?.slice(0, 120)`))
await saveShot('s03-drill-detail-stop-degraded.png')

edge.kill()
console.log('done')
process.exit(0)
