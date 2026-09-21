#!/bin/sh
grep -E 'Tests run: .*FAILURE|BUILD' /tmp/wc-full-suite.log | tail -5
echo '--- failing classes ---'
grep -rl 'Failures="[1-9]\|Errors="[1-9]' /opt/build/pr/control-app/target/surefire-reports --include='TEST-*.xml' | head -10
echo '--- failure cases ---'
grep -hE '<<< (FAILURE|ERROR)!' /opt/build/pr/control-app/target/surefire-reports/*.txt | head -20
exit 0
