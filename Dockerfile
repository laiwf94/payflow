# syntax=docker/dockerfile:1
FROM eclipse-temurin:25.0.4_7-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar \
 && java -Djarmode=tools -jar build/libs/payflow-0.1.0.jar extract --layers --launcher --destination /app

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fc6f4aec634ee433ff16a5d18814181e7928f4be9f434b44b48231e2eb7becee
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
EXPOSE 8080 8081
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
