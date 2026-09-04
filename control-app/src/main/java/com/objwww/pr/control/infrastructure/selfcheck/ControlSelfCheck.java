package com.objwww.pr.control.infrastructure.selfcheck;

import com.objwww.pr.control.alert.infrastructure.selfcheck.AlertSelfCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Control 启动自检的纯判定逻辑（零 Spring，假探针可单测）。
 * 每条违规产出一句人话文案；只出现变量名/表名/权限名，永不出现值。
 *
 * <p>AM1-T00 清障后：PR 域检查项（outbox_command 权限等）随死代码删除；
 * 告警域检查项（V7 表权限、webhook/Holmes 凭证存在性）由 AM1-T09 AlertSelfCheck 增补。
 *
 * <p>当前判定清单：
 * <ol>
 *   <li>模型 key（AGENT_MODEL_API_KEY）存在（M3 模型治理保留面，AM4 复用）；</li>
 *   <li>DB 角色对 model_call_ledger 有 INSERT 权（V5 授权配错早发现）；</li>
 *   <li>告警域自检（AlertSelfCheck，AM1-T09）：webhook token、Holmes 凭证、V7 九表权限。</li>
 * </ol>
 */
public final class ControlSelfCheck {

    public static final String MODEL_KEY_ENV = "AGENT_MODEL_API_KEY";
    public static final String MODEL_LEDGER_TABLE = "model_call_ledger";

    private ControlSelfCheck() {
    }

    /**
     * 完整自检（通用域 + 告警域）。
     *
     * @param env 环境变量
     * @param db DB 权限探针
     * @param holmesEnabled 是否启用 HolmesGPT
     * @return 违规列表（空 = 通过）
     */
    public static List<String> violations(
            Map<String, String> env,
            DbPrivilegeProbe db,
            boolean holmesEnabled
    ) {
        List<String> violations = new ArrayList<>();

        // 通用域检查
        String modelKey = env.get(MODEL_KEY_ENV);
        if (modelKey == null || modelKey.isBlank()) {
            violations.add("缺少模型凭证环境变量 " + MODEL_KEY_ENV + "（M3 模型治理保留面，AM4 复用）");
        }

        if (!db.hasTablePrivilege(MODEL_LEDGER_TABLE, "INSERT")) {
            violations.add("control DB 角色对 " + MODEL_LEDGER_TABLE
                    + " 无 INSERT 权限（DB 授权配错，检查 V5 grants）");
        }

        // 告警域自检（AM1-T09）
        violations.addAll(AlertSelfCheck.violations(env, db::hasTablePrivilege, holmesEnabled));

        return violations;
    }

    /**
     * 兼容性重载（不检查告警域）。
     */
    public static List<String> violations(Map<String, String> env, DbPrivilegeProbe db) {
        return violations(env, db, false);
    }
}
