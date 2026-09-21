#!/bin/sh
docker inspect alert-arena-chaos-admin-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -iE 'datasource|db|spring' | sed -E 's/(PASSWORD=....).*/\1**/'
echo '---session table hunt---'
docker exec deploy-postgres-1 psql -U postgres -At -c "select datname from pg_database;" | grep -v template
