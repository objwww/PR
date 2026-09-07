#!/bin/sh
# ============================================================================
# e2e-am4-common.sh —— AM4 E2E 公共函数（只读查询、记账、批次 ID、证据清单）
#
# 纪律（AM4 落码方案附录 §一，v1.4）：
#   - 脚本不含凭据、固定生产 ID、"失败后换 mock"分支；
#   - 连接信息从部署环境变量取得，绝不 dump；
#   - 所有 DB 访问只读（psql 单条 SELECT），断言不直写业务事实表。
#
# 证据面（runs/<UTC批次>/）：sql/ 与 raw/ 目录、commands.log（每条 SQL 与每场景
# 调用逐条记账）、assertions.tsv（断言流水，runall 汇总为 assertions.json）、
# suite-manifest.json / sha256sums.txt 由 runall 批次收尾统一生成。
#
# 用法：各场景脚本 source 本文件后调用 e4_begin / e4_assert_eq / e4_summary。
# 环境变量：AM4_PG_CONTAINER（必填，195 部署侧 PG 容器名）、
#           AM4_PG_USER（缺省 control_app）、AM4_PG_DB（缺省 control）；
#           E4_BATCH_ID / E4_RUN_DIR（runall 统一批次时导出，单场景运行则自建）。
# ============================================================================

E4_BATCH_ID="${E4_BATCH_ID:-am4-$(date -u +%Y%m%dT%H%M%SZ)}"
# 证据目录锚定脚本位置（../runs/，绝对路径）：v1 相对路径以 cwd 解析，单场景
# 从 e2e-脚本/ 目录运行时嵌套成 e2e-脚本/docs/...（195 实证），runall 汇总对不上
E4_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
E4_RUN_DIR="${E4_RUN_DIR:-$E4_SCRIPT_DIR/../runs/$E4_BATCH_ID}"
E4_PASS_COUNT=0
E4_FAIL_COUNT=0

POLL_MAX="${POLL_MAX:-240}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/build/pr/deploy}"

# 影子触发统一入口（v1，195 实证）：等 incident 活跃 run 收敛（holmes 调查期
# 材料变化时 orchestrator 会铸 RERUN——直接触发撞 uq_rca_run_active_incident）
# → compose run 一次性入口触发（失败自动重试 ≤3 次）→ stdout 捕获影子 run id；
# 全程诊断走 stderr（命令替换只捕获 stdout，错误不得被吞）。
e4_trigger_shadow() {
    holmes="$1"
    shift
    i=0
    active_n=1
    while [ $i -lt 12 ]; do   # 12*10s=120s
        active_n=$(e4_sql "
            select count(*) from rca_run r
             where r.incident_id = (select incident_id from rca_run where id='$holmes')
               and r.state in ('QUEUED','RUNNING','REPORTING')")
        [ "$active_n" -eq 0 ] && break
        sleep 10
        i=$((i + 1))
    done
    if [ "$active_n" -ne 0 ]; then
        echo "  FAIL: incident 活跃 run 未收敛（影子触发将撞 uq_rca_run_active_incident）" >&2
        return 1
    fi
    attempt=0
    trigger_out=""
    while [ "$attempt" -lt 3 ]; do
        trigger_out=$( (cd "$DEPLOY_DIR" && docker compose run --rm --no-deps control-app \
            --spring.profiles.active=docker,am4-shadow-trigger \
            --spring.main.web-application-type=none \
            --am4.shadow-trigger.holmes-run-id="$holmes" "$@") </dev/null 2>&1 ) && break
        attempt=$((attempt + 1))
        echo "  WARN: 影子触发第 $attempt 次失败，20s 后重试" >&2
        printf '%s\n' "$trigger_out" | tail -8 >&2
        sleep 20
    done
    if [ "$attempt" -ge 3 ]; then
        echo "  FAIL: 影子触发重试耗尽" >&2
        printf '%s\n' "$trigger_out" | tail -20 >&2
        return 1
    fi
    printf '%s\n' "$trigger_out" | grep "^AM4_SHADOW_RUN_ID=" | tail -1 | cut -d= -f2
}

# 批次/场景事件记账（commands.log 缺失可写时静默跳过——证据面不得反向阻断测试）
e4_note() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" \
        >> "$E4_RUN_DIR/commands.log" 2>/dev/null || :
}

