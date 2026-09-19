// M-d 专用字典（根因结论六要素/把握档位/缺失口径）
// 独立模块缘由：zh.js 属其他 agent 在途改动面，本模块以新文件承载 M-d 新增文案，
// 全中文经字典渲染铁律不变（src/dict/ 模块化同 scenarioZh.js 先例）。

export const mdZh = {
  sixParts: {
    title: '根因结论六要素（M-d）',
    source: '来源 GET /eval/runs/{runId}/six-parts（逐案检出：发生了什么/根因/凭什么/影响/把握/建议；缺席=未评如实）',
    assessed: '已检案例',
    complete: '六要素齐',
    rate: '六要素齐率',
    levels: '把握分布',
    levelHigh: '高把握',
    levelMedium: '中把握',
    levelLow: '低把握',
    noData: '未评',
  },
  processMetrics: {
    title: '过程面指标（M-d）',
    source: '来源 GET /eval/runs/{runId}/process-metrics（率=真实计数比；分母为 0 → — 如实）',
    structurePass: '结构通过率',
    repeatedAction: '重复动作率',
    toolError: '工具错误率',
    checkpointCoverage: '检查点覆盖率',
    grounded: '结论有据率',
    latency: '延迟 P50 / P95',
    noData: '—',
  },
  drafts: {
    title: '提示词草稿',
    source: '来源 /api/v1/prompt-workbench/drafts（起草→对照→裁定；发布走版本中心受控激活，零绕行）',
    newDraft: '起草',
    role: '角色',
    proposedTemplate: '提案模板全文',
    baseSnapshot: '基线快照（起草时刻冻结）',
    discard: '弃稿',
    diff: '对照',
    baseline: '现行基线',
    proposal: '本稿提案',
    empty: '暂无草稿——起草后此处出数',
    submitOk: '草稿已落档',
    discardOk: '已弃稿',
    status: { DRAFT: '起草中', DISCARDED: '已弃稿', APPLIED: '已应用' },
  },
}

/** 把握档位中文映射（未知值原样透传——不冒充已知枚举） */
export function mdSixPartsLevel(level) {
  const map = {
    HIGH: mdZh.sixParts.levelHigh,
    MEDIUM: mdZh.sixParts.levelMedium,
    LOW: mdZh.sixParts.levelLow,
  }
  return (level && map[level]) || level || mdZh.sixParts.noData
}
