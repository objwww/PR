#!/usr/bin/env bash
# ============================================================================
# m3-lib.sh —— M3 模型端点故障注入共享库（M3-T10，docs/M3-技术方案.md §4.2/§11）
#
# 前提：已 source m2-lib.sh（复用 m2_stub_admin/m2_map_add/m2_map_del）。
#
# m3_model_fault_on <类型> [retry-after秒]：在 stub 上挂 priority=1 模型映射，
#   使一切 chat/completions 调用按指定故障形态响应；
# m3_model_fault_off：摘除。
#
# 扩展族（e2e-m3.sh G2 核心集，详见各函数注释）：
#   m3_model_fault_on_route/off_route   路径精确限定的故障注入（双路由主/备区分）
#   m3_model_fault_once_on/off          WireMock scenario「首败后成」（E2E-41）
#   m3_model_leak_on/off <sentinel>     500 错误体嵌入密钥原文的脱敏探针（E2E-56/61）
#
# 类型 ↔ §4.2 status×code 二维分类（ProviderErrorClassifier 的部署面对照面）：
#   rl-429-header   429 + Retry-After + Throttling.RateQuota       → RATE_LIMITED_TRANSIENT(MODEL)
#   rl-429-bare     429 无头无 code                                 → RATE_LIMITED_TRANSIENT(ACCOUNT)
#   quota-429-temp  429 + Retry-After + Throttling.AllocationQuota → QUOTA_TEMPORARY(ACCOUNT)
#   quota-403       403 + AllocationQuota.FreeTierOnly             → QUOTA_EXHAUSTED(ACCOUNT)
#   billing-400     400 + Arrearage                                → BILLING_OR_ACTIVATION(ACCOUNT)
#   auth-401        401                                            → AUTH_DENIED(CREDENTIAL)
#   invalid-400     400 无 code                                    → REQUEST_INVALID(MODEL)
#   server-500      500                                            → SERVER_ERROR(ENDPOINT)
#   timeout-408     408                                            → TIMEOUT(remote, ENDPOINT)
#   malformed-200   200 + 截断 JSON                                → PROTOCOL_ERROR(ENDPOINT)
#   empty-200       200 + 空 content                               → PROTOCOL_ERROR(ENDPOINT)
#   no-usage-200    200 无 usage 字段                              → OK + usage_missing=true
# 超时注入沿用 m2_model_delay_on <ms>（fixedDelay 超 per-call-timeout → 本地 TIMEOUT）。
# ============================================================================

M3_MODEL_FAULT_MAP_ID=""

