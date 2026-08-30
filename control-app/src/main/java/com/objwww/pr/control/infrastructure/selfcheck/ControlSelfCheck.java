package com.objwww.pr.control.infrastructure.selfcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Control 启动自检的纯判定逻辑（B25 运行时门，§6.5；零 Spring，假探针可单测）。
 * 每条违规产出一句人话文案；只出现变量名/表名/权限名，永不出现值。
 *
 * <p>判定清单：
 * <ol>
 *   <li>无写凭证环境变量：已知危险名单 + 名字模式扫描（DP-02 动态对应，F1-A/I2）；</li>
 *   <li>模型 key（AGENT_MODEL_API_KEY）存在（Control 需要模型访问）；</li>
 *   <li>DB 角色对 outbox_command 无 UPDATE 权（I10/AFT-06 冻结，DP-03）；</li>
 *   <li>DB 角色对 outbox_command 有 INSERT 权（配错早发现）。</li>
 * </ol>
 */
public final class ControlSelfCheck {

    public static final String MODEL_KEY_ENV = "AGENT_MODEL_API_KEY";
    public static final String OUTBOX_TABLE = "outbox_command";

    /** 已知危险变量名单（写凭证/App 私钥/通用 PAT） */
    static final Set<String> FORBIDDEN_ENV = Set.of(
            "GITHUB_APP_KEY", "GITHUB_APP_PRIVATE_KEY", "GITHUB_WRITE_TOKEN",
            "GITHUB_TOKEN", "GH_TOKEN");

    /** 名字模式兜底：任何 GITHUB* 且含私钥/App key/写 token 字样的变量 */
    static final Pattern WRITE_CREDENTIAL_NAME =
            Pattern.compile(".*GITHUB.*(PRIVATE_KEY|APP_KEY|WRITE_TOKEN).*");

    private ControlSelfCheck() {
    }

    public static List<String> violations(Map<String, String> env, DbPrivilegeProbe db) {
        List<String> violations = new ArrayList<>();

        for (String name : env.keySet()) {
            if (FORBIDDEN_ENV.contains(name) || WRITE_CREDENTIAL_NAME.matcher(name).matches()) {
                violations.add("检测到写凭证环境变量 " + name
                        + "（F1-A/I2：Control 写凭证零接触，拒绝启动）");
            }
        }
        String modelKey = env.get(MODEL_KEY_ENV);
        if (modelKey == null || modelKey.isBlank()) {
            violations.add("缺少模型凭证环境变量 " + MODEL_KEY_ENV + "（Control 需要模型访问）");
        }

        if (db.hasTablePrivilege(OUTBOX_TABLE, "UPDATE")) {
            violations.add("control DB 角色对 " + OUTBOX_TABLE
                    + " 持有 UPDATE 权限（I10/AFT-06 冻结被破坏，检查 V2 grants/角色配置）");
        }
        if (!db.hasTablePrivilege(OUTBOX_TABLE, "INSERT")) {
            violations.add("control DB 角色对 " + OUTBOX_TABLE
                    + " 无 INSERT 权限（DB 授权配错，检查 V2 grants）");
        }
        return violations;
    }
}
