# NetonAPI 运行镜像。
# Kotlin/Native 产出静态可执行文件，故运行阶段不需要 JVM——构建阶段用 JDK 跑 Gradle，
# 运行阶段只带一个二进制与配置。
#
# 构建上下文必须是**同时含 `Neton/` 与 `NewGate/` 的那一层**（docker-compose 里写的是
# `context: ..`），且这两个外层目录名是构建契约的一部分：
#   docker build -f NewGate/newgate/Dockerfile -t nanogate:latest .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 镜像内必须复刻宿主布局：settings.gradle.kts 用 `../../Neton/<repo>` 引框架与 canonical
# 模块，所以应用要落在两层深处（/src/NewGate/newgate），依赖落在同层的 /src/Neton/ 下。
# 下面八个仓一个都不能少——少任何一个，Gradle 在 configuration 阶段就因找不到
# included build / projectDir 而失败（module-system、module-infra、geolite4k 曾被漏掉）。
COPY Neton/neton                             /src/Neton/neton
COPY Neton/geolite4k                         /src/Neton/geolite4k
COPY Neton/neton-application-module-system   /src/Neton/neton-application-module-system
COPY Neton/neton-application-module-infra    /src/Neton/neton-application-module-infra
COPY Neton/neton-application-module-account  /src/Neton/neton-application-module-account
COPY Neton/neton-application-module-member   /src/Neton/neton-application-module-member
COPY Neton/neton-application-module-payment  /src/Neton/neton-application-module-payment
COPY Neton/neton-application-module-platform /src/Neton/neton-application-module-platform
COPY Neton/neton-application-module-gateway  /src/Neton/neton-application-module-gateway
COPY NewGate/newgate                         /src/NewGate/newgate

WORKDIR /src/NewGate/newgate
# libcurl 头文件：SMTP 发信（smtps / STARTTLS）通过 cinterop 调系统 libcurl；运行镜像的 curl 包自带 libcurl4
RUN apt-get update && apt-get install -y --no-install-recommends libcurl4-openssl-dev && rm -rf /var/lib/apt/lists/*
RUN ./gradlew :application:linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libgcc-s1 tzdata curl \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑服务
RUN useradd --system --create-home --uid 10001 newgate
WORKDIR /app

COPY --from=build /src/NewGate/newgate/application/build/bin/linuxX64/releaseExecutable/application.kexe /app/newgate
COPY --from=build /src/NewGate/newgate/application/config /app/config

USER newgate
EXPOSE 8800

# 配置一律用环境变量覆盖（镜像内的默认值只适用于本地起步）：
#   NETON_DATABASE__DEFAULT__URI       PostgreSQL 连接串
#   NETON_SECURITY__JWT__SECRETKEY     管理台 JWT 签名密钥（必须改）
#   NEWGATE_CHANNEL_KEY_ENC_KEY        渠道 Key 加密主密钥（base64 32 字节，强烈建议配置）
# /health 是匿名端点（gateway 组其余路由都要 Bearer sk-）：进程活着且数据库能应答 SELECT 1 才 200，
# 数据库不可达返回 503。带 -f 让 503 判成不健康——网关每个请求都要落库，DB 挂了就不该接流量。
# compose 里 caddy / 前端对本服务的 depends_on 用 service_healthy，靠的就是这条。
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS --max-time 3 -o /dev/null http://127.0.0.1:8800/health || exit 1

ENTRYPOINT ["/app/newgate"]
