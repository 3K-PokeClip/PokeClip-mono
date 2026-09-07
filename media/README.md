# media — Media Origin

**담당: 1번 (`@xodbs1021`)**

## 무엇이 들어가나

방송을 받아서 저장하고 내보내는 서버.

| 단계 | 내용 |
|---|---|
| 수신 | OBS가 SRT로 쏜 방송을 받는다 |
| 조각화 | CMAF 세그먼트로 자른다 |
| 저장 | S3에 올린다 (클립의 원본이 된다) |
| 송출 | LL-HLS로 대시보드에 실시간 전달, DVR 되감기 지원 |

## 구성

**수신·먹싱·DVR은 MediaMTX**를 쓴다 (2026-07-25 EC2 실증 완료).
그 위에 얹는 **차별화 레이어는 Go로 직접 구현**한다 — 매니페스트 합성, 티어드 서빙,
`EXT-X-ENDLIST` 즉시 VOD, 업로더/janitor.

상세는 `PokeClip-LLM-WIKI`의 ADR-003·ADR-020을 본다.

## 다른 폴더와의 경계

- **→ 웹 대시보드**: LL-HLS 재생 규약 ([`contracts/`](../contracts/) 계약3)
- **→ Clip Service**: 세그먼트 인덱스 — "몇 시 조각이 S3 어디에 있나".
  이게 없으면 클립을 자를 수 없다
- **← Auth**: 스트림 키 검증

## 상태

로컬 개발 환경에는 이미 MediaMTX가 떠 있다 — [`infra/compose/mediamtx.yml`](../infra/compose/mediamtx.yml)

그 옆에 **`segment-indexer` 사이드카**(POK-29)가 붙어 있다. MediaMTX가 `/recordings`에 4초마다
떨어뜨리는 fMP4 세그먼트를 감지해, 완성된 것만 `stream_segments` 표에 정확히 한 줄씩 기록한다.
클립을 자르려면 "몇 번째 조각이 어디에 있고 몇 초짜리인가"를 알아야 하는데, 그 목록을 만드는 일이다.

| 항목 | 내용 |
|---|---|
| 진입점 | [`cmd/segment-indexer/main.go`](cmd/segment-indexer/main.go) |
| 완성 판정 | 후속 파일 생성(주) + 유휴 타임아웃(보조) + 크기 안정 대기 |
| 길이 | fMP4 박스 실측(`internal/fmp4meta`). 벽시계로 어림잡지 않는다 |
| 파일 접근 | **읽기 전용**(`recordings:/recordings:ro`). 쓰지도 지우지도 않는다 |
| S3 | **완성 즉시 개별 업로드(POK-30, ADR-014).** 완성 조각을 예약된 `s3_key` 그대로 PUT하고 성공 시 `pending → uploaded`(CAS), 실패 시 재시도 소진 후 `failed`. 스위퍼(기본 30s)가 `pending`·`failed` 잔여를 주기 회수한다(`failed`는 종국 아님). **`S3_BUCKET`이 비면 업로더 전체가 꺼지고 인덱싱만 동작**(`uploader_disabled` INFO 1줄) — 2·3번의 기본 상태다. 1번 전용 활성화는 `docker-compose.override.yml.example`을 `cp` 해서 쓴다 |
| 스키마 | `internal/index/ddl.go`가 **정본 DDL — 1번 소유** (2026-08-01 3번 위임, ADR-0001). **컬럼 추가·변경은 3번 승인 필수**(3번이 이 표를 읽음). RDS 적용 절차는 AWS 배포 시점에 결정 |

동작 확인 절차는 [`docs/dev-environment.md`](../docs/dev-environment.md)의 "세그먼트 인덱싱 확인"에 있다.

### 알려진 제약 (이번 범위 밖 — 이월)

| 항목 | 내용 | 승계처 |
|---|---|---|
| media 실행 계정 비대칭 (`no-new-privileges` 미적용) | media는 root로 실행되면서 세 볼륨(`recordings`·`dvr`·`hooks`)에 쓰기 권한을 갖고 있었다. 사이드카에만 `no-new-privileges:true`가 붙어 있어 비대칭이었는데, **플래그만 넣으면 막는 것이 0이라**(root는 이미 최상위이고 이미지에 setuid 파일이 0개) ADR-031이 일부러 보류했다 | **해소** — POK-79. 아래 "실행 계정과 파일 소유" 절이 상시 사양 |
| `(stream_id, local_path)` UNIQUE | 재기동 후 같은 파일이 두 번 들어가는 것을 막는 최후 방어선 | **해소** — DDL 소유가 1번으로 위임되며 정본에 포함 확정 (U12 종결) |
| UNIQUE 인덱스 이름 | `stream_segments_local_path_uq` — 이름으로 "정상 멱등"과 "번호 충돌"을 가르므로 이름 자체가 계약 | **정본 사양** — 변경 시 store.go 분류 로직 동시 수정 필요 |
| 경로 이력 메모리 | 스트림의 기록된 경로 전부를 메모리에 올린다. 창 제한이 없어 장기 실행 시 계속 자란다 | 이월 |
| DB 권한 | 사이드카가 `POSTGRES_USER`를 그대로 쓴다. 이 표만 다루는 전용 롤이 바람직하다 | 이월 |
| 파일명 파서 fuzz | 화이트리스트와 정규식에 대한 fuzz 테스트가 없다 | 이월 |
| 끝내 안정되지 않는 파일 | 되돌리기가 `DeferMaxCycles`(기본 3) 사이클을 넘으면 그 파일을 붙잡지 않고 흘려보낸다. 스트림 전체가 그 파일 하나 때문에 멈추는 것을 막기 위해서다. 그 결과 해당 4초는 `late_segment_skipped` ERROR와 함께 인덱스에서 빠지며, **자동 복구는 없다** — 파일은 `:ro`로 남아 있으니 로그의 경로를 보고 수동 복구한다 | 이월(L5) |
| 중첩 `%path` | `live/kr/demo` 같은 슬래시 포함 스트림 이름은 거부한다. 필요해지면 `internal/recording/name.go`의 화이트리스트만 고치면 된다 | U9 확정 시 |
| **인제스트와 인덱싱의 허용 범위가 다르다** | MediaMTX는 `paths: all_others`로 **모든 경로를 받지만**, 사이드카는 `[A-Za-z0-9_-]{1,64}`에 맞는 이름만 인덱싱한다. 어긋난 이름으로 송출하면 **녹화 파일은 생기지만 인덱스에는 안 잡히고** `stream_id_rejected` WARN만 남는다 | U9 / 계약4 |
| 초고속 재접속·PTS 리셋 미검출(L1) · PTS 누적 오차 상한 미보장(L4) | **POK-36 실측 종결(2026-08-03):** 녹화 fMP4의 tfdt는 파일마다 원점 리셋(파일 기준)이라 **tfdt로는 해소 불가 확정** — tfdt 전환 기각. 남은 경로였던 **MediaMTX 훅이 POK-74로 들어왔다**: `runOnOnline`/`runOnOffline` 한 쌍이 재접속을 알려 주고, 그 사이에 낀 첫 조각에 `is_discontinuity=true`가 붙는다. 벽시계 드리프트 판정은 **제거하지 않고** 안전망으로 남는다 | L1 = **훅이 1차 신호로 해소**(단, 아래 "훅 검출의 사각" 3행 참조). L4는 잔존 |
| 훅 스풀 무회전 | 훅 이벤트는 `/hooks/events.jsonl` 한 파일에 계속 덧붙기만 하고 회전(rotate)하지 않는다. 스트림 1개 기준 하루 약 4MB로 느리게 자란다. 회전을 넣으면 inode 전환 처리가 읽는 쪽으로 들어와 "어디까지 읽었나"의 계약이 깨지므로 일부러 넣지 않았다 | 이월 — 볼륨 사용량이 문제가 되면 별도 티켓 |
| 훅 유실은 무징후다 | 훅은 fire-and-forget이다. MediaMTX는 훅 명령의 실패를 재시도하지도, 세그먼트 훅에 대해서는 로그를 남기지도 않는다(세션 훅만 서버 로그에 1줄). 그래서 **파일 감시와 5분 주기 재스캔이 안전망으로 반드시 함께 돌아야 한다** — 훅 채널이 조용히 죽어도 인덱싱 자체는 멈추지 않는다. 채널이 살아 있는지는 `segment_indexed` 로그의 `reason` 분포로 본다(`5` = 훅) | 구조적 — 안전망 제거 금지 |
| 훅 검출은 사이드카 무중단 구간 한정 | 세션 경계 상태는 사이드카 프로세스 메모리에만 있고, 스풀은 기동 시점 끝(EOF)부터 읽는다. 따라서 **사이드카가 재기동된 창에 걸친 재접속은 훅으로 검출되지 않고** 기존 벽시계 드리프트 판정으로 강등된다. 이미 기록된 행의 `is_discontinuity`를 나중에 고치는 경로는 없다(소급 보정 없음) | 이월 — 체크포인트 파일은 정합 비용이 이득보다 커서 만들지 않았다 |
| 훅이 이긴 조각은 승격·학습에서 빠진다 | 같은 파일을 훅과 파일 감시가 모두 올리려 하고 먼저 온 쪽이 이긴다. 훅이 이긴 파일은 `ReasonHook`이라 크기 재확인(승격)과 길이 학습 대상에서 제외된다 — 훅 시점의 파일이 이미 최종 크기라는 실측(29/29)에 기댄 결정이다. **업로더(POK-30) 병합 이후 방어선이 옮겨갔다.** `ReasonHook`은 `growthConfirmed`가 참이라 `TailHold` 없이 곧바로 업로드가 요청된다. 그래서 `correctTail`의 유효 창은 "꼬리인 4초"가 아니라 **그 행이 꼬리이면서 아직 `pending`인 구간**(= 워커가 `uploaded`/`failed`로 확정하기 전)이며, 확정된 뒤의 재성장은 교정 없이 `regrow_after_upload_ignored` ERROR로 **관측만** 된다. 실질 방어선은 **업로드 워커의 PUT 직전 크기 재확인**이다 — 꼬리(`IsTail`)의 실물 크기가 장부와 다르면 PUT 자체를 하지 않아 잘린 실물이 S3에 굳지 않고, 마킹은 bytes CAS가 한 번 더 막는다. 다만 쓰기가 PUT 내내 멈춰 있다 재개되는 극단 순서까지는 막지 못한다 | 관측만(YAGNI) — `segment_indexed`의 `duration_ms`·`reason`, 굳음 사고는 `regrow_after_upload_ignored` ERROR로 본다 |
| 훅 이벤트에 레이트리밋·상태 맵 TTL이 없다 | 스트림별 세션 상태(`pendingOffline`·`lastOnlineAt`·`breaks`)는 경계 큐 상한(64)만 있고 시간 기반 만료가 없다. 스트림 수가 매우 많고 각각 짧게 붙었다 떨어지면 맵 항목이 남는다. 스풀을 폭주시키는 송출자에 대한 방어도 없다 | 이월 — 로컬·소규모에서는 발생하지 않는다. 멀티테넌트 규모에서 재평가 |
| 스풀·녹화 경로의 심링크 검증 없음 | 인덱서는 훅이 준 경로를 문자열로만 검사한다 — 루트 밖(`..`)이면 거부하지만 심링크를 따라간 결과까지 풀어 보지는 않는다(`EvalSymlinks` 없음). 그 경로로 하는 일은 로컬 `stat`·길이 프로브·인덱스 기록뿐이라 파일이 밖으로 나가지 않는다 | **해소됨** — 파일을 밖으로 내보내는 유일한 지점인 업로더가 `os.Root` 핸들 기반으로만 연다(`cmd/segment-indexer/main.go`의 `os.OpenRoot(SegmentRoot)` → `upload/worker.go`의 `Root.Open(rel)`). 루트 밖으로 풀리는 심링크는 열기 단계에서 거부되고(`path escapes from parent`), 그 실패와 `ELOOP`은 `classifyOpenError`의 기본 갈래에서 거부+격리된다. 정규 파일이 아니면 `IsRegular()`가 따로 막는다 |

