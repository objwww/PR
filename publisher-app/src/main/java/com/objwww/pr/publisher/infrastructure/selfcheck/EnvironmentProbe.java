package com.objwww.pr.publisher.infrastructure.selfcheck;

import java.util.Map;

/**
 * 环境变量探针（T15 可测试抽象）：生产读 {@link System#getenv()}，单测用假实现。
 * 自检只读变量名与存在性，值永不进日志/异常消息。
 */
@FunctionalInterface
public interface EnvironmentProbe {

    Map<String, String> all();

    static EnvironmentProbe system() {
        return System::getenv;
    }
}
