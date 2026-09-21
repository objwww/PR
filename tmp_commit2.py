# -*- coding: utf-8 -*-
import subprocess

files = [
    r'control-app/src/main/java/com/objwww/pr/control/alert/domain/repository/DelegationReceiptRepository.java',
    r'control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresDelegationReceiptRepository.java',
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/DelegationReceiptService.java',
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/RoleRunner.java',
    r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/SingleToolRoleRunner.java',
    r'control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java',
    r'control-app/src/main/java/com/objwww/pr/control/infrastructure/config/AlertFlowConfig.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/DelegationReceiptServiceTest.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/ContextAssemblerTest.java',
    r'control-app/src/test/java/com/objwww/pr/control/alert/application/agent/SingleToolRoleRunnerChildResultTest.java',
    r'alert-web/src/main.js',
    r'alert-web/src/router/index.js',
    r'alert-web/src/composables/echarts.js',
    r'alert-web/src/views/MonitorView.vue',
    r'alert-web/src/views/OverviewView.vue',
]
r = subprocess.run(['git', 'add', '--'] + files, capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
print('add rc', r.returncode, (r.stderr or '')[-200:])

msg = (
"fix(rv-c04+e11): 审查方案 C 批 RV04 回执生产能力/事务收尾 + E 批 RV11 前端拆包"
"（docs/告警-优化后详细审查与修改测试方案-20260914.md）——"
"RV04/BA-142 回执生产缺口：RoleDriveResult 增结构化 ChildResult（findings/support/counter/missing 四清单，协议既有形状不另造表），"
"SingleToolRoleRunner 诚实面（findings 只述查询、反证恒空不造假 witness、能力缺口如实入 missing_information，T17/T18）；"
"executor 统一收尾 finalizeWithReceipt：任务终态 CAS 与回执持久化同一短事务（receiptTx 可空=legacy 顺序面），"
"回执失败随事务回滚任务保持 RUNNING、恢复重驱同 messageId 幂等重投，不再产生 DONE 无回执永久缺口；"
"结构化结果优先消费、legacy 结果回退机械映射（兼容零漂移）。"
"RV04/T20 并发幂等：DelegationReceiptRepository.insertIfAbsent（ON CONFLICT (message_id) DO NOTHING）——"
"PG 事务内唯一冲突置 aborted 不能异常后续操作，冲突面改原子插入+健康事务内另条查询读实际胜者（废弃 catch 后 orElse 假回执）；"
"审计行同走 ON CONFLICT 面。RV04/T21 线性化：admitOnce 的 run 读改 findByIdForUpdate（取消与回执同一把 run 行锁，单行锁无环）。"
"RV04/T22 引用 Host 校验：EvidenceRepository 入参（可空=假件面跳过），support/counter 引用必须解析到本 run 证据行，"
"越界引用 REJECTED_SHAPE 审计留痕——模型自报反证不直接流入记忆。"
"RV11 拆包：路由全量懒加载（Login 静态首屏）+ echarts 按需注册随图表页（Monitor/Overview）局部 composable，"
"废弃 main.js 全局注册；实测首包 1850kB(gzip 618)→387kB(gzip 141)=-77%（目标 30%），echarts 独立 chunk 648kB(gzip 222) 仅图表页加载。"
"验证：定向 DelegationReceiptServiceTest 9/SingleToolRoleRunnerChildResultTest 1/R7PrimaryModeExecutorTest 8/"
"NativeInvestigationExecutorTest 9 全绿；全量回归 3990 例 0 失败 0 错误 BUILD SUCCESS；npm build 绿。"
"遗留：T19~T21 真 PG IT 归 195 服务器测试窗"
)
r = subprocess.run(['git', 'commit', '-m', msg], capture_output=True, text=True,
                   encoding='utf-8', errors='replace')
print('commit rc', r.returncode)
print((r.stdout or r.stderr)[-300:])
