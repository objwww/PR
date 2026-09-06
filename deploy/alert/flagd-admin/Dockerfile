# flagd-admin 侧车镜像（AM3 M3-30）：stdlib http.server 单文件，零第三方依赖
FROM python:3.11-alpine
COPY server.py /app/server.py
EXPOSE 8081
ENTRYPOINT ["python", "-u", "/app/server.py"]
