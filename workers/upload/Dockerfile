# 업로드 일꾼 이미지. 빌드 컨텍스트는 이 폴더(workers/upload)다.
#   docker build -t pokeclip-upload workers/upload
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10004 --create-home upload
USER upload
WORKDIR /app
COPY --from=build /src/build/libs/upload-worker-0.0.1-SNAPSHOT.jar app.jar
ENV UPLOAD_WORK_DIR=/tmp/pokeclip-upload
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
