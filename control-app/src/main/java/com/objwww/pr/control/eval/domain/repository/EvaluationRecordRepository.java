package com.objwww.pr.control.eval.domain.repository;

import com.objwww.pr.control.eval.domain.model.EvaluationRecordV1;

/**
 * 评测门禁记录仓储（M5-08）：insert-only，落档即冻结——重评 = 换新 record id
 * （与 V10 eval_case_result 同纪律）。无 UPDATE/DELETE 面。
 *
 * <p>持久化实现（独立表 vs eval_run 扩展列）为落码方案开放项 O-1：
 * 原定默认窗口（V23 同任务扩列）已随 V23 发布关闭（INV-AM5-10 已发布迁移不得追加），
 * 独立表路径按落码方案 §M5-08② 需用户确认——接口先行，实现待 O-1 裁定。
 */
public interface EvaluationRecordRepository {

    void insert(EvaluationRecordV1 record);
}
