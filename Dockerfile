# --- Build stage: compile the Spring Boot fat jar with the Gradle wrapper ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy build scripts and wrapper first so Docker can cache dependency resolution.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew

COPY src src
RUN ./gradlew --no-daemon bootJar

# --- Runtime stage: just the JRE + the jar ---
FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /app/build/libs/cloud-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# The "docker" profile points the shard URLs at the compose service names.
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "app.jar"]