# 只读 SQL：经部署侧 psql 容器执行单条 SELECT（连接取环境变量，不落凭据）；
# 每条 SQL 逐条记入 commands.log（附录 §一 sql/ 前后只读事实对拍的执行留痕）
e4_sql() {
    e4_note "SQL: $1"
    docker exec "${AM4_PG_CONTAINER:?需要 AM4_PG_CONTAINER（195 部署侧 PG 容器名）}" \
        psql -U "${AM4_PG_USER:-control_app}" -d "${AM4_PG_DB:-control}" \
        -Atqc "$1"
}

# 环境自愈管理面 SQL（v3）：只用于轮间遗留物回收，非断言、非业务事实写入——
# "断言只读"纪律的立法意图是场景断言不得伪造业务事实自证；本面不改任何断言
# 依赖的本轮数据。
e4_admin_sql() {
    e4_note "ADMIN-SQL: $1"
    docker exec "${AM4_PG_CONTAINER:?需要 AM4_PG_CONTAINER（195 部署侧 PG 容器名）}" \
        psql -U "${AM4_PG_USER:-control_app}" -d "${AM4_PG_DB:-control}" \
        -Atqc "$1"
}

# 注入前环境自愈（v9，195 迭代实证）：
#   a) quiesce 静止面——上轮同 fault 会话未收口时先 off。scenarioId 每轮按时钟
#      生成且 uq_chaos_scenario 全局唯一（同 id 不可二次激活），quiesce.py 无法
#      自行探测上轮 id，由本函数经 PG 查出活跃会话传参执行 off；
#   b) AM 重启 + 清存储面——E2E 对同 fingerprint 反复 firing/resolved，AM 组
#      对象出现 notify 死锁：v7 轮间 600s 逐次轮询实证该组 resolved 永不 flush
#      （对照批次 2 分钟即落库），组锁死到 repeat_interval(4h)。v8 仅 restart
#      不够：AM 挂 RW volume（--storage.path=/alertmanager），nflog 通知去重
#      状态跨重启持久化（195 实证：16:05:58 轮 A 投递被 nflog 记住，16:07:28
#      轮 B 同 fingerprint 零投递）——v9 stop → 清 nflog/silences → start，
#      通知状态彻底归零，任何 firing 必为新组首发投递；Prometheus 会对 AM 恢复
#      后重发当前 firing alerts，不丢真告警；靶场无静默规则，清 silences 无副作用。
#      放行条件 = AM /-/ready 200。
#   c) 影子 run 轮间回收——影子 run 终点 REPORTING 挂 uq_rca_run_active_incident
#      （QUEUED/RUNNING/REPORTING 部分唯一），不回收则同 incident 下一轮 firing
#      intake 开 run 必撞 DuplicateKeyException → 告警死信（195 实证）。RERUN 仅
#      由影子触发器产生，回收不触碰主链 INITIAL run。

E4_AM_CONTAINER="${E4_AM_CONTAINER:-alertmanager-am0}"

