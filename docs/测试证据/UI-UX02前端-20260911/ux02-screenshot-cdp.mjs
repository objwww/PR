// UX-02 前端接线取证：Edge headless + CDP + SSH 隧道（本地 8090 → 195）+ 真登录 test/12345678
// 复用 docs/测试证据/UI-UX01前端-20260911/ux01-screenshot-cdp.mjs 方法；窗口 1366×768。
// 前置：ssh -i ~/.ssh/id_ed25519 -N -L 8090:127.0.0.1:8090 root@146.56.195.225
// 运行：node ux02-screenshot-cdp.mjs
// 目的：195 已部署后端无 /api/v1/duty-bot/**（全部 404）——证明值班页「仿真机器人」入口存在、
// 抽屉展开后显式横幅"机器人接口依赖后端 UX-02，当前未部署"、输入框禁用——不报错不伪造回复。
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9230',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ux02-edge', '--window-size=1366,768',
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

// ---- 契约证据：195 后端无 duty-bot 端点 ----
const probe = await evalJs(`(async () => {
  const res = await fetch('/api/v1/duty-bot/sessions', { credentials: 'same-origin' })
  return res.status
})()`)
console.log('duty-bot probe status =', probe)

// ---- s01 值班页：对话窗收起态（入口按钮 + 仿真标记，主体不挤压） ----
await nav(BASE + '/duty', 4000)
console.log('duty entry =', await evalJs(`JSON.stringify({
  entryBtn: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).filter(t => t.includes('仿真机器人')),
  simTag: [...document.querySelectorAll('.el-tag')].map(t => t.textContent.trim()),
  drawerOpen: !!document.querySelector('.el-drawer'),
})`))
await saveShot('s01-duty-collapsed-entry.png')

// ---- s02 抽屉展开态：诚实横幅 + 会话区降级 ----
await evalJs(`[...document.querySelectorAll('button')].find(b => b.textContent.trim() === '仿真机器人')?.click(); 'open'`)
await sleep(3000)
console.log('drawer =', await evalJs(`JSON.stringify({
  title: document.querySelector('.el-drawer__title')?.textContent?.trim(),
  banner: document.querySelector('.bot-panel .el-alert__title')?.textContent?.trim(),
  bannerDesc: document.querySelector('.bot-panel .el-alert__description')?.textContent?.trim(),
  sessionBarBtnsDisabled: [...document.querySelectorAll('.session-bar button')].map(b => b.disabled),
})`))
await saveShot('s02-drawer-expanded-not-deployed-banner.png')

// ---- s03 输入禁用态：textarea disabled + 发送按钮禁用 ----
console.log('input =', await evalJs(`JSON.stringify({
  textareaDisabled: document.querySelector('.input-area textarea')?.disabled,
  placeholder: document.querySelector('.input-area textarea')?.placeholder,
  sendDisabled: [...document.querySelectorAll('.input-foot button')].map(b => b.disabled),
  count: document.querySelector('.input-foot .count')?.textContent,
})`))
await evalJs(`document.querySelector('.input-area textarea')?.scrollIntoView({block:'end'}); 'scrolled'`)
await sleep(400)
await saveShot('s03-input-disabled.png')

ws.close(); edge.kill()
console.log('DONE')
