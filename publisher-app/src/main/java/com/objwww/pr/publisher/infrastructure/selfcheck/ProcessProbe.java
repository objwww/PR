package com.objwww.pr.publisher.infrastructure.selfcheck;

/**
 * 进程身份探针（T15 可测试抽象）：生产实现读 {@code user.name} 系统属性。
 * root 判定在纯逻辑里（PublisherSelfCheck）：userName=root 或 env UID=0。
 */
@FunctionalInterface
public interface ProcessProbe {

    String userName();

    static ProcessProbe current() {
        return () -> System.getProperty("user.name");
    }
}
