#!/bin/sh
# B1-1 FULL 捕获窗准备：compose 面安全检查 → override 挂载 → 重建 control-app → 验证 env
set -u
CD=/opt/build/pr/deploy
OV=/opt/build/b1-fullcap-override.yml

echo "== 1) compose 基面安全检查 =="
docker compose version
ls -la "$CD/.env" >/dev/null 2>&1 && echo ".env 在场" || echo ".env 不在场（必需变量将失败）"
echo "-- control-app 服务定义（image/env 附近）--"
sed -n '/^  control-app:/,/^  [a-z]/p' "$CD/docker-compose.yml" | grep -E "image:|container_name|depends_on" | head -8

echo "== 2) 写 override（仅加 INPUTCAPTURE=full 一键）=="
cat > "$OV" <<'YML'
# B1-1 临时覆盖：R2 输入捕获 FULL 档（app.alert.r7.input-capture）——验证窗结束即撤
services:
  control-app:
    environment:
      APP_ALERT_R7_INPUTCAPTURE: "full"
YML
cat "$OV"

echo "== 3) 重建 control-app（合并 override）=="
docker compose -p deploy -f "$CD/docker-compose.yml" -f "$OV" up -d control-app 2>&1 | tail -5

echo "== 4) 等启动（health 日志最多 90s）=="
i=0
while [ $i -lt 18 ]; do
  sleep 5
  if docker logs deploy-control-app-1 --since 5m 2>&1 | grep -q "Started ControlApplication\|自检.*PASS\|selfcheck.*PASS"; then
    echo "startup-ok（第 $((i+1)) 轮）"; break
  fi
  i=$((i+1))
done
docker inspect deploy-control-app-1 --format "StartedAt={{.State.StartedAt}} Status={{.State.Status}} Health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}"

echo "== 5) 验证 env =="
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i "INPUTCAPTURE" || echo "FAIL: env 未生效"
echo "PREP-DONE"
