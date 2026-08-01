# 빌드 컨텍스트는 services/ 다 (Gradle 루트). 저장소 루트에서 빌드하면
# settings.gradle을 못 찾는다.
#   docker build -f services/core/Dockerfile -t pokeclip-core services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :core:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/core/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
