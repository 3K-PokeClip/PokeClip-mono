# obs-plugin — PokeClip for OBS

**담당: 1번 (`@xodbs1021`) · A1 착수·구현: 2번** · 티켓 [POK-169](https://xodbs9810.atlassian.net/browse/POK-169) (에픽 [POK-5](https://xodbs9810.atlassian.net/browse/POK-5))

스트리머 PC의 OBS에 설치하는 C++ 플러그인(libobs)이다. 본방(치지직 RTMP)을 켜면 **같은 인코더로** 우리 서버에 SRT+MPEG-TS를 함께 보낸다 (ADR-001·ADR-002).

## 지금 되는 것 (A1)

| 기능 | 내용 |
|---|---|
| 동시 송출 | 본방 스트리밍 시작(`STREAMING_STARTING`)에 맞춰 `ffmpeg_mpegts_muxer` + `rtmp_custom`(srt://) 출력을 띄우고, 본방이 멈추면 같이 멈춘다 |
| 인코더 공유 | 본방 출력의 비디오·오디오 인코더를 그대로 붙인다 — **추가 인코딩 0** |
| GOP 2s 강제 | 시작 직전 방송 비디오 인코더 `keyint_sec`를 2로 바꾸고 `x264opts`/`opts`의 keyint 계열 토큰을 걷는다 (ADR-020) |
| 페어링 | 독에 8자리 코드 → `POST /api/stream-keys/pairing-codes/exchange` → streamid·passphrase 저장 (ADR-019) |
| 브라우저 독 | obs-browser(CEF) 패널에 `ui/` 페이지. 상태·전송 시간·비트레이트·송출 점검·연결 해제 확인·고급 설정. 화면은 claude.ai/design 시안 「PokeClip Plugin UI」 기준이고, web 디자인 토큰·브랜드 심볼을 그대로 쓴다 |
| Qt 폴백 | obs-browser가 없거나(일부 Linux·Wayland) 페이지가 붙지 않으면 최소 Qt 패널 — 기능은 같다 |
| 재연결 표시 | 중간 단절은 libobs 기본 재연결(20회·2초)에 맡기고 독에 「재연결 중」을 띄운다 |

**아직 안 되는 것** — 멀티오디오(A2), 초기 접속 실패 재시도(A5), 핫키 마킹·종료 신호(A3·A4), SRT RTT 통계(A6), 설치 파일·서명(A7). 방송 중 1080p가 아니면 **경고만** 한다(M1 예외).

## 구조

```
obs-plugin/
  src/                C++ — plugin-main(이벤트·독 등록) · stream-target(출력 수명) · gop-guard · pairing
                      · bridge-server(루프백 HTTP) · dock-host(CEF/폴백) · app-state(단일 상태) · config
  ui/                 브라우저 독 페이지 (Vite + Preact + TS). 빌드 산출물 → data/ui (gitignore)
  data/locale         en-US · ko-KR (폴백 패널·독 제목)
  tests/              OBS 없이 도는 C++ 테스트 (독립 CMake 프로젝트)
  third_party/        cpp-httplib 0.56.0 · obs-browser panel/browser-panel.hpp (OBS 32.2.1 서브모듈 3f0a2cd)
  cmake/ .github/     obs-plugintemplate 모듈·빌드 스크립트 (obs-multi-rtmp 0.7.4.3 판)
  cmake/local/        macOS 개발 빌드 — 설치된 OBS.app에 링크 (Xcode 불필요)
```

```
OBS 본방 시작 ─ STREAMING_STARTING ─▶ GopGuard(keyint 2) ─▶ StreamTarget.Start
                                                            ├ 본방 인코더 공유
                                                            └ srt://ingest?latency&pkt_size  (key=streamid, password=passphrase)
독 페이지(CEF) ⇄ http://127.0.0.1:<임의 포트>  (Bearer 토큰 · Host/Origin 검사 · SSE /api/events)
```

베이스는 [sorayuki/obs-multi-rtmp](https://github.com/sorayuki/obs-multi-rtmp) 0.7.4.3이다. 범용 다중 타깃 UI(edit-widget·obs-properties-widget·nlohmann-json·NSIS·Flatpak)는 걷고, 출력 생성·인코더 참조·해제 순서만 이식했다. 이식한 파일 머리에 원저작자 고지를 남겼다.

## 빌드

### 1. 독 페이지 (모든 빌드의 선행)

```bash
cd obs-plugin/ui
pnpm install
pnpm test        # 포맷터·SSE 파서
pnpm build       # tsc + vite build → ../data/ui
```

`pnpm dev` 후 `http://127.0.0.1:5178/dev/preview.html` 에서 목 브리지 시나리오 9종(미연결·대기·전송·재연결·오류·인코더 점유·키 없음·라이트 테마)을 독 폭으로 나란히 본다.

### 2. 배포 빌드 — Windows · macOS (CI)

`.github/workflows/obs-plugin-ci.yml`이 obs-plugintemplate 경로로 빌드한다. `obs-plugin/**`를 건드린 PR과 브랜치 push에서 돈다.

| 잡 | 산출물 |
|---|---|
| `windows` | `pokeclip-obs-0.2.0-windows-x64.zip` — `obs-plugins/64bit/pokeclip-obs.dll` + `data/obs-plugins/pokeclip-obs/{locale,ui}` |
| `macos` | `pokeclip-obs-0.2.0-macos-universal.*` (미서명) |

로컬에서 템플릿 경로를 쓰려면 Windows는 Visual Studio 2022 + `cmake --preset windows-x64`, macOS는 **Xcode 16+** + `cmake --preset macos`다 (buildspec.json이 OBS 32.2.1 소스·obs-deps·Qt6를 `.deps/`에 받는다).

### 3. macOS 개발 빌드 — Xcode 없이 (OBS.app 링크)

```bash
brew install cmake ninja qtbase simde
git clone --depth 1 --branch 32.2.1 https://github.com/obsproject/obs-studio ~/src/obs-studio   # 헤더만 쓴다
export OBS_SOURCE_DIR=~/src/obs-studio
cd obs-plugin
cmake --preset macos-local
cmake --build --preset macos-local --target install-local   # ~/Library/Application Support/obs-studio/plugins/pokeclip-obs.plugin
```

- 설치된 **OBS.app과 Homebrew qtbase의 부 버전이 같아야 한다** (OBS 32.2.2 = Qt 6.11.1, brew 6.11.2 → 확인됨). 빌드 후 `cmake/local/relink-qt-to-obs.sh`가 Qt 링크를 `@rpath`(OBS.app 안의 Qt)로 바꾼다 — 안 바꾸면 Qt가 두 벌 로드돼 죽는다.
- 이 프리셋은 `POKECLIP_DEV_LOG_DOCK_URL`을 켠다: OBS 로그에 `[dev] dock url: http://127.0.0.1:…/#token=…`가 찍혀 **브라우저에서 같은 독 페이지를 열어** 볼 수 있다. 배포 빌드에는 없다.

### 4. 테스트 (OBS 불필요)

```bash
cmake -S obs-plugin/tests -B obs-plugin/build_tests && cmake --build obs-plugin/build_tests && ctest --test-dir obs-plugin/build_tests --output-on-failure
```

## 설치

| OS | 위치 |
|---|---|
| Windows | zip을 `C:\Program Files\obs-studio\`에 풀면 `obs-plugins\64bit\pokeclip-obs.dll` + `data\obs-plugins\pokeclip-obs\` 가 된다 |
| macOS | `pokeclip-obs.plugin`을 `~/Library/Application Support/obs-studio/plugins/`에 둔다 |

처음 실행하면 **PokeClip 독이 한 번 펼쳐진다** (새 플러그인 독은 OBS가 숨긴 채 등록하므로). 이후 위치는 OBS가 기억한다. 메뉴 「도킹 › PokeClip」.

## 설정 파일

`plugin_config/pokeclip-obs/pokeclip.json` (macOS `~/Library/Application Support/obs-studio/`, Windows `%APPDATA%\obs-studio\`) — 계정 단위라 OBS 프로필과 무관하다. POSIX에서는 권한 0600으로 쓴다.

| 키 | 기본값 | 뜻 |
|---|---|---|
| `api_base` | `http://dev.pokeclip.com` | 페어링 교환 API. 운영 HTTPS 도메인이 정해지면 바꾼다 (로컬 auth 직결: `http://localhost:8082`) |
| `ingest_host` · `ingest_port` | `ingest.pokeclip.com` · `8890` | SRT 수신부 (ADR-020 3절) |
| `streamid` · `passphrase` | — | 페어링이 채운다. **passphrase는 로그·브리지 응답에 싣지 않는다** |
| `send_passphrase` | `true` | 로컬 compose MediaMTX는 passphrase 미설정이라 `false`여야 붙는다 (보내면 `REJ_BADSECRET`) |
| `latency_ms` | `1000` | SRT latency. URL에는 마이크로초로 들어간다 |
| `sync_start` | `true` | 본방 시작 시 함께 전송 |
| `force_fallback` | `false` | 브라우저 독 대신 Qt 폴백 패널 |
| `dock_intro_shown` | `false` | 첫 실행 독 펼치기 여부 (내부용) |

독 「고급 설정」에서는 수신 호스트·포트·SRT 지연과 스위치 셋(본방 동기·암호 사용·기본 패널)만 바꾼다 (송출 중에는 잠김). `api_base`는 스트리머가 바꿀 값이 아니라 화면에 없다 — 개발 중에는 설정 파일이나 `PUT /api/config`로 바꾼다.

## 브리지 프로토콜 (독 페이지 ↔ 플러그인)

`127.0.0.1:<임의 포트>`. 토큰(64 hex)은 독 URL 조각 `#token=`으로만 넘기고 페이지가 읽은 즉시 주소창에서 지운다.

| 경로 | 인증 | 내용 |
|---|---|---|
| `GET /` `/assets/*` | Host 검사 | `data/ui` 정적 파일. CSP `default-src 'self'` |
| `GET /api/hello` | Bearer | 버전·현재 상태. 첫 호출이 독 워치독을 해제한다 |
| `GET /api/events` | Bearer | SSE `event: state` (상태가 바뀔 때, 15초 keep-alive). EventSource는 헤더를 못 붙여 페이지는 fetch 스트리밍으로 읽는다 |
| `POST /api/pair` `{code}` | Bearer | 200 / 400 invalid_format / 404 not_found / 410 expired / 409 already_used·streaming / 429 rate_limited / 502 network·server_error |
| `POST /api/unpair` | Bearer | 송출 중이면 409 |
| `GET·PUT /api/config` | Bearer | 위 설정 표의 비밀 아닌 키 |

Host가 `127.0.0.1:<포트>`가 아니거나 `Origin`이 다른 오리진이면 403, 토큰이 없거나 틀리면 401, 본문 16KB 초과 413.

## 로컬 검증 (치지직 없이)

```
OBS 본방  → rtmp://127.0.0.1:1935  key=main          (compose MediaMTX RTMP — 치지직 대역)
플러그인  → srt://127.0.0.1:8890   #!::r=<token>,m=publish  (같은 MediaMTX SRT)
```

1. `docker compose up -d postgres redis media` · auth `./gradlew :auth:bootRun` · web `pnpm dev`
2. `pokeclip.json`에 `api_base=http://localhost:8082`, `ingest_host=127.0.0.1`, `send_passphrase=false`
3. 웹 설정 › 플러그인에서 코드 발급 → 독에 입력
4. OBS 방송 시작 → OBS 로그 `[pokeclip-obs] stream encoder … keyint_sec 0 -> 2` · `SRT output started`
5. 녹화 조각의 키프레임: `docker cp pokeclip-media-1:/recordings/<token> ./rec` 후
   `ffprobe -v error -select_streams v:0 -skip_frame nokey -show_entries frame=pts_time -of csv=p=0 <조각>.mp4` → 조각마다 `0.07 · 2.07` 두 개

**2026-09-13 macOS 실측 (OBS 32.2.2, x264 1080p30, 로컬 compose)** — 결과는 PR 본문에 원문으로 남긴다.

## 문제 해결 (독 사유 코드)

| 코드 | 뜻 · 조치 |
|---|---|
| `no_key` | 페어링 안 됨 — 본방만 나간다 |
| `encoder_active` | 녹화가 방송 인코더를 이미 쓰는 중 — x264는 실행 중 GOP를 못 바꾼다. 녹화를 멈추거나 녹화 인코더 분리 |
| `multitrack_video` | 설정 › 방송의 멀티트랙 비디오를 끈다 |
| `bad_path` | 수신 서버가 passphrase(또는 주소)를 거절 — 로컬 compose면 `send_passphrase=false` |
| `connect_failed` · `timeout` | 서버 거절 · 무응답 — 호스트·포트·방화벽(UDP) |
| `disconnected` | 재연결 20회 소진 — 본방을 다시 시작 (A5에서 자동화) |
| `main_stream_failed` | 본방 출력이 뜨지 않아 같이 멈춤 |

## 라이선스

GPL-2.0 (`LICENSE`). 서드파티 고지는 `THIRD_PARTY_NOTICES.md`.
