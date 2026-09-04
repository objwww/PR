# HolmesGPT HTTP API 服务镜像(G0-10:AM0 的 CLI 常驻形态升级为 §6.5 server 形态)
#
# - holmesgpt pip 包 0.40.0 不带 server 入口(`holmes --help` 无 server 子命令),
#   官方 http-api.md 描述的 /api/chat 由仓库根 server.py 提供——本目录 vendor 该文件:
#     来源 https://raw.githubusercontent.com/HolmesGPT/holmesgpt/ad965c69e1f4e631e8aec7f519f70cce9335ebe6/server.py
#     (master,2026-08-31;SHA-256 b455805023bb6b58177c8b6aa302b7812589372936c64d435bbbd562f42d9121)
# - fastapi/uvicorn 已是 holmesgpt 0.40.0 的传递依赖,无需显式安装;
#   版本 pin holmesgpt==0.40.0(与 195 实测部署版本一致,可复现)。
# - 已知非致命不兼容(实测日志):启动期 "Failed to warm git skill repos"
#   AttributeError(skill_repo_manager 为 master 新属性,0.40.0 Config 无)——
#   server 捕获后继续,/api/chat 冒烟 200 通过。
FROM python:3.12-slim
RUN pip install --no-cache-dir holmesgpt==0.40.0 -i https://pypi.tuna.tsinghua.edu.cn/simple
COPY server.py /app/server.py
WORKDIR /app
# 端口由 HOLMES_PORT 控制(部署侧 8080,与 control-app 的 HOLMES_BASE_URL 对齐);
# 鉴权由 HOLMES_API_KEY 开启(所有端点除 healthz/readyz 外要求 X-API-Key/Bearer)
ENTRYPOINT ["python", "server.py"]
