set -e
echo '== 备份双树（先备份后变更，BA-34） =='
tar -C /opt -czf /opt/backups/pre-m605-99040b0-deploy-tree.tar.gz build/pr
tar -C /opt -czf /opt/backups/pre-m605-99040b0-it-tree.tar.gz projects/pr_agent_it
ls -la /opt/backups/pre-m605-99040b0-*.tar.gz
echo '== 传输回读对账（BA-34⑧：双侧 sha256 由主会话对拍） =='
sha256sum /tmp/m6-605-tree.tgz
echo '== 解包双树（M6-05 HEAD=99040b0） =='
tar -C /opt/build/pr -xzf /tmp/m6-605-tree.tgz
tar -C /opt/projects/pr_agent_it -xzf /tmp/m6-605-tree.tgz
echo '== 解包后 blob 级抽查（BA-34⑧/BA-51：git hash-object 对拍） =='
cd /opt/projects/pr_agent_it
git hash-object \
  control-app/src/main/resources/db/migration/V34__am6_engine_shadow_work.sql \
  control-app/src/main/java/com/objwww/pr/control/alert/domain/repository/HolmesShadowWorkRepository.java \
  control-app/src/main/java/com/objwww/pr/control/infrastructure/persistence/PostgresHolmesShadowWorkRepository.java \
  control-app/src/main/java/com/objwww/pr/control/alert/application/HolmesShadowSampler.java \
  control-app/src/main/java/com/objwww/pr/control/alert/application/HolmesShadowWorker.java \
  control-app/src/main/java/com/objwww/pr/control/alert/application/HolmesShadowScheduler.java \
  control-app/src/test/java/com/objwww/pr/control/it/PostgresHolmesShadowIT.java \
  deploy/docker-compose.yml
echo '== 迁移面确认（V34 就位、V33/V32 历史在案） =='
ls control-app/src/main/resources/db/migration/ | tail -4
echo 'SYNC_M605_OK'