e4_quiesce() {   # $1 = FAULT (F1|F2|F3)
    fault="$1"
    active=$(e4_sql "
        select scenario_id || ' ' || generation from arena.oa_chaos_session
         where fault_type = '$fault'
           and state in ('PREPARED','ACTIVE','RECOVERING')
         order by created_at desc limit 1")
    if [ -n "$active" ]; then
        # 故意词切分：quiesce.py F1 <scenario_id> <generation>
        docker exec arena-e2e-cli python3 /e2e/quiesce.py "$fault" $active
    else
        docker exec arena-e2e-cli python3 /e2e/quiesce.py "$fault"
    fi
    am_data=$(docker inspect "$E4_AM_CONTAINER" --format \
        '{{range .Mounts}}{{if eq .Destination "/alertmanager"}}{{.Source}}{{end}}{{end}}')
    docker stop "$E4_AM_CONTAINER" >/dev/null
    [ -n "$am_data" ] && rm -rf "${am_data:?AM 存储目录为空则不清}"/* 2>/dev/null
    docker start "$E4_AM_CONTAINER" >/dev/null
    i=0
    code="000"
    while [ $i -lt 24 ]; do   # 24*5s=120s
        code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:9093/-/ready" || echo 000)
        [ "$code" = "200" ] && break
        sleep 5
        i=$((i + 5))
    done
    if [ "$code" != "200" ]; then
        echo "  FAIL: AM 重启后未就绪（http=$code）"
        exit 1
    fi
    echo "  quiesce: AM 已重启并清通知状态（就绪，放行注入）"
    e4_admin_sql "
        update rca_run set state='CANCELLED', finished_at=now(), updated_at=now()
         where trigger_kind='RERUN'
           and state in ('QUEUED','RUNNING','REPORTING')"
}

# 计数断言：e4_assert_eq <描述> <实际> <期望>（流水同步落 assertions.tsv）
e4_assert_eq() {
    desc="$1"; actual="$2"; expected="$3"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS: $desc (=$actual)"
        E4_PASS_COUNT=$((E4_PASS_COUNT + 1))
        printf '%s|%s|%s|true\n' "$desc" "$actual" "$expected" \
            >> "$E4_RUN_DIR/assertions.tsv" 2>/dev/null || :
    else
        echo "  FAIL: $desc (actual=$actual expected=$expected)"
        E4_FAIL_COUNT=$((E4_FAIL_COUNT + 1))
        printf '%s|%s|%s|false\n' "$desc" "$actual" "$expected" \
            >> "$E4_RUN_DIR/assertions.tsv" 2>/dev/null || :
    fi
}

# 批次/场景开始：建证据目录（sql/ 只读事实对拍、raw/ 脱敏组件输出）+ 记账；
# 环境自愈：arena-e2e-cli 是 sleep 14400 常驻容器（/e2e 驱动面），到期退出后
# 原地 start 续命（Cmd 重跑，2026-09-07 15:43 实证 sleep 到期 Exited 0 断驱动）
e4_begin() {
    cli_state=$(docker inspect arena-e2e-cli --format '{{.State.Running}}' 2>/dev/null || echo missing)
    if [ "$cli_state" != "true" ]; then
        docker start arena-e2e-cli >/dev/null
        echo "[AM4-E2E] arena-e2e-cli 未运行，已 start（环境自愈）"
    fi
    mkdir -p "$E4_RUN_DIR/sql" "$E4_RUN_DIR/raw"
    e4_note "BEGIN batch=$E4_BATCH_ID scenario=${0##*/} git=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "[AM4-E2E] batch=$E4_BATCH_ID dir=$E4_RUN_DIR"
}

# 断言流水（TSV）→ 机器可读 JSON 数组（runall 批次收尾调用；描述中的引号转义）
e4_assertions_json() {
    {
        printf '[\n'
        first=1
        while IFS='|' read -r desc actual expected pass; do
            [ -n "$desc" ] || continue
            esc=$(printf '%s' "$desc" | sed 's/\\/\\\\/g; s/"/\\"/g')
            [ "$first" -eq 1 ] || printf ',\n'
            first=0
            printf '{"desc":"%s","actual":"%s","expected":"%s","pass":%s}' \
                "$esc" "$actual" "$expected" "$pass"
        done < "$E4_RUN_DIR/assertions.tsv" 2>/dev/null
        printf '\n]\n'
    } > "$E4_RUN_DIR/assertions.json"
}

# 批次收尾：assertions.json 落盘；任一 FAIL 即非零退出（缺场景=FAIL，同 runall 纪律）
e4_summary() {
    e4_note "SUMMARY scenario=${0##*/} pass=$E4_PASS_COUNT fail=$E4_FAIL_COUNT"
    e4_assertions_json
    if [ "$E4_FAIL_COUNT" -gt 0 ]; then
        echo "[AM4-E2E] FAIL: pass=$E4_PASS_COUNT fail=$E4_FAIL_COUNT"
        exit 1
    fi
    echo "[AM4-E2E] PASS: 全部断言通过 (pass=$E4_PASS_COUNT)"
}
