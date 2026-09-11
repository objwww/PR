package com.objwww.pr.control.release.domain.model;

import java.util.regex.Pattern;

/**
 * 密钥材料键名检测（INV-AM5-5 单一事实源）：ConfigBundle 与 ReleaseAsset 共用同一
 * 黑名单正则——安全检查禁止两份实现漂移。键名递归匹配，命中即由调用方 fail-closed 拒绝。
 * 详细消息文本归调用方（bundle/资产各自语境）。
 */
final class SecretKeys {

    /** 密钥材料键名黑名单（与 ControlArchitectureTest AFT-28 同族语义；键名递归匹配） */
    private static final Pattern SECRET_KEY = Pattern.compile(
            ".*(apikey|api_key|authorization|bearer|secret|password|privatekey).*");

    private SecretKeys() {
    }

    static boolean isSecretKey(String key) {
        return SECRET_KEY.matcher(key.toLowerCase()).matches();
    }
}
