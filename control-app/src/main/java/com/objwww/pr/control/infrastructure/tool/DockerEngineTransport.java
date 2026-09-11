package com.objwww.pr.control.infrastructure.tool;

/**
 * Docker Engine 受限传输面（EN-05，§一只读边界"不得挂原始 socket/通用 shell——使用
 * 限制 API 的采集适配器"）：只暴露 GET 白名单路径的只读读取；实现方负责引擎寻址
 * （生产 = TCP 端点 {@link TcpDockerEngineTransport}；测试 = 内存假件）。
 */
public interface DockerEngineTransport {

    /** GET 一条引擎 API 路径；实现方限制在只读容器查询面（/containers/json、/containers/{name}/json） */
    byte[] get(String path);
}
