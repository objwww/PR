#!/bin/sh
# 部署生效验证：运行镜像构建源 jar 内新面类常量在位（TRUSTED/[EX]/CONFIGURATION_ERROR/detected_level/evidence_roles）
cd /tmp && rm -rf aschk && mkdir aschk && cd aschk
unzip -o -q /opt/build/pr/control-app/target/control-app-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/classes/com/objwww/pr/control/alert/application/agent/*' \
  'BOOT-INF/classes/com/objwww/pr/control/infrastructure/tool/LokiAggregateExecutor.class'
B=BOOT-INF/classes/com/objwww/pr/control
chk() { # $1=class $2=marker $3=expectation
  n=$(grep -ac "$2" "$B/$1" 2>/dev/null || echo 0)
  echo "$n hits [$2] in $1 (期望$3)"
}
chk alert/application/agent/ContextAssembler.class 'TRUSTED_EXCLUSION_MARK' '>=1'
chk alert/application/agent/PrimaryGatewayToolPort.class 'CONFIGURATION_ERROR' '>=1'
chk alert/application/agent/PrimaryClaimAdmission.class 'ALL_COUNT_NOT_ERROR_EVIDENCE' '>=1'
chk infrastructure/tool/LokiAggregateExecutor.class 'detected_level' '>=1'
chk alert/application/agent/BoundedLlmRoleRunner.class 'evidence_roles' '>=1'
chk alert/application/agent/PrimaryFinalClaimProjector.class 'r7-primary-v2' '>=1'
