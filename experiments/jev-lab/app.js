'use strict'
const $ = id => document.getElementById(id)
let config, dataset, current, polling = false
const busy = () => current && ['RUNNING', 'CANCELLING'].includes(current.status)
const pct = value => value == null ? '—' : `${(value * 100).toFixed(1)}%`
const pp = value => value == null ? '—' : `${(value * 100).toFixed(1)} 个百分点`
const num = value => value == null ? '—' : Number(value).toLocaleString('zh-CN', {maximumFractionDigits: 2})
const usd = value => value == null ? '—' : `$${Number(value).toFixed(6)}`
const delta = (a, b, fmt = num) => a == null || b == null ? '—' : `${b > a ? '+' : ''}${(fmt === pct ? pp : fmt)(b - a)}`
const statuses = {RUNNING:'运行中', CANCELLING:'正在取消（等待当前调用结束）', CANCELLED:'已取消', COMPLETED:'运行完成', INTERRUPTED:'进程中断', TIMED_OUT:'超过时限', FAILED:'失败', HALTED_ERRORS:'连续失败，已停止'}
const verdicts = {IMPROVED:'Recall 与 F1 均有提升证据', REGRESSED:'至少一项指标有下降证据', NO_CLEAR_GAIN:'暂无明确提升：置信区间包含 0', INCONCLUSIVE:'证据不足，暂不能判定真实提升'}
function fail(error) { $('error').textContent = error.message || String(error); $('error').hidden = false }
async function api(path, body) {
  const response = await fetch(path, body === undefined ? {} : {method:'POST', headers:{'Content-Type':'application/json', 'X-Lab-Token':config.token}, body:JSON.stringify(body)})
  const value = await response.json()
  if (!response.ok) throw new Error(value.error || `HTTP ${response.status}`)
  return value
}
function controls() {
  const count = dataset?.cases?.length || 0, rounds = Number($('rounds').value)
  const clusters = new Set((dataset?.cases || []).map(c => c.cluster_id)).size
  $('datasetInfo').textContent = dataset ? `${dataset.version} · ${count} 案例 / ${clusters} 独立事件 · ${dataset.synthetic ? '合成数据' : '人工标注数据'}` : '尚未载入数据集'
  $('plan').textContent = `${rounds} 批 × ${count} 案例 = ${rounds * count} 对；最多 ${rounds * count * 3} 次模型请求（2 次诊断 + 1 次 Jev / 对）。无自动重试。`
  $('start').textContent = `运行 ${rounds} 批配对实验`
  $('start').disabled = !count || busy() || ($('mode').value === 'live' && !config?.live_ready)
  $('cancel').disabled = !busy() || current.status === 'CANCELLING'
  $('download').disabled = !current
  for (const id of ['file','example','mode','rounds','maxItems','maxChars','threshold','deadline']) $(id).disabled = busy()
}
async function history() {
  const selected = current?.id
  const runs = await api('/api/runs')
  $('history').replaceChildren(new Option('选择实验', ''))
  for (const run of runs) $('history').add(new Option(`${new Date(run.created_at*1000).toLocaleString('zh-CN')} · ${run.mode.toUpperCase()} · ${statuses[run.status]}`, run.id))
  $('history').value = selected || ''
}
function cellRow(target, values) {
  const tr = document.createElement('tr')
  for (const value of values) { const td = document.createElement('td'); td.textContent = value; tr.append(td) }
  target.append(tr); return tr
}
function card(title, value, sub) {
  const article = document.createElement('article'); article.className = 'card metric-card'
  for (const [cls, content] of [['muted',title],['value',value],['sub',sub]]) {
    const node = document.createElement('div'); node.className = cls; node.textContent = content; article.append(node)
  }
  $('cards').append(article)
}
function ciText(stats) { return stats.ci95 ? `95% CI [${(stats.ci95[0]*100).toFixed(1)}, ${(stats.ci95[1]*100).toFixed(1)}] 个百分点 · ${stats.clusters} 个事件` : `${stats.clusters} 个独立事件 · CI 未评` }
function render() {
  if (!current) return
  const s = current.summary, a = s.arms.baseline, b = s.arms.jev
  $('runMode').textContent = `${current.mode.toUpperCase()}${current.dataset.synthetic ? ' / 合成数据' : ''}`
  $('statusText').textContent = statuses[current.status]
  $('progressText').textContent = `${s.completed_pairs} / ${s.planned_pairs} 配对 · 有效 ${s.valid_pairs}`
  $('progress').max = s.planned_pairs; $('progress').value = s.completed_pairs
  $('verdict').textContent = verdicts[s.verdict] + (current.error ? ' · '+current.error : '')
  $('provenance').textContent = `数据 SHA256 ${current.dataset_hash} · 版本 ${current.version} · ${s.note}`
  $('cards').replaceChildren()
  card('最终症状 Recall · micro', `${pct(a.micro?.recall)} → ${pct(b.micro?.recall)}`, `Δ ${delta(a.micro?.recall, b.micro?.recall, pct)} · 同一组成对成功样本`)
  card('最终症状 F1 · micro', `${pct(a.micro?.f1)} → ${pct(b.micro?.f1)}`, `Δ ${delta(a.micro?.f1, b.micro?.f1, pct)} · TP / FP / FN 集合评分`)
  card('事件等权 Δ F1 · 配对统计', pp(s.paired_f1.delta), ciText(s.paired_f1))
  card('事件等权 Δ Recall', pp(s.paired_recall.delta), ciText(s.paired_recall))
  $('metrics').replaceChildren()
  const rows = [
    ['最终症状 Precision · micro', a.micro?.precision, b.micro?.precision, pct],
    ['最终症状 Recall · micro', a.micro?.recall, b.micro?.recall, pct],
    ['最终症状 F1 · micro', a.micro?.f1, b.micro?.f1, pct],
    ['最终症状 F1 · macro', a.macro_f1, b.macro_f1, pct],
    ['根因三元组完全命中率', a.root_accuracy, b.root_accuracy, pct],
    ['筛选证据 Recall（中间指标）', a.selection_recall, b.selection_recall, pct],
    ['筛选证据 F1（中间指标）', a.selection_f1, b.selection_f1, pct],
    ['最终引用证据 F1', a.citation_f1, b.citation_f1, pct],
    ['不可见 / 无效引用数量', a.invalid_citations, b.invalid_citations, num],
    ['上下文证据字符总数 · 配对成功', a.evidence_chars, b.evidence_chars, num],
    ['全链路延迟 p50 (ms)', a.latency_p50_ms, b.latency_p50_ms, num],
    ['全链路延迟 p95 (ms)', a.latency_p95_ms, b.latency_p95_ms, num],
    ['累计失败次数', a.failed, b.failed, num],
    ['未结束 / 中断中的尝试', a.incomplete_attempts, b.incomplete_attempts, num],
    ['累计已发起模型调用', a.attempted_calls, b.attempted_calls, num],
    ['诊断模型累计输入 Token', a.llm_input_tokens, b.llm_input_tokens, num],
    ['Jev 累计输入 Token', a.jev_input_tokens, b.jev_input_tokens, num],
    ['所有模型累计输入 Token（跨 tokenizer 求和）', a.input_tokens, b.input_tokens, num],
    ['所有模型累计输出 Token', a.output_tokens, b.output_tokens, num],
    ['全链路累计 Token 总数', a.total_tokens, b.total_tokens, num],
    ['缺失 usage 的调用', a.missing_usage_calls, b.missing_usage_calls, num],
    ['估算累计费用 USD（按配置单价）', a.cost_usd, b.cost_usd, usd],
  ]
  for (const [label, av, bv, fmt] of rows) cellRow($('metrics'), [label, fmt(av), fmt(bv), delta(av, bv, fmt)])
  for(const [label,key] of [['诊断模型输入 Token 节省率','llm_input_tokens'],['全链路 Token 节省率','total_tokens'],['全链路估算费用节省率','cost_usd']]) cellRow($('metrics'),[label,'基准',pct(s.savings[key]),'正值为节省；仅完整实验计算'])
  $('details').replaceChildren()
  for (const pair of current.pairs) {
    const av = pair.baseline?.metrics?.symptoms?.f1, bv = pair.jev?.metrics?.symptoms?.f1
    const tr = cellRow($('details'), [`${pair.batch} / ${pair.case_id}`, pair.order.join(' → '), `${pair.baseline?.status || '未运行'} / ${pct(av)}`, `${pair.jev?.status || '未运行'} / ${pct(bv)}`, delta(av,bv,pct)])
    const show = () => { $('audit').open = true; $('auditText').textContent = JSON.stringify(pair, null, 2) }
    tr.tabIndex = 0; tr.addEventListener('click', show); tr.addEventListener('keydown', e => { if(e.key === 'Enter') show() })
  }
  chart(current); controls()
}
function chart(run) {
  const svg = $('chart'); svg.replaceChildren()
  const add = (tag, attrs, text) => { const el = document.createElementNS('http://www.w3.org/2000/svg',tag); for(const [k,v] of Object.entries(attrs)) el.setAttribute(k,v); if(text) el.textContent=text; svg.append(el) }
  for (const v of [0,.5,1]) { const y=170-v*145; add('line',{x1:40,x2:975,y1:y,y2:y,class:'grid'}); add('text',{x:0,y:y+4},pct(v)) }
  const batches = run.options.rounds
  for (const [arm,cls] of [['baseline','a'],['jev','b']]) {
    let last
    for(let n=1;n<=batches;n++) {
      const pairs=run.pairs.filter(p=>p.batch===n && p.baseline?.status==='SUCCESS' && p.jev?.status==='SUCCESS')
      const x=40+(n-1)*935/Math.max(1,batches-1)
      if(arm==='baseline' && (n===1||n===batches||n%5===0)) add('text',{x,y:195},String(n))
      if(!pairs.length) {last=null;continue}
      const y=170-pairs.reduce((v,p)=>v+p[arm].metrics.symptoms.f1,0)/pairs.length*145
      if(last) add('line',{x1:last.x,y1:last.y,x2:x,y2:y,class:cls})
      add('circle',{cx:x,cy:y,r:2.5,class:cls});last={x,y}
    }
  }
}
async function load(id) { if (!id) return; current = await api('/api/runs/'+id); render() }
$('file').addEventListener('change', async e => { try { const file=e.target.files[0]; if(!file)return; if(file.size>2000000)throw new Error('数据集超过 2MB'); const value=JSON.parse(await file.text()); if(!Array.isArray(value.cases)||!value.cases.every(c=>c && typeof c==='object'))throw new Error('缺少有效 cases'); dataset=value; controls() }catch(e){fail(e)} })
$('example').addEventListener('click', async()=>{try{dataset=await api('/api/example');controls()}catch(e){fail(e)}})
for(const id of ['mode','rounds','maxItems','maxChars','threshold','deadline']) $(id).addEventListener('input',controls)
$('start').addEventListener('click',async()=>{
  $('error').hidden=true; $('start').disabled=true
  try {
    const result=await api('/api/runs',{dataset,mode:$('mode').value,options:{rounds:Number($('rounds').value),max_items:Number($('maxItems').value),max_chars:Number($('maxChars').value),threshold:Number($('threshold').value),deadline_seconds:Number($('deadline').value)}})
    await load(result.id); await history()
  }catch(e){fail(e)}finally{controls()}
})
$('cancel').addEventListener('click',async()=>{try{await api('/api/runs/'+current.id+'/cancel',{});await load(current.id)}catch(e){fail(e)}})
$('history').addEventListener('change',async e=>{try{await load(e.target.value)}catch(e){fail(e)}})
$('refresh').addEventListener('click',async()=>{try{await history();if(current)await load(current.id)}catch(e){fail(e)}})
$('download').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(current,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=`jev-lab-${current.id}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)})
setInterval(async()=>{if(!current||!busy()||polling)return;polling=true;try{await load(current.id);if(!busy())await history()}catch(e){fail(e)}finally{polling=false}},2000)
;(async()=>{try{config=await api('/api/config');$('connection').textContent=config.live_ready?'真实 API 已配置':'本地演示可用 · 真实 API 未配置';$('modelInfo').textContent=`Jev: ${config.models.jev} / 诊断模型: ${config.models.llm||'未配置'}`;await history();const runs=await api('/api/runs');if(runs.length)await load(runs[0].id);controls()}catch(e){fail(e)}})()
