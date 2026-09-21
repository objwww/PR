// Executes only the named local rendering/load functions; does not run the Vue app.
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const chat = fs.readFileSync(path.join(root, 'alert-web/src/views/DutyChatView.vue'), 'utf8');
const start = chat.indexOf('function escapeHtml(');
const end = chat.indexOf('const m_sev', start);
assert(start >= 0 && end > start);
const render = vm.createContext({});
vm.runInContext(chat.slice(start, end), render);
const html = render.renderMd('[audit](https://example.invalid/"onmouseover="globalThis.audit=1)');
assert(html.includes('"onmouseover="globalThis.audit=1'));
console.log('REPRODUCED: unescaped quote reaches event-handler attribute in v-html');
console.log(html);
fs.writeFileSync(path.join(root, 'docs/review-current-render-fragment.txt'), html);

const notifications = fs.readFileSync(path.join(root, 'alert-web/src/views/NotificationsView.vue'), 'utf8');
const a = notifications.indexOf('async function load(reset)');
const b = notifications.indexOf('// feed', a);
assert(a >= 0 && b > a);
let resolve;
let calls = 0;
const state = { tab: {value: 'unread'}, loading: {value: false}, rows: {value: []},
  nextCursor: {value: null}, unreadCount: {value: 0}, PAGE_SIZE: 50,
  api: () => { calls++; return new Promise(r => {resolve = r}); },
  ElMessage: { error: m => { throw Error(m); } } };
const ctx = vm.createContext(state);
vm.runInContext(notifications.slice(a, b), ctx);
(async () => {
  const pending = ctx.load(true);
  state.tab.value = 'all';
  await ctx.load(true);
  resolve({ notifications: [{id: 'unread-result'}], unreadCount: 1 });
  await pending;
  assert.equal(calls, 1);
  assert.equal(state.tab.value, 'all');
  assert.equal(state.rows.value[0].id, 'unread-result');
  console.log('REPRODUCED: tab=all receives previous unread response; no new request issued');
})().catch(e => { console.error(e); process.exitCode = 1; });
