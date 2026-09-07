package com.objwww.pr.control.eval.domain.model;

/**
 * 评测四分区（M5-01 落枚举面，M5-02 落 RLS 权限矩阵）：TUNING=调优可见、
 * VALIDATION=验证门、HOLDOUT=封存门（Agent/RAG 身份恒 0 可见）、REDTEAM=红队集。
 * 同 scenario_family_id 整组只许落同一分区（拆分违约由
 * {@code DatasetAdapter.assertFamilyPartition} 应用层断言 + M5-02 DB 唯一约束兜底）。
 */
public enum PartitionClass {TUNING, VALIDATION, HOLDOUT, REDTEAM}