### 실행 계정과 파일 소유 (POK-79) — 상시 사양

두 컨테이너는 **서로 다른 비특권 계정**으로 돈다. 정본은 각각의 Dockerfile `USER` 한 줄이다.

| 컨테이너 | UID:GID | 정본 | 세 볼륨에 대해 |
|---|---|---|---|
| media (MediaMTX + 훅) | `10002:10002` | [`Dockerfile.mtxhook`](Dockerfile.mtxhook)의 `USER` | 씀 (`recordings`·`dvr`·`hooks`) |
| segment-indexer (사이드카) | `10001:10001` | [`Dockerfile`](Dockerfile)의 `USER` | 읽음 (`:ro`) |

**왜 일부러 다른 UID인가.** 파일 교환은 소유권이 아니라 **모드**로 성립한다 — 디렉토리 0755,
녹화·스풀 파일 0644. 두 계정을 같게 두면 그 모드 계약이 **런타임에서 관측 불가**가 된다:
스풀이 0600으로 퇴화해도 UID가 같아 사이드카가 계속 읽고 스모크가 통과해 버린다.
다르게 두면 스모크 자체가 모드 계약의 실물 검사가 되고, "누가 쓴 파일인가"가 소유자로 드러난다
(10001 소유 파일이 보이면 사이드카가 썼다는 이상 신호다).

**볼륨 소유권은 이미지 층에 심는다.** 런타임에 `chown`을 하는 주체는 **없다**. Docker Engine이
named volume이 **비어 있을 때만** 이미지의 같은 경로에서 소유권을 복사하는 동작에 기대어,
`Dockerfile.mtxhook`의 `prep` 스테이지가 빈 디렉토리 3종을 최종 이미지에 넣는다. 회귀 방지 장치는
[`internal/mtxhook/runtime_identity_contract_test.go`](internal/mtxhook/runtime_identity_contract_test.go)다.

- ⚠️ **복사 금지 옵션(`volume.nocopy: true`)을 붙이지 마라.** 붙는 순간 그 복사가 끊겨 비root
  쓰기가 **조용히** 실패한다. 계약 테스트가 `docker-compose.yml`은 잡지만
  `docker-compose.override.yml`은 gitignore라 잡지 못한다 — 개인 override에도 넣지 마라.
- ⚠️ **기존 볼륨은 자동으로 고쳐지지 않는다.** 이미 파일이 든 볼륨에는 복사가 일어나지 않으므로
  **1회 초기화**가 필요하다(절차: [`docs/dev-environment.md`](../docs/dev-environment.md) "시작").
- ⚠️ **리눅스 전제**: bind mount된 `/mediamtx.yml`의 호스트 모드가 그대로 보인다. 체크아웃 umask가
  0077이면 비root가 설정을 못 읽고 **기동 실패**한다(Mac은 Docker Desktop이 소유권을 재매핑해서
  재현되지 않는다). 처방은 `chmod a+r infra/compose/mediamtx.yml`.
  **이 처방은 그 파일에 자격증명이 하나도 없기 때문에 성립한다**(전수 확인함). 계약4의 내부 토큰
  (`X-Internal-Token`)이나 SRT passphrase가 이 파일에 들어오면 `o+r`은 ADR-018(평문 금지)과
  충돌하므로, 그때는 **`o+r` 대신 소유권·그룹으로** 읽기 권한을 준다.

**이번 전환의 가장 큰 실질 이득**은 `/hooks-bin/mtxhookwrite`가 **`root:root 0755`로 남는다**는
것이다. 10002는 그 바이너리를 **실행만 할 수 있고 덮어쓸 수 없다**(실측: 10002로 그 경로에 쓰기를
시도하면 `permission denied`). POK-74로 MediaMTX가 **외부 명령을 실행하기 시작한 것**이 이 티켓의
발단인데, 서버가 root였을 때는 그 명령 자체를 서버가 바꿔 칠 수 있었다. 이제는 아니다 —
`no-new-privileges:true`가 그 위에서 권한 되찾기 경로를 막는다.

이 해법이 **한시적**인 이유(K8s의 파드 레벨 `fsGroup`이 대체한다)와 폐기 절차는
[`docs/decisions/2026-08-17-media-비특권-전환.md`](../docs/decisions/2026-08-17-media-비특권-전환.md)에 있다.

