# -*- coding: utf-8 -*-
# 在 告警-PROGRESS.md 里程碑表的最后一行 "| 2026-..." 后追加 FUP-04(a) 行
import io

path = r"docs/告警-PROGRESS.md"
with io.open(path, "r", encoding="utf-8") as f:
    lines = f.readlines()

last_idx = None
for i, ln in enumerate(lines):
    if ln.startswith("| 2026-"):
        last_idx = i
assert last_idx is not None, "no ledger row found"

row = ("| 2026-09-20 16:40 | **FUP-04(a) F1 窗口内清偿落地并实机验证（靶场域根治，提交 f085e7ab）**："
       "**①机制定谳（数字闭环）**——S3(F1) 会话 TTL=1260s ≫ 卡单阈值 60s：F1 幂等旁路产生的重复单在 "
       "ACTIVE 窗口内最长晾 21 分钟无人清偿（原恢复算法只在 RECOVERING 补偿），一旦支付面相关旗标使命中单"
       "滞留 CREATED 越阈即点亮 oa_stuck_orders_current>0（ArenaOrderStuck, F3 症状标签）整窗——S5(F3) "
       "跨场景污染源，锚点容差(04(b))只是让 S5 如实测量而非根治。"
       "**②修法**——ChaosRecoveryService ACTIVE F1 分支窗口内清偿：非 canonical 重复 CREATED 单超宽限"
       "（新配置 app.arena.chaos.f1-drain-grace-seconds，默认 40s<60s 阈值留 20s 余量）即走正常业务路径废单"
       "（live 零污染）；F1 症状 gauge（重复单）宽限窗内照常在场供调查；RECOVERING 分支原样保留=收口兜底。"
       "新存储面 findF1YoungDuplicates（canonical 排序 rn>1∧CREATED∧超宽限）；ChaosRecoveryIT 补两 IT。"
       "**③部署+E2E**——清窗检查（chaos/eval/drill 三零）后重建 order-arena（容器健康，jar 指纹对拍一致）；"
       "验证批 6564c6a6 全绿（5/5 DECIDABLE、5/5 工具真值、**5/5 根因命中 100%**）；"
       "15s 采样监视器全程 S3 窗口 stuck_gauge=0.0。"
       "**④受控活体实验（修复的直接证据）**——真 API 激活 F1 会话（C-5 ground truth 正门，DB 手插被 "
       "fn_assert_active_has_gt 正确拒绝）+ 两张超龄 CREATED 重复单：12s 内日志 `F1 窗口内清偿: 宽限=40s "
       "废单=1`，非 canonical 即 DISCARDED/F1_DUPLICATE、canonical 保留。"
       "**⑤如实登记**——本批 S3 重复单因支付全部 AUTH/SUCCEEDED 推进到 ENABLED（不滞留 CREATED），批内"
       "未自然触发清偿分支：污染条件（支付失败相关旗标使命中单滞留 CREATED）为条件触发，修复面已由活体实验"
       "直接覆盖；实验残留已正门清理（会话 TTL 收口+测试单删除），收尾 FIRING=0、无 ACTIVE/RECOVERING 会话。"
       "取证弯路入账：grep 压缩 jar 误判容器旧代码（须 unzip -p 解包）；409 uq_chaos_scenario 重演一次"
       "（run-tag p8c 已消费，换 p10 重发）。| 主会话 |\n")

lines.insert(last_idx + 1, row)
with io.open(path, "w", encoding="utf-8", newline="") as f:
    f.writelines(lines)
print("appended after line", last_idx + 1)
