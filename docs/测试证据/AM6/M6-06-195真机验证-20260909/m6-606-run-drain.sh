set -e
docker exec -i deploy-postgres-1 sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB' < /tmp/m6-606-drain.sql