### 훅 채널 (POK-74) — 무엇이 켜져 있고 로그를 어떻게 읽나

MediaMTX가 이벤트마다 컨테이너 안의 작은 명령을 실행하고(`media/Dockerfile.mtxhook`이 얹은
`/hooks-bin/mtxhookwrite`), 그 명령이 공유 볼륨의 스풀 파일에 JSON 한 줄을 덧붙인다.
사이드카는 그 파일을 따라 읽는다. 켜 둔 훅은 3종이다 —
`runOnOnline`·`runOnOffline`(세션 붙음/끊김) + `runOnRecordSegmentComplete`(조각 닫힘).

- 구명칭 `runOnReady`는 **쓰지 않는다.** v1.19.3·v1.20.1에서 그것은 `runOnAvailable`로 매핑되며
  "읽기 가능" 축이지 세션 축이 아니다.
- 설정 자리: [`infra/compose/mediamtx.yml`](../infra/compose/mediamtx.yml)의 `pathDefaults`
  (이 블록은 `all_others`에도 상속된다).

**로그 판독 규칙 — `hook_break_discarded`와 `hook_break_dropped`가 같은 시각에 함께 나오면 한 건의 정리다.**
`discarded`는 "판정 대상이던 경계 1건이 왜 버려졌는가"(`already_passed`·`no_tail`·`duplicate_path`)이고,
`dropped`는 "그와 함께 정리된, 세그먼트가 한 건도 없던 더 오래된 경계들"이다.
**미탐 건수는 `discarded`만 센다** — `dropped`는 애초에 붙일 세그먼트가 없던 경계이므로 미탐이 아니다.

| 로그 키 | 뜻 |
|---|---|
| `hook_break_armed` | 재접속 한 쌍(offline→online)이 확인돼 "다음 조각에 표시" 무장 |
| `hook_break_consumed` | 무장이 실제 조각에 붙었다. 이 줄의 `seq`가 `is_discontinuity=true` 행의 seq다 |
| `hook_break_discarded` | 무장이 붙지 못하고 버려졌다 = **미탐**. 벽시계 안전망으로 강등된다 |
| `hook_break_dropped` | 조각이 한 건도 없던 오래된 경계의 정리. 미탐이 아니다 |
| `hook_spool_missing` | 스풀이 아직 없다. 첫 송출 전이면 정상이며 기동당 1회만 나온다 |
| `hook_line_invalid` / `hook_line_overflow` / `hook_spool_truncated` | 스풀이 깨졌다는 신호. 평시 0건이어야 한다 |
| `segment_indexed`의 `reason` | 채널 승자. `5`가 훅이며 이 값이 0건이면 훅 채널이 조용히 죽은 것이다 |

확인 절차는 [`docs/dev-environment.md`](../docs/dev-environment.md)의 "훅 채널 확인"에 있다.

## segment-indexer 사이드카가 인식하는 환경변수

`cmd/segment-indexer`가 읽는 값 전부다. 코드를 읽지 않고도 무엇을 켤 수 있는지 알 수 있게
여기에 모아 둔다. 튜닝값은 `.env`가 아니라 `docker-compose.yml`의 서비스 블록에 인라인으로 둔다.

**필수 — 없거나 빈 문자열이면 즉시 종료(코드 1)한다.**

| 이름 | 의미 |
|---|---|
| `POSTGRES_USER` | DB 사용자 |
| `POSTGRES_PASSWORD` | DB 비밀번호. 빈 문자열도 누락으로 취급한다 |
| `POSTGRES_DB` | DB 이름 |

**선택 — 기본값이 있다.**

| 이름 | 기본값 | 의미 |
|---|---|---|
| `POSTGRES_HOST` | `postgres` | DB 호스트 |
| `POSTGRES_PORT` | `5432` | DB 포트 |
| `POSTGRES_SSLMODE` | `prefer` | DB 접속 TLS 모드. `prefer`는 가능하면 TLS, 안 되면 평문. 네트워크를 건너는 배포에서는 `require` 이상으로 올린다 |
| `SEGMENT_ROOT` | `/recordings` | 감시할 녹화 루트. 컨테이너에 읽기 전용으로 붙는다 |
| `ENSURE_SCHEMA` | `false` | true면 기동 시 정본 DDL(1번 소유)로 `stream_segments`를 만든다. 로컬 compose 전용 — RDS/마이그레이션 도구 도입 시 끈다 |
| `TZ` | (미설정) | 컨테이너 시간대. **UTC로 고정한다.** 저장 값은 코드가 UTC로 강제하지만 로그 시각도 UTC로 맞춘다 |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `SEGMENT_EXPECTED_DURATION_MS` | `4000` | 세그먼트 1개의 기대 길이. `mediamtx.yml`의 `recordSegmentDuration`과 수동으로 맞춘 값 |
| `SEGMENT_SUSPECT_BELOW_MS` | `3850` | 이보다 짧게 측정되면 덜 써진 파일로 의심하고 다시 잰다 |
| `SEGMENT_DRIFT_TOLERANCE_MS` | `1500` | 벽시계 기대치와 실제가 이만큼 넘게 어긋나면 끊김으로 판정한다 |
| `SEGMENT_INSERT_RETRY_MAX` | `5` | INSERT 재시도 상한(지수 백오프). 소진하면 프로세스를 끝내고 재기동으로 복구한다 |
| `SEGMENT_IDLE_TIMEOUT` | `10s` | 이만큼 무이벤트면 마지막 조각을 확정한다 |
| `SEGMENT_RESCAN_EVERY` | `5m` | 주기 전수 재점검 간격(안전망) |
| `SEGMENT_SETTLE_WAIT` | `2s` | 크기가 이만큼 불변이면 다 써진 것으로 본다. MediaMTX `recordPartDuration` 1s의 2배 |
| `SEGMENT_SETTLE_POLL` | `500ms` | 크기 확인 주기 |
| `SEGMENT_SETTLE_MAX` | `30s` | 안정 대기 상한 |
| `SEGMENT_FIFO_WARN_LEN` | `256` | 내부 대기줄이 이 길이를 넘으면 경고 |
| `SEGMENT_FIFO_MAX_LEN` | `4096` | 이 길이를 넘으면 회복 불가로 보고 종료한다. 재기동 후 전수 스캔이 따라잡는다 |
| `SEGMENT_MAX_WATCH_DIRS` | `1024` | 감시할 디렉토리 수 상한. inotify watch는 커널 자원이라 무한정 늘릴 수 없다. 초과하면 ERROR 1회 후 신규 등록을 무시하며, 파일은 주기 재스캔이 계속 따라잡는다 |
| `HOOK_SPOOL_PATH` | (빈 값) | MediaMTX 훅이 한 줄씩 덧붙이는 스풀 파일. **빈 값이면 훅 어댑터를 아예 기동하지 않는다** — 판정이 벽시계 드리프트만 쓰는 현행으로 돌아가는 즉시 롤백 스위치다(사이드카만 재기동하면 되고 MediaMTX는 건드리지 않는다). compose 기본값은 `/hooks/events.jsonl` |
| `HOOK_POLL_INTERVAL` | `200ms` | 스풀을 다시 읽는 주기. 훅이 발화하고 판정에 닿기까지 더해지는 지연의 상한이다 |
| `HOOK_BREAK_GUARD` | `20ms` | 새 세션 첫 조각 판정의 하한 여유. 파일명 시각 해상도와 훅 기록 시각의 미세 차이만 흡수하며 그 이상의 의미는 없다(실측 짝짓기 오차 ±2ms의 10배). 크게 잡으면 이전 세션의 마지막 조각이 새 세션의 표시를 가로챈다. 0·음수는 기동 거부 |
| `FS_OP_TIMEOUT` | `5s` | 개별 파일시스템 호출(stat·프로브) 하나의 상한. 멈춘 파일시스템에서 메인 루프가 이 시간 이상 붙잡히지 않게 한다 — 넘긴 파일은 건너뛰고 다음 재스캔이 회수한다 |
| `SCAN_COLLECT_BUDGET` | `45s` | 전수 수집(디렉토리 순회)의 soft 예산. 넘기면 걷은 데까지만 처리하고 절단한다. 정지 판정(`scan_collect_stalled`)은 이 값의 배수로 따로 본다 |
| `REWIND_SEED_ENABLED` | `false` | 되감기 컷오프 **주조** 스위치(계약 6항 2단계). 꺼져 있으면 컷오프를 새로 만들지 않고, 켜면 각 스트림의 첫 자격 유입이 컷오프 하나를 만든다. 되돌려 꺼도 **이미 기록된 컷오프는 유효하다** — 판정은 이 플래그가 아니라 장부를 읽는다 |

