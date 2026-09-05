package com.objwww.pr.control.alert.domain.tool;

import com.objwww.pr.shared.Digests;

/**
 * 动作摘要（AM4 M4-15 后半）：sha256(toolName + canonicalJson(args) + scope) 纯函数。
 *
 * <p>三段以 '|' 分隔 + "v1|" 版本前缀（同 AlertIdentityFactory 的规范化纪律），
 * 避免简单拼接的边界歧义（"ab"+"c" 与 "a"+"bc" 碰撞）。字段序无关由 CanonicalJson 保证——
 * 同一工具同一语义参数同一 scope 必然同一 digest，是 ToolGateway 去重/回放匹配的锚点。
 */
public final class ActionDigest {

    private ActionDigest() {
    }

    public static String of(String toolName, Object args, String scope) {
        if (toolName == null || toolName.isEmpty()) {
            throw new IllegalArgumentException("toolName 不得为空");
        }
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("scope 不得为空");
        }
        return Digests.sha256Hex("v1|" + toolName + "|"
                + CanonicalJson.canonicalize(args) + "|" + scope);
    }
}
