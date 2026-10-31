FROM node:22.23.4-bookworm-slim AS javascript-runtime

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre

COPY --from=javascript-runtime /usr/local/bin/node /usr/local/bin/node

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl ffmpeg libatomic1 python3 python3-pip \
    && pip3 install --break-system-packages --no-cache-dir "yt-dlp[default]==2026.10.26" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system hub \
    && useradd --system --gid hub --home-dir /app --no-create-home hub \
    && mkdir -p /app /data/database /data/users /var/tmp/hub/video-downloads \
    && chown -R hub:hub /app /data /var/tmp/hub

WORKDIR /app
COPY --from=build --chown=hub:hub /workspace/target/*.jar /app/hub.jar

USER hub
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/hub.jar"]
