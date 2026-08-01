# 빌드 컨텍스트는 services/ 다 (Gradle 루트).
#   docker build -f services/chat-collector/Dockerfile -t pokeclip-chat-collector services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :chat-collector:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/chat-collector/build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
