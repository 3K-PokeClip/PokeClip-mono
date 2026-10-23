# 빌드 컨텍스트는 services/ 다 (Gradle 루트). 저장소 루트에서 빌드하면
# settings.gradle을 못 찾는다.
#   docker build -f services/clip/Dockerfile -t pokeclip-clip services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :clip:bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/clip/build/libs/*.jar app.jar
# RDS 통합 CA 번들. POSTGRES_AUTH=iam이면 POSTGRES_SSLROOTCERT가 이 경로를 가리킨다(verify-full, POK-407). 경로는 media 사이드카 이미지와 같다.
COPY --from=build --chmod=0644 /app/db-support/certs/rds-global-bundle.pem /etc/pokeclip/rds-ca/global-bundle.pem
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
