package com.objwww.pr.control.alert.application;

import com.objwww.pr.control.alert.domain.repository.RuntimeFlagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时 Jev 开关解析（JE-01）：页面旗标（alert_runtime_flag 行）优先，无行回退
 * 配置默认 {@code app.alert.r7.jev.enabled}；TTL 缓存吸收铸造点读放大（三处铸造点
 * 共用本面）。旗标存储不可读 = fail-open 到配置默认并 WARN（不阻断告警铸造主链——
 * 开关是增强意愿，不是可用性依赖）。
 */
public class RuntimeJevRunFlag implements JevRunFlag {

    private static final Logger log = LoggerFactory.getLogger(RuntimeJevRunFlag.class);

    /** 旗标行名（JevFlagController 与本常量同源） */
    public static final String FLAG_NAME = "jev_enhanced";
    static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final RuntimeFlagRepository flags;
    private final boolean configDefault;
    private final Clock clock;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    private record Cached(boolean value, Instant at) {
    }

    public RuntimeJevRunFlag(RuntimeFlagRepository flags, boolean configDefault,
            Clock clock) {
        this.flags = Objects.requireNonNull(flags, "flags");
        this.configDefault = configDefault;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean enabledForNewRuns() {
        Instant now = clock.instant();
        Cached cached = cache.get();
        if (cached != null && cached.at().plus(CACHE_TTL).isAfter(now)) {
            return cached.value();
        }
        boolean value;
        try {
            value = flags.findEnabled(FLAG_NAME).orElse(configDefault);
        } catch (RuntimeException e) {
            log.warn("Jev 旗标存储不可读，回退配置默认（{}）: {}",
                    configDefault, e.getClass().getSimpleName());
            value = configDefault;
        }
        cache.set(new Cached(value, now));
        return value;
    }
}