**되감기 상태 관측(POK-195) — MediaMTX Control API 폴링.**
용어: **관측**은 "지금 이 스트림이 실제로 송출 중인가"를 Control API(MediaMTX가 자기 상태를
알려주는 HTTP 창구)에서 주기적으로 읽어 오는 것이고, **주조**는 되감기 재생의 시작점(컷오프)을
장부에 처음 기록하는 것이다. `MTX_API_URL`이 비면 폴러를 아예 기동하지 않는다 —
관측이 없으면 **관측 기반 주조(스캔 유입 ⓐ2)만 멈춘다**(안전한 방향으로 잠긴다).
워처·훅 유입(ⓐ1) 주조는 계속되며 그것은 `REWIND_SEED_ENABLED`로만 멈춘다.
인덱싱·업로드는 그대로 돈다.

| 이름 | 기본값 | 의미 |
|---|---|---|
| `MTX_API_URL` | (빈 값 = 관측 끔) | Control API 베이스 URL. compose 기본값은 `http://media:9997`. **9997은 호스트에 공개하지 않는다** — compose 내부망에서만 도달한다. scheme+호스트가 필요하고 자격증명(`user:pass@`)은 거부한다(그 원문이 실패 로그에 실리기 때문). query(`?`)·fragment(`#`)도 거부한다 — 이 값 뒤에 조회 경로가 이어 붙으므로 넣으면 폴이 영구 실패한다 |
| `OBS_POLL` | `10s` | 관측 주기이자 **폴 1회의 상한**이다. 주기가 곧 재시도라 따로 재시도를 두지 않으며, 헤더조차 오지 않는 응답을 이 시간에 끊는다. `OBS_FRESH`보다 짧아야 기동한다 |
| `OBS_FRESH` | `30s` | 관측이 이보다 낡으면 방증으로 쓰지 않는다. 주조 트랜잭션 상한(10s, `index.TxnDeadline`)보다 길어야 기동한다 — 같거나 짧으면 기동 거부 |
| `OBS_BACKFILL` | `60s` | 관측 시점보다 이만큼 더 과거인 조각은 밀린 백로그의 머리로 보아 주조하지 않는다 |
| `OBS_BOOT_WAIT` | `3s` | 기동 시 첫 관측을 기다리는 상한. 타임아웃이어도 **기동은 계속된다** — 관측 없이 뜨면 그동안 **관측 기반 주조(스캔 유입 ⓐ2)만** 잠긴다(워처·훅 유입 ⓐ1 주조는 이 값과 무관하게 계속된다) |
| `SESSION_FLOOR_SLACK` | `1s` | 세션 귀속 하한의 여유(시계 역행 방어). 세션 시작보다 이보다 더 과거인 조각은 그 세션에 귀속시키지 않는다 |

> **팀 1회 조치** — `docker-compose.yml`과 `infra/compose/mediamtx.yml`이 함께 바뀌었다.
> 저장소 최상위에서:
>
> ```bash
> git pull
> docker compose up -d        # 사이드카를 새 설정(MTX_API_URL)으로 재생성한다
> docker compose restart media  # MediaMTX가 바뀐 mediamtx.yml을 다시 읽게 한다
> ```
>
> **둘째 줄만으로는 부족하다.** `mediamtx.yml`은 바인드 마운트라 파일 내용이 바뀌어도
> compose가 보는 `media` 서비스 정의는 그대로다(설정 해시 동일 — 실측). 그래서 `up -d`는
> `media`를 "Running"으로 두고 넘어가고, MediaMTX는 기동 때 읽은 옛 설정으로 계속 돈다.
> 재기동하지 않으면 Control API가 없는 상태라 사이드카 호출이 실패하고, **관측 기반 주조(스캔
> 유입 ⓐ2)만 아무 증상 없이 멈춘다**(워처·훅 유입 ⓐ1 주조는 `REWIND_SEED_ENABLED`가 켜진 한
> 계속된다 — 위 정의와 같다). 사이드카 로그의 `mtxstate_poll_failed`만이 유일한 흔적이다.

**S3 업로더(POK-30) — `S3_BUCKET`이 비어 있으면 업로더가 꺼지고 아래 값은 무시된다(단 `SEGMENT_UPLOAD_TAIL_HOLD`만 예외 — 표 안 설명 참조).**

| 이름 | 기본값 | 의미 |
|---|---|---|
| `S3_BUCKET` | (빈값 = 비활성) | 업로드 대상 버킷. **버킷 판정이 다른 S3 값 검증보다 앞이라, 비워 두면 나머지 S3 설정이 틀려도 인덱싱은 산다** |
| `AWS_REGION` | `ap-northeast-2` | 버킷 리전 |
| `S3_ENDPOINT` | (미설정 = AWS 기본) | 호환 스토리지(MinIO 등)용 엔드포인트 URL. scheme+호스트 필수 |
| `S3_FORCE_PATH_STYLE` | `false` | 호환 스토리지용 path-style 주소 지정 |
| `SEGMENT_UPLOAD_RETRY_MAX` | `4` | 조각 1개당 PUT 시도 상한(지수 백오프). 소진하면 `failed` 기록 후 스위퍼가 회수 |
| `SEGMENT_UPLOAD_SWEEP_EVERY` | `30s` | 스위퍼 회차 간격 — `pending`·`failed` 잔여를 주기 회수 |
| `SEGMENT_UPLOAD_CIRCUIT_MAX` | `3` | 연속 실패가 이 값에 닿으면 브레이커가 열려 PUT을 멈추고 다음 회차 탐침으로 복구를 살핀다. `0`이면 브레이커 없음 |
| `SEGMENT_UPLOAD_TAIL_HOLD` | `5s` | 꼬리(마지막) 조각을 이만큼 보류해 교정 창과의 충돌을 피한다. 꼬리 유예(2m)보다 짧고 `SEGMENT_SETTLE_WAIT` 이상이어야 기동한다. **예외: 이 값만은 버킷이 비어 있어도 항상 읽고 검증한다** — 보류 시계는 업로더가 아니라 인덱서 소유라 비활성에서도 돈다. 잘못된 값이면 버킷과 무관하게 기동이 거부된다 |

자격증명은 SDK 기본 체인(env 3종 → 공유 프로필)을 쓴다. 로컬에서는
`eval "$(aws configure export-credentials --format env)"` 후
`docker-compose.override.yml.example`을 복사해 기동한다(파일 안 주석 참조).
`AWS_PROFILE`은 `~/.aws` 마운트 폴백을 켤 때만 함께 넣는다 — 마운트 없이 넣으면
SDK가 존재하지 않는 프로필을 요구해 기동이 죽는다(2026-08-04 실측).

위 표가 `internal/config/config.go`가 읽는 전부다(`TZ` 제외 41개). 잘못된 값(숫자 자리에 문자,
음수, 파싱 불가한 기간)은 조용히 기본값으로 넘어가지 않고 기동에 실패한다.
`SEGMENT_SUSPECT_BELOW_MS`가 `SEGMENT_EXPECTED_DURATION_MS`보다 크면 기동 단계에서 거부한다.

## 되감기 M3 이관 기록 (POK-195)

M3에서 **무엇이 들어왔고 무엇이 일부러 빠졌는지**를 적는다. 빠진 것은 미완이 아니라
다음 마일스톤에 배치된 것이며, 그 근거를 함께 남긴다 — 다음 사람이 "왜 안 만들었나"를
다시 묻지 않게 하는 것이 이 절의 목적이다.

**용어(일상어와 뜻이 다른 것만)**

