# -*- coding: utf-8 -*-
"""WC-5 落地状态扫描：每个文件检查若干标记串是否存在，输出 yes/no 一览。"""
import io, os

BASE = r"E:\kimiCode\control-app\src\main\java\com\objwww\pr\control"
CHECKS = [
    ("alert\\application\\AlertMetrics.java",
     ["reconcileScanSucceeded", "cancelToQuiesce", "lateCommitRejected", "unknownAction",
      "rca_reconcile_last_success_epoch_ms", "CHANNEL_ACTIVE"]),
    ("alert\\domain\\repository\\RcaRunRepository.java", ["oldestActiveCreatedAt"]),
    ("infrastructure\\persistence\\PostgresRcaRunRepository.java", ["oldestActiveCreatedAt"]),
    ("alert\\domain\\repository\\RcaTaskRepository.java", ["countOpenTasksUnderTerminalRuns"]),
    ("infrastructure\\persistence\\PostgresRcaTaskRepository.java", ["countOpenTasksUnderTerminalRuns"]),
    ("alert\\domain\\tool\\RcaToolInvocationLedger.java", ["countByRunAndState"]),
    ("infrastructure\\persistence\\PostgresRcaToolInvocationLedger.java", ["countByRunAndState"]),
    ("alert\\application\\RunReconciler.java",
     ["AlertMetrics", "reconcileScanSucceeded", "reconcileDecision", "cancelToQuiesce"]),
    ("alert\\application\\agent\\PrimaryCheckpointCommitService.java",
     ["lateCommitRejected", "AlertMetrics"]),
    ("alert\\application\\RcaRunOrchestrator.java", ["lateCommitRejected"]),
    ("alert\\application\\RunQueryService.java",
     ["terminationRequestedAt", "localExecutionState", "inflightCount", "unknownActionCount",
      "InFlightToolCancels"]),
    ("alert\\application\\RcaWorker.java", ["AlertMetrics", "unknownAction"]),
    ("infrastructure\\config\\PersistenceConfig.java",
     ["am4InFlightToolCancels", "rcaToolInvocationLedger"]),
    ("infrastructure\\config\\AlertFlowConfig.java",
     ["alertMetrics", "AlertMetrics"]),
    ("infrastructure\\config\\AlertAm4Config.java",
     ["am4InFlightToolCancels", "alertMetrics", "AlertMetrics"]),
    ("alert\\application\\tool\\InFlightToolCancels.java", ["cancelRun", "inflightCount"]),
    ("alert\\application\\tool\\ReadOnlyToolFace.java", ["InFlightToolCancels"]),
    ("alert\\application\\tool\\ToolGateway.java", ["externalDeadline"]),
    ("alert\\interfaces\\RunCommandController.java", ["InFlightToolCancels", "cancelRun"]),
]

for rel, markers in CHECKS:
    path = os.path.join(BASE, rel)
    if not os.path.exists(path):
        print("MISSING-FILE", rel)
        continue
    with io.open(path, "r", encoding="utf-8") as f:
        text = f.read()
    flags = ["%s=%s" % (m, "Y" if m in text else "-") for m in markers]
    print(os.path.basename(rel).ljust(44), " ".join(flags))
