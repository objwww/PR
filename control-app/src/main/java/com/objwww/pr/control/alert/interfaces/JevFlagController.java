package com.objwww.pr.control.alert.interfaces;

import com.objwww.pr.control.alert.application.RuntimeJevRunFlag;
import com.objwww.pr.control.alert.domain.repository.RuntimeFlagRepository;
import com.objwww.pr.control.infrastructure.auth.AuthenticatedActor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Jev 增强开关端点（JE-01，/api/v1/** 已由 SecurityConfig 归 OPERATOR）。读面 =
 * 旗标行 + 生效来源；写面 = 幂等 upsert（actor/reason 落审计列）。这里只写"当前
 * 意愿"——各 run 是否增强由铸造时点冻结（rca_run.jev_enabled），切换不影响在跑
 * 调查。docker profile（PersistenceConfig 同域——默认空 profile 无 PG 仓储不装配，
 * 与 EventQueryController 同律）。
 */
@RestController
@org.springframework.context.annotation.Profile("docker")
@RequestMapping("/api/v1/jev-enhanced")
public class JevFlagController {

    private final RuntimeFlagRepository flags;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.objwww.pr.control.alert.application.agent.JevEnhancementPort> enhancement;
    private final org.springframework.core.env.Environment env;

    public JevFlagController(RuntimeFlagRepository flags) {
        this(flags,null,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JevFlagController(RuntimeFlagRepository flags,
            org.springframework.beans.factory.ObjectProvider<com.objwww.pr.control.alert.application.agent.JevEnhancementPort> enhancement,
            org.springframework.core.env.Environment env) {
        this.flags = Objects.requireNonNull(flags, "flags");
        this.clock = Clock.systemUTC();
        this.enhancement=enhancement;
        this.env=env;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", RuntimeJevRunFlag.FLAG_NAME);
        out.put("ready", ready());
        out.put("unavailableReason", ready() ? "" : "需部署主 Agent、配置 Jev API，并设 jev.mode=SELECT、review-enabled=true");
        out.put("configDefault",env!=null && env.getProperty("app.alert.r7.jev.enabled",Boolean.class,false));
        out.put("activationDelaySeconds",10);
        out.put("features",Map.of("selection",true,"summary",true,"mainModelRetryLimit",1,"reviewLimit",3,"freshReadOnlyEvidence",true));
        out.put("row", flags.findByName(RuntimeJevRunFlag.FLAG_NAME)
                .map(row -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("enabled", row.enabled());
                    r.put("updatedBy", row.updatedBy());
                    r.put("reason", row.reason());
                    r.put("updatedAt", row.updatedAt() == null
                            ? null : row.updatedAt().toString());
                    return r;
                }).orElse(null));
        return out;
    }

    /** body: {"enabled": true/false, "reason": "..."}；enabled 缺失 = 400 语义拒绝 */
    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body) {
        Object enabledRaw = body == null ? null : body.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            throw new IllegalArgumentException("enabled 必须为布尔");
        }
        if(enabled && !ready()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"Jev 增强执行面未就绪，开关未保存");
        String reason = body.get("reason") == null ? null
                : String.valueOf(body.get("reason"));
        String actor = AuthenticatedActor.name();
        if(reason!=null && reason.length()>500) throw new IllegalArgumentException("reason 最多 500 字符");
        flags.upsert(RuntimeJevRunFlag.FLAG_NAME, enabled, actor, reason,
                clock.instant());
        return get();
    }

    private boolean ready() {
        var service=enhancement==null?null:enhancement.getIfAvailable();
        return service!=null && service.available() && env!=null
                && env.getProperty("app.alert.r7.primary.enabled",Boolean.class,false);
    }
}
