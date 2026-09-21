#!/bin/sh
echo '=== exec jar 解包验证（正确方法）==='
cd /opt/build/pr
unzip -p order-arena/target/order-arena-0.0.1-SNAPSHOT-exec.jar \
  BOOT-INF/classes/com/objwww/pr/arena/application/chaos/ChaosRecoveryService.class \
  | grep -c '窗口内清偿'
echo '=== 容器内 jar 与宿主 jar 指纹对拍 ==='
sha256sum order-arena/target/order-arena-0.0.1-SNAPSHOT-exec.jar | cut -c1-16
docker exec alert-order-arena-1 sh -c "sha256sum /app/app.jar" | cut -c1-16
