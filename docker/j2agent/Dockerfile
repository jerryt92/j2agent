# 在宿主机先打包后再构建镜像，例如：
#   mvn -f j2agent/pom.xml -pl j2agent-model -am -DskipTests install
#   mvn -f j2agent/pom.xml -pl j2agent-starter -am -DskipTests package
# 亦可将 *.tar.gz 置于本 Dockerfile 同级目录（docker/j2agent/），构建时优先使用该文件。
# syntax=docker/dockerfile:1.4
FROM --platform=linux/amd64 eclipse-temurin:21-jre AS unpack
RUN --mount=type=bind,source=.,target=/ctx,readonly \
    mkdir -p /opt && \
    if ls /ctx/docker/j2agent/*.tar.gz >/dev/null 2>&1; then \
      tar -xzf /ctx/docker/j2agent/*.tar.gz -C /opt; \
    elif ls /ctx/j2agent-starter/target/*.tar.gz >/dev/null 2>&1; then \
      tar -xzf /ctx/j2agent-starter/target/*.tar.gz -C /opt; \
    else \
      echo "ERROR: no *.tar.gz found in docker/j2agent/ or j2agent-starter/target/" >&2 && exit 1; \
    fi

FROM --platform=linux/amd64 eclipse-temurin:21-jre
WORKDIR /opt/j2agent
COPY --from=unpack /opt/j2agent /opt/j2agent
EXPOSE $J2AGENT_PORT
ENTRYPOINT ["sh", "-c", "exec java -cp \"/opt/j2agent/classes:/opt/j2agent/lib/*\" $JAVA_OPTS io.github.jerryt92.j2agent.AICenterStarterMain $SPRING_OPTIONS"]
