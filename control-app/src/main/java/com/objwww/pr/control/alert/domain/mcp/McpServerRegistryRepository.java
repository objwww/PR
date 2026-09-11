package com.objwww.pr.control.alert.domain.mcp;

import java.time.Instant;
import java.util.List;

/**
 * MCP server 注册表端口（EN-06，§2.3；V64 mcp_server_registry，与调用账本同库）。
 * name 为幂等键（M12 重复注册幂等顶替不新增行）；generation 全表单调递增，
 * 读路径无锁快照的 revision 基准 = max(generation)。实现方每方法自含短事务。
 * 首期 server 数量少：读全量配置 + 单调代际，避免 updated_at 同值与删除漏读。
 */
public interface McpServerRegistryRepository {

    /** 管理面提交形态（generation/updatedAt 由实现方在 upsert 时指派） */
    record ServerSpec(String name, String transport, String endpoint,
            List<String> args, String headersRef, boolean enabled) {
    }

    /** 持久形态：spec + 单调代际 + 更新时刻 */
    record ServerRecord(ServerSpec spec, long generation, Instant updatedAt) {
    }

    /** 全量按 generation 升序（恢复面的一致 revision 前提） */
    List<ServerRecord> loadAll();

    /** 同名幂等顶替（M12）：不新增行，返回携新 generation 的持久行 */
    ServerRecord upsert(ServerSpec spec);

    /** 删除面（deregister）；重复删除幂等返回 false */
    boolean delete(String name);
}
