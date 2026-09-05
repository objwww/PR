# ============================================================================
# order-arena 运行镜像（AM2 M2-01，沿 control-app 镜像模板）
# 构建策略：jar 由宿主机 maven 产出（mvn package），本镜像只封装运行时——
# 不在镜像内联网拉依赖。hardening 主体在 deploy/alert/docker-compose.yml
# （read_only rootfs / cap_drop / no-new-privileges / mem_limit 512m 逐项声明）。
#
# uid 10002：control-app=10001、order-arena=10002、arena-chaos-admin=10003，
# 各容器 uid 刻意错开便于审计与卷属主排查。
# ============================================================================
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10002 app \
    && useradd --system --uid 10002 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY target/order-arena-*-exec.jar /app/app.jar

USER app

# docker profile：装配 DataSource/业务链路全量 bean；JVM 在 512MiB 容器限额内
# 按 RAM 百分比取堆（≈320MiB 堆 + 元空间/虚拟线程栈余量），不写死 -Xmx
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=62.5", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
