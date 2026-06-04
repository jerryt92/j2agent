# 在宿主机先打包后再构建镜像，例如：
# mvn -pl j2agent-starter -am -DskipTests package
FROM --platform=linux/amd64 eclipse-temurin:21-jre
WORKDIR /opt/j2agent

COPY ../j2agent-starter/target/*.tar.gz /tmp/j2agent.tar.gz

RUN mkdir -p /opt && tar -xzf /tmp/j2agent.tar.gz -C /opt && rm /tmp/j2agent.tar.gz
EXPOSE $J2AGENT_PORT
ENTRYPOINT ["sh", "-c", "exec java -cp \"/opt/j2agent/classes:/opt/j2agent/lib/*\" $JAVA_OPTS io.github.jerryt92.j2agent.AICenterStarterMain $SPRING_OPTIONS"]
