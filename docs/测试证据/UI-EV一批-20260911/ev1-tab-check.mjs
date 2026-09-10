import { spawn } from 'child_process'
import { writeFileSync } from 'fs'
import { fileURLToPath } from 'url'
const OUT = fileURLToPath(new URL('.', import.meta.url))
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const BASE = 'http://localhost:8090'
const edge = spawn(EDGE, ['--headless=new', '--disable-gpu', '--remote-debugging-port=9229',
  '--user-data-dir=C:/Users/wangp/AppData/Local/Temp/ev1c-edge', '--window-size=1440,900', 'about:blank'], { stdio: 'ignore' })
await new Promise(r => setTimeout(r, 3000))
const targets = await (await fetch('http://127.0.0.1:9229/json')).json()
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl)
let id = 0; const pending = new Map()
const send = (m, p = {}) => new Promise((res) => { const i = ++id; pending.set(i, { res }); ws.send(JSON.stringify({ id: i, method: m, params: p })) })
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending.has(m.id)) { pending.get(m.id).res(m.result); pending.delete(m.id) } }
await new Promise(r => { ws.onopen = r })
await send('Page.enable')
const evalJs = async x => (await send('Runtime.evaluate', { expression: x, returnByValue: true, awaitPromise: true })).result.value
const sleep = ms => new Promise(r => setTimeout(r, ms))
await send('Page.navigate', { url: BASE + '/login' }); await sleep(2500)
await evalJs(`(async () => {
  await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/)
  return (await fetch('/api/auth/login', { method: 'POST', credentials: 'same-origin',
    headers: { 'X-XSRF-TOKEN': m ? decodeURIComponent(m[1]) : '', 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username: 'test', password: '12345678' }) })).status
})()`)
await send('Page.addScriptToEvaluateOnNewDocument', { source: "sessionStorage.setItem('am7.session', JSON.stringify({user:'test'}))" })
await send('Page.navigate', { url: BASE + '/eval/runs/439f2cb2-5755-4068-8519-3324e44c2914?tab=usage' }); await sleep(3500)
console.log('detail tab cur =', await evalJs(`JSON.stringify([...document.querySelectorAll('.run-detail > .tabs .tab')].map(t => t.textContent.trim() + (t.classList.contains('cur') ? '*' : '')))`))
console.log('usage placeholder =', await evalJs(`document.querySelector('.run-detail .panel .el-empty__description')?.textContent?.trim()`))
const { data } = await send('Page.captureScreenshot', { format: 'png' })
writeFileSync(OUT + '/s18-eu05-tab-usage.png', Buffer.from(data, 'base64'))
console.log('saved s18-eu05-tab-usage.png')
ws.close(); edge.kill(); console.log('DONE')
