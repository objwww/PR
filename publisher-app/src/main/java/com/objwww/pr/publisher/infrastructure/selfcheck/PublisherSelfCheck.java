package com.objwww.pr.publisher.infrastructure.selfcheck;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Publisher 启动自检的纯判定逻辑（B25 运行时门，§6.5；零 Spring，假探针可单测）。
 *
 * <p>判定清单：
 * <ol>
 *   <li>进程非 root（user.name=root 或 env UID=0 即拒，B16 hardening）；</li>
 *   <li>无模型 key AGENT_MODEL_API_KEY（模型访问是 Control 侧职责，F1 隔离）；</li>
 *   <li>GitHub App 私钥文件存在且只读（无任何写位；非 POSIX 平台回退 isWritable）；</li>
 *   <li>DB 角色对 outbox_command：INSERT=false（不能伪造写意图）、UPDATE=true。</li>
 * </ol>
 */
public final class PublisherSelfCheck {

    public static final String MODEL_KEY_ENV = "AGENT_MODEL_API_KEY";
    public static final String OUTBOX_TABLE = "outbox_command";

    private static final Set<PosixFilePermission> ANY_WRITE = EnumSet.of(
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);

    private PublisherSelfCheck() {
    }

    public static List<String> violations(Map<String, String> env, DbPrivilegeProbe db,
                                          ProcessProbe process, FileSecurityProbe files,
                                          String privateKeyPath) {
        List<String> violations = new ArrayList<>();

        if ("root".equalsIgnoreCase(process.userName()) || "0".equals(env.get("UID"))) {
            violations.add("Publisher 不得以 root 运行（B16 hardening），当前 user="
                    + process.userName());
        }
        String modelKey = env.get(MODEL_KEY_ENV);
        if (modelKey != null && !modelKey.isBlank()) {
            violations.add("Publisher 进程不应持有模型 key 环境变量 " + MODEL_KEY_ENV
                    + "（模型访问是 Control 侧职责，F1 隔离）");
        }

        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            violations.add("publisher.github.private-key-path 未配置（私钥只读挂载路径，B16）");
        } else {
            Path keyFile = Path.of(privateKeyPath);
            if (!files.exists(keyFile)) {
                violations.add("GitHub App 私钥文件不存在: " + privateKeyPath);
            } else {
                Optional<Set<PosixFilePermission>> perms = files.posixPermissions(keyFile);
                if (perms.isPresent()) {
                    if (perms.get().stream().anyMatch(ANY_WRITE::contains)) {
                        violations.add("GitHub App 私钥文件必须只读挂载（B16），权限含写位: "
                                + privateKeyPath);
                    }
                } else if (files.writable(keyFile)) {
                    violations.add("GitHub App 私钥文件必须只读（非 POSIX 平台回退检查）: "
                            + privateKeyPath);
                }
            }
        }

        if (db.hasTablePrivilege(OUTBOX_TABLE, "INSERT")) {
            violations.add("publisher DB 角色对 " + OUTBOX_TABLE
                    + " 持有 INSERT 权限（Publisher 不能伪造写意图，检查 V2 grants）");
        }
        if (!db.hasTablePrivilege(OUTBOX_TABLE, "UPDATE")) {
            violations.add("publisher DB 角色对 " + OUTBOX_TABLE
                    + " 无 UPDATE 权限（DB 授权配错，检查 V2 grants）");
        }
        return violations;
    }
}
