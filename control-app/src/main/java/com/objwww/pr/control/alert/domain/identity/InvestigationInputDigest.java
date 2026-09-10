package com.objwww.pr.control.alert.domain.identity;

/**
 * 调查输入身份 digest（EX-A0 F04 三身份之一）：Run 铸造时冻结的调查输入
 * （incident episode+告警窗口+查询参数，见 {@link InvestigationInputs}）的内容身份。
 * 持久列 = rca_run.investigation_input_digest（V36）。执行期随 CallContext/工具
 * 信封下传，是动作与预算身份的输入绑定面；与输出侧
 * {@link EvidenceSnapshotDigest} 无转换关系——NativeRcaAgent 拿输入比对输出
 * 快照的 F04 混用即两类型分立的立型理由。
 */
public record InvestigationInputDigest(String value) {

    public InvestigationInputDigest {
        Hex64.require(value, "investigationInputDigest");
    }

    public String hex() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
