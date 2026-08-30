# ============================================================================
# control-app 运行镜像（M0-T18，B16）
# 构建策略：jar 由宿主机 maven 容器产出（mvn package，挂 /opt/m2 缓存，
# 见 deploy/README.md），本镜像只封装运行时——不在镜像内联网拉依赖。
# hardening 主体在 deploy/docker-compose.yml（non-root 之外的只读 rootfs /
# cap_drop / no-new-privileges 都在那里逐项声明，便于 DP-04 inspect 断言）。
# ============================================================================
FROM eclipse-temurin:21-jre

# 固定 uid/gid，便于卷属主对齐与审计；
# /var/cas 预建 1777（sticky）：两容器 uid 刻意不同（10001/10002），named volume 首挂
# 继承先挂者镜像内属主——单方 chown 会让另一方写不进；1777 顺序无关，sticky 位防互删
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /var/cas && chmod 1777 /var/cas

WORKDIR /app

# 胖 jar 带 classifier=exec（主 artifact 保持瘦 jar 供 publisher IT 反应堆解析，见 pom 注释）
COPY target/control-app-*-exec.jar /app/app.jar

USER app

# docker profile：装配 DataSource/Flyway/自检/Worker 等全量 bean
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
