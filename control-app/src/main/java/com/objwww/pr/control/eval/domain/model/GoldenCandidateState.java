package com.objwww.pr.control.eval.domain.model;

/**
 * Golden Candidate 状态（M5-03，INV-AM5-2）：DRAFT→REVIEW→PUBLISHED/REJECTED/WITHDRAWN；
 * PUBLISHED/REJECTED/WITHDRAWN 终态无出边（发布/拒绝/撤回无旁路）。
 * 与 V22 ck_golden_candidate_state 同值域。
 */
public enum GoldenCandidateState {DRAFT, REVIEW, PUBLISHED, REJECTED, WITHDRAWN}