| 말 | 뜻 |
|---|---|
| 되감기(DVR) | 방송 중에 뒤로 감아 보는 재생. 서버 몫은 "되감아 볼 수 있는 목록(매니페스트)과 그 재료"를 만드는 것이고, 플레이어 UI는 2번 몫이다(계약3) |
| 세션(회차) | 한 번의 방송. 같은 스트림이 껐다 켜지면 새 회차이며 `stream_sessions` 한 줄이다 |
| PDT | Program Date Time — 조각마다 "이 4초가 몇 시 몇 분의 화면인가"를 적은 값. 되감기 목록이 시간을 표시하는 근거다 |
| 컷오프(주조) | 되감기 재생이 시작될 수 있는 첫 지점. 스트림당 한 번 장부에 적히며, 적는 행위를 **주조**라 부른다 |
| init(MAP) | fMP4 재생의 머리 조각(`ftyp+moov`). 이것이 S3에 올라가 있어야 그 회차를 재생할 수 있다 |
| carrier 3열 | 세그먼트 행의 `session_id`·`playback_pdt`·`playback_s3_key`. 되감기 목록을 만들 재료다 |

### 지금 켜진 것과 아닌 것

- **켜졌다**: 세션 행 생성, carrier 3열 채움, PDT 재귀식, MediaMTX Control API 상태 관측,
  업로더의 축 구분(② 아카이브 / ③ 되감기 / init).
- **아직 없다**: 되감기 매니페스트를 **발행**하는 코드 전체(M4). 그래서 M3만 배포해도
  시청자에게 보이는 동작은 바뀌지 않는다.
- **플래그와 무관하게 쓰이는 것이 있다**: `REWIND_SEED_ENABLED`(기본 `false`)가 가르는 것은
  **컷오프 주조 권한 하나**다. 세션 행·carrier 3열·init 준비는 플래그가 꺼져 있어도 쓰인다
  (설계대로이며, 계보에 구멍을 내지 않기 위해서다 — kty 확인 2026-09-02).
- **관측 축 롤백 손잡이**: `MTX_API_URL`을 비우면 폴러를 아예 기동하지 않는다 → 관측 없음 →
  스캔 유입은 세션을 열지 않는다(안전한 방향으로 잠긴다). 위 env 절의 **팀 1회 조치**도 함께 본다.

### M4(발행 층)로 넘어간 것

| 항목 | 왜 M3가 아닌가 |
|---|---|
| `playback.Producer`(③·init 바이트 생산자)와 그 호출 통로 | 생산자가 없으면 M3에 두는 호출부는 호출자 0인 코드가 된다. M3에는 순수 키 파생(`playback/key.go`)만 남겼다 |
| `init_s3_key`·`init_sha256`·`init_bytes` 세 열의 **쓰기 경로**와 워커 배선 | 값의 유일한 생산자가 위 `Producer.Init`이다. M3는 확정 CAS 문장·계약과 픽스처까지 |
| ③ 바이트 추출·③ 재수거·init 재수거 | 바이트가 없는 상태에서 수거를 켜면 없는 파일을 집어 영구 격리된다 |
| 스위퍼의 init 조회 · backlog 축별 집계 | 회수기가 M4라 지표만 켜면 행동 없는 신호가 된다. **M3 동안 backlog 지표는 ② 축만 보여 준다**(③·init은 프로덕션 동작이 0건이라 숨는 장애가 없다) |
| 발행 게이트 실물(`init_uploaded_at IS NULL` → `ready:false`)·G7 skew·`rewind_cutoff_absent` 알람·writer fence | 전부 발행 층 소유다. M3는 그 **장부 축**만 픽스처로 확인했다 |
| 세션 종료 전이(`live` → `ending` → `ended`) | M4/M6. **그래서 M3에서는 같은 스트림의 연속 방송이 첫 회차에 계속 귀속된다** — 발행 층이 없어 무해하며, 테스트가 현 동작을 문서화한다 |
| `REWIND_SEED_ENABLED` 기본값 `true` 전환 | 발행 층이 오는 PR에서 함께 켠다(kty 결정 2026-09-02). 스위치 자체는 롤백 손잡이로 남는다 |
| `discontinuity_base` 증분 산식 | 렌더 축 값이라 소비자(M4)와 같은 커밋에서 정한다. M3는 명시적 기본값 `0`("승계 없음") |
| 상태 관측 등급(tier) ⓘⓘ·ⓘⓘⓘ 상수 | 폴러가 실측상 ⓘ만 산출한다. 소비자가 생길 때 같이 넣는다 |
| **부채** — `indexer.go`가 1228줄(M3 전 1074)이다. 판정 묶음(`observation`·`buildSeed`·`corroborates`·`withStateObs`·`sessionOp` = `:595`~`:726`, 약 130줄)을 `judgment.go`로 분리한다 | 응집이 하나(유입 → 방증 → 연산)라 지금 나눠도 읽기가 나아지지만, 그 묶음을 실제로 건드리는 것이 M4(발행 축 판정 편입)라 **그때 같은 커밋에서** 옮긴다. 지금 옮기면 M4 리뷰가 이동과 변경을 함께 읽어야 한다 |

### M5(보안 강화)로 넘어간 것

MediaMTX Control API의 `authInternalUsers` `api` 항목은 **자격증명도 IP 제한도 없다**.
v3의 `api` 권한은 조회 전용이 아니라 설정 변경·경로 추가·강제 끊기·녹화 삭제까지 포함하므로,
지금은 **⑴ compose 전용 브리지 네트워크 격리 ⑵ 9997 포트 미공개** 두 겹에만 의존한다.
좁히기(IP 대역 또는 자격증명)는 IPAM 신설을 동반해 인프라 축이 넓어지므로 M5로 미뤘다.

### 설계 문서 정정 후보 (코드 변경 아님)

구현이 설계와 다른 것이 아니라, 설계 문서의 그림·문장이 자기 본문과 어긋나는 자리다.

1. TD(목표 길이) 초과 판정의 위치 — 그림은 트랜잭션 밖, 본문은 안. 낡은 값으로 판정하지 않으려면 안이 맞다.
2. PDT 재귀식의 "직전 조각" 정의 — 스트림 전체가 아니라 **세션 계보 안**에서 찾는다.
3. 세션 개시 연산의 분해(결정 / 쓰기 2회 호출) — `first_pdt`를 앞에서 참조하는 원문 형상으로는 순서가 성립하지 않는다.
4. "귀속 하한은 항상" vs "TD 분할이 먼저"의 순서 긴장.
5. 관측 등급 잔여 항목은 실측(F-34)으로 닫혔다. 부수 관측: SRT 송출을 강제 종료하면 항목 소멸이 EOF까지 15~20초 늦다(F-34는 RTMP 정상 종료 측정).

### 공시

- 계약3 `session_id`의 **최초 규정**: `S-{YYYYMMDD}-{HHMMSS}-{streamID}-{seq}`(UTC 기준, kty 확정 2026-09-02).
  계약3 원문은 되감기 URL 형상만 정하고 이 형식을 규정한 적이 없어, 이번이 개정이 아니라 신규 규정이다.
  팀 위키 계약3 추기는 별건이며 3번 리뷰 대상이다.
- 인덱스 도달 기록: 직전 세션 조회는 `stream_sessions_stream_idx` + Incremental Sort(정상 — 동률 타이브레이커가 인덱스에 없다),
  현 live 조회는 소표라 Seq Scan이 뜬다(비용 판정이 아니라 도달 판정이다).
- 환경 사실: Docker PG의 시계가 호스트보다 최대 +956ms 어긋나는 것을 실측했다 →
  시간 픽스처는 **한 시계만** 쓴다(두 시계를 섞으면 판정이 뒤집힌다).

### 미확인 (정직 서술)

- Control API 기본 페이지 크기의 정확한 값(2 이상인 것만 실측 — 계약은 "1페이지인가 아닌가" 두 갈래라 영향 없다).
- 세그먼트 벽시계가 역행하는 폭(F-39). 역행이 세션 경계보다 크면 "직전 세션" 선택이 흔들릴 수 있다.
- 워처가 기동 후 죽는 국면의 **재기동 루프**는 CI에서 재현하지 못해 수동 검증으로 강등했다
  (compose 재기동 실측: 관측 → 초기 수집 순서 확인, 재기동부터 주조까지 약 1.62초).
