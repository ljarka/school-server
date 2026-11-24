FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY model/build.gradle.kts model/
COPY journal/build.gradle.kts journal/
COPY server/build.gradle.kts server/

RUN ./gradlew --no-daemon dependencies || true

COPY model model
COPY journal journal
COPY server server

ARG APP_VERSION=dev
RUN ./gradlew :server:installDist --no-daemon -PappVersion=${APP_VERSION}

FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.title="School Server" \
      org.opencontainers.image.description="Grades and messages from a school e-journal, with a change feed"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && (userdel -r ubuntu 2>/dev/null || true) \
    && (groupdel ubuntu 2>/dev/null || true) \
    && groupadd -g 1000 school \
    && useradd -u 1000 -g school -m -s /bin/sh school \
    && mkdir -p /data \
    && chown -R school:school /data

COPY --from=build /src/server/build/install/server /app
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENV PORT=5100 \
    SCHOOL_DB_PATH=/data/school.db \
    TZ=Europe/Warsaw \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC"

VOLUME /data
WORKDIR /data
EXPOSE 5100

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
    CMD curl -fsS "http://127.0.0.1:${PORT:-5100}/health" || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["/app/bin/server"]
