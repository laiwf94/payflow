# syntax=docker/dockerfile:1
# The jar is the same on every CPU, so it is built once on the build machine's own platform; only
# the runtime stage, which runs nothing, differs between amd64 and arm64. No emulation needed.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25.0.4_7-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
# Empty for a local build, which then reports the version in build.gradle.kts.
ARG VERSION=
RUN ./gradlew --no-daemon -q bootJar ${VERSION:+-PreleaseVersion=$VERSION} \
 && java -Djarmode=tools -jar build/libs/payflow.jar extract --layers --launcher --destination /app

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fc6f4aec634ee433ff16a5d18814181e7928f4be9f434b44b48231e2eb7becee
ARG VERSION=
LABEL org.opencontainers.image.title=payflow \
      org.opencontainers.image.source=https://github.com/laiwf94/payflow \
      org.opencontainers.image.licenses=MIT \
      org.opencontainers.image.version=$VERSION
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
EXPOSE 8080 8081
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
