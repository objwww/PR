#!/bin/sh
# b2-cl06-v2-probe5.sh —— 专家回执空根因：委派 prompt 构造面 + 记忆行触发面
. /opt/build/r7-operator-env.sh
. /opt/build/b2tree/e2e-r7-common.sh
G() { r7_psql_ro R7_PG_URL "$1" '-At'; }
echo "== invocations (call_seq) run1:"
G "select call_seq||' '||tool_name from rca_tool_invocation where run_id='bbf9af6a-8b1e-4fa3-80bc-d8672722c003' order by call_seq"
echo "== delegate prompt 构造（BoundedLlmRoleRunner / driveDelegate）:"
grep -rn 'driveDelegate' /opt/build/pr/control-app/src/main/java --include=*.java -l | head -3
grep -rn 'counter_refs\|counterRefs' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/alert/application/agent/BoundedLlmRoleRunner.java 2>/dev/null | head -10
echo "== 专家 prompt 里有没有主 Agent 的任务描述: "
grep -rn 'taskDescription\|task_description\|delegationInstruction\|instruction' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/alert/application/agent/BoundedLlmRoleRunner.java 2>/dev/null | head -10
echo "== working memory append 调用点:"
grep -rn 'append\|commit' /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresWorkingMemory.java | head -10
grep -rn 'workingMemory\.\|WorkingMemoryPort' /opt/build/pr/control-app/src/main/java --include=*.java -l | head -6
