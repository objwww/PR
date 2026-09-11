-- EN-06（MCP 单点）：mcp_server_registry——动态挂载注册表（§2.3 注册快照的持久面）
-- name 为幂等键（M12 重复注册幂等顶替不新增行）；generation 全表单调（uq 兜底并发
-- 窗口，单实例控制面经发布锁串行化），快照 revision = max(generation)。
-- stdio 仅运维预装固定命令（管理 API 不接受任意可执行文件，M11）；
-- headers_ref 存凭证引用名（env 变量名）而非凭证值（M10 无泄密）。
CREATE TABLE mcp_server_registry (
    name        text        NOT NULL,
    transport   text        NOT NULL,
    endpoint    text        NOT NULL,
    args        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    headers_ref text,
    enabled     boolean     NOT NULL DEFAULT TRUE,
    generation  bigint      NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_mcp_server_registry PRIMARY KEY (name),
    CONSTRAINT ck_mcp_server_registry_transport CHECK (transport IN ('streamable_http', 'stdio')),
    CONSTRAINT uq_mcp_server_registry_generation UNIQUE (generation)
);

COMMENT ON TABLE mcp_server_registry IS
    'EN-06 MCP server 动态挂载注册表（name 幂等键；generation 全表单调 = 快照 revision 基准）';
COMMENT ON COLUMN mcp_server_registry.headers_ref IS
    '凭证引用名（env 变量名），不存凭证值';

-- 管理面 = 控制面 REST（register/disable/enable/deregister/status）：control_app 全权；
-- eval_app/notify_app 全零（MCP 注册表不进评估/通知面）
GRANT SELECT, INSERT, UPDATE, DELETE ON mcp_server_registry TO control_app;
REVOKE ALL ON mcp_server_registry FROM eval_app;
REVOKE ALL ON mcp_server_registry FROM notify_app;
