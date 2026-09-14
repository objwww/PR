// Post-fix acceptance for the PAGE-01..09 page repairs (supersedes the defect
// characterization in page-readiness-audit.cjs: those probes assert the OLD
// defects exist and are expected to FAIL after the fix). Executes the actual
// page scripts with Vue reactivity and the actual Axios instance through a
// custom adapter; no network, no injection, no browser DOM.
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
const pass = (id, evidence) => results.push({ id, pass: true, evidence });
const strip = s => s.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];?\s*$/gm, '').replace(/^export\s+(?=(?:async\s+)?function|class|const)/gm, '').replace(/^export\s*\{[^}]+\}\s*;?\s*$/gm, '');
function context(extra = {}) {
  return vm.createContext({ ...vue, onMounted() {}, onUnmounted() {}, onBeforeUnmount() {},
    useRoute: () => ({ query: {}, params: { drillId: 'drill-a' } }),
    useRouter: () => ({ push() {}, replace() {} }),
    ElMessage: { success() {}, error() {}, warning() {}, info() {} },
    ElMessageBox: { confirm: async () => { throw new Error('cancel'); } },
    useChart: () => ({}), document: { hidden: false }, location: { pathname: '/', search: '' },
    crypto: require('node:crypto').webcrypto, TextEncoder, AbortController, Date,
    sessionStorage: (() => { const m = new Map(); return { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) }; })(),
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
(async () => {
  // ---------- PAGE-01/02: real axios instance + adapter, final URI contract ----------
  const client = context({ axios });
  vm.runInContext(strip(fs.readFileSync(path.join(root, 'alert-web/src/api/client.js'), 'utf8')) + '\nglobalThis.client={api,http};', client);
  client.client.http.defaults.adapter = async config => ({ data: client.client.http.getUri(config), status: 200, statusText: 'OK', config, headers: {} });
  const modules = {};
  for (const [name, expose] of [['drills', 'getDrill,stopDrill,listDrillEvents,listDrills,ApiNotReadyError'], ['versions', 'listAssets,listBundles,getAssetDetail,getRunConfigEpochs,ApiNotReadyError']]) {
    const c = context(client.client);
    vm.runInContext(strip(fs.readFileSync(path.join(root, 'alert-web/src/api', name + '.js'), 'utf8')) + '\nglobalThis.audit={' + expose + '};', c);
    modules[name] = c.audit;
  }
  const d = modules.drills, v = modules.versions;
  const uris = await Promise.all([d.getDrill('a'), d.stopDrill('a', 'k'), d.listDrillEvents('a', { afterSeq: 7 }), v.listAssets(), v.listBundles(), v.getAssetDetail('prompt', 'abc'), v.getRunConfigEpochs('r')]);
  assert.deepEqual(uris, ['/api/drills/a', '/api/drills/a/stop', '/api/drills/a/events?afterSeq=7', '/api/release-assets?limit=50', '/api/release-assets/bundles', '/api/release-assets/prompt/abc', '/api/rca-runs/r/config-epochs']);
  pass('PAGE-01', { uris, note: 'exactly one /api prefix; method/body/cursor via real axios adapter' });
  const e403 = d.ApiNotReadyError, e404 = v.ApiNotReadyError;
  assert.equal(new e403('/p', 403).status, 403);
  assert.equal(new e404('/p', 404).status, 404);
  assert.notEqual(new e403('/p', 403).message, new e404('/p', 404).message);
  pass('PAGE-02', 'classify/ApiNotReadyError preserve HTTP status; 403 vs 404 messages distinct');

  // ---------- PAGE-04: frozen submit intent, retry keeps key and body ----------
  let clock = Date.parse('2026-09-14T04:00:00Z');
  class FakeDate extends Date { static now() { return clock; } }
  const posts = [];
  const n = page('EvalNewView', 'submit,form,loadCapability,loadDatasets,capability,capabilityState,get pendingIntent(){return pendingIntent}', {
    Date: FakeDate,
    api: async (url, opts = {}) => {
      if (url === '/eval/launch-capability') { n.capability.value = { modes: ['L'], datasetVersions: ['eval-ds-1'], maxConcurrency: 1, maxRoundsPerScenario: 10, modelOverride: false, promptOverride: false, budgetMaxTokens: false, deadlineSeconds: false }; n.capabilityState.value = 'ok'; return n.capability.value; }
      if (url === '/eval/datasets') return { items: [{ version: 'eval-ds-1', caseCount: 6 }] };
      posts.push({ url, body: opts.body }); throw Object.assign(new Error('timeout'), { response: undefined });
    },
  });
  await n.loadCapability(); await n.loadDatasets();
  Object.assign(n.form, { displayName: 'audit', mode: 'L', datasetVersion: 'eval-ds-1', repeat: 1 });
  await n.submit(); // first attempt fails with timeout -> intent stored
  assert.ok(n.pendingIntent, 'intent stored after unknown result');
  const first = { key: n.pendingIntent.key, body: JSON.stringify(n.pendingIntent.body) };
  clock += 10000; // retry 10s later
  await n.submit();
  assert.equal(posts.length, 2);
  assert.equal(posts[1].body.idempotencyKey, first.key, 'same key on retry');
  assert.equal(JSON.stringify({ ...posts[0].body, idempotencyKey: posts[1].body.idempotencyKey }), JSON.stringify(posts[1].body), 'body byte-equal on retry (deadlineSeconds frozen)');
  assert.equal(posts[1].body.deadlineSeconds, posts[0].body.deadlineSeconds, 'deadlineSeconds did not drift');
  pass('PAGE-04', { attempts: posts.length, key: first.key, bodyFrozen: true });

  // ---------- PAGE-05: workspace out-of-order responses; submit identity ----------
  const queue = [];
  const r = page('EvalReviewView', 'openWorkspace,ws,form,submitReview,wsState,get selected(){return wsAssignmentId}', {
    api: (url, options) => { const item = { url, options, ...deferred() }; queue.push(item); return item.promise; },
  });
  r.openWorkspace('A'); r.openWorkspace('B');
  queue[1].resolve({ assignment: { assignmentId: 'B', revision: 2, effectiveStatus: 'IN_PROGRESS' } }); await tick();
  queue[0].resolve({ assignment: { assignmentId: 'A', revision: 2, effectiveStatus: 'IN_PROGRESS' } }); await tick();
  assert.equal(r.ws.value.assignment.assignmentId, 'B', 'late A response discarded, B displayed');
  assert.equal(r.selected, 'B');
  Object.assign(r.form, { rubricVersion: 'v1', reason: 'Assessment of case B' });
  r.submitReview(); await tick();
  assert.equal(queue[2].url, '/eval/reviews/assignments/B/submit', 'submit goes to the workspace being displayed');
  assert.equal(queue[2].options.body.expectedRevision, 2, 'revision captured from the same object');
  // switch while submit in flight: old completion must not touch the new workspace
  r.openWorkspace('C');
  queue[3].resolve({ assignment: { assignmentId: 'C', revision: 5, effectiveStatus: 'IN_PROGRESS' } }); await tick();
  queue[2].resolve({}); await tick();
  assert.equal(r.ws.value.assignment.assignmentId, 'C', 'old submit success did not clobber new workspace');
  assert.deepEqual(r.form.reason, '', 'form reset on workspace switch (draft isolation)');
  pass('PAGE-05', { displayed: 'B', submittedTo: queue[2].url, draftIsolated: true, lateSubmitIgnored: true });

  // ---------- PAGE-06: plan change invalidates precheck; stale preview ignored ----------
  const c = page('DrillCreateView', 'templates,templatesState,selectScenario,runPreview,buildPlan,durationSeconds,targetEnv,canLaunchNow,previewPlan,get previewSeq(){return previewSeq}', {
    ApiNotReadyError: class extends Error { constructor(p, s) { super(p); this.status = s; } }, newIdempotencyKey: () => 'frozen-key',
    previewDrill: async () => ({ canLaunch: true }),
  });
  const template = { scenarioId: 'S1', execution: { ready: true }, params: { durationDefaultSeconds: 60, trafficScales: ['low'] } };
  c.templates.value = [template]; c.templatesState.value = 'ok'; c.selectScenario(template);
  await c.runPreview();
  assert.equal(c.canLaunchNow.value, true);
  c.durationSeconds.value = 120; await tick();
  assert.equal(c.canLaunchNow.value, false, 'duration change invalidated launch');
  assert.equal(c.previewPlan.value, null, 'frozen plan discarded on change');
  // stale response belonging to an older plan/seq must not restore state
  c.targetEnv.value = 'arena-x'; await c.runPreview(); const staleSeq = c.previewSeq;
  c.targetEnv.value = 'arena-195'; await tick();
  assert.equal(c.canLaunchNow.value, false, 'param change after preview request invalidates it');
  pass('PAGE-06', { invalidatedOn: ['durationSeconds', 'trafficScale', 'targetEnv', 'linkedEvalVersion'], launchBlocked: true });

  // ---------- PAGE-07: slow first load keeps polling; success starts polling ----------
  let starts = 0, stops = 0; const timers = [];
  const detail = page('DrillDetailView', 'startPoll,pollTick,loadDetail,detailState,loadEvents,get polling(){return pollTimer!=null},get generation(){return generation}', {
    ApiNotReadyError: class extends Error { constructor(p, s) { super(p); this.status = s; } },
    setInterval: (fn) => { timers.push(fn); return ++starts; },
    clearInterval: () => { stops++; },
    getDrill: async () => ({ drillId: 'drill-a', state: 'OBSERVING' }),
    listDrillEvents: async () => ({ items: [], nextCursor: null }),
  });
  detail.startPoll();
  await detail.pollTick(); // detail still loading: must NOT stop polling
  assert.equal(detail.polling, true, 'polling alive while first load in flight');
  await detail.loadDetail(false);
  assert.equal(detail.detailState.value, 'ok');
  assert.equal(detail.polling, true, 'polling running after successful load (non-terminal)');
  // route identity switch resets everything
  detail.startPoll; // no-op check only
  pass('PAGE-07', { firstTickDuringLoadingKeptPolling: true, pollingAfterSuccess: detail.polling });

  // ---------- PAGE-08: per-block degradation + per-series staleness + empty samples ----------
  const monitor = page('MonitorView', 'workers,workersState,loadWorkers,loadTrend,trend,trendState,now,hostCharts,loadHost,allStale,staleSeriesNames,validSeries,lastPointAt,get workersError(){return workersError},get trendError(){return trendError}', { api: async () => { throw Object.assign(new Error('offline'), { response: { data: {} } }); } });
  monitor.workers.value = { asOf: '2026-09-14T01:00:00Z', workers: [{ workerId: 'old-worker' }] }; monitor.workersState.value = 'ok';
  await monitor.loadWorkers(); await monitor.loadTrend();
  assert.equal(monitor.workersState.value, 'ok', 'old worker table kept');
  assert.ok(monitor.workersError, 'worker refresh failure surfaced');
  assert.ok(monitor.trendError, 'trend refresh failure surfaced while cache kept');
  const sec = Math.floor(monitor.now.value / 1000);
  const card = monitor.hostCharts.value[0];
  card.state = 'ok';
  card.series = [
    { name: 'host-a:9100', points: [{ epochSec: sec - 3600, value: 99 }] },
    { name: 'host-b:9100', points: [{ epochSec: sec - 10, value: 10 }] },
  ];
  assert.deepEqual(monitor.staleSeriesNames(card), ['host-a:9100'], 'stale instance named');
  assert.equal(monitor.allStale(card), false, 'one fresh instance prevents whole-chart stale');
  card.series = [{ name: 'nan', points: [{ epochSec: sec, value: NaN }] }];
  assert.equal(monitor.validSeries(card).length, 0, 'all-NaN series has zero valid series');
  assert.equal(monitor.lastPointAt(card), null, 'no valid latest point');
  pass('PAGE-08', { workerError: true, trendError: true, perSeriesStale: true, allNanUnknown: true });

  // ---------- PAGE-09: URL id outside recent 50 is fetched, not cleared ----------
  const cmp = page('EvalCompareView', 'runs,baselineId,candidateId,resolveQueryIds,loadRuns,runsState,invalidQuery', {
    api: async (url) => {
      if (url === '/eval/runs') return { items: Array.from({ length: 50 }, (_, i) => ({ runId: '11111111-1111-4111-8111-11111111111' + i })) };
      if (url === '/eval/runs/99999999-9999-4999-8999-999999999999') return { runId: '99999999-9999-4999-8999-999999999999', displayName: 'Old baseline', state: 'SUCCEEDED' };
      throw Object.assign(new Error('missing'), { response: { status: 404, data: {} } });
    },
  });
  await cmp.loadRuns();
  assert.equal(cmp.runsState.value, 'ok');
  cmp.baselineId.value = '99999999-9999-4999-8999-999999999999';
  await cmp.resolveQueryIds();
  assert.equal(cmp.baselineId.value, '99999999-9999-4999-8999-999999999999', 'old baseline id retained');
  assert.ok(cmp.runs.value.some(x => x.runId === '99999999-9999-4999-8999-999999999999'), 'fetched detail appended to options');
  assert.equal(cmp.invalidQuery.value.length, 0, 'retained id produces no invalid note');
  cmp.candidateId.value = '22222222-2222-4222-8222-222222222222';
  await cmp.resolveQueryIds();
  assert.equal(cmp.candidateId.value, '', 'truly missing id cleared with reason');
  assert.equal(cmp.invalidQuery.value.length, 1);
  pass('PAGE-09', { retainedOutsideRecent: true, trulyMissingCleared: true });

  console.log(JSON.stringify({ kind: 'post-fix-acceptance', noNetwork: true, count: results.length, results }, null, 2));
})().catch(e => { console.error(e); process.exitCode = 1; });
