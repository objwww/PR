package com.objwww.pr.publisher.domain.handler;

/**
 * reconcile 探测判定（Handler.interpretProbe 产出）。
 *
 * <p>对列表型探针（LIST_CHECKS_FOR_SHA / LIST_REVIEWS），{@link #notFound()} 仅表示
 * "本页未命中"；窗口穷尽与否由 FencedPublicationExecutor 的翻页预算裁决
 * （超窗口未命中 → unknown，EX-04 不无限翻页）。
 */
public record ReconcileVerdict(Kind kind, String remoteId, String remoteUrl) {

    public enum Kind {
        /** 远端对象已存在（找到幂等探针）→ CONFIRMED，不重复创建 */
        FOUND,
        /** 确认不存在（窗口内穷尽）→ RETRY_WAIT 安全重发 */
        NOT_FOUND,
        /** 查不到也不能确认 → reconcile_not_found_count+1，超预算熔断 MANUAL */
        UNKNOWN,
        /** 策略性人工（如 UPDATE_CHECK 远端 404，M0 不自动重建，§6.3） */
        MANUAL_POLICY
    }

    public ReconcileVerdict {
        if (kind == null) {
            throw new NullPointerException("kind");
        }
    }

    public static ReconcileVerdict found(String remoteId, String remoteUrl) {
        if (remoteId == null) {
            throw new NullPointerException("remoteId");
        }
        return new ReconcileVerdict(Kind.FOUND, remoteId, remoteUrl);
    }

    public static ReconcileVerdict notFound() {
        return new ReconcileVerdict(Kind.NOT_FOUND, null, null);
    }

    public static ReconcileVerdict unknown() {
        return new ReconcileVerdict(Kind.UNKNOWN, null, null);
    }

    public static ReconcileVerdict manualPolicy() {
        return new ReconcileVerdict(Kind.MANUAL_POLICY, null, null);
    }
}
