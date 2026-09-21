import io

row = ("| 2026-09-13 11:00 | SR 批收官（影子 Run 正确结束 + 生产 Run 恢复对账；方案=docs/告警-Run停滞对账与影子执行收口方案-v1.md，"
       "工作树未提交，分支 feat/a-batch-readfaces）：**影子收口**（V108 purpose/purpose_source/completion_kind 持久身份列；"
       "影子取证结束 SUCCEEDED+SHADOW_EVIDENCE_ONLY 合法非活跃终态；orchestrator SHADOW 门禁正式报告与通知；"
       "前端「影子取证完成」；V25 活跃唯一索引原样保留——SR02 真 PG 验证收口即释放槽位、生产 Run 可再准入）"
       "→**RunReconciler 对账**（决策表：有效租约 WAIT / 租约过期复用任务回收 / REPORTING 缺 driver 查持久材料铸唯一 "
       "REPORT_FINALIZE 优先级 9 / 材料不完整 ORPHAN 只告警 / 硬期限 AUTO_EXPIRE 才 CAS 终止；"
       "三档 ALERT_ONLY→SAFE_RECOVER→AUTO_EXPIRE，195 以 ALERT_ONLY 上线；对账与 finishTask 同围栏——"
       "SR08 真 PG 竞态实测暴露 finishTask×expireRun AB-BA 死锁，lockNonTerminalByRunIdForUpdate 统一 task→run 锁序修复）"
       "→**验证**：本地 SR UT 21/21（Am4ShadowTriggerTest 7+RunReconcilerTest 11+ReportFinalizeRecoveryTest 3）"
       "+全量回归 1874/1874 三轮+195 真 PG PostgresRunReconcilerIT 4/4（FK/微秒精度/AB-BA/ck 指纹四类真库独有约束全数撞出并修复）"
       "+部署面（V108 迁移 success、health 200、ERROR=0、reconciler 活跃、两历史 REPORTING Run 原样未动、purpose 全 NULL 无误分类）"
       "→**收官窗两新卡即修**：BA-136 巡逻热旋转（decided==0 才睡→常驻候选恒被决策→~45 条/s 日志洪水（10min 27282 条），"
       "改无条件 30s 拍巡逻后 200s 窗 14 行+时间戳恰 30s 间隔实证）；BA-137 部署脚本 rsync --delete 误删 195 deploy/.env+根 .env"
       "+4 个 maven 模块、恢复途中 bcrypt 裸 $ 被 compose .env 二次插值截断（live 容器 env 重建 46 变量与 09-07 bak 逐值对账"
       "仅 am3 钥匙一差=史实+$$ 转义+白名单补模块重打包重部署，现场完全恢复，BA-16 同族再犯）。"
       "**运营遗留**：模式保持 ALERT_ONLY，误报观察后再启 SAFE_RECOVER/AUTO_EXPIRE；历史孤立 Run 清理仅走有审计的定向 CANCEL。"
       "BUGLOG BA-126/127 关单+BA-136/137 新立 | 主线话 |\n")

path = r'E:\kimiCode\docs\告警-PROGRESS.md'
with io.open(path, 'r', encoding='utf-8') as f:
    content = f.read()
assert content.endswith('| B0 执行者 |\n'), content[-40:]
with io.open(path, 'a', encoding='utf-8', newline='') as f:
    f.write(row)
print('appended, new total lines:', content.count('\n') + 1)
