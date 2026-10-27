FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl ffmpeg nodejs python3 python3-pip \
    && pip3 install --break-system-packages --no-cache-dir "yt-dlp[default]" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system hub \
    && useradd --system --gid hub --home-dir /app --no-create-home hub \
    && mkdir -p /app /data \
    && chown -R hub:hub /app /data

WORKDIR /app
COPY --from=build --chown=hub:hub /workspace/target/*.jar /app/hub.jar

USER hub
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/hub.jar"]
