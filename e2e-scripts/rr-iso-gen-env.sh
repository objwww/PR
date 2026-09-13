#!/bin/sh
# rr-iso-gen-env.sh [MODEL_KEY_VALUE] —— 隔离栈 env（v3：姿态冻结模板）
# 秘密=rr-iso.secrets 一次铸；姿态=rr-iso.posture 首铸时从运行中在栈容器快照
# （R7/AM4/NATIVE/PROMPT）+固定字面量（与 deploy/.env 解耦——该文件被多执行方
# 共享编辑，不可作为稳定派生基座）；模型键=参数。
set -e
DST=/opt/build/pr/rr-iso/rr-iso.env
SEC=/opt/build/pr/rr-iso/rr-iso.secrets
POS=/opt/build/pr/rr-iso/rr-iso.posture
mkdir -p /opt/build/pr/rr-iso

# 1) 姿态模板（存在即复用；首铸源=运行中容器 env 的真值 + 固定字面量）
if [ ! -f "$POS" ]; then
  {
    echo "POSTGRES_DB=pr_agent"
    echo "AGENT_MODEL=deepseek-v4-flash-0731"                   # 唯一有配额模型（qwen3-max/glm-5 免费配额耗尽 2026-09-13；RR14 键面按此铸造）
    echo "AGENT_MODEL_FALLBACK="
    echo "OPENAI_COMPAT_BASE_URL=http://litellm-am3:4000"     # 经 rriso-net 解析
    echo "OPENAI_COMPAT_BASE_URL_FALLBACK="
    echo "APP_ALERT_EVAL_PROVIDER_FINGERPRINT=litellm:deepseek-v4-flash-0731@litellm"
    echo "APP_ALERT_R7_PRIMARY_MAX_DELEGATION_BATCHES=0"       # 零委派直查车（B1 配方）
    docker exec deploy-control-app-1 env | grep -E '^APP_ALERT_R7_PRIMARY_ENABLED=|^APP_ALERT_R7_PRIMARY_RELEASE_DIGEST=|^APP_ALERT_R7_PRIMARY_PROMPT=|^APP_ALERT_AM4_PROMETHEUS_SERVICE_ALLOWLIST=|^APP_ALERT_AM4_BUDGET_TOOL_CALLS=|^APP_ALERT_NATIVE_METRICS_EXPR=|^APP_ALERT_NATIVE_TOOL_REGISTRY_DIGEST=' || true
  } > "$POS"
  chmod 600 "$POS"
  echo "posture 首铸（$(wc -l < "$POS") 行；源=运行中容器+字面量）"
fi

# 2) 秘密一次铸造（含 bcrypt：宿主 JDK21 + control-app jar 抽 spring-security-crypto）
if [ ! -f "$SEC" ]; then
  {
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
    echo "CONTROL_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "PUBLISHER_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "ARENA_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "CHAOS_ADMIN_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "EVAL_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "NOTIFY_DB_PASSWORD=$(openssl rand -hex 16)"
    echo "ALERTMANAGER_WEBHOOK_BEARER_TOKEN=iso-$(openssl rand -hex 16)"
    echo "CONTROL_WEBHOOK_BEARER_TOKEN=iso-$(openssl rand -hex 16)"
    echo "APP_RELEASE_API_BEARER=iso-$(openssl rand -hex 16)"
    echo "APP_OPERATOR_API_BEARER=iso-$(openssl rand -hex 16)"
    echo "APP_DUTY_ADAPTER_BEARER=iso-$(openssl rand -hex 16)"
    echo "AUTH_OPERATOR_USERNAME=isoop"
  } > "$SEC"
  JAR=$(ls /opt/build/pr/control-app/target/*SNAPSHOT*.jar 2>/dev/null | head -1)
  [ -n "$JAR" ] || { echo "control-app jar 未找到"; exit 1; }
  LIB=$(unzip -l "$JAR" | grep -oE 'BOOT-INF/lib/spring-security-crypto[^ ]*\.jar' | head -1)
  unzip -p "$JAR" "$LIB" > /tmp/rr-sscrypto.jar
  JCL=$(unzip -l "$JAR" | grep -oE 'BOOT-INF/lib/spring-jcl[^ ]*\.jar' | head -1)
  unzip -p "$JAR" "$JCL" > /tmp/rr-spring-jcl.jar
  cat > /tmp/BcryptGen.java <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class BcryptGen {
    public static void main(String[] a) {
        System.out.println(new BCryptPasswordEncoder(10).encode(a[0]));
    }
}
JAVA
  BC=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/rr-sscrypto.jar:/tmp/rr-spring-jcl.jar /tmp/BcryptGen.java iso-op-pw-rr15 | tail -1)
  case "$BC" in *'$2'*) ;; *) echo "bcrypt 生成异常: $BC"; exit 1;; esac
  echo "AUTH_OPERATOR_PASSWORD_BCRYPT=$(printf '%s' "$BC" | sed 's/\$/\$\$/g')" >> "$SEC"
  chmod 600 "$SEC"
  echo "secrets 首铸"
fi

# 3) 组装：posture + secrets + 可选模型键
cat "$POS" "$SEC" > "$DST"
if [ -n "$1" ]; then echo "AGENT_MODEL_API_KEY=$1" >> "$DST"; fi
chmod 600 "$DST"
echo "iso env 组装: $(wc -l < "$DST") 行"

# 4) iso 操作员 env
OP=/opt/build/pr/rr-iso/rr-iso-openv.sh
{
  echo '#!/bin/sh'
  echo 'export R7_CONTROL_URL="http://127.0.0.1:18091"'
  echo "export R7_WEBHOOK_BEARER=\"\$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' $DST | head -1 | cut -d= -f2-)\""
  echo "export R7_RELEASE_BEARER=\"\$(grep -E '^APP_RELEASE_API_BEARER=' $DST | head -1 | cut -d= -f2-)\""
  echo 'export R7_PSQL_CMD="docker exec rriso-postgres-1 psql"'
  echo "export R7_PG_URL=\"postgres://control_app:\$(grep -E '^CONTROL_DB_PASSWORD=' $DST | cut -d= -f2-)@127.0.0.1:5432/pr_agent\""
  echo 'export R7_RUNS_DIR="/opt/build/runs-rriso"'
  echo 'export R7_CONTROL_CONTAINER="rriso-control-app-1"'
  echo 'export R7_PRIMARY_ALLOWLIST="prometheus.query,logs.query,prometheus.instant,prometheus.metric_value,prometheus.catalog,prometheus.label_values,prometheus.rules,logs.aggregate"'
} > "$OP"
chmod 600 "$OP"
echo "iso 操作员 env 已刷新"
