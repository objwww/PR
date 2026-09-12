package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R3 路线B 一致性案（修补方案 v1 R3 测试落点）："prompt 协议文本与执行面行为
 * 一致"——PROTOCOL_SUFFIX 委派段描述的字段集必须与 PrimaryDecision.DelegateRequest
 * 的实际分量集、BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS 断言面三方可账；
 * 协议文案漂移 = 新决策字段无协议描述（模型输出必然 UNPARSEABLE 或字段被静默
 * 丢弃）。诚实面文案（question 仅入台账审计/不接受自由文本指令）随协议在位。
 */
class R3ProtocolConsistencyTest {

    @Test
    @DisplayName("三方对账：协议文本字段集 = DELEGATE_REQUEST_FIELDS = DelegateRequest 分量集")
    void protocolFieldsMatchDecisionRecordAndAssertFace() {
        Set<String> protocolFields = delegateFieldsInProtocol();

        assertThat(protocolFields).as("PROTOCOL_SUFFIX 委派段必须描述全部请求字段")
                .containsAll(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS);

        // 执行面 record 分量为 camelCase，协议/JSON 面为 snake_case——对账须换算
        Set<String> recordFields = Arrays.stream(
                        PrimaryDecision.DelegateRequest.class.getRecordComponents())
                .map(c -> camelToSnake(c.getName()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS)
                .as("断言面与执行面 record 分量集（snake 形）一致（新字段漏改断言面即红）")
                .containsExactlyInAnyOrderElementsOf(recordFields);
        assertThat(protocolFields).as("协议文案不描述执行面不存在的字段（诚实面）")
                .isEqualTo(new LinkedHashSet<>(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS));
    }

    /** camelCase → snake_case（协议/JSON 契约形；与 PrimaryDecision.parse 同映射） */
    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    @Test
    @DisplayName("R3 路线B 诚实面文案在位：固定查询专家/question 仅台账审计/不接受自由文本")
    void honestSemanticsTextPresent() {
        String suffix = BoundedLlmRoleRunner.PROTOCOL_SUFFIX;

        assertThat(suffix).as("委派语义=按冻结窗+inputRefs 的固定查询专家")
                .contains("固定查询专家");
        assertThat(suffix).as("question 仅入台账审计，不进子任务执行面")
                .contains("仅入台账审计");
        assertThat(suffix).as("不接受自由文本指令（R3 卡 B 路线①）")
                .contains("不接受自由文本");
    }

    @Test
    @DisplayName("协议三形状与执行面分支一致：tool_call/delegate/final 各出现恰一次")
    void protocolShapesMatchDecisionBranches() {
        String suffix = BoundedLlmRoleRunner.PROTOCOL_SUFFIX;
        List<String> branchNames = Arrays.stream(PrimaryDecision.Branch.values())
                .map(b -> b.name().toLowerCase())
                .toList();

        assertThat(branchNames).containsExactlyInAnyOrder("tool_call", "delegate", "final");
        assertThat(suffix).contains("\"tool_call\"").contains("\"delegate\"")
                .contains("\"final\"");
    }

    /** 从 PROTOCOL_SUFFIX 的 delegate 形状段提取 "字段": 出现的字段名集合 */
    private static Set<String> delegateFieldsInProtocol() {
        String suffix = BoundedLlmRoleRunner.PROTOCOL_SUFFIX;
        int delegateStart = suffix.indexOf("\"delegate\"");
        int finalStart = suffix.indexOf("\"final\"");
        assertThat(delegateStart).isPositive();
        assertThat(finalStart).isGreaterThan(delegateStart);
        String delegateSection = suffix.substring(delegateStart, finalStart);
        return Arrays.stream(BoundedLlmRoleRunner.DELEGATE_REQUEST_FIELDS.toArray())
                .map(String::valueOf)
                .filter(delegateSection::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
