-- V6__m4_sandbox.sql
-- M4 沙箱执行面：sandbox_job（作业生命周期）+ tool_call（工具调用账本）
-- + artifact_grant（Capability）+ artifact.ck_artifact_type 枚举扩展
-- v1.1 修订要点：单向 FK、attempt_id、部分唯一索引并发闸、REJECTED 终态约束、
-- lineage/不可变触发器、列级授权、failure_class

-- 建表顺序：tool_call 先（不含 sandbox_job_id 反向列），sandbox_job 后（单向引用 tool_call）
CREATE TABLE tool_call (
    id                     uuid PRIMARY KEY,
    review_run_id          uuid not null REFERENCES review_run(id),
    run_step_id            uuid not null REFERENCES run_step(id),
    attempt_id             uuid not null REFERENCES step_attempt(id),   -- 观测链 run→attempt→step→tool_call
    call_seq               int  not null CHECK (call_seq >= 1),     -- attempt 内单调序号，新 attempt 从 1 重计
    tool_name              text not null,
    tool_args              jsonb not null,                          -- 工具入参（snapshot digest、diff digest、policy tier 等）
    state                  text not null CHECK (state IN ('RUNNING','SUCCEEDED','FAILED','REJECTED')),
    exit_code              int  CHECK (exit_code >= 0),             -- 容器退出码（0=成功，非 0=失败）；REJECTED 无退出码
    observation_digest     char(64),                                -- 工具返回的观测（findings JSON、日志、二进制输出等）内容寻址（→artifact）
    observation_summary    text,                                    -- 前 200 字简化表示
    observation_bytes      bigint CHECK (observation_bytes >= 0),
    truncated              boolean not null default false,          -- 输出超限截断标记
    lease_epoch            bigint not null,                         -- 绑定租约世代（心跳带回，终态写入时强制匹配）
    started_at             timestamptz not null default now(),
    finished_at            timestamptz,
    UNIQUE (attempt_id, call_seq)                                   -- 同一 attempt 内 call_seq 唯一
);
CREATE INDEX idx_tool_call_run ON tool_call (review_run_id, started_at);

-- sandbox_job（单向引用 tool_call，一对一）
CREATE TABLE sandbox_job (
    id                     uuid PRIMARY KEY,
    tool_call_id           uuid not null UNIQUE REFERENCES tool_call(id),  -- 单向 FK，一对一
    review_run_id          uuid not null REFERENCES review_run(id),
    run_step_id            uuid not null REFERENCES run_step(id),
    attempt_id             uuid not null REFERENCES step_attempt(id),
    worker_id              text,                                    -- Broker 实例标识
    state                  text not null CHECK (state IN ('PENDING','LEASED','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED')),
    lease_owner            text,                                    -- 持有者标识（Broker instance + claim UUID）
    lease_until            timestamptz,
    lease_epoch            bigint not null default 0,               -- 租约世代（每次 claim/renew +1，终态写入必须匹配当前 epoch）
    heartbeat_at           timestamptz,
    attempt_count          int  not null default 0 CHECK (attempt_count >= 0),
    max_attempts           int  not null default 3 CHECK (max_attempts >= 1),
    container_id           text,                                    -- Docker 容器 ID（12 字符短 ID 或 64 字符全 ID）
    exit_code              int  CHECK (exit_code >= 0),
    result_digest          char(64),                                -- 工具结果（→artifact JOB_RESULT）
    log_digest             char(64),                                -- 容器日志（→artifact JOB_LOG）
    error_code             text,                                    -- 结构化错误码（TIMEOUT/LEASE_LOST/CONTAINER_START_FAILED 等）
    sanitized_message      text,                                    -- 安全脱敏后错误消息（不含敏感路径）
    failure_class          text CHECK (failure_class IN ('INFRASTRUCTURE','USER_CODE','POLICY_REJECTION')),
    retryable              boolean,                                 -- 是否可重试
    created_at             timestamptz not null default now(),
    started_at             timestamptz,
    finished_at            timestamptz,
    -- JobSpec 不可变列（身份列，触发器 + 列级授权双保险）
    job_spec_immutable     jsonb not null,                          -- {image,entrypoint,cmd,env,security_profile,workspace_digests,timeouts}
    CHECK ((state = 'PENDING' AND lease_owner IS NULL AND lease_until IS NULL)
        OR (state = 'LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (state IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED'))),
    CHECK ((state IN ('SUCCEEDED','FAILED','TIMED_OUT') AND finished_at IS NOT NULL)
        OR (state IN ('PENDING','LEASED','CANCELLED')))
);
CREATE INDEX idx_sandbox_job_claim  ON sandbox_job (created_at) WHERE state = 'PENDING';
CREATE INDEX idx_sandbox_job_lease  ON sandbox_job (lease_until) WHERE state = 'LEASED';
CREATE INDEX idx_sandbox_job_run    ON sandbox_job (review_run_id, created_at);

