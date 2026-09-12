package com.objwww.pr.control.ops.domain.repository;

/**
 * 指标区间查询只读端口（监控页主机/执行器两区，方案 §三.12 + 全量联通方案 §5.11/§6 B6）：
 * 白名单代理的远端咽喉——实现只接受「已拼好的白名单 PromQL + 整数 epoch 秒窗 + step 秒」，
 * 不暴露任意查询面给调用方以外的任何人（白名单枚举归
 * {@code ops/application/MetricsWhitelistService}，本端口不复用 Agent 工具面
 * PrometheusQueryExecutor——那是 R0 模型工具身份，监控页不借）。
 *
 * <p>实现语义：Prometheus 不可达 / 非 200 / 响应非法 →
 * {@code ops/application/MetricsSourceUnavailableException}（HTTP 面映射 503）；
 * 返回体为 /api/v1/query_range 的原始 JSON 文本，解析归服务层。
 */
public interface MetricsRangeGateway {

    /**
     * 执行 query_range。
     *
     * @param promql        白名单模板拼出的完整 PromQL（调用方保证非自由输入）
     * @param startEpochSec 窗起点（含，整数 epoch 秒）
     * @param endEpochSec   窗终点（含，整数 epoch 秒）
     * @param stepSec       步长（秒）
     * @return Prometheus 响应原始 JSON（status=data 全量，解析归调用方）
     */
    String queryRange(String promql, long startEpochSec, long endEpochSec, long stepSec);
}
