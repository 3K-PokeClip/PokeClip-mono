# render: 렌더 일꾼 (POK-246)

**담당: 3번 (`@kth4778`)**. 2026-09-14에 1번에게서 넘어왔다.

**편집자가 「영상 만들기」를 누르면 clip이 주문서를 줄(SQS `jobs-render`)에 넣는다. 이 일꾼이 주문서를 꺼내
S3의 녹화 조각을 받아 이어 붙이고, 편집 기록(레시피)대로 자르고 세로·정사각으로 맞춰 mp4를 만들어 S3에 올린 뒤
clip에 「끝났다」를 보고한다.** 사람이 부르는 문이 없고, DB에도 붙지 않는다(`workers/README.md` 규칙).

```
clip ──주문서──▶ [SQS jobs-render] ──▶ 렌더 일꾼 ──▶ S3 clips/{clipId}/{executionToken}/{outputId}.mp4
  ▲                                      │
  └──── POST /internal/jobs/{jobId}/events (STARTED · PROGRESS · SUCCEEDED · …) ◀┘
```

규칙의 정본은 위키 [계약1](https://github.com/3K-PokeClip/PokeClip-LLM-WIKI/blob/main/contracts/%EA%B3%84%EC%95%BD1-%EC%9E%A1%EC%88%98%EB%AA%85%EC%A3%BC%EA%B8%B0.md)(주문서·보고)과
[계약6](https://github.com/3K-PokeClip/PokeClip-LLM-WIKI/blob/main/contracts/%EA%B3%84%EC%95%BD6-%EB%A0%88%EC%8B%9C%ED%94%BC%EC%8A%A4%ED%82%A4%EB%A7%88.md)(레시피)이다. clip 쪽 절반은 [`services/README.md`](../../services/README.md)의 「영상 만들기 주문」 절.

## 주문 하나가 지나가는 길

| 순서 | 무엇 | 어디 |
|---|---|---|
| 1 | 주문서를 읽는다. 봉투는 모르는 칸을 무시하고, **레시피는 모르는 칸 하나에도 거부**한다(fail-closed) | `job/EnvelopeParser` · `recipe/RecipeParser` |
| 2 | 여기서 떨어지면 토큰 없이 `TERMINAL_FAILED`(`SCHEMA_VERSION`·`ENVELOPE_VALIDATION`·`VALIDATION`) | `work/JobProcessor` |
| 3 | `STARTED` → clip이 `executionToken`을 준다. `proceed:false`면 일하지 않고 메시지를 지운다 | `report/ClipReporter` |
| 4 | 조각이 자를 구간을 다 덮는지(±100ms), 끊긴 데가 없는지 본다 → 아니면 `SOURCE_RANGE` | `work/ClipRenderer` |
| 5 | 조각을 받는다. 없으면(404) `SOURCE_EXPIRED` | `storage/S3Store` |
| 6 | 조각마다 ffprobe. 영상 길이가 장부와 ±100ms 안인지(`SOURCE_MISMATCH`), 고른 트랙이 있는지(`SOURCE_MISSING`) | `media/MediaProbe` |
| 7 | 조각을 **소리 끝에 맞춰** 한 파일로 잇는다(재인코딩 없음: 아래 「잇기」) | `media/RenderCommands.concat` |
| 8 | 고른 트랙을 섞은 소리의 크기를 잰다(-14 LUFS 두 번 돌기의 첫 번째) | `media/Loudness` |
| 9 | output마다: 자르기 → crop → 1080 기준 scale → 자막 번인 → 섞기 → 평준화 → H.264/AAC | `media/RenderCommands.render` |
| 10 | `clips/{clipId}/{executionToken}/` 아래에 올리고 `SUCCEEDED` → 메시지를 지운다 | `work/JobProcessor` |

**보고의 답에 따라 메시지를 다르게 다룬다**(계약1 4절 「워커 거동」):

| 답 | 메시지 |
|---|---|
| 200(종결) · 404(모르는 잡) · 409 `TERMINAL`/`CANCELLED` · `proceed:false` | 지운다 |
| 409 `SUPERSEDED`(내 실행이 무효가 됐다: 새 실행이 도는 중) | **손대지 않는다.** 지우면 살아 있는 실행의 안전망이 사라진다 |
| 400 `INVALID_RESULT` | 같은 토큰으로 `TERMINAL_FAILED(RESULT_VALIDATION)` 뒤 지운다 |
| 5xx · 시한 초과 · 연결 오류 | 같은 `eventId`로 5s·15s·45s 다시 보낸다. 끝내 안 되면 메시지를 둔다(다시 받으면 clip 판정이 이어진다) |

**실패는 둘로 갈린다.** 레시피·조각이 틀린 것(`VALIDATION`·`SOURCE_*`)은 다시 해도 같으니 바로 `TERMINAL_FAILED`.
S3 일시 오류·ffmpeg 급사·시한 초과는 `RETRY_SCHEDULED` + 숨김 시간 60~120초(절반 고정·절반 무작위) 뒤 다시 받는다.
clip이 `isFinalAttempt:true`(세 번째)를 준 실행의 실패는 다시 하지 않고 종결한다.

**오래 걸리는 주문**: 숨김 시간(600초)이 2분 남은 때부터 1분마다 2분으로 다시 잡는다. 한 주문의 상한은 `RENDER_JOB_TIMEOUT`(15분)이고,
넘기면 ffmpeg를 끊고 일시 실패로 다룬다. 진행 보고는 5초에 한 번까지다.

**한 일꾼은 한 번에 한 주문만** 한다. 렌더는 CPU를 다 쓰는 일이라 겹치면 둘 다 느려진다. 늘리려면 일꾼 수를 늘린다.

## 잇기: 조각 경계에 빈틈이 생기지 않게 (실측 2026-09-24)

MediaMTX 조각은 **조각마다 머리(`ftyp+moov`)가 있는 완전한 fMP4**라 ffmpeg concat demuxer로 재인코딩 없이 잇는다.
그런데 그냥 이으면 **조각 경계마다 0.05~0.15초 빈틈이 생기고 쌓인다**(조각 셋에 영상 86·150ms: 3분 클립이면 조각 45개라 2초 넘게 밀린다).

- 원인: demuxer는 다음 조각을 「앞 파일의 길이(가장 늦게 끝나는 트랙)」만큼 민다. MediaMTX 조각은 파일 머리가 소리 시작이고,
  영상은 수십 ms 뒤에 시작하며 마지막 프레임 길이도 부풀어 있다.
- 처방: 목록에 조각마다 `duration`을 적는다. **앞 조각 소리가 끝난 자리에 다음 조각 소리 시작을 맞춘다**(소리는 AAC 프레임 단위라 길이가 정확하다).
  그렇게 이으면 영상도 30fps 격자에 정확히 맞는다(360프레임, 빈틈 0).
- 시험: `ClipRendererTest.이은_원본에_조각_경계_빈틈이_없다`. 처방을 빼면 이 시험이 빨간불이다(확인함).

## 정한 것 (레시피에 없는 렌더 상수)

| 항목 | 값 | 근거 |
|---|---|---|
| 출력 해상도 | `VERT_9_16` 1080×1920 · `SQUARE_1_1` 1080×1080 | 계약6 「출력 해상도는 렌더 내부 결정」 |
| 영상 | H.264 `veryfast` CRF 20, yuv420p, `+faststart` | 웹에서 바로 재생·유튜브 업로드 |
| 소리 | 고른 트랙만 섞음(나누지 않음) → 48kHz 스테레오 → **-14 LUFS 두 번 돌기**(재고 선형 보정) · AAC 160k | 계약6 0절. 한 번에 하면 동적 압축으로 소리가 출렁인다. 무음이면 평준화를 건너뛴다 |
| crop | 표시 평면 픽셀로 옮겨 ±1% 안이면 **중심을 지키며 줄여** 흡수, 넘으면 `VALIDATION` · 짝수 픽셀 | 계약6 2절 |
| 자막 | 컷 밖은 무시, 경계에 걸치면 자름 · 번인 = `Noto Sans CJK KR` 13(기준 높이 288) 아래 여백 60 · srt는 컷 안 자막이 있을 때만 output마다 하나 | 계약6 2절 · 계약1 3절(빈 파일 금지) |
| 트랙 번호 | 트랙 N = N번째 소리 스트림(0 = 최종 믹스) | 계약9 2026-09-03 트랙 고정 |

## 환경변수

| 이름 | 기본값 | 뜻 |
|---|---|---|
| `RENDER_QUEUE_URL` | (빈 값) | 주문 줄 주소. **비면 줄을 안 본다**(부품만 띄울 때) |
| `RENDER_QUEUE_ENDPOINT` | (빈 값) | 가짜 SQS(LocalStack) 주소 |
| `S3_ENDPOINT` · `S3_PATH_STYLE` | (빈 값) · `false` | 가짜 S3 주소 · 경로식 주소 |
| `AWS_REGION` | `ap-northeast-2` | 자격증명은 SDK 기본 체인(env·프로필·역할) |
| `CLIP_BASE_URL` | `http://localhost:8081` | 보고를 받는 clip |
| `INTERNAL_API_TOKEN` | (빈 값) | clip `/internal/**`의 열쇠. auth·clip·chat-collector와 같은 값 |
| `RENDER_WORK_DIR` | `/tmp/pokeclip-render` | 주문마다 임시 폴더. 끝나면 지운다 |
| `FFMPEG_PATH` · `FFPROBE_PATH` | `ffmpeg` · `ffprobe` | 실행 파일 |
| `RENDER_JOB_TIMEOUT` | `15m` | 주문 하나의 상한 |
| `RENDER_VISIBILITY_TIMEOUT` | `600s` | **큐 설정과 같아야 한다**. 이 값에서 2분 전부터 늘린다 |
| `RENDER_FONTS_DIR` | (빈 값) | 번인 글꼴 폴더. 비면 시스템 글꼴(이미지에 `fonts-noto-cjk`) |

## 로컬에서 돌리기

**시험**: `ffmpeg`가 있으면 실물 조각으로 렌더까지 돈다. LocalStack 시험은 도커가 있어야 한다.

```bash
cd workers/render && ./gradlew test
```

- `src/test/resources/segments/`는 **로컬 MediaMTX에 실제로 송출해 받은 녹화 조각 셋**이다(영상 640×360 30fps + 소리 6트랙).
- 🔴 **홈브루 ffmpeg는 libass가 없어 자막 번인을 못 한다.** 그 기계에서는 번인 시험이 CC만 켜고 돈다.
  번인까지 재려면 배포 이미지와 같은 ffmpeg로 돌린다:

```bash
docker run --rm -v "$PWD":/src -w /src eclipse-temurin:21-jdk bash -c 'apt-get update -qq && apt-get install -y -qq ffmpeg fonts-noto-cjk >/dev/null && ./gradlew -q test --tests "*ClipRendererTest"'
```

**이미지**: 빌드 컨텍스트는 이 폴더다. ffmpeg(libass·libx264)와 한글 글꼴이 들어간다.

```bash
docker build -t pokeclip-render workers/render
```

**실물 조각으로 mp4 하나 만들기(종단)**: 2026-09-24에 이렇게 확인했다.

1. LocalStack에 SQS·S3를 켜고 `jobs-render`(+DLQ, 숨김 600초)와 버킷 둘(조각·완성)을 만든다.
2. clip을 `RENDER_ENABLED=true RENDER_QUEUE_URL=… RENDER_QUEUE_ENDPOINT=http://127.0.0.1:4566 CLIPS_BUCKET=… SEGMENT_BUCKET=…`로 띄운다.
3. 로컬 MediaMTX에 송출하고(`srt://…:8890?streamid=publish:<이름>`) 방송 시작 편지를 넣는다.
4. 🔴 **로컬 media는 S3에 안 올린다**(`S3_BUCKET` 비어 있음 · `S3_ENDPOINT`는 https만). 조각을 손으로 조각 버킷의 `s3_key` 자리에 올리고
   장부 `upload_state`를 `uploaded`로 바꾼다. clip이 「연속 uploaded」일 때만 주문을 받는다.
5. 편집본 저장(`POST /api/clip/broadcasts/{streamId}/recipes`) → 주문(`POST …/recipes/{id}/renders`).
6. 일꾼을 띄운다. clip이 `127.0.0.1`에만 열려 있으면 컨테이너에서 못 닿으니 jar로 띄우고 `FFMPEG_PATH`만 이미지의 ffmpeg로 돌린다.
7. `GET /api/clip/broadcasts/{streamId}/clips/{clipId}`가 `rendered`와 산출물 목록을 준다.

실측(720p 조각 4개, 15초 컷, 세로·정사각 두 벌 + 자막): **7초**에 끝났고, 두 벌 다 15.000초 · 1080×1920/1080×1080 ·
스테레오 · **-14.0 LUFS**, 한글 번인과 srt가 클립 축으로 맞았다.

## 아직 없는 것

| 무엇 | 왜 없나 / 어디서 |
|---|---|
| 완성 영상 내려받기 문 | 창고가 비공개다. clip에 서명 주소를 주는 문을 따로 판다 |
| 무효 실행 폴더 치우기(janitor) | `SUPERSEDED`된 실행이 올린 파일은 아무도 안 가리킨다. 계약1 「janitor 주기 미정」 |
| 고착 알람(STARTED 뒤 30분 무보고) | 관측 장치가 생기면 clip 쪽에 붙인다 |
| 오토스케일·배포 정의(ECS) | 인프라 몫. 이미지는 이 폴더의 `Dockerfile` |
| 자막 스타일(글꼴·위치) | 계약6 P1. 레시피에 칸이 생기면 |
| CI | 서버 넷과 같이 로컬 시험만 있다 |
