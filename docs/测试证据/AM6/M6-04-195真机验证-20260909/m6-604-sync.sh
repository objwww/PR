set -e
echo '== 备份双树（先备份后变更，BA-34） =='
tar -C /opt -czf /opt/backups/pre-m604-0f390f1-deploy-tree.tar.gz build/pr
tar -C /opt -czf /opt/backups/pre-m604-0f390f1-it-tree.tar.gz projects/pr_agent_it
ls -la /opt/backups/pre-m604-0f390f1-*.tar.gz
echo '== 解包双树（M6-04 HEAD=0f390f1） =='
tar -C /opt/build/pr -xzf /tmp/m6-604-tree.tgz
tar -C /opt/projects/pr_agent_it -xzf /tmp/m6-604-tree.tgz
echo '== 解包后 blob 级抽查（BA-34⑧/BA-51：git hash-object 对拍） =='
cd /opt/projects/pr_agent_it
git hash-object \
  control-app/src/main/resources/db/migration/V33__am6_run_fallback.sql \
  control-app/src/main/java/com/objwww/pr/control/alert/application/FallbackService.java \
  control-app/src/test/java/com/objwww/pr/control/it/PostgresRunFallbackIT.java \
  control-app/src/main/java/com/objwww/pr/control/alert/application/RcaRunOrchestrator.java
file control-app/src/main/resources/db/migration/V33__am6_run_fallback.sql
echo '== 迁移面确认（V33 就位、V32 历史在案） =='
ls control-app/src/main/resources/db/migration/ | tail -4
echo 'SYNC_M604_OK'
