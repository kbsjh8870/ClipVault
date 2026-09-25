# Build from the repo root (multi-project Gradle build):
#   docker build -f backend/Dockerfile -t clipvault-backend .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon :backend:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home app
COPY --from=build /src/backend/build/libs/backend-*-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
# Required env: DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET, CLIP_ENCRYPTION_KEY (optional: PORT)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