-- 全局并发闸（F-38 方案 B：部分唯一索引，声明式，任何写入路径绕不过）
-- 保证同一时刻 state='LEASED' 的行 <= 1
CREATE UNIQUE INDEX uq_sandbox_job_inflight ON sandbox_job ((1)) WHERE state = 'LEASED';

-- sandbox_job lineage 触发器（血缘一致性：job.{run,step,attempt}_id 必须与 tool_call 匹配）
CREATE FUNCTION fn_sandbox_job_lineage() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM tool_call
        WHERE id = NEW.tool_call_id
          AND review_run_id = NEW.review_run_id
          AND run_step_id = NEW.run_step_id
          AND attempt_id = NEW.attempt_id
    ) THEN
        RAISE EXCEPTION 'sandbox_job lineage mismatch with tool_call %', NEW.tool_call_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_sandbox_job_lineage
    BEFORE INSERT ON sandbox_job
    FOR EACH ROW EXECUTE FUNCTION fn_sandbox_job_lineage();

-- JobSpec 不可变触发器（身份列：job_spec_immutable、tool_call_id、review_run_id、run_step_id、attempt_id）
CREATE FUNCTION fn_sandbox_job_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.job_spec_immutable IS DISTINCT FROM NEW.job_spec_immutable
        OR OLD.tool_call_id IS DISTINCT FROM NEW.tool_call_id
        OR OLD.review_run_id IS DISTINCT FROM NEW.review_run_id
        OR OLD.run_step_id IS DISTINCT FROM NEW.run_step_id
        OR OLD.attempt_id IS DISTINCT FROM NEW.attempt_id
    THEN
        RAISE EXCEPTION 'sandbox_job immutable columns cannot be modified';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_sandbox_job_immutable
    BEFORE UPDATE ON sandbox_job
    FOR EACH ROW EXECUTE FUNCTION fn_sandbox_job_immutable();

-- tool_call 身份列不可变触发器（tool_call_id、review_run_id、run_step_id、attempt_id、call_seq、tool_name、tool_args、started_at 不可改）
CREATE FUNCTION fn_tool_call_immutable() RETURNS trigger AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.review_run_id IS DISTINCT FROM NEW.review_run_id
        OR OLD.run_step_id IS DISTINCT FROM NEW.run_step_id
        OR OLD.attempt_id IS DISTINCT FROM NEW.attempt_id
        OR OLD.call_seq IS DISTINCT FROM NEW.call_seq
        OR OLD.tool_name IS DISTINCT FROM NEW.tool_name
        OR OLD.tool_args IS DISTINCT FROM NEW.tool_args
        OR OLD.started_at IS DISTINCT FROM NEW.started_at
    THEN
        RAISE EXCEPTION 'tool_call immutable columns cannot be modified';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_tool_call_immutable
    BEFORE UPDATE ON tool_call
    FOR EACH ROW EXECUTE FUNCTION fn_tool_call_immutable();

