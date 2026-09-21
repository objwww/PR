#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---qualifications---'
$PG "select left(candidate_digest::text,12)||'|'||quality_verdict||'|'||coalesce(revoked_at::text::char(19),'-')||'|'||granted_at from release_qualification order by granted_at desc limit 6;"
