set -e
echo '== 解包双树（BA-55 修复 HEAD=ffddff4；变更前备份沿用 pre-m604-0f390f1-*） =='
tar -C /opt/build/pr -xzf /tmp/m6-604b-tree.tgz
tar -C /opt/projects/pr_agent_it -xzf /tmp/m6-604b-tree.tgz
echo '== blob 级抽查 =='
cd /opt/projects/pr_agent_it
sha256sum /tmp/m6-604b-tree.tgz
git hash-object control-app/src/main/java/com/objwww/pr/control/alert/application/FallbackService.java control-app/src/test/java/com/objwww/pr/control/it/PostgresRunFallbackIT.java
echo 'SYNC_M604B_OK'
