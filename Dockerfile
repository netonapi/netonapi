# NanoGate 运行镜像。
# Kotlin/Native 产出静态可执行文件，故运行阶段不需要 JVM——构建阶段用 JDK 跑 Gradle，
# 运行阶段只带一个二进制与配置。
#
# 构建（在包含 neton 框架与各模块的父目录下执行，构建上下文必须能看到同级仓库）：
#   docker build -f newgate/Dockerfile -t newgate:latest .

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src

# 依赖仓库与应用一起进入构建上下文（Gradle composite build 需要同级路径）
COPY neton                              /src/neton
COPY neton-application-module-member    /src/neton-application-module-member
COPY neton-application-module-payment   /src/neton-application-module-payment
COPY neton-application-module-platform  /src/neton-application-module-platform
COPY neton-application-module-gateway   /src/neton-application-module-gateway
COPY newgate                            /src/newgate

WORKDIR /src/newgate
RUN ./gradlew :application:linkReleaseExecutableLinuxX64 --no-daemon

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libgcc-s1 tzdata curl \
    && rm -rf /var/lib/apt/lists/*

# 不用 root 跑服务
RUN useradd --system --create-home --uid 10001 newgate
WORKDIR /app

COPY --from=build /src/newgate/application/build/bin/linuxX64/releaseExecutable/application.kexe /app/newgate
COPY --from=build /src/newgate/application/config /app/config

USER newgate
EXPOSE 8080

# 配置一律用环境变量覆盖（镜像内的默认值只适用于本地起步）：
#   NETON_DATABASE__DEFAULT__URI       PostgreSQL 连接串
#   NETON_SECURITY__JWT__SECRETKEY     管理台 JWT 签名密钥（必须改）
#   NEWGATE_CHANNEL_KEY_ENC_KEY        渠道 Key 加密主密钥（base64 32 字节，强烈建议配置）
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/ >/dev/null || exit 1

ENTRYPOINT ["/app/newgate"]