# 百炼错误体（OpenAI 兼容嵌套结构，§4.2 双结构解析的嵌套面）
m3_model_fault_on() { # <类型> [retry-after秒]
    local type="$1" ra="${2:-}" json
    local ERR='{"error":{"code":"%s","message":"m3 fault injection"}}'
    case "$type" in
        rl-429-header)
            json=$(jq -n --arg ra "${ra:-3}" \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:429, headers:{"Retry-After":$ra,"Content-Type":"application/json"},
                    jsonBody:{error:{code:"Throttling.RateQuota",message:"m3 fault injection"}}}}') ;;
        rl-429-bare)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:429, headers:{"Content-Type":"application/json"},
                    jsonBody:{message:"m3 fault injection"}}}') ;;
        quota-429-temp)
            json=$(jq -n --arg ra "${ra:-30}" \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:429, headers:{"Retry-After":$ra,"Content-Type":"application/json"},
                    jsonBody:{error:{code:"Throttling.AllocationQuota",message:"m3 fault injection"}}}}') ;;
        quota-403)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:403, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"AllocationQuota.FreeTierOnly",message:"m3 fault injection"}}}}') ;;
        billing-400)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:400, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"Arrearage",message:"m3 fault injection"}}}}') ;;
        auth-401)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:401, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"InvalidApiKey",message:"m3 fault injection"}}}}') ;;
        invalid-400)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:400, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{message:"m3 fault injection"}}}}') ;;
        server-500)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:500, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"InternalError",message:"m3 fault injection"}}}}') ;;
        timeout-408)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:408, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"RequestTimeout",message:"m3 fault injection"}}}}') ;;
        malformed-200)
            # 截断 JSON：body 用字符串而非 jsonBody，WireMock 原样下发
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:200, headers:{"Content-Type":"application/json"},
                    body:"{\"id\":\"chatcmpl-stub\",\"choices\":[{\"index\":0,"}}}') ;;
        empty-200)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:200, headers:{"Content-Type":"application/json"},
                    jsonBody:{id:"chatcmpl-stub", object:"chat.completion", created:0, model:"stub",
                      choices:[{index:0, finish_reason:"stop", message:{role:"assistant", content:""}}],
                      usage:{prompt_tokens:10, completion_tokens:0, total_tokens:10}}}}') ;;
        no-usage-200)
            json=$(jq -n \
                '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
                  response:{status:200, headers:{"Content-Type":"application/json"},
                    jsonBody:{id:"chatcmpl-stub", object:"chat.completion", created:0, model:"stub",
                      choices:[{index:0, finish_reason:"stop",
                        message:{role:"assistant",
                          content:"[{\"file\":\"src/Main.java\",\"line\":1,\"existing_code\":\"System.out.println(\\\"hello stub\\\");\",\"rule\":\"smoke-stub\",\"severity\":\"INFO\",\"message\":\"stub 固定发现\"}]"}}]}}}') ;;
        *) echo "m3_model_fault_on: 未知故障类型 $type" >&2; return 1 ;;
    esac
    m3_model_fault_off
    M3_MODEL_FAULT_MAP_ID=$(m2_map_add "$json")
    [ -n "$M3_MODEL_FAULT_MAP_ID" ]
}

m3_model_fault_off() {
    [ -n "$M3_MODEL_FAULT_MAP_ID" ] && m2_map_del "$M3_MODEL_FAULT_MAP_ID" >/dev/null 2>&1
    M3_MODEL_FAULT_MAP_ID=""
}

# ---------------------------------------------------------------- 扩展（e2e-m3.sh G2 核心集）
# 以下三族均经 m2_map_add 注入（id 入 M2_MAP_IDS，脚本 trap 统一摘除兜底）。

# m3_model_fault_on_route <urlPath精确> <类型> [retry-after秒]：
#   路由/路径限定的故障映射——双路由区分注入用（E2E-42/46/51/60）。
#   主侧路径通常为 /v1/chat/completions（Spring AI completionsPath 默认含 /v1，
#   195 journal 实证），备侧由 OPENAI_COMPAT_BASE_URL_FALLBACK 的 path 前缀派生
#   （如 /fallback/v1/chat/completions）；用 urlPath 精确匹配而不用
#   否定 lookahead 正则（WireMock 对 lookahead 支持依赖底层 regex 引擎，精确
#   匹配更简单可靠）。目前支持 server-500 / rl-429-header（够用即可，不泛化）。
M3_ROUTE_FAULT_MAP_ID=""

m3_model_fault_on_route() { # <urlPath> <类型> [retry-after秒]
    local path="$1" type="$2" ra="${3:-}" json
    case "$type" in
        server-500)
            json=$(jq -n --arg u "$path" \
                '{priority:1, request:{method:"POST", urlPath:$u},
                  response:{status:500, headers:{"Content-Type":"application/json"},
                    jsonBody:{error:{code:"InternalError",message:"m3 route fault injection"}}}}') ;;
        rl-429-header)
            json=$(jq -n --arg u "$path" --arg ra "${ra:-3}" \
                '{priority:1, request:{method:"POST", urlPath:$u},
                  response:{status:429, headers:{"Retry-After":$ra,"Content-Type":"application/json"},
                    jsonBody:{error:{code:"Throttling.RateQuota",message:"m3 route fault injection"}}}}') ;;
        *) echo "m3_model_fault_on_route: 未支持的类型 $type" >&2; return 1 ;;
    esac
    m3_model_fault_off_route
    M3_ROUTE_FAULT_MAP_ID=$(m2_map_add "$json")
    [ -n "$M3_ROUTE_FAULT_MAP_ID" ]
}

