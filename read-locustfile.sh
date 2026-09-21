#!/bin/sh
echo '== checkout_person 构造 =='
docker exec load-generator sh -c 'grep -n -B3 -A12 "checkout_person" /usr/src/app/locustfile.py | head -40'
echo '== people.json 样本 =='
docker exec load-generator sh -c 'head -c 500 /usr/src/app/people.json'
echo
