# 빌드 컨텍스트는 services/ 다 (Gradle 루트).
#   docker build -f services/chat-collector/Dockerfile -t pokeclip-chat-collector services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :chat-collector:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/chat-collector/build/libs/*.jar app.jar
# RDS 통합 CA 번들. POSTGRES_AUTH=iam이면 POSTGRES_SSLROOTCERT가 이 경로를 가리킨다(verify-full, POK-407).
COPY --from=build /app/db-support/certs/rds-global-bundle.pem /etc/pokeclip/rds-global-bundle.pem
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
