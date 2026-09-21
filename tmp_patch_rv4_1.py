# -*- coding: utf-8 -*-
import io

p = r'control-app/src/main/java/com/objwww/pr/control/alert/domain/repository/DelegationReceiptRepository.java'
t = io.open(p, encoding='utf-8').read()
old = """    void insert(DelegationReceipt receipt);"""
new = """    void insert(DelegationReceipt receipt);

    /**
     * RV04/T20：原子幂等插入（ON CONFLICT (message_id) DO NOTHING）——返回是否
     * 本副本插入成功（0=同键已存在）。PG 事务内唯一冲突会置 aborted、后续语句
     * 25P02，不能异常后同事务续操作；并发分支必须走本方法，冲突后另条查询读胜者。
     */
    int insertIfAbsent(DelegationReceipt receipt);"""
assert old in t
io.open(p, 'w', encoding='utf-8').write(t.replace(old, new))
print('1 ok')

p = r'control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresDelegationReceiptRepository.java'
t = io.open(p, encoding='utf-8').read()
anchor = '    @Override\n    public Opti'
assert anchor in t
method = """    /** RV04/T20：并发同 messageId 原子幂等——ON CONFLICT 下 PG 事务不置 aborted，
     * 冲突后可同事务另条查询读实际胜者 */
    @Override
    public int insertIfAbsent(DelegationReceipt r) {
        return jdbc.sql(\"\"\"
                INSERT INTO rca_delegation_receipt (
                    id, message_id, run_id, primary_task_id, child_task_id,
                    round_id, gap_id, role_id, child_status, admission,
                    findings, support_refs, counter_refs, missing_information,
                    payload_digest, payload_bytes, received_at
                ) VALUES (
                    :id, :messageId, :runId, :primaryTaskId, :childTaskId,
                    :roundId, :gapId, :roleId, :childStatus, :admission,
                    CAST(:findings AS jsonb), CAST(:supportRefs AS jsonb),
                    CAST(:counterRefs AS jsonb), CAST(:missingInformation AS jsonb),
                    :payloadDigest, :payloadBytes, :receivedAt
                )
                ON CONFLICT (message_id) DO NOTHING
                \"\"\")
                .param("id", r.id())
                .param("messageId", r.messageId())
                .param("runId", r.runId())
                .param("primaryTaskId", r.primaryTaskId())
                .param("childTaskId", r.childTaskId())
                .param("roundId", r.roundId())
                .param("gapId", r.gapId())
                .param("roleId", r.roleId())
                .param("childStatus", r.childStatus().name())
                .param("admission", r.admission().name())
                .param("findings", toJson(r.findings()))
                .param("supportRefs", toJson(r.supportRefs()))
                .param("counterRefs", toJson(r.counterRefs()))
                .param("missingInformation", toJson(r.missingInformation()))
                .param("payloadDigest", r.payloadDigest())
                .param("payloadBytes", r.payloadBytes())
                .param("receivedAt", Timestamp.from(r.receivedAt()))
                .update() > 0 ? 1 : 0;
    }

"""
t = t.replace(anchor, method + anchor, 1)
io.open(p, 'w', encoding='utf-8').write(t)
print('2 ok')
