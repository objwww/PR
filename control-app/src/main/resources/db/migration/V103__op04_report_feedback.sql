-- OP-04 终态报告反馈（后续优化技术方案 §5.1）：append-only 反馈台账。
-- 评价对象是"已发布报告"（非活跃 Run 命令面），身份取认证主体（author 不可自报）；
-- verdict 四值封闭；更正=新行 supersedes_id 指向前序（原意见永不覆盖）；同一前序
-- 至多一条更正（部分唯一索引）；(author, idempotency_key) 幂等，同键异载荷在
-- 应用层显式冲突。report_digest 服务端按 packageJson 计算——请求可携带期望值对账。
create table report_feedback (
    id uuid primary key,
    report_id uuid not null,
    run_id uuid not null,
    report_digest char(64) not null,
    author text not null,
    verdict text not null,
    reason text not null,
    evidence_refs jsonb not null default '[]',
    supersedes_id uuid,
    idempotency_key text not null,
    created_at timestamptz not null,
    constraint ck_report_feedback_verdict check (verdict in
        ('ACCEPTED', 'PARTIAL', 'INCORRECT', 'INSUFFICIENT')),
    constraint ck_report_feedback_reason check (length(trim(reason)) > 0)
);

create unique index uq_report_feedback_idem on report_feedback (author, idempotency_key);

-- 同一前序版本至多一条更正：并发冲突更正一胜一拒（FO25 不覆盖原意见）
create unique index uq_report_feedback_supersedes on report_feedback (supersedes_id)
    where supersedes_id is not null;

create index ix_report_feedback_report on report_feedback (report_id, created_at);

grant select, insert on report_feedback to control_app;
