package com.objwww.pr.control.infrastructure.github;

import com.objwww.pr.control.domain.port.CredentialTokenPort;

/**
 * 只读 token 的环境变量 stub（M0-T14 CredentialBroker 就位前的占位实现）。
 * 只够本地手动联调用；集成/生产走 Publisher 窄接口签发的真实 installation token。
 * token 不落日志：缺失时报错消息只出现变量名，不出现值。
 */
public class EnvCredentialTokenPort implements CredentialTokenPort {

    public static final String ENV_VAR = "GITHUB_READONLY_TOKEN";

    @Override
    public String requestReadOnlyToken(long installationId) {
        String token = System.getenv(ENV_VAR);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "缺少只读 token 环境变量 " + ENV_VAR + "（stub 实现；正式签发见 M0-T14 CredentialBroker）");
        }
        return token;
    }
}
