/**
 * 评测稳定性指标（EvalRunDetail 稳定性卡片区）中文文案字典（M-e D01 / 审查 F09 修正）。
 * 命名纪律：
 * - micro（逐轮等权）与 macro（场景等权）分称，场景轮数不同时二者必然偏离，
 *   不可混称同一个 pass@1；
 * - 「全部计划轮次成功率」= 全轮命中口径，只有固定 k 且全部计划 trial 终态完整
 *   （后端 planComplete=true 且 plannedRoundsPerScenario 非空）时才以 pass^k 命名；
 *   少轮/缺计划身份时只是暂态观测，页面标「暂态」并展示进度，不构成通过结论；
 * - 「至少一次成功」是通行 pass@k 本义（k 次至少一轮命中），实测比例直展，
 *   不假设各轮独立、绝不用 p^k 估算冒充实测；
 * - 轮间一致性与对错正交——稳定地答错也一致，一致性单独不代表质量。
 */
export const EVAL_STABILITY_ZH = {
  microPass: {
    label: '逐轮成功率 micro',
    tip: '命中轮次 ÷ 已终态落档轮次（逐轮等权平均）。与场景等权的 macro 不是同一指标：场景轮数不同时二者必然偏离，不可混称同一个 pass@1。',
  },
  macroPass: {
    label: '场景等权成功率 macro',
    tip: '各场景命中率（场景命中轮 ÷ 场景落档轮）的算术平均，场景等权；与逐轮等权的 micro 分称，各自如实呈现。',
  },
  allPlannedRounds: {
    label: '全部计划轮次成功率',
    tip: '冻结计划轮次全部终态落档且每轮根因全中的场景 ÷ 场景数（全轮命中口径）。只有固定 k 且全部计划 trial 终态完整时才构成 pass^k 通过结论；少轮/缺计划身份时只是暂态观测。',
  },
  passAtLeastOnce: {
    label: '至少一次成功 pass@k',
    tip: 'k 次重复中至少一轮根因命中的场景 ÷ 场景数（通行 pass@k 本义）。实测比例直展，不假设各轮独立、不用 p^k 估算冒充实测。',
  },
  consistency: {
    label: '轮间一致性',
    tip: '判定与实际根因三元组全轮一致的场景 ÷ 场景数。与对错正交——稳定地答错也一致，一致性单独不代表质量。',
  },
  roundsProgress: {
    label: '计划轮次进度',
    tip: '已终态落档轮次 ÷ 冻结 launch plan 计划轮次（回放形态场景计划裁剪为 1 轮）。计划快照不可用或缺 caseKeys 身份时显示「未统计」——不猜计划。',
  },
}

/** 全部计划轮次成功率展示名：固定 k 且计划终态完整 → 附 pass^k 命名；否则只给基础名 */
export function allPlannedRoundsLabel(stability) {
  const base = EVAL_STABILITY_ZH.allPlannedRounds.label
  if (stability?.planComplete === true && stability?.plannedRoundsPerScenario != null) {
    return `${base} pass^${stability.plannedRoundsPerScenario}`
  }
  return base
}
