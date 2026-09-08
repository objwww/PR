set -e
cd /opt/build/pr/deploy
echo '== flyway one-shot（V33） =='
docker compose up migrate
docker compose ps migrate
echo '== V33 验表（stdin 脚本，BA 纪律） =='
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' < /tmp/m6-verify-v33.sql
echo 'MIGRATE_M604_OK'
