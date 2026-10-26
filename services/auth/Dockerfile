# 빌드 컨텍스트는 services/ 다 (Gradle 루트). 저장소 루트에서 빌드하면
# settings.gradle을 못 찾는다.
#   docker build -f services/auth/Dockerfile -t pokeclip-auth services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :auth:bootJar --no-daemon

FROM eclipse-temurin:21-jre
# 비루트 계정으로 돈다(POK-486). 번호는 저장소의 다른 이미지(10001–10004)와 겹치지 않게 서버마다 따로 둔다.
# USER 는 숫자로 적는다 — ECS 가 이름을 풀지 않고도 비루트임을 확인한다. jar · CA 번들은 root 소유 그대로 읽기만 한다.
RUN groupadd --system --gid 10005 auth \
 && useradd --system --uid 10005 --gid 10005 --create-home auth
WORKDIR /app
COPY --from=build /app/auth/build/libs/*.jar app.jar
# --chmod 는 COPY 가 새로 만드는 부모 디렉터리에도 0644(실행 비트 없음)를 줘 비루트가 번들에 못 닿는다(POK-486 실측).
# 그래서 디렉터리를 먼저 만든다(0755). media/Dockerfile 과 같은 방법이다.
RUN mkdir -p /etc/pokeclip/rds-ca
# RDS 통합 CA 번들. POSTGRES_AUTH=iam이면 POSTGRES_SSLROOTCERT가 이 경로를 가리킨다(verify-full, POK-407). 경로는 media 사이드카 이미지와 같다.
COPY --from=build --chmod=0644 /app/db-support/certs/rds-global-bundle.pem /etc/pokeclip/rds-ca/global-bundle.pem
USER 10005:10005
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
