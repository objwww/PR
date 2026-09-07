#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AM4 E2E quiesce（注入前静止面，195 真栈迭代补充）。

教训（2026-09-07 runall 迭代实证）：同 fault 上一会话未恢复时注入新会话，
业务 gauge 持续非零 → 告警从未 resolve→refire → alertmanager 不重发 webhook
（repeat 语义）→ 本轮注入不产生新 incident/run，收敛轮询必然超时。

用法（arena-e2e-cli 容器内）：
  python3 /e2e/quiesce.py F1|F2|F3 [scenario_id generation]

v2：scenarioId 每轮按时钟生成（uq_chaos_scenario 全局唯一，同 id 不可二次
激活），本脚本无法自行探测上一轮会话——活跃会话由调用方（e2e-am4-common.sh
e4_quiesce）经 PG 查出后传参执行 off；未传参 = 上轮已收口，直接等静止面。

步骤：（有参则 off(expectedGeneration=generation)）→ 轮询等 gauge==0 且告警
回落（resolve 后才可能再次 firing）。输出 E2E 行协议。
"""
import sys
import time

sys.path.insert(0, "/e2e")
import driver  # noqa: E402  复用行协议/凭证/客户端（import 不触发 main）

QUIESCE_TIMEOUT_SECS = 240
POLL_INTERVAL_SECS = 5


def main():
    fault = sys.argv[1].upper()
    sc = driver.SC[fault]
    if len(sys.argv) >= 4:
        scenario, gen = sys.argv[2], int(sys.argv[3])
        st, body = driver.deactivate(fault, scenario, gen)
        print("E2E|INFO|quiesce:%s|off=%s %s" % (fault, st,
                                                 body.get("state", "")), flush=True)
    deadline = time.time() + QUIESCE_TIMEOUT_SECS
    while time.time() < deadline:
        firing = driver.prom_firing(sc.alertname) is not None
        gauge = driver.prom_query(sc.gauge()) or 0.0
        if not firing and gauge == 0.0:
            print("E2E|INFO|quiesce:%s|gauge=0 alert=resolved" % fault, flush=True)
            sys.exit(0)
        time.sleep(POLL_INTERVAL_SECS)
    driver.check("quiesce:%s 静止面（上轮会话已恢复归零）" % fault, False,
                 "gauge=%s firing未回落" % driver.prom_query(sc.gauge()))
    sys.exit(1)


if __name__ == "__main__":
    main()
