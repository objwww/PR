package com.objwww.pr.publisher.interfaces.token;

import com.objwww.pr.publisher.infrastructure.credential.CredentialBroker;
import com.objwww.pr.publisher.infrastructure.credential.TokenScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

/**
 * 只读 token 签发窄接口（评审修正 #6：publisher interfaces 层唯一 HTTP 入站，T14）。
 * {@code GET /internal/tokens/readonly?installation_id=} → 短期只读（contents:read）token。
 *
 * <p>安全语义（MVP 形态 P8，诚实边界）：
 * <ul>
 *   <li>仅 compose 内网可达（端口不对外发布，T18 compose 落实；等效 loopback）；</li>
 *   <li>共享密钥头 {@code X-Internal-Token} 校验，常数时间比较；密钥未配置时
 *       fail-closed 一律 401；</li>
 *   <li>除此之外 publisher 无其他 HTTP 端点。</li>
 * </ul>
 * token 不进日志：拒绝/成功路径都不打印 token 与密钥。
 */
@RestController
@Profile("docker")
public class ReadOnlyTokenController {

    public static final String SECRET_HEADER = "X-Internal-Token";

    private final CredentialBroker credentialBroker;
    private final String sharedSecret;

    public ReadOnlyTokenController(CredentialBroker credentialBroker,
                                   @Value("${publisher.internal-token-secret:}") String sharedSecret) {
        this.credentialBroker = Objects.requireNonNull(credentialBroker);
        this.sharedSecret = Objects.requireNonNull(sharedSecret);
    }

    @GetMapping("/internal/tokens/readonly")
    public ResponseEntity<Map<String, String>> readonlyToken(
            @RequestParam("installation_id") long installationId,
            @RequestHeader(value = SECRET_HEADER, required = false) String presentedSecret) {
        if (!secretMatches(presentedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        String token = credentialBroker.token(installationId, TokenScope.READ);
        return ResponseEntity.ok(Map.of("token", token));
    }

    /** 常数时间比较；密钥未配置 = fail-closed */
    private boolean secretMatches(String presented) {
        if (sharedSecret.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sharedSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
