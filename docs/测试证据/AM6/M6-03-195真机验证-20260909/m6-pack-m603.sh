set -e
cd /opt/projects/pr_agent_it/e2e-am6/runs
tar czf /tmp/m6-m603-e2e-suites.tgz 20260908T192428Z-am6-03 m6-m603-e2e.log
sha256sum /tmp/m6-m603-e2e-suites.tgz
