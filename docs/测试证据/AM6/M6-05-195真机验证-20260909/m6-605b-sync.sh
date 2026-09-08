set -e
echo '== BA-56 修复树重解包（0a67f9a；回滚锚=pre-m605-99040b0-* 已在案，不另备） =='
tar -C /opt/build/pr -xzf /tmp/m6-605b-tree.tgz
tar -C /opt/projects/pr_agent_it -xzf /tmp/m6-605b-tree.tgz
echo '== 三改动文件 blob 对拍 =='
cd /opt/projects/pr_agent_it
git hash-object \
  control-app/src/main/java/com/objwww/pr/control/infrastructure/config/AlertFlowConfig.java \
  control-app/src/main/java/com/objwww/pr/control/infrastructure/config/Am4ShadowTriggerConfig.java \
  control-app/src/test/java/com/objwww/pr/control/infrastructure/config/EngineComparisonRecorderWiringTest.java
echo 'SYNC_M605B_OK'
