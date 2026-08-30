# PR-Agent（AI Code Review Agent）

模块结构：`shared-kernel`（纯值对象/枚举，零框架依赖）+ `control-app`（无 GitHub 写凭证，Flyway 迁移）+ `publisher-app`（独占写凭证）；各应用按 DDD 四层分包 `interfaces / application / domain / infrastructure`。

构建：`export JAVA_HOME=<jdk21 路径> && mvn -DskipTests package`（服务器构建可加 `-s maven-settings-aliyun.xml` 走 Aliyun 镜像加速）。
