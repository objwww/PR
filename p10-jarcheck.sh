#!/bin/sh
echo '=== [1] 宿主 exec jar 含新字符串? ==='
cd /opt/build/pr
ls -la order-arena/target/*exec*.jar 2>/dev/null
grep -c '窗口内清偿' order-arena/target/order-arena-0.0.1-SNAPSHOT-exec.jar 2>/dev/null || echo nojar
echo '=== [2] class 文件时间戳 vs 源文件 ==='
ls -la order-arena/target/classes/com/objwww/pr/arena/application/chaos/ChaosRecoveryService.class 2>/dev/null
ls -la order-arena/src/main/java/com/objwww/pr/arena/application/chaos/ChaosRecoveryService.java
echo '=== [3] order-arena 镜像清单 ==='
docker images --format '{{.ID}} {{.CreatedAt}} {{.Repository}}:{{.Tag}}' | grep order-arena
echo '=== [4] 容器所用镜像 ID ==='
docker inspect alert-order-arena-1 --format '{{.Image}}' | cut -c8-19
