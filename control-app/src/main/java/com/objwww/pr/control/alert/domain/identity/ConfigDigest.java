package com.objwww.pr.control.alert.domain.identity;

/**
 * 配置版本 digest（EX-A0 F04 三身份之一）：路由 bundle 的内容身份。
 * 持久列 = rca_run.config_digest（V25）。与
 * {@link InvestigationInputDigest}/{@link EvidenceSnapshotDigest} 无转换关系——
 * 混用编译期拒绝（NativeInvestigationExecutor 把 config 当输入身份的 F04 混用即本类型的立型理由）。
 */
public record ConfigDigest(String value) {

    public ConfigDigest {
        Hex64.require(value, "configDigest");
    }

    public String hex() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
