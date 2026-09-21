#!/bin/sh
# B1-1 FULL 捕获前置检查：活跃 run / flagd 状态 / compose 定位 / 当前捕获 env / control 健康
echo "== 活跃 run（应为空）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id::text||' '||state from rca_run where state in ('QUEUED','RUNNING','REPORTING');"
echo "== 最近 3 个 run =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select created_at::time(0)||' '||state from rca_run order by created_at desc limit 3;"
echo "== flagd flags 全量（paymentFailure 当前值）=="
docker exec flagd-admin-am3 python3 -c "import urllib.request;print(urllib.request.urlopen('http://127.0.0.1:8081/flags').read().decode())" 2>&1 || echo "(读取失败)"
echo "== control 容器与 compose 定位 =="
for c in $(docker ps --format '{{.Names}}' | grep -i control); do
  echo "container=$c"
  docker inspect "$c" --format "  workdir={{index .Config.Labels \"com.docker.compose.project.working_dir\"}}"
  docker inspect "$c" --format "  service={{index .Config.Labels \"com.docker.compose.service\"}}"
  docker inspect "$c" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i "INPUTCAPTURE\|INPUT_CAPTURE" || echo "  capture-env=(未设置=默认 digest-only)"
done
echo "== control 健康 =="
docker ps --format '{{.Names}} {{.Status}}' | grep -i control
