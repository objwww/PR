package com.objwww.pr.control.infrastructure.selfcheck;

import java.util.Map;

/**
 * 环境变量探针（T15 启动自检的可测试抽象）：生产实现读 {@link System#getenv()}，
 * 单测用假实现注入。自检只读变量名与存在性，值永不进日志/异常消息。
 */
@FunctionalInterface
public interface EnvironmentProbe {

    Map<String, String> all();

    static EnvironmentProbe system() {
        return System::getenv;
    }
}
