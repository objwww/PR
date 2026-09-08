set -e
echo '== 全部运行容器（找 holmes 面） =='
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' | sort
echo '== compose 项目清单 =='
docker compose ls
echo '== holmes 域名解析面（deploy 网络内） =='
docker network ls --format '{{.Name}}' | sort