m3_model_fault_off_route() {
    [ -n "$M3_ROUTE_FAULT_MAP_ID" ] && m2_map_del "$M3_ROUTE_FAULT_MAP_ID" >/dev/null 2>&1
    M3_ROUTE_FAULT_MAP_ID=""
}

# m3_model_fault_once_on [类型=server-500]：WireMock scenario 状态机——
#   首个 chat/completions 请求吃故障并翻状态（Started→DONE），其后请求吃成功映射
#   （响应体与静态 model-chat-completions 一致：固定 1 条 finding + usage 100/50/150）。
#   用于 E2E-41「首次 500、call 级重试成功、真实 HTTP 恰 2 次」的确定性注入
#   （不依赖"轮询命中后抢摘映射"的竞态手法）。scenario 名带 PID/随机后缀防跨轮串态。
M3_ONCE_MAP_IDS=()

m3_model_fault_once_on() {
    local type="${1:-server-500}" scenario="m3-once-$$-$RANDOM"
    [ "$type" = "server-500" ] || { echo "m3_model_fault_once_on: 暂只支持 server-500" >&2; return 1; }
    m3_model_fault_once_off
    local fault success id1 id2
    fault=$(jq -n --arg sc "$scenario" \
        '{priority:1, scenarioName:$sc, requiredScenarioState:"Started", newScenarioState:"DONE",
          request:{method:"POST", urlPath:"/v1/chat/completions"},
          response:{status:500, headers:{"Content-Type":"application/json"},
            jsonBody:{error:{code:"InternalError",message:"m3 fault injection (once)"}}}}')
    success=$(jq -n --arg sc "$scenario" \
        '{priority:1, scenarioName:$sc, requiredScenarioState:"DONE",
          request:{method:"POST", urlPath:"/v1/chat/completions"},
          response:{status:200, headers:{"Content-Type":"application/json"},
            jsonBody:{id:"chatcmpl-stub", object:"chat.completion", created:0, model:"stub",
              choices:[{index:0, finish_reason:"stop",
                message:{role:"assistant",
                  content:"[{\"file\":\"src/Main.java\",\"line\":1,\"existing_code\":\"System.out.println(\\\"hello stub\\\");\",\"rule\":\"smoke-stub\",\"severity\":\"INFO\",\"message\":\"stub 固定发现（DP-05 冒烟）\"}]"}}],
              usage:{prompt_tokens:100, completion_tokens:50, total_tokens:150}}}}')
    id1=$(m2_map_add "$fault"); id2=$(m2_map_add "$success")
    [ -n "$id1" ] && M3_ONCE_MAP_IDS+=("$id1")
    [ -n "$id2" ] && M3_ONCE_MAP_IDS+=("$id2")
    [ -n "$id1" ] && [ -n "$id2" ]
}

m3_model_fault_once_off() {
    local id
    for id in ${M3_ONCE_MAP_IDS[@]+"${M3_ONCE_MAP_IDS[@]}"}; do m2_map_del "$id"; done
    M3_ONCE_MAP_IDS=()
}

# m3_model_leak_on <sentinel>：密钥泄漏探针（E2E-56/61，§4.11）——500 错误体
#   message 嵌入 "Authorization: Bearer <sentinel>"（UT-71/EX-51 的脱敏触发形态）。
#   sentinel 经 jq --arg 内存装配（不落盘、不回显）；调用方传入真实
#   AGENT_MODEL_API_KEY 值，断言面=该值在 DB/日志/事件/journal 请求体零命中。
M3_LEAK_MAP_ID=""

m3_model_leak_on() { # <sentinel>
    local json
    json=$(jq -n --arg s "$1" \
        '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
          response:{status:500, headers:{"Content-Type":"application/json"},
            jsonBody:{error:{code:"InternalError",
              message:("upstream echo: Authorization: Bearer " + $s)}}}}')
    m3_model_leak_off
    M3_LEAK_MAP_ID=$(m2_map_add "$json")
    [ -n "$M3_LEAK_MAP_ID" ]
}

m3_model_leak_off() {
    [ -n "$M3_LEAK_MAP_ID" ] && m2_map_del "$M3_LEAK_MAP_ID" >/dev/null 2>&1
    M3_LEAK_MAP_ID=""
}
