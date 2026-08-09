FROM maven:3.9.9-eclipse-temurin-17 AS builder

ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

WORKDIR /workspace
COPY backend ./backend
COPY deploy/docker/maven-settings.xml ./maven-settings.xml

RUN cd backend && \
    mvn -B -s /workspace/maven-settings.xml -DskipTests clean install && \
    for service in gateway user-service knowledge-service community-service message-service; do \
      mvn -B -s /workspace/maven-settings.xml -DskipTests -pl "$service" package spring-boot:repackage; \
    done

FROM eclipse-temurin:17-jre-jammy

ARG SERVICE
RUN test -n "$SERVICE" && \
    apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --gid 10001 app && \
    useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app && \
    mkdir -p /app /data && \
    chown -R app:app /app /data

WORKDIR /app
COPY --from=builder --chown=app:app /workspace/backend/${SERVICE}/target/${SERVICE}-1.0.0.jar /app/app.jar

USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