- 스캔 유입 단독으로 주조되는 국면은 compose에서 실증할 수 없다(워처가 상시 살아 있다) — PG 통합 테스트까지가 한계다.
- 포크 태그의 `api` 액션 범위는 상류 문서 기준이며 원문 대조는 하지 않았다.

### 출처

- 설계 정본: 팀 위키 `PokeClip-LLM-WIKI` — `contracts/계약-세그먼트인덱스.md`·`contracts/계약3-LLHLS-DVR재생규약.md`, ADR-020·ADR-044·ADR-063.
- 이번 마일스톤 설계·계획: POK-195 작업 산출물(설계 r17 5.1.1·5.2·5.3·5.5·6.5, 계획 4.1·4.5·6절·8절·9절).
- 실측: MediaMTX Control API 응답 실물(F-34), compose 기동·SRT 송출 실측(2026-09-02), Docker PG 시계 드리프트 실측.
- 결정: kty 확정 4건(2026-09-02) — `session_id` 형식 · M3 단독 PR · 커버리지 게이트 확장 · 되감기 스위치 현행 유지.

## 테스트

```bash
cd media && go test ./...
```

`internal/index`·`internal/session`·`internal/indexer`·`cmd/segment-indexer`의 통합 테스트와
`internal/pgtest`의 자기 테스트는 **실제 PostgreSQL이 필요**하다. `PG_DSN`이 없으면 해당 케이스는 전부 `skip`되고 나머지는 그대로
돈다 — 즉 `PG_DSN` 없이 돌린 결과만으로는 DB 계층이 검증되지 않는다.

```bash
set -a; . ../.env; set +a
export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
go test ./internal/index/ ./internal/session/ ./internal/indexer/ ./cmd/segment-indexer/ ./internal/pgtest/ -v
```

이 통합 테스트들은 `PG_DSN`이 가리키는 DB에 **쓰지 않는다.** `PG_DSN`은 관리 접속으로만
쓰이고, 같은 서버에 전용 테스트 DB(`PG_TEST_DB`, 기본 `pokeclip_uploadtest`)를 만들어 그 안에서만
돌며 테스트 함수마다 비운다. **전용 DB는 패키지마다 하나씩이다**(`internal/pgtest`) —
`go test ./...`는 패키지별 테스트 바이너리를 병렬로 띄우므로 한 DB를 나눠 쓰면 서로의 표를
비운다. 실제 이름은 `PG_TEST_DB`에 패키지 접미를 붙인 것으로, `internal/index`만 무접미
(`pokeclip_uploadtest`)이고 `internal/session`은 `_session`, `internal/indexer`는 `_indexer`,
`cmd/segment-indexer`는 `_cmd`, `internal/pgtest` 자기 테스트는 `_selftest`다. 그래서 개발 DB의 `stream_segments`는 오염되지 않는다(대신 `PG_DSN`
롤에 `CREATEDB` 권한이 필요하고 — **로컬 compose와 CI(`media-ci`)의 postgres 서비스 컨테이너 둘 다 superuser라 이미 갖고 있다**, `PG_TEST_DB`를 개발 DB 이름과 같게
주면 테스트가 기동 즉시 실패한다. `PG_DSN`에 DB 이름 자체를 안 적었을 때도 같은데, 단
`PGDATABASE`가 설정된 환경에서는 그 값이 DB 이름으로 채워져 "이름이 비었다" 가드 대신 동일 이름
가드가 판정한다).
**이미 있는 DB는 이 테스트가 심어 둔 소유 표식(빈 표 `pokeclip_testdb_marker`)이 있을 때만
채택한다 — 없으면 남의 DB일 수 있으므로 아무것도 건드리지 않고 실패한다.** 표식은 테스트가 DB를
새로 만들 때만 심으므로, **표식 도입 전에 만들어 둔 기존 전용 DB는 한 번 `DROP DATABASE` 후 다시
돌려야 한다**(한 번만 겪는 마이그레이션이고 실패 메시지가 그대로 안내한다).
**`PG_DSN`에는 로컬 compose의 개발 DB만 준다 — 공유·원격·프로덕션 DSN을 주지 않는다.**
**같은 `PG_TEST_DB`로 두 실행을 동시에 돌리면 서로의 데이터를 지운다 — CI나 병렬 실행에서는
실행마다 고유한 `PG_TEST_DB`를 주고, 실행이 끝나면 그 이름의 접미 DB 전부를 `DROP DATABASE`로
정리한다**(정리 없이 고유 이름만 늘리면 DB가 무한히 쌓인다). 이 규약은 **PG를 공유하는 실행이 있을 때** 적용된다 —
`media-ci`의 postgres는 잡마다 뜨고 함께 사라져 공유가 없다 — 규약을 완화한 것이 아니라 적용
조건을 드러낸 것이다. `ddl.go`를 바꾼 뒤에는 전용 DB가 옛 스키마를 유지하므로
(`CREATE TABLE IF NOT EXISTS`) **접미 DB 전부**를 지운 뒤 다시 돌린다 — `pokeclip_uploadtest`
하나만 지우면 `internal/index`는 새 스키마로 통과하고 `_session`·`_indexer`·`_cmd`는 옛
스키마 그대로라 그 세 패키지만 "does not exist" 계열로 실패한다.

```bash
# PG_TEST_DB 를 바꿔 돌렸다면 그 이름이 밑이름이다 — 기본 이름만 지우면 실제 DB 는 남는다.
base="${PG_TEST_DB:-pokeclip_uploadtest}"
for suffix in "" _session _indexer _cmd _selftest; do
  psql "$PG_DSN" -c "DROP DATABASE IF EXISTS ${base}${suffix}"
done
```

`internal/fmp4meta` 테스트는 `testdata/`의 커밋된 파일만 쓰므로 Docker가 꺼져 있어도 돈다.

`cmd/mtxhookwrite` 테스트는 **바이너리를 직접 빌드해 프로세스 8개를 동시에 띄운다**(줄 섞임 검증).
`go test`만 있으면 되고 Docker는 필요 없지만, 다른 테스트보다 몇 초 더 걸린다.

CI(`media-ci`)는 `go test`에 `-coverprofile`을 붙여 패키지별 커버리지를 함께 재고,
`internal/index`·`internal/upload`·`internal/indexer`·`internal/session`·`internal/mtxstate`
**다섯 패키지 중 하나라도 80% 미만이면 잡을 실패시킨다**(뒤 둘은 POK-195 M3에서 추가 —
되감기 판정 로직이 그 안에 있다). 나머지 패키지는 수치만 로그에 남고 게이트 대상이 아니다.

## MediaMTX 버전업 체크리스트

**핀이 어긋나면 [`internal/mtxhook/version_contract_test.go`](internal/mtxhook/version_contract_test.go)가
빨간불이 되고, 그 실패 메시지가 이 절로 안내한다.** 상수는 셋이고 책임이 다르다 —
`pinnedMediaMTXTag`(FROM 태그와 대조)·`pinnedMediaMTXDigest`(FROM digest와 대조)·
**`upstreamBaseVersion`(아래 전제 9곳을 짊어지는 상수)**. **9곳 재확인의 서명은 마지막 하나이고**,
그 값이 바뀌는 순간만 진짜 버전업이다(포크 태그의 `.1`→`.2`는 보통 우리 수정만 바뀐 것이다 — 다만 상류 베이스 커밋이 함께 이동하면 준버전업이며 아래 "포크 태그와 상류 버전의 관계" 규약을 따른다).
실패 메시지에 9개 목록이 그대로 들어 있다.

버전 고정의 유일한 자리는 [`media/Dockerfile.mtxhook`](Dockerfile.mtxhook)의 `FROM`이다
(compose의 `image:`가 `build:`로 바뀌면서 옮겨왔다). **지금 그 줄은 상류 공식 이미지가 아니라
우리 포크 빌드를 태그+digest로 가리킨다** — 아래 "이미지 출처에 묶인 전제" 절을 함께 읽는다. 훅 파라미터 이름은 버전 사이에 조용히
바뀌거나 사라질 수 있고, **훅이 실행되지 않아도 아무 오류가 나지 않는다**. 그래서 절차를 고정한다.