-- artifact_grant（Capability：短期读/写令牌，Job-scoped）
CREATE TABLE artifact_grant (
    id                     uuid PRIMARY KEY,
    token_hash             char(64) not null UNIQUE,   -- DB 只存 token 的 SHA-256（明文只在签发/补领响应出现一次）
    job_id                 uuid not null REFERENCES sandbox_job(id),
    worker_id              text not null,
    lease_epoch            bigint not null,            -- B19：绑定租约世代；epoch 变化即失效
    kind                   text not null CHECK (kind IN ('ARTIFACT_READ','RESULT_UPLOAD')),
    artifact_type          text,                       -- RESULT_UPLOAD scope：限定 TOOL_OBSERVATION/JOB_LOG/JOB_RESULT
    allowed_digests        jsonb not null default '[]'::jsonb
        CHECK (jsonb_typeof(allowed_digests) = 'array'),  -- 仅 ARTIFACT_READ 使用；RESULT_UPLOAD 恒为 []
    max_object_bytes       bigint CHECK (max_object_bytes > 0),   -- RESULT_UPLOAD scope：单对象大小上限
    max_total_bytes        bigint not null CHECK (max_total_bytes > 0),
    used_bytes             bigint not null default 0 CHECK (used_bytes >= 0),
    use_count              int  not null default 0 CHECK (use_count >= 0),
    max_uses               int  not null CHECK (max_uses >= 1),  -- 有限次数：断点续传重试（§17.2 否决纯一次性）
    expires_at             timestamptz not null,       -- 短 TTL
    status                 text not null CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    created_at             timestamptz not null default now(),
    revoked_at             timestamptz,
    CHECK (used_bytes <= max_total_bytes),
    CHECK (use_count <= max_uses),
    -- 两类 grant 的 scope 形态互斥（G1 乙 P0-11：废除 UploadGrant digest 伪白名单）
    CHECK ((kind = 'ARTIFACT_READ'  AND jsonb_array_length(allowed_digests) > 0)
        OR (kind = 'RESULT_UPLOAD' AND jsonb_array_length(allowed_digests) = 0
            AND artifact_type IS NOT NULL AND max_object_bytes IS NOT NULL))
);
CREATE INDEX idx_artifact_grant_job ON artifact_grant (job_id);

-- artifact 类型枚举扩展（观测/日志/结果三个新类型）
ALTER TABLE artifact DROP CONSTRAINT ck_artifact_type;
ALTER TABLE artifact ADD CONSTRAINT ck_artifact_type
    CHECK (artifact_type IN (
        'SOURCE_SNAPSHOT','DIFF_BUNDLE','FINDING_BODY','REVIEW_PAYLOAD','WEBHOOK_PAYLOAD',
        'MODEL_RESPONSE','TOOL_OBSERVATION','JOB_LOG','JOB_RESULT'));

-- 授权矩阵（列级 UPDATE，沿用 V2~V5 最小权限模式）

-- tool_call 权限
GRANT INSERT, SELECT ON tool_call TO control_app;
GRANT UPDATE (
    state, exit_code, observation_digest, observation_summary,
    observation_bytes, truncated, lease_epoch, finished_at
) ON tool_call TO control_app;

-- sandbox_job 权限
GRANT INSERT, SELECT ON sandbox_job TO control_app;
GRANT UPDATE (
    state, lease_owner, lease_until, lease_epoch, heartbeat_at,
    attempt_count, container_id, exit_code, result_digest, log_digest,
    error_code, sanitized_message, failure_class, retryable,
    started_at, finished_at, worker_id
) ON sandbox_job TO control_app;

-- artifact_grant 权限
GRANT INSERT, SELECT ON artifact_grant TO control_app;
GRANT UPDATE (
    used_bytes, use_count, status, revoked_at, expires_at
) ON artifact_grant TO control_app;

-- publisher_app 零权限（显式 REVOKE）
REVOKE ALL ON tool_call FROM publisher_app;
REVOKE ALL ON sandbox_job FROM publisher_app;
REVOKE ALL ON artifact_grant FROM publisher_app;
