package com.objwww.pr.control.eval.application;

/**
 * 陈旧 worker 自拒护栏（2026-09-16 实证：47h 旧镜像 eval worker 抢跑新 LAUNCH
 * 命令，其 INSERT 与新 schema 不兼容半途失败，批件被污染）。镜像内迁移面落后于
 * DB flyway 最大版本即 stale——worker 不得领取新命令。
 */
@FunctionalInterface
public interface WorkerSchemaFreshnessGuard {

    /**
     * true = 本进程 schema 落后于 DB（禁止领取，命令留 PENDING 等新镜像 worker
     * 接管——不可判 REJECTED，否则陈旧 worker 会永久杀死本属于新 worker 的命令）。
     * 检查自身失败（DB 不可达 / 迁移面扫描失败）同样按 stale 论（fail-closed）。
     */
    boolean stale();
}
