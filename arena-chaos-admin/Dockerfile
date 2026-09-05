# ============================================================================
# arena-chaos-admin 运行镜像（AM2 C-3：独立管理面服务）
# uid 10003（control-app=10001、order-arena=10002、本服务=10003）。
# 无宿主端口发布；只入 eval-mgmt（--internal 私网）。
# ============================================================================
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10003 app \
    && useradd --system --uid 10003 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY target/arena-chaos-admin-*-exec.jar /app/app.jar

USER app

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=62.5", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
