# 렌더 일꾼 이미지. 빌드 컨텍스트는 이 폴더(workers/render)다.
#   docker build -t pokeclip-render workers/render
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
# ffmpeg: 배포판 꾸러미는 libass(번인 자막)·libx264·libsrt가 다 켜져 있다(홈브루 기본 빌드는 libass가 없다).
# fonts-noto-cjk: 번인 자막의 한글 글꼴. 없으면 libass가 대체 글꼴을 못 찾아 네모로 찍는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg fonts-noto-cjk \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10003 --create-home render
USER render
WORKDIR /app
COPY --from=build /src/build/libs/render-worker-0.0.1-SNAPSHOT.jar app.jar
ENV RENDER_WORK_DIR=/tmp/pokeclip-render
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
