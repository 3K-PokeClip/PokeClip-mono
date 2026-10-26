# 빌드 컨텍스트는 services/ 다 (Gradle 루트).
#   docker build -f services/chat-collector/Dockerfile -t pokeclip-chat-collector services/
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :chat-collector:bootJar --no-daemon

# 실행 단계는 Ubuntu 24.04(noble)에 고정한다. 꼬리표 없는 21-jre가 26.04로 넘어가자 Playwright 1.52가 그 판을 몰라 브라우저 설치가 실패했다(로컬 빌드 실측)
FROM eclipse-temurin:21-jre-noble
# 비루트 계정으로 돈다(POK-486). 번호는 저장소의 다른 이미지(10001–10004)와 겹치지 않게 서버마다 따로 둔다.
# USER 는 숫자로 적는다 — ECS 가 이름을 풀지 않고도 비루트임을 확인한다. jar · CA 번들은 root 소유 그대로 읽기만 한다.
RUN groupadd --system --gid 10007 chat-collector \
 && useradd --system --uid 10007 --gid 10007 --create-home chat-collector
WORKDIR /app
COPY --from=build /app/chat-collector/build/libs/*.jar app.jar
# --chmod 는 COPY 가 새로 만드는 부모 디렉터리에도 0644(실행 비트 없음)를 줘 비루트가 번들에 못 닿는다(POK-486 실측).
# 그래서 디렉터리를 먼저 만든다(0755). media/Dockerfile 과 같은 방법이다.
RUN mkdir -p /etc/pokeclip/rds-ca
# RDS 통합 CA 번들. POSTGRES_AUTH=iam이면 POSTGRES_SSLROOTCERT가 이 경로를 가리킨다(verify-full, POK-407). 경로는 media 사이드카 이미지와 같다.
COPY --from=build --chmod=0644 /app/db-support/certs/rds-global-bundle.pem /etc/pokeclip/rds-ca/global-bundle.pem
# SOOP 채팅(POK-397 PR4)은 화면 없는 Chromium에서 공식 Chat SDK를 돌린다(ADR-105). 브라우저와 그 시스템 꾸러미를 여기서 굽고,
# 실행 중에는 내려받지 않는다(Playwright 기본은 실행 중에 받으려 한다). 내려받는 도구는 앱 jar 안의 Playwright CLI다.
# --only-shell: 화면 없는 실행 파일만(전체 Chromium보다 작다). 꺼져 있으면(SOOP_CHAT_ENABLED=false) 브라우저는 안 뜬다.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
        install --with-deps --only-shell chromium \
 && rm -rf /var/lib/apt/lists/*
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
# 브라우저 설치(apt 가 root 를 요구한다) 뒤에 바꾼다. /ms-playwright 는 root 소유 0755 라 이 계정이 읽고 실행만 한다.
USER 10007:10007
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
