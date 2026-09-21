#!/bin/sh
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select left(content->'native'->>'proposal', 700) from config_bundle where revision=151;"
echo '---proposal 内键---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(k, ', ') from (select jsonb_object_keys(content->'native'->'proposal') k from config_bundle where revision=151) t;"
