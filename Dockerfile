FROM eclipse-temurin:17-jdk AS build

COPY . /src
WORKDIR /src
RUN ./gradlew :komf-app:clean :komf-app:shadowjar

FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y pipx \
    && rm -rf /var/lib/apt/lists/*

RUN pipx install --include-deps pipx \
    && /root/.local/bin/pipx install --global --include-deps apprise

WORKDIR /app
COPY --from=build /src/komf-app/build/libs/komf-app-1.0.0-SNAPSHOT-all.jar ./
ENV LC_ALL=en_US.UTF-8
ENV KOMF_CONFIG_DIR="/config"
ENTRYPOINT ["java","-jar", "komf-app-1.0.0-SNAPSHOT-all.jar"]
EXPOSE 8085

LABEL org.opencontainers.image.url=https://github.com/schen31/komf org.opencontainers.image.source=https://github.com/schen31/komf
