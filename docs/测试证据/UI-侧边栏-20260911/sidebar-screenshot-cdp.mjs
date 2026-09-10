// UI 侧边栏取证：Edge headless + CDP，真登录 test，截 展开态特写 / 收起态特写 / 总览整页
import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'

const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'

const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9226',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/sidebar-edge', '--window-size=1440,900',
  'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9226/json')).json()
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

async function saveShot(file, clip) {
  const params = { format: 'png' }
  if (clip) params.clip = { ...clip, scale: 1 }
  const { data } = await send('Page.captureScreenshot', params)
  writeFileSync(OUT + '/' + file, Buffer.from(data, 'base64'))
  console.log('saved', file)
}

// 到 /login 并真登录（与 LoginView 同路径）
await send('Page.navigate', { url: BASE + '/login' })
await new Promise(r => setTimeout(r, 2500))
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

// 展开态：确保 localStorage 收起标记清除
await evalJs(`localStorage.setItem('app.navCollapsed', '0'); 'ok'`)
await send('Page.navigate', { url: BASE + '/overview' })
await new Promise(r => setTimeout(r, 5000))
await saveShot('s01-sidebar-expanded.png', { x: 0, y: 0, width: 216, height: 900 })

// 收起态：点击收起按钮，等过渡完成
await evalJs(`document.querySelector('.collapse-btn').click(); 'clicked'`)
await new Promise(r => setTimeout(r, 1200))
const collapsedInfo = await evalJs(`JSON.stringify({
  cls: document.querySelector('.shell-side').className,
  w: document.querySelector('.shell-side').getBoundingClientRect().width,
  ls: localStorage.getItem('app.navCollapsed') })`)
console.log('collapsed state =', collapsedInfo)
await saveShot('s02-sidebar-collapsed.png', { x: 0, y: 0, width: 64, height: 900 })

// 恢复展开，截总览整页
await evalJs(`document.querySelector('.collapse-btn').click(); 'clicked'`)
await new Promise(r => setTimeout(r, 1200))
await saveShot('s03-overview-full.png')

ws.close(); edge.kill()
