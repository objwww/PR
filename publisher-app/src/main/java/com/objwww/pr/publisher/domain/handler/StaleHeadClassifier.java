package com.objwww.pr.publisher.domain.handler;

import com.objwww.pr.shared.TypedResponse;

import java.util.Locale;

/**
 * 422 归类（评审顺手修正，§6.3/EX-02）：GitHub 对 Reviews/Checks API 返回 422 且原因是
 * commit/head 不匹配时，属确定性否定应答（远端结果已知 = 未创建）→ SUPERSEDED(STALE_HEAD)；
 * 参数错误等其他 422 → FAILED_TERMINAL。
 *
 * <p>GitHub 无结构化错误码区分二者，按 message 措辞启发式匹配；判错方向的代价不对称——
 * 漏判（STALE_HEAD 被当参数错误）只是 FAILED_TERMINAL 而非 SUPERSEDED，不制造孤儿副作用。
 */
final class StaleHeadClassifier {

    private StaleHeadClassifier() {
    }

    static boolean isHeadMismatch422(TypedResponse response) {
        if (response.status() != 422 || response.objectBody() == null) {
            return false;
        }
        Object message = response.objectBody().get("message");
        if (message == null) {
            return false;
        }
        String text = message.toString().toLowerCase(Locale.ROOT);
        return text.contains("no commit found")
                || text.contains("commit_id")
                || text.contains("head_sha")
                || text.contains("invalid commit");
    }
}