### 버전에 묶인 전제 9곳 — 절차보다 먼저 확인한다

버전을 고정하는 자리는 한 곳이지만, **"고정 버전이라서 참인 사실"에 기대는 자리는 아래 9곳**이다.
전제가 깨져도 예외도 로그도 나지 않는다 — 훅이 조용히 안 돌거나 길이가 조용히 틀릴 뿐이다.
"닻"은 그 자리를 `git grep`으로 바로 찾기 위한 문구다(줄 번호는 금방 낡아서 적지 않는다).

| # | 자리 (닻) | 무엇이 참이라고 전제하는가 | 어떻게 재확인하는가 |
|---|---|---|---|
| 1 | `infra/compose/mediamtx.yml` — `pathDefaults` 블록<br>(닻: `all_others 에도 상속된다`) | 설정 로딩이 `pathDefaults`(모든 경로의 기본값 묶음)를 먼저 복사하므로, 훅 3종을 여기에만 적어도 `all_others`(설정에 이름을 안 적은 모든 경로)에 그대로 붙는다 | 새 이미지를 띄우고 아무 이름(예: `demo`)으로 15초 송출한 뒤 스풀 `/hooks/events.jsonl`에 줄이 쌓이는지 본다. **안 쌓이면 상속이 사라진 것** — 훅 3종을 `paths: all_others:` 아래로 내려 적는다 |
| 2 | `infra/compose/mediamtx.yml` — 훅 명령 3줄<br>(닻: `이 세 줄에`) | 명령 문자열을 shell 규칙으로 **먼저 쪼갠 뒤** 조각별로 변수를 치환한다. 그래서 `$MTX_PATH`를 넣어도 인자 개수는 안 늘고, 대신 그 인자 하나의 내용이 송출자 제어가 된다 | 업스트림 `internal/externalcmd/cmd_os_other.go`(#6156에서 `cmd_os.go`를 개명)에서 분해와 치환의 **순서**를 확인한다. 치환이 먼저로 뒤집혔다면 송출자가 경로 이름만으로 인자를 늘릴 수 있다 |
| 3 | `media/README.md` — 훅 채널 절<br>(닻: `구명칭 runOnReady`) | 구명칭 `runOnReady`는 `runOnAvailable`("읽기 가능" 축)로 매핑되며 세션(Online) 축이 아니다. 그래서 쓰지 않는다 | 3·4는 같은 사실이다. `docker compose logs media \| head -50`에서 deprecated/unknown 파라미터 WARN을 본다. 훅 3종 이름(`runOnOnline`·`runOnOffline`·`runOnRecordSegmentComplete`)이 WARN 없이 살아 있는지가 핵심 |
| 4 | `media/internal/mtxhook/event.go` — `Kind` 주석<br>(닻: `runOnAvailable 로 매핑`) | 위와 같은 사실을 코드 쪽에 적어 둔 것 | 위 3번과 함께 한 번에 확인한다 |
| 5 | `media/internal/fmp4meta/probe.go`<br>(닻: `트랙 중 최대 길이`) | `moov/mvhd`(파일 전체 길이가 적힌 상자)의 duration이 "트랙 중 최대 길이"와 일치한다. 이게 어긋나면 인덱스의 `duration_ms`가 조용히 틀린다 | 새 버전이 떨어뜨린 세그먼트를 `ffprobe`(이 코드와 무관한 독립 구현)로 재고, `ProbeDurationMS` 결과와 100ms 안에서 맞는지 대조한다 |
| 6 | `media/internal/fmp4meta/probe_test.go` + `testdata/`<br>(닻: `채취: MediaMTX`) | 픽스처 3종이 **1.19.3이 `recordPath`로 직접 떨어뜨린 원본**이다(1.20.1 산출물과 박스 배치 동일 확인 — 2026-08-28, 재채취 불요). 검증 대상이 MediaMTX의 박스 배치라서 재인코딩본으로는 대체할 수 없다 | 5번 대조가 어긋났을 때만 손댄다 — 새 버전 산출물로 픽스처를 다시 채취하고 오라클(ffprobe 실측값)도 함께 갱신한다. 어긋나지 않으면 그대로 둔다 |
| 7 | `media/internal/recording/settle.go`<br>(닻: `업스트림 기본값 recordPartDuration`) | 업스트림 기본값 `recordPartDuration` = 1s. 쓰기와 쓰기 사이 공백을 "다 썼다"로 오해하지 않으려면 공백의 2배는 기다려야 하므로, 그 2배가 `SEGMENT_SETTLE_WAIT` 2s의 근거다 | 새 태그의 업스트림 기본 설정 파일(`mediamtx.yml`)에서 `recordPartDuration` 값을 확인한다. **1s보다 커졌으면 `SEGMENT_SETTLE_WAIT`를 그 2배로 올린다** — 안 올리면 절반짜리 파일을 완성으로 판정한다 |
| 8 | `media/Dockerfile.mtxhook`<br>(닻: `USER 10002:10002`) | **MediaMTX가 루트FS·CWD에 쓰지 않는다.** 비root(UID 10002)로 도니까 쓰려는 순간 실패한다. 우리 설정은 `moq: no`라 참이지만, `moq`/`webrtc`/`rtsps`를 켜며 `auto.key`류 자동 생성 경로를 쓰면 비root에서 기동 자체가 실패한다(POK-79 실험 E7) | 새 버전 **기본 설정**에서 CWD에 파일을 쓰는 지점이 늘었는지 본다. 실물 확인은 기동 로그에 `failed to save`·`permission denied`가 뜨는지 — `docker compose logs media \| grep -iE 'permission denied\|failed to save'`가 0건이어야 한다 |
| 9 | HLS 서빙 경계<br>(닻: `302 cookieCheck`) | **HLS 첫 요청은 302 `cookieCheck` 리다이렉트를 돈다 — 1.19.3에도 있던 동작이며 이번(1.20.1 전환)에 처음 체크리스트화했다**(우리 2026-08-17 결정 문서·v1.19.3 원문 대조). **버전별 델타**: 1.20.1은 plain HTTP에서 일반 쿠키를 중단하고 **Partitioned 쿠키(HTTPS 전용)로 통합**, HTTP에선 쿠키 미회신 시 **`?session=` 쿼리로 폴백**(만료 시 401 — 실측)·iOS UA 400 분기 제거. CDN(Bearer) 경로는 302를 우회한다 | CDN·서명 쿠키·매니페스트 TTL 경계에서 실측 — 캐시가 302·Set-Cookie를 어떻게 다루는지, **HTTP 오리진에서 세션 쿼리가 캐시 키를 오염시키는지**. 이 행은 버전 특정이 아니라 **서빙 경계 상시 리스크**다 — 롤백해도 걷어내지 않고 델타 서술만 그 버전 값으로 갱신한다. ADR-050 선결 A |

**1.19.3 → 1.20.1 재검증 기록 (2026-08-28)**: 기존 8곳 전항 확인 + 9번 신설(체크리스트화) — ①`all_others` 송출로 훅 3종 실발화(스풀 기록) ②`shellquote.Split` 후 `expandEnv` 순서 불변(`cmd_os.go:16→22`) ③④`runOnReady` deprecated 별칭 생존(`conf/path.go:354`)·기동 WARN 0 ⑤1.20.1 실산출물 mvhd 4.117s = 최대 트랙 길이 일치 ⑥박스 배치 동일(ftyp·moov·(moof·mdat)×N — 픽스처 유지) ⑦`recordPartDuration` 기본 1s 불변(`conf/path.go:376`) ⑧UID 10002로 녹화 기록·권한 오류 0(+상류 read-only FS 복원 커밋 `c9f003f`). 부수: 상류 `a56c635`가 우리가 겪은 설정 API 데드락을 해소.

**준버전업 재확인 기록 (2026-09-03, `.1`→`.2`, 상류 베이스 e175003→f82bc23 13커밋)**: 이동 구간이 닿는 전제만 표적 재확인 — ②`shellquote.Split`(16행) 후 조각별 `expandEnv`(21~22행) 순서 불변, 파일은 #6156에서 `cmd_os.go`→`cmd_os_other.go`로 개명 ③④훅 이름 5종 존치·`runOnReady` 별칭 매핑 유지(`conf/path.go:351~364, 967~972`), 기동 WARN 0 ⑦`recordPartDuration` 기본값 1s(소스 `path.go:376` + 기동 후 `pathdefaults/get` 실측) ⑤⑥면제 — `internal/recorder` 이동 구간 diff 0(`record`·`formatprocessor` 경로는 존재하지 않음) ⑧`.2` compose 기동 로그에 권한 오류 0 · 추가로 `authInternalUsers` 기본 두 항목이 새 베이스 샘플과 동일(정규화 YAML 대조), playback 기본 비활성(`global/get` 실측). 스모크: 훅 3종 실발화(online 1·segcomplete 4·offline 1), #6155 신동작 확인(훅 비0 종료가 `runOnOnline command exited: command exited with code 1`로 보고), 익명 read 302 cookieCheck→200. 발행 이미지 격리 rig(GHCR digest 기준): 8축 전부 통과(유휴 무녹화·RTSP 송출 녹화 시작·동결·재개·정적 소스·오프라인 PATCH 동결·런타임 등록·SRT), 판정 대상 산출물 13개 전부 640x360(슬레이트 1920x1080 0건 — 일부러 슬레이트를 녹화하는 대조군 `pub1`은 지문 대상에서 제외), 기동 로그 `v1.20.1-pokeclip.2`. 포크 전량 테스트는 66패키지 중 63 통과, 3패키지(webrtc ICE 후보·mpegts/rtp 멀티캐스트 UDP)는 우리 커밋 없는 상류 원본 트리에서도 동일 실패 — 호스트 네트워크 환경 의존으로 제외.

전제는 아니지만 **버전 문자열을 그대로 적어 둔 곳**이 더 있다. 함께 고친다 —
[`docs/dev-environment.md`](../docs/dev-environment.md)의 서비스 표,
[`Dockerfile.mtxhook`](Dockerfile.mtxhook) 주석의 상류 베이스 서술,
그리고 [`infra/dev-media/compose.yml`](../infra/dev-media/compose.yml)의 상류 이미지 태그
(그쪽은 임시 데모용이라 본선 핀을 따라가지 않는다 — ADR-040 만료분, 철거 대기).

### 이미지 출처에 묶인 전제 — 우리 포크 라인(`pokeclip`)

위 9곳이 **MediaMTX 버전**에 묶인 전제라면, 이것은 **어느 이미지냐**에 묶인 전제 하나다.

`FROM`이 가리키는 것은 상류 공식 이미지가 아니라 우리 포크 빌드
`ghcr.io/xodbs1021/mediamtx`다. 이 이미지에만 있는 것은 **슬레이트(송출이 끊겼을 때 서버가
대신 내보내는 대기 화면) 구간을 녹화에서 빼는 스위치** `alwaysAvailableRecorded`이며,
상류 제안(PR #6182, 원저자 PR #5767 승계)은 아직 머지 전이다. 결정 근거는 ADR-050 선결 B(ⓑ 자체 빌드 선행).

| 전제 | 깨지면 무슨 일이 나나 | 어떻게 재확인하는가 |
|---|---|---|
| `FROM`이 우리 포크 빌드를 가리킨다<br>(닻: `pokeclip`) | 공식 이미지로 되돌리면 스위치가 사라져 **대기 화면이 다시 녹화되어 저장소로 올라간다 — 오류도 로그도 없이** | 기동 로그 첫 줄의 버전 문자열이 `v…-pokeclip.N`인지 본다(`docker compose logs media \| head -1`). 기계 방어는 `TestPinnedMediaMTXDigestMatchesDockerfile` |

**핀은 태그와 digest를 함께 적는다.** 태그(`v1.20.1-pokeclip.2`)는 사람이 읽는 이름이고,
digest는 불변 좌표다 — 같은 태그를 다시 밀어도 가리키는 이미지가 바뀌지 않는다. 그래서
버전 대조 테스트도 둘 다 본다(`pinnedMediaMTXTag`·`pinnedMediaMTXDigest`).

**새 이미지를 만들 때**: `xodbs1021/mediamtx`의 `pokeclip` 라인에 커밋 → `*-pokeclip.*` 태그를
민다 → `pokeclip-image` 워크플로가 멀티아치 이미지를 GHCR에 올리고 **실행 요약에 `FROM …@sha256:`
한 줄을 찍는다** → 그 값을 `Dockerfile.mtxhook`과 테스트 상수 2개에 옮긴다. 이미지 생성은
자동이고 제품 반영은 수동이다 — 핀 교체가 이 절의 재확인을 동반해야 하기 때문이다.

**상류에 머지되면**(포크가 필요 없어지면) **포크 전용 장치를 전부 걷어낸다** — 빠뜨리면 공식
태그에서 포크 전용 단언이 남아 빨간불이 된다. 정리 목록은
[`version_contract_test.go`](internal/mtxhook/version_contract_test.go)의 `forkPinGuide`에 번호로
적혀 있다(FROM 복귀 · `mediaMTXImage` 복귀 · 태그 상수 정리 · digest 상수와 그 테스트 삭제 ·
포크 전용 테스트 2종 삭제 · 이 절 삭제). 경로 설정의 `alwaysAvailableRecorded`는 그대로 둔다 —
파라미터 이름이 같다.

**포크 태그와 상류 버전의 관계**: 태그는 `v<상류버전>-pokeclip.<N>` 형식이고, 테스트
`TestPinnedTagCarriesUpstreamBaseVersion`이 그 대응을 지킨다. `.1`→`.2`처럼 뒤 숫자만 오르는
것은 보통 **우리 수정만 바뀐 것**이라 위 9곳 재확인 대상이 아니다(아래 준버전업 예외 참조). 앞의 상류 버전이 바뀌는 순간이
진짜 버전업이고, 그때 `upstreamBaseVersion`을 함께 고치며 9곳을 재확인한다.

**준버전업 — 릴리스 문자열은 같은데 상류 베이스 커밋이 이동한 경우**: 태그의 `.N`만 오르지만 "우리 수정만 바뀐 것"이
아니다. `upstreamBaseVersion`은 그대로 두되(9곳 전수 서명이 아니므로), 이동 구간 `git log <옛 베이스>..<새 베이스>`가
닿는 전제만 **표적 재확인**하고 그 결과를 위 "재검증 기록"에 남긴다. Debian revision·Alpine `-rN`·RPM `Release`·
Homebrew `revision`이 비슷한 구분이다 — 다만 그 관례들은 상류 소스가 그대로인 재패키징이고, 준버전업은 상류 트리가
이동한 경우이므로 **표적 재확인이 필수**라는 점이 다르다. 아래 "절차"의 단계 2(`upstreamBaseVersion` 갱신)는 준버전업에는
해당하지 않는다(상류 버전 문자열 불변).

### 절차

1. **`FROM` 변경은 별도 PR로 낸다.** 다른 변경과 섞으면 회귀 원인을 가를 수 없다.
2. 위 표 9곳을 확인한 뒤 **같은 PR에서 `upstreamBaseVersion`을 새 상류 버전으로 고친다.**
   확인 없이 상수만 맞추면 이 장치는 무력해진다 — 상수 수정은 확인했다는 서명이지 형식 절차가 아니다.
3. 기동 로그에서 **deprecated/unknown 파라미터 WARN**을 확인한다 — `docker compose logs media | head -50`.
   훅 3종의 이름이 그대로 살아 있는지가 핵심이다.
4. **스모크**: 15초 송출 후 스풀에 `online`·`segcomplete`·`offline` 3종이 찍히는지 본다
   (`docs/dev-environment.md`의 "훅 채널 확인").
5. **재접속 합성**을 다시 돌려 `is_discontinuity=true`가 재접속 지점에 붙는지 확인한다.
6. `segment_indexed`의 `reason` 분포에 `5`(훅)가 남아 있는지 본다 — 사라졌으면 훅이 안 도는 것이다.
7. 이상이 있으면 `HOOK_SPOOL_PATH`를 빈 값으로 두고 사이드카만 재기동해 즉시 롤백한다.
   그 상태로도 인덱싱은 정상이고, 재접속 검출만 현행 수준으로 돌아간다.
