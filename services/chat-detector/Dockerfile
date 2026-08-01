# 빌드 컨텍스트는 services/ 다 (Gradle 루트).
#   docker build -f services/chat-detector/Dockerfile -t pokeclip-chat-detector services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :chat-detector:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/chat-detector/build/libs/*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
