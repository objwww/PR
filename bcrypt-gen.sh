#!/bin/sh
# 从运行容器取胖 jar → 解出 spring-security-crypto → BCrypt.main 生成哈希（值不落文档，仅本地 /tmp）
set -e
docker cp deploy-control-app-1:/app/app.jar /tmp/app.jar
python3 - <<'EOF'
import zipfile
z = zipfile.ZipFile('/tmp/app.jar')
name = [x for x in z.namelist() if 'spring-security-crypto' in x][0]
open('/tmp/ssc.jar', 'wb').write(z.read(name))
print('extracted:', name)
EOF
/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar org.springframework.security.crypto.bcrypt.BCrypt 'Tmp#linkfix-20260916'
