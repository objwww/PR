// Offline characterization of current page defects, not post-fix acceptance tests.
// SUPERSEDED 2026-09-14: PAGE-01..09 have been repaired; the defect assertions below
// are EXPECTED TO FAIL now. Post-fix acceptance lives in page-fix-acceptance.cjs.
// Executes actual page scripts with Vue reactivity and local Axios adapter; no network or injection.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const local = createRequire(path.join(root, 'alert-web/package.json'));
const vue = local('vue');
const axios = local('axios');
const results = [];
const strip = s => s.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\s*$/gm, '').replace(/^export\s+(?=(?:async\s+)?function|class|const)/gm, '').replace(/^export\s*\{[^}]+\}\s*;?\s*$/gm, '');
function context(extra = {}) {
  return vm.createContext({ ...vue, onMounted() {}, onUnmounted() {}, onBeforeUnmount() {},
    useRoute: () => ({ query: {}, params: { drillId: 'drill-a' } }),
    useRouter: () => ({ push() {}, replace() {} }),
    ElMessage: { success() {}, error() {}, warning() {}, info() {} },
    useChart: () => ({}), document: { hidden: false }, location: { pathname: '/', search: '' },
    crypto: require('node:crypto').webcrypto, TextEncoder, AbortController, Date,
    setInterval: () => 1, clearInterval() {}, api: async () => ({}), ...extra });
}
function page(name, expose, extra = {}) {
  const source = fs.readFileSync(path.join(root, 'alert-web/src/views', name + '.vue'), 'utf8');
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1];
  const ctx = context(extra);
  vm.runInContext(strip(script) + '\nglobalThis.audit = {' + expose + '};', ctx, { filename: name + '.vue' });
  return ctx.audit;
}
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
const tick = async () => { await Promise.resolve(); await vue.nextTick(); };
function found(id, evidence) { results.push({ id, defectReproduced: true, evidence }); }
(async () => {
  const client = context({ axios });
  vm.runInContext(strip(fs.readFileSync(path.join(root, 'alert-web/src/api/client.js'), 'utf8')) + '\nglobalThis.client={api,http};', client);
  client.client.http.defaults.adapter = async config => ({ data: client.client.http.getUri(config), status: 200, statusText: 'OK', config, headers: {} });
  const modules = {};
  for (const [name, expose] of [['drills', 'getDrill,stopDrill,listDrillEvents,listDrills,classify'], ['versions', 'listAssets,listBundles,getAssetDetail,getRunConfigEpochs']]) {
    const c = context(client.client);
    vm.runInContext(strip(fs.readFileSync(path.join(root, 'alert-web/src/api', name + '.js'), 'utf8')) + '\nglobalThis.audit={' + expose + '};', c);
    modules[name] = c.audit;
  }
  const d = modules.drills, v = modules.versions;
  const uris = await Promise.all([d.getDrill('a'), d.stopDrill('a', 'k'), d.listDrillEvents('a'), v.listAssets(), v.listBundles(), v.getAssetDetail('prompt', 'abc'), v.getRunConfigEpochs('r')]);
  assert(uris.every(u => u.startsWith('/api/api/')));
  assert.equal(await d.listDrills(), '/api/drills');
  found('PAGE-01', uris);
  const forbidden = d.classify('/api/drills', { response: { status: 403 } });
  const missing = d.classify('/api/drills', { response: { status: 404 } });
  assert.equal(forbidden.name, missing.name); assert.equal(forbidden.status, undefined);
  found('PAGE-02', '403 and 404 both become ApiNotReadyError; HTTP status is lost');

  let clock = Date.parse('2026-09-14T04:00:00Z');
  class FakeDate extends Date { static now() { return clock; } }
  const n = page('EvalNewView', 'form,idempotencyKeyOf,deadlineSeconds', { Date: FakeDate });
  Object.assign(n.form, { displayName: 'audit', datasetVersion: 'v1', deadline: '2026-09-14T05:00:00Z' });
  const key1 = await n.idempotencyKeyOf(), seconds1 = n.deadlineSeconds(); clock += 10000;
  const key2 = await n.idempotencyKeyOf(), seconds2 = n.deadlineSeconds();
  assert.equal(key1, key2); assert.notEqual(seconds1, seconds2);
  found('PAGE-04', { sameIdempotencyKey: true, seconds1, seconds2 });

  const queue = [];
  const r = page('EvalReviewView', 'openWorkspace,ws,form,submitReview,get selected(){return wsAssignmentId}', {
    api: (url, options) => { const item = { url, options, ...deferred() }; queue.push(item); return item.promise; },
  });
  r.openWorkspace('A'); r.openWorkspace('B');
  queue[1].resolve({ assignment: { assignmentId: 'B', revision: 2, effectiveStatus: 'IN_PROGRESS' } }); await tick();
  queue[0].resolve({ assignment: { assignmentId: 'A', revision: 2, effectiveStatus: 'IN_PROGRESS' } }); await tick();
  assert.equal(r.ws.value.assignment.assignmentId, 'A'); assert.equal(r.selected, 'B');
  Object.assign(r.form, { rubricVersion: 'v1', reason: 'Assessment of case A' });
  r.submitReview(); await tick();
  assert.equal(queue[2].url, '/eval/reviews/assignments/B/submit');
  found('PAGE-05', { displayed: r.ws.value.assignment.assignmentId, submittedTo: queue[2].url, body: queue[2].options.body });

  const c = page('DrillCreateView', 'templates,templatesState,selectScenario,runPreview,buildPlan,durationSeconds,canLaunchNow,launchKey', {
    ApiNotReadyError: class extends Error {}, newIdempotencyKey: () => 'frozen-key',
    previewDrill: async () => ({ canLaunch: true }),
  });
  const template = { scenarioId: 'S1', execution: { ready: true }, params: { durationDefaultSeconds: 60, trafficScales: ['low'] } };
  c.templates.value = [template]; c.templatesState.value = 'ok'; c.selectScenario(template);
  await c.runPreview(); const oldPlan = c.buildPlan(); c.durationSeconds.value = 120; await tick();
  assert.equal(c.canLaunchNow.value, true); assert.notEqual(oldPlan.durationSeconds, c.buildPlan().durationSeconds);
  found('PAGE-06', { previewDuration: oldPlan.durationSeconds, launchDuration: c.buildPlan().durationSeconds, launchStillEnabled: true });

  let starts = 0, stops = 0;
  const detail = page('DrillDetailView', 'startPoll,pollTick,loadDetail,detailState,get polling(){return pollTimer!=null}', {
    ApiNotReadyError: class extends Error {}, setInterval: () => ++starts, clearInterval: () => { stops++; },
    getDrill: async () => ({ drillId: 'drill-a', state: 'OBSERVING' }),
  });
  detail.startPoll(); await detail.pollTick(); await detail.loadDetail();
  assert.equal(detail.detailState.value, 'ok'); assert.equal(detail.polling, false);
  found('PAGE-07', { initialLoadingAtFirstTick: true, starts, stops, pollingAfterSuccessfulLoad: detail.polling });

  const monitor = page('MonitorView', 'workers,workersState,loadWorkers,now,chartStale', { api: async () => { throw new Error('offline'); } });
  monitor.workers.value = { asOf: '2026-09-14T01:00:00Z', workers: [{ workerId: 'old-worker' }] }; monitor.workersState.value = 'ok';
  await monitor.loadWorkers(); assert.equal(monitor.workersState.value, 'ok');
  const sec = Math.floor(monitor.now.value / 1000);
  assert.equal(monitor.chartStale({ series: [{ points: [{ epochSec: sec - 3600, value: 99 }] }, { points: [{ epochSec: sec, value: 10 }] }] }), false);
  found('PAGE-08', { workerRefreshFailedButState: monitor.workersState.value, oneHostStaleButChartStale: false });

  const cmp = page('EvalCompareView', 'runs,baselineId,candidateId,invalidQuery,validateQuery', {});
  cmp.runs.value = Array.from({ length: 50 }, (_, i) => ({ runId: 'recent-' + i }));
  cmp.baselineId.value = 'older-existing'; cmp.candidateId.value = 'recent-0'; cmp.validateQuery();
  assert.equal(cmp.baselineId.value, '');
  found('PAGE-09', 'A linked run outside the most recent 50 rows is cleared without a detail lookup');
  console.log(JSON.stringify({ kind: 'defect-characterization-not-acceptance', noNetwork: true, count: results.length, results }, null, 2));
})().catch(e => { console.error(e); process.exitCode = 1; });
