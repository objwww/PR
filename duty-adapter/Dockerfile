# ============================================================================
# duty-adapter 运行镜像（M7-17，127 独立故障域；jar 由构建面产出，镜像不联网拉依赖）
# 192MiB 预算：SerialGC + 小栈 + MaxRAMPercentage——Spring Boot web 单服务的下限形态。
# hardening（non-root/只读 rootfs/cap_drop/no-new-privileges）在 compose 逐项声明。
# ============================================================================
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10107 app \
    && useradd --system --uid 10107 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /data && chown app:app /data

WORKDIR /app
COPY target/duty-adapter-*.jar /app/app.jar

USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"

# /data 卷：快照磁盘副本 + 回写 spool（跨重启保留最后已知排班与未补台账）
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
