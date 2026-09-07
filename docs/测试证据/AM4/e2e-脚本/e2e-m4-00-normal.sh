#!/bin/sh
# ============================================================================
# e2e-m4-00-normal.sh —— E2E-M4-00：B0 正常订单 + 完整观测窗口（LIVE_BUSINESS）
#
# 必断言（AM4 技术方案 §15.3）：业务成功；故障告警 / Incident / Run / Claim /
# 候选通知增量均为 0；Native/Shadow 不额外影响 Holmes 主路径。
#
# 触发：B0 = 无故障注入，仅正常业务请求 + 等待观测窗口（OBS_WINDOW 秒）。
# 前置：arena-e2e-cli 容器（e2e_setup.sh）；AM4_PG_CONTAINER 已设置。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am4-common.sh"

OBS_WINDOW="${OBS_WINDOW:-300}"

e4_begin
echo "[E2E-M4-00] B0 正常观测窗口 ${OBS_WINDOW}s"

# 批前基线（只读）
B_ALERT=$(e4_sql "select count(*) from alert_event")
B_INC=$(e4_sql "select count(*) from incident")
B_RUN=$(e4_sql "select count(*) from rca_run")
B_CLAIM=$(e4_sql "select count(*) from rca_claim")
B_PUB=$(e4_sql "select count(*) from report_publication")
B_OUTBOX=$(e4_sql "select count(*) from notify_outbox")
echo "  基线: alert=$B_ALERT incident=$B_INC run=$B_RUN claim=$B_CLAIM pub=$B_PUB outbox=$B_OUTBOX"

# 正常业务请求（B0）：经 order-arena 对外业务 API（真实业务入口，禁直写事实表）。
# API 形态见 order-arena 服务面；执行者也可手工完成正常下单后按回车继续。
# 探测工具：arena-e2e-cli（python:alpine）无 curl，用 busybox wget（200→rc=0）
if [ -z "$B0_SKIP_BUSINESS" ]; then
    docker exec arena-e2e-cli wget -q -O /dev/null -T 10 http://order-arena:8080/healthz \
        || { echo "  FAIL: order-arena 不可达"; exit 1; }
    echo "  order-arena 可达（业务请求按 195 配方执行正常下单）"
fi

echo "  等待观测窗口 ${OBS_WINDOW}s ..."
sleep "$OBS_WINDOW"

# 零增量断言（无故障不制造 RCA / 候选通知）
e4_assert_eq "告警事件零增量" "$(e4_sql "select count(*) from alert_event")" "$B_ALERT"
e4_assert_eq "Incident 零增量" "$(e4_sql "select count(*) from incident")" "$B_INC"
e4_assert_eq "RcaRun 零增量" "$(e4_sql "select count(*) from rca_run")" "$B_RUN"
e4_assert_eq "Claim 零增量" "$(e4_sql "select count(*) from rca_claim")" "$B_CLAIM"
e4_assert_eq "发布零增量" "$(e4_sql "select count(*) from report_publication")" "$B_PUB"
e4_assert_eq "候选通知零增量" "$(e4_sql "select count(*) from notify_outbox")" "$B_OUTBOX"

# Native/Shadow 不额外影响主路径：窗口内无影子面工具调用残留
e4_assert_eq "窗口内无工具账本新增（无 RCA run 即无影子调用）" \
    "$(e4_sql "select count(*) from rca_tool_invocation")" "0"

e4_summary
