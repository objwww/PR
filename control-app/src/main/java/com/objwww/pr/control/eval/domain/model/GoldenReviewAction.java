package com.objwww.pr.control.eval.domain.model;

/**
 * Golden Candidate 复核事件动作（M5-03）：candidate 状态机每次迁移落一条
 * append-only 事件（golden_review_event，V22 只授 select,insert）。
 * 与 V22 ck_golden_review_action 同值域。
 */
public enum GoldenReviewAction {PROPOSED, SUBMITTED, PUBLISHED, REJECTED, WITHDRAWN}
