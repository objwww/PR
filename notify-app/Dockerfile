# ============================================================================
# notify-app 运行镜像（AM3 M3-20~23；模板=control-app Dockerfile）
# 构建策略：jar 由宿主机 maven 预先 package（mvn package，挂 /opt/m2 缓存），
# 本镜像只封装运行时——不在镜像内联网拉依赖。
# hardening 主体在 deploy/docker-compose.yml（read_only rootfs / cap_drop /
# no-new-privileges 都在那里逐项声明，便于 inspect 断言）。
# ============================================================================
FROM eclipse-temurin:21-jre

# 固定 uid/gid（与 control-app 同族不同号：10002），不写宿主路径
RUN groupadd --system --gid 10002 app \
    && useradd --system --uid 10002 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

# exec classifier jar（pom spring-boot-maven-plugin classifier=exec）
COPY target/notify-app-*-exec.jar /app/app.jar

USER app

# notify-app 无 docker profile 门（单 worker 常驻 + /health）；端口 8082 见 application.yml
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
