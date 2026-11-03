# 렌더 일꾼 이미지. 빌드 컨텍스트는 이 폴더(workers/render)다.
#   docker build -t pokeclip-render workers/render
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

# ffmpeg 판을 고정한다(PDT 설계 7.7 (가)). 기반 이미지는 태그가 아니라 목록(manifest list) 다이제스트로 박는다.
# 목록 하나에 amd64(dev)·arm64(prod)가 다 든다. 21-jre 태그의 Ubuntu 26.04(resolute) 판, 2026-10-29 빌드.
# 아카이브가 아래 꾸러미 판을 내리면 빌드가 실패해 멈춘다(조용히 다른 판이 깔리지 않게).
FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
# 판 글자는 여기 한 곳이다. workers-ci render 잡과 README의 시험 명령이 이 줄을 읽는다.
# 판을 올리면 RenderImagePinTest의 판 글자도 고친다. 라이브러리 이름(so 판)이 바뀌면 아래 목록 ·
# workers-ci · README · RenderImagePinTest의 꾸러미 이름도 같이 고친다.
ARG FFMPEG_VERSION=7:8.0.1-3ubuntu2
# ffmpeg: 배포판 꾸러미는 libass(번인 자막)·libx264·libsrt가 다 켜져 있다(홈브루 기본 빌드는 libass가 없다).
#   ffmpeg는 같은 소스의 라이브러리 7개를 `>= 7:8.0.1` 하한으로만 문다. 같이 박지 않으면 수정판이 올라온 날
#   실행 파일만 고정 판이고 라이브러리는 새 판으로 섞여 깔린다.
#   설치 뒤 ffmpeg 소스 꾸러미 전부의 판을 다시 본다. 목록 밖 꾸러미가 다른 판으로 끌려와도 빌드가 멈춘다.
# fonts-noto-cjk: 번인 자막의 한글 글꼴. 없으면 libass가 대체 글꼴을 못 찾아 네모로 찍는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ffmpeg=${FFMPEG_VERSION} libavcodec62=${FFMPEG_VERSION} libavdevice62=${FFMPEG_VERSION} \
      libavfilter11=${FFMPEG_VERSION} libavformat62=${FFMPEG_VERSION} libavutil60=${FFMPEG_VERSION} \
      libswresample6=${FFMPEG_VERSION} libswscale9=${FFMPEG_VERSION} fonts-noto-cjk \
 && dpkg-query -W -f '${source:Package} ${Package} ${Version}\n' \
      | awk -v want="$FFMPEG_VERSION" '$1 == "ffmpeg" { n++ } $1 == "ffmpeg" && $3 != want { print "판이 다른 ffmpeg 꾸러미: " $2 " " $3; bad = 1 } END { exit bad || n == 0 }' \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10003 --create-home render
USER render
WORKDIR /app
COPY --from=build /src/build/libs/render-worker-0.0.1-SNAPSHOT.jar app.jar
ENV RENDER_WORK_DIR=/tmp/pokeclip-render
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
