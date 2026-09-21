# -*- coding: utf-8 -*-
import io, re

p = r'control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java'
t = io.open(p, encoding='utf-8').read()

# --- 1) 全参构造 +23 参数 receiptTx
old = """            AgentProfile primaryProfile,
            com.objwww.pr.control.alert.application.agent.DelegationReceiptService
                    delegationReceipts) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");"""
new = """            AgentProfile primaryProfile,
            com.objwww.pr.control.alert.application.agent.DelegationReceiptService
                    delegationReceipts,
            org.springframework.transaction.support.TransactionOperations receiptTx) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");"""
assert old in t
t = t.replace(old, new)

# --- 2) 字段
m = re.search(r"private final com\.objwww\.pr\.control\.alert\.application\.agent\.DelegationReceiptService\n\s+delegationReceipts;", t)
assert m, 'field decl not found'
t = t[:m.end()] + ("\n    /** RV04：终态与回执同短事务（null=legacy 顺序面，回执失败仅留痕） */\n"
                   "    private final org.springframework.transaction.support.TransactionOperations receiptTx;") + t[m.end():]

# --- 3) 赋值
old = """        this.delegationReceipts = delegationReceipts;"""
new = """        this.delegationReceipts = delegationReceipts;
        this.receiptTx = receiptTx;"""
assert old in t
t = t.replace(old, new)

io.open(p, 'w', encoding='utf-8').write(t)
print('executor ctor/field ok')
