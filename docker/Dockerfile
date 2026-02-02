# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew gradlew.bat build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S bidstream && adduser -S bidstream -G bidstream
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER bidstream
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
