#!/bin/sh
sed -n '1,18p' /opt/build/e2e-b2-cl06.sh > /tmp/c1.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh >> /tmp/c1.sh
sh -n /tmp/c1.sh && echo "c1(1-18+20) OK" || echo "c1(1-18+20) FAIL"
{ sed -n '19p' /opt/build/e2e-b2-cl06.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh; } > /tmp/c2.sh
sh -n /tmp/c2.sh && echo "c2(19+20) OK" || echo "c2(19+20) FAIL"
sed -n '1,9p' /opt/build/e2e-b2-cl06.sh > /tmp/c3.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh >> /tmp/c3.sh
sh -n /tmp/c3.sh && echo "c3(1-9+20) OK" || echo "c3(1-9+20) FAIL"
sed -n '10,18p' /opt/build/e2e-b2-cl06.sh > /tmp/c4.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh >> /tmp/c4.sh
sh -n /tmp/c4.sh && echo "c4(10-18+20) OK" || echo "c4(10-18+20) FAIL"
sed -n '13,18p' /opt/build/e2e-b2-cl06.sh > /tmp/c5.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh >> /tmp/c5.sh
sh -n /tmp/c5.sh && echo "c5(13-18+20) OK" || echo "c5(13-18+20) FAIL"
sed -n '16,18p' /opt/build/e2e-b2-cl06.sh > /tmp/c6.sh; sed -n '20p' /opt/build/e2e-b2-cl06.sh >> /tmp/c6.sh
sh -n /tmp/c6.sh && echo "c6(16-18+20) OK" || echo "c6(16-18+20) FAIL"
