package com.objwww.pr.control.alert.application.agent;

import com.objwww.pr.control.alert.domain.agent.PrimaryCheckpoint;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jev 增强路径缝（JE-01，方案 §5 步骤3）：BoundedLlmRoleRunner 在共享守卫/预算/
 * 取消约束下调用的两个决策点——装配前选材、FINAL 提交前复核。实现方必须满足：
 * <ul>
 *   <li>任何失败一律<b>有界回退</b>（选材回现有窗口、复核放行本次 final），永不
 *       打断主路径；</li>
 *   <li>所有 Jev 网络调用过预算门 + rca_model_call 账本（独立 roleId + 保留段
 *       动作序），计入同一次调查账目；</li>
 *   <li>关闭/未冻结/无客户端 = 返回"未启用"（null/empty），既有行为零漂移。</li>
 * </ul>
 * 可空注入（null = 装配零漂移），与 ContextCompactionService 同律。
 */
public interface JevEnhancementPort {

    /** Full enhancement is available only in SELECT mode with a configured client. */
    default boolean available() { return false; }
    default boolean enabledFor(RoleRunner.RoleDriveRequest request) { return false; }
    /** At most one additional primary-model retry per run, counted from the durable model ledger. */
    default boolean allowModelRetry(RoleRunner.RoleDriveRequest request) { return false; }

    /**
     * 装配前选材（证据窗截断前对全量候选池评分）。返回 null = 本步不应用选材
     * （未启用/无冗余/失败回退），装配器按既有确定性窗口出料。
     */
    ContextAssembler.EvidenceSelection selectContext(SelectionInput input);

    /**
     * FINAL 提交前复核。empty = 放行本次 final（未启用/无缺口/失败/同签名第二次）；
     * present = 缺口反馈文本（含机器签名前缀，经 lastError 回喂，主任务计步重驱）。
     */
    Optional<String> reviewFinal(ReviewInput input);

    /** 选材输入（冻结快照由运行器单次读库后传入——装配 CL-03 单读纪律不破） */
    record SelectionInput(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint,
            ContextAssembler.EvidenceSnapshot snapshot,
            ContextAssembler.AlertMaterial material) {
    }

    /** 复核输入：claimRows 为 PrimaryClaimAdmission 准入后的行（含 evidence_refs） */
    record ReviewInput(RoleRunner.RoleDriveRequest request,
            PrimaryCheckpoint checkpoint,
            List<Map<String, Object>> admittedClaimRows,
            List<String> missingInformation, List<Map<String, Object>> evidence) {
        public ReviewInput(RoleRunner.RoleDriveRequest request, PrimaryCheckpoint checkpoint,
                List<Map<String,Object>> claims, List<String> missing) {
            this(request,checkpoint,claims,missing,List.of());
        }
    }
}
