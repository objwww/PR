# ============================================================================
# publisher-app 运行镜像（M0-T18，B16）
# 构建策略同 control-app/Dockerfile：jar 由宿主机 maven 容器产出。
# 注意：本模块胖 jar 未加 classifier（control-app 才带 exec），
# spring-boot repackage 直接覆盖主 artifact，故取主 jar。
# ============================================================================
FROM eclipse-temurin:21-jre

# uid/gid 与 control 容器刻意不同：两容器不共享身份（B16 独立用户）；
# /var/cas 预建 1777（sticky）：本容器只读挂载 CAS 卷，但若它先挂、named volume 从本镜像
# 初始化，必须保证 control（uid 10001）随后可写——单方 chown 会导致顺序依赖的启动失败
RUN groupadd --system --gid 10002 app \
    && useradd --system --uid 10002 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /var/cas && chmod 1777 /var/cas

WORKDIR /app

COPY target/publisher-app-*.jar /app/app.jar

USER app

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=docker"]
