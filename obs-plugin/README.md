# obs-plugin — PokeClip for OBS

**담당: 1번 (`@xodbs1021`) · A1 착수·구현: 2번** · 티켓 [POK-169](https://xodbs9810.atlassian.net/browse/POK-169) (에픽 [POK-5](https://xodbs9810.atlassian.net/browse/POK-5))

스트리머 PC의 OBS에 설치하는 C++ 플러그인(libobs)이다. 본방(치지직 RTMP)을 켜면 **같은 인코더로** 우리 서버에 SRT+MPEG-TS를 함께 보낸다 (ADR-001·ADR-002).

## 지금 되는 것 (A1·A2)

| 기능 | 내용 |
|---|---|
| 동시 송출 | 본방 스트리밍 시작(`STREAMING_STARTING`)에 맞춰 `ffmpeg_mpegts_muxer` + `rtmp_custom`(srt://) 출력을 띄우고, 본방이 멈추면 같이 멈춘다 |
| 인코더 공유 | 본방 출력의 비디오 인코더와 오디오 트랙 1 인코더를 그대로 붙인다 — **추가 영상 인코딩 0** |
| 오디오 6트랙 (A2) | 트랙 1 = 최종 믹스(본방 인코더 공유), 트랙 2~6 = 소스별 스템(플러그인이 AAC 128 kbps 인코더 5개를 만든다). 소스가 없는 트랙은 무음이 계속 나간다 (ADR-017) |
| 소스 자동 배정 (A2) | 소리 나는 소스를 트랙 2~6에 하나씩 자동으로 앉히고 설정 파일에 기억한다 — 아래 「오디오 트랙」 |
| GOP 2s 강제 | 시작 직전 방송 비디오 인코더 `keyint_sec`를 2로 바꾸고 `x264opts`/`opts`의 keyint 계열 토큰을 걷는다 (ADR-020) |
| 페어링 | 독에 8자리 코드 → `POST /api/stream-keys/pairing-codes/exchange` → streamid·passphrase 저장 (ADR-019) |
| 브라우저 독 | obs-browser(CEF) 패널에 `ui/` 페이지. 상태·전송 시간·비트레이트·송출 점검·연결 해제 확인·고급 설정. 화면은 claude.ai/design 시안 「PokeClip Plugin UI」 기준이고, web 디자인 토큰·브랜드 심볼을 그대로 쓴다 |
| Qt 폴백 | obs-browser가 없거나(일부 Linux·Wayland) 페이지가 붙지 않으면 최소 Qt 패널 — 기능은 같다 |
| 재연결 표시 | 중간 단절은 libobs 기본 재연결(20회·2초)에 맡기고 독에 「재연결 중」을 띄운다 |

**아직 안 되는 것** — 트랙 배정을 독에서 손으로 고치기(후속), 초기 접속 실패 재시도(A5), 핫키 마킹·종료 신호(A3·A4), SRT RTT 통계(A6), 설치 파일·서명(A7). 방송 중 1080p가 아니면 **경고만** 한다(M1 예외).

## 구조

```
obs-plugin/
  src/                C++ — plugin-main(이벤트·독 등록) · stream-target(출력 수명·오디오 6트랙) · gop-guard · pairing
                      · audio-assign(배정 규칙, OBS 무관) · audio-router(소스 열거·트랙 비트·기억)
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
                                                            ├ 본방 비디오 인코더 · 트랙 1 오디오 공유
                                                            ├ 트랙 2~6 AAC 5개 (pokeclip-audio-2..6)
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
| `sync_start` | `true` | **본 방송과 송출 동기화** — 본방 시작·정지에 맞춰 함께 전송·정지한다. 본방이 잠시 끊겨 재연결 중일 땐 우리 송출을 유지한다(녹화 구멍 방지). 본방 송출 중에 켜면 그 자리에서 시작한다(이미 돌던 인코더는 keyint를 못 바꿔 `encoder_active`로 거절 — 본방을 다시 켠다) |
| `force_fallback` | `false` | 브라우저 독 대신 Qt 폴백 패널 |
| `dock_intro_shown` | `false` | 첫 실행 독 펼치기 여부 (내부용) |
| `audio_auto_assign` | `true` | 오디오 소스를 트랙 2~6에 자동 배정. 끄면 OBS 고급 오디오 설정의 트랙 체크를 그대로 보낸다 |
| `audio_track_map` | — | 소스별로 기억한 트랙(내부용, 최대 64개). 배정을 처음부터 다시 하려면 OBS를 끄고 이 키를 지운다 |

독 「고급 설정」에서는 수신 호스트·포트·SRT 지연과 스위치 넷(본방 동기·오디오 자동 배정·암호 사용·기본 패널)만 바꾼다 (송출 중에는 잠김). `api_base`는 스트리머가 바꿀 값이 아니라 화면에 없다 — 개발 중에는 설정 파일이나 `PUT /api/config`로 바꾼다.

## 오디오 트랙 (A2)

OBS 트랙 1~6이 그대로 서버의 6트랙이 된다 (ADR-017, 계약9 `audio1`~`audio6`, 편집기 `trackId` 0~5).

| OBS 트랙 | 믹서 | 내용 | 인코더 |
|---|---|---|---|
| 1 | 0 | 최종 믹스(시청용) — 본방과 같은 소리 | 본방 오디오 인코더 공유. 본방이 AAC가 아니거나 트랙 1이 아니면 우리가 AAC를 하나 더 만든다 |
| 2~6 | 1~5 | 소스별 스템(편집용) | `pokeclip-audio-2..6` AAC 128 kbps. 방송이 멈추면 놓는다 |

독 비트레이트는 출력 전체라 스템 5개 몫(약 640 kbps)이 더해진 값이다.

**자동 배정 규칙** (`src/audio-assign.cpp`, 페어링된 OBS에서만 동작)

1. **후보** = 실제로 소리를 내는 소스(OBS 오디오 믹서에 보이는 것). 오디오를 넘기지 않는 브라우저 소스(채팅창 등)는 자리를 안 차지한다. 모니터 전용은 믹스에 안 들어가 제외하고 독에 표시만 한다.
2. **한 트랙에 한 소스.** 트랙 1 체크는 건드리지 않는다.
3. **기억 먼저** — 설정 파일(`audio_track_map`)에 기억한 트랙을 먼저 돌려준다. 방송이 바뀌어도 같은 소스는 같은 트랙이다. 설정›오디오의 전역 장치(데스크탑·마이크)는 채널 번호로 기억해, 장치를 끄고 켜도 트랙이 유지된다.
4. **새 소스는 우선순위대로 가장 낮은 빈 트랙에** — 마이크 → 데스크탑 → 앱·게임 → 미디어 → 브라우저 → 기타. 우선순위는 새 소스를 넣을 때만 쓰고, 이미 앉은 배정을 재정렬하지 않는다.
5. **지금 없는 소스의 기억은 자리를 잡지 않는다** — 소스를 지우면 그 트랙이 비어 다음 새 소스가 쓴다. 기억은 남아서, 두 소스가 같은 트랙을 기억하면 먼저 앉은 쪽이 이긴다.
6. **자리가 모자라면**(소리 나는 소스 6개 이상) 우선순위가 낮은 쪽이 믹스 전용이 되고 독에 경고가 뜬다.
7. **방송·녹화 중에는** 지금 트랙에서 나가고 있는 소스를 옮기지 않는다. 새 소스만 빈 트랙에 들어간다(트랙 체크만 바뀌고 인코더 설정은 그대로라 송출에 영향 없음).
8. 자동 배정이 켜져 있으면 트랙 2~6 체크는 플러그인이 맡는다 — OBS 고급 오디오 속성에서 바꿔도 되돌아간다. 손으로 정하려면 독 「고급 설정」에서 끈다.

🔴 **스트리머의 녹화 트랙도 같은 체크를 쓴다.** 고급 출력 녹화에서 트랙 2~6을 쓰면 녹화 내용도 이 배정을 따른다. 트위치 VOD 트랙(트랙 2)을 쓰는 스트리머는 자동 배정을 끈다.

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
6. 오디오 6트랙: `ffprobe -v error -select_streams a -show_entries stream=index,codec_name,sample_rate,channels -of csv=p=0 <조각>.mp4` → `aac` 6줄.
   `curl -s http://127.0.0.1:8888/<token>/index.m3u8 | grep -c EXT-X-MEDIA` → 6
7. 스템 분리: 미디어 소스로 테스트 톤(`ffmpeg -f lavfi -i "sine=frequency=1000:duration=300" sine.wav`)을 넣고 독에서 트랙 번호를 본 뒤,
   `for i in 0 1 2 3 4 5; do ffmpeg -v error -i <조각>.mp4 -map 0:a:$i -af volumedetect -f null - 2>&1 | grep mean_volume; done`
   → 톤 트랙과 `a:0`(믹스)만 소리가 있고 나머지는 무음(-91 dB)

**2026-09-13 macOS 실측 (OBS 32.2.2, x264 1080p30, 로컬 compose)** — 결과는 PR 본문에 원문으로 남긴다.

## 문제 해결 (독 사유 코드)

| 코드 | 뜻 · 조치 |
|---|---|
| `no_key` | 페어링 안 됨 — 본방만 나간다 |
| `encoder_active` | 방송 인코더가 이미 돌고 있어 GOP를 못 바꿈(x264는 실행 중 변경 불가) — 녹화가 방송 인코더를 공유하거나, 본방 송출 중에 동기화를 켠 경우. 녹화를 멈추거나 본방을 다시 켠다 |
| `multitrack_video` | 설정 › 방송의 멀티트랙 비디오를 끈다 |
| `bad_path` | 수신 서버가 passphrase(또는 주소)를 거절 — 로컬 compose면 `send_passphrase=false` |
| `connect_failed` · `timeout` | 서버 거절 · 무응답 — 호스트·포트·방화벽(UDP) |
| `disconnected` | 재연결 20회 소진 — 본방을 다시 시작 (A5에서 자동화) |
| `main_stream_failed` | 본방 출력이 뜨지 않아 같이 멈춤 |
| `output_no_multitrack` | 이 OBS의 MPEG-TS 출력이 다중 오디오 트랙을 받지 않는다 — OBS 버전 확인(32.x 기준) |
| `audio_encoder_failed` | AAC 인코더를 만들지 못함 — OBS 로그의 인코더 id 확인 (`ffmpeg_aac`가 기본 대체) |
| `audio_track_attach_failed` | 6트랙 중 일부가 출력에 안 붙음 — 반쯤 붙은 채 보내지 않고 시작을 멈춘다 |

## 라이선스

GPL-2.0 (`LICENSE`). 서드파티 고지는 `THIRD_PARTY_NOTICES.md`.
