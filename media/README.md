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
| `S3_ENDPOINT` | (미설정 = AWS 기본) | 호환 스토리지(MinIO 등)용 엔드포인트 URL. scheme+호스트 필수. https 여야 한다 — http 는 SDK 서명 단계에서 PUT 이 거부된다(2026-09-24 실측) |
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

셋째 칸은 PR ⓐ(생산 층)·ⓑ(렌더·경계)가 들어온 뒤의 반영 상태다. 자세한 것은 아래 「되감기 M4 ⓐ 이관 기록」·「되감기 M4 ⓑ 이관 기록」 절에 있다.

| 항목 | 왜 M3가 아닌가 | M4 반영 |
|---|---|---|
| `playback.Producer`(③·init 바이트 생산자)와 그 호출 통로 | 생산자가 없으면 M3에 두는 호출부는 호출자 0인 코드가 된다. M3에는 순수 키 파생(`playback/key.go`)만 남겼다 | **완료(PR ⓐ)** — `internal/playback/producer.go` `Producer`·`Remuxer`(Go 재포장). 호출 통로는 업로드 워커(`internal/upload/axis_body.go` `produce`)이고 주입 자리는 `upload.Options.Producer` 다 |
| `init_s3_key`·`init_sha256`·`init_bytes` 세 열의 **쓰기 경로**와 워커 배선 | 값의 유일한 생산자가 위 `Producer.Init`이다. M3는 확정 CAS 문장·계약과 픽스처까지 | **완료(PR ⓐ)** — 첫 init CAS(`internal/index/upload_store.go` `MarkInitUploaded`)가 세 열과 `init_uploaded_at` 을 한 문장으로 쓴다. 워커 배선은 `axis_body.go` `markInitUploaded` |
| ③ 바이트 추출·③ 재수거·init 재수거 | 바이트가 없는 상태에서 수거를 켜면 없는 파일을 집어 영구 격리된다 | **완료(PR ⓐ)** — 추출은 `axis_body.go` `playbackPayload`, 재수거는 `internal/upload/sweep.go` `sweepAxes`(② → ③ → init) |
| 스위퍼의 init 조회 · backlog 축별 집계 | 회수기가 M4라 지표만 켜면 행동 없는 신호가 된다. **M3 동안 backlog 지표는 ② 축만 보여 준다**(③·init은 프로덕션 동작이 0건이라 숨는 장애가 없다) | **완료(PR ⓐ)** — init 조회 `pendingInitUploadsSQL`, 잔량 3벌(`countArchiveBacklogSQL`·`countPlaybackBacklogSQL`·`countInitBacklogSQL`). `upload_backlog` 가 축 라벨을 단다 |
| 발행 게이트 실물(`init_uploaded_at IS NULL` → `ready:false`)·G7 skew·`rewind_cutoff_absent` 알람·writer fence | 전부 발행 층 소유다. M3는 그 **장부 축**만 픽스처로 확인했다 | **일부(PR ⓐ·ⓑ)** — G7 완결: ⓐ 의 skew 기록(`internal/indexer/chain.go` `recordSkew`, Debug)에 ⓑ 의 S7 PDT 엄격 증가 검사(`internal/rewind/validate.go` `checkPDT`)가 더해졌다. 발행 게이트·알람·fence 는 ⓒ |
| 세션 종료 전이(`live` → `ending` → `ended`) | M4/M6. **그래서 M3에서는 같은 스트림의 연속 방송이 첫 회차에 계속 귀속된다** — 발행 층이 없어 무해하며, 테스트가 현 동작을 문서화한다 | **일부(PR ⓐ)** — init 불일치에 의한 `live → ending(init_mismatch)` 만 들어왔다(`upload_store.go` `MarkPlaybackFailed` 결속 갈래). 송출 종료 전이는 ⓒ, `ended` 는 M6 — 그래서 연속 방송이 첫 회차에 귀속되는 동작은 ⓒ 까지 그대로다 |
| `REWIND_SEED_ENABLED` 기본값 `true` 전환 | 발행 층이 오는 PR에서 함께 켠다(kty 결정 2026-09-02). 스위치 자체는 롤백 손잡이로 남는다 | 대기 — ⓒ |
| `discontinuity_base` 증분 산식 | 렌더 축 값이라 소비자(M4)와 같은 커밋에서 정한다. M3는 명시적 기본값 `0`("승계 없음") | **완료(PR ⓑ)** — `rewind.EvictedDiscontinuityTags`(순수 함수 — 목록 앞에서 빠지는 끊김 표시 수 = base 에 더할 증분). 배선(발행 예약 문장 P0 에 동봉)은 ⓒ |
| 상태 관측 등급(tier) ⓘⓘ·ⓘⓘⓘ 상수 | 폴러가 실측상 ⓘ만 산출한다. 소비자가 생길 때 같이 넣는다 | 대기 — 슬레이트 유입이 착지하는 마일스톤(계획 8절) |
| **부채** — `indexer.go`가 1228줄(M3 전 1074)이다. 판정 묶음(`observation`·`buildSeed`·`corroborates`·`withStateObs`·`sessionOp` = `:595`~`:726`, 약 130줄)을 `judgment.go`로 분리한다 | 응집이 하나(유입 → 방증 → 연산)라 지금 나눠도 읽기가 나아지지만, 그 묶음을 실제로 건드리는 것이 M4(발행 축 판정 편입)라 **그때 같은 커밋에서** 옮긴다. 지금 옮기면 M4 리뷰가 이동과 변경을 함께 읽어야 한다 | 대기 — ⓒ |

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

## 되감기 M4 ⓐ 이관 기록 (POK-195)

M4(되감기 발행 층)는 PR 셋으로 나눠 들어온다 — ⓐ 생산 층 · ⓑ 렌더·경계 · ⓒ 발행.
이 절은 **ⓐ 가 무엇을 넣었고, 무엇이 일부러 빠졌고, 운영자가 무엇을 알아야 하는지**를 적는다.
ⓐ 는 되감기용 조각(③)을 만들어 S3 에 올리는 층이다. 되감기 목록(매니페스트)을 만들고
발행하는 코드는 아직 없으므로, ⓐ 만 배포해도 시청자에게 보이는 동작은 바뀌지 않는다.

### M4 ⓐ 용어

위 M3 절에서 푼 말(세션·PDT·컷오프·init·carrier 3열)은 다시 풀지 않는다.

| 말 | 뜻 |
|---|---|
| ②·③·init 축 | 한 조각을 목적별로 따로 올리는 세 갈래. ② = 녹화 파일 그대로(클립 소재 — 3번이 읽는다) · ③ = 되감기용으로 다시 짠 조각 · init = 회차의 머리 조각(MAP) |
| 재포장(remux) | 압축은 풀지 않고 파일의 상자(box) 구조만 다시 짜는 것. ③ 은 녹화 파일을 재포장해 만든다 |
| mtxi | MediaMTX 녹화기가 녹화 파일 머리말에 넣는 자체 상자. 녹화기 표식(녹화기 인스턴스마다 새 ID)과 누적 DTS(송출자 연결마다 0 에서 시작하는 미디어 시계, 나노초)가 들어 있다 |
| 시간 도장(pos) | ③ 조각 안에 적는 "이 조각은 재생 타임라인의 몇 초부터인가"(fMP4 `tfdt`). 재생목록의 달력 시각(PDT)과는 다른 값이다 |
| 보정값(offset) | 조각이 빈틈 없이 이어지도록 mtxi 에 더하는 값. 평소 0 이다 |
| 사슬 | 인덱서가 스트림마다 기억하는 "직전 회차 조각의 도장 재료". 다음 조각의 보정값을 정하는 데 쓴다 |
| 재수거 | 스위퍼(주기 회수기 — 기본 30초)가 `pending`·`failed` 로 남은 업로드를 다시 집어 올리는 것 |
| GAP | 어떤 조각의 ③ 이 끝내 올라가지 못해 되감기 목록에서 빈칸이 되는 것 |
| CAS | 조건이 맞을 때만 바꾸는 한 문장 UPDATE. 조건이 틀리면 0행이라 두 쓰기자가 서로를 덮지 못한다 |

### M4 ⓐ 로 켜진 것과 아직 없는 것

- **켜졌다**: ③ 생산자(Go 재포장)와 ③·init 업로드, ③ 결과 CAS 와 init 열 쓰기, init 이
  달라지면 회차를 끊는 처리(`ending(init_mismatch)`), 스위퍼의 ③·init 재수거와 축별 잔량
  지표, G7 skew 기록. 되감기 등재 조건인 `playback_upload_state='uploaded'` 가 처음으로 실제로
  참이 된다.
- **아직 없다**: 되감기 목록을 만드는 렌더(ⓑ), S3 발행과 송출 종료 전이(ⓒ).
- **설정·스키마**: 새 env 0, DDL 변화 0. 기존 `S3_BUCKET`·`SEGMENT_UPLOAD_*` 가 ③·init 에도
  그대로 적용된다. 장부 어휘는 하나 는다 — `stream_sessions.end_reason` 의 새 값
  `init_mismatch`(M3 의 `td_exceeded` 와 같은 부류, CHECK 없는 text 열).

**③ 은 플래그와 무관하게 돌고, 비용은 ⓐ 배포 시점부터 는다.**
`REWIND_SEED_ENABLED`(기본 `false`)가 가르는 것은 컷오프 주조 권한 하나다(위 M3 절과 같다).
③ 추출·업로드는 회차에 귀속된 조각마다, init 업로드는 회차마다 이 값과 무관하게 돈다(설계
`c3_extract_only` — 인덱서 `internal/indexer/upload.go` `requestRowUploads` 에 플래그 검사가
없다). 그래서 ⓐ 를 배포하는 순간부터 다음이 는다.

- S3 PUT: 스트림당 시간 900회 → 1,800회(조각마다 ② 에 ③ 이 하나 더해진다)
- S3 저장: 약 1.7배(ADR-057 수용분)
- init PUT: 회차당 1회
- CPU·메모리: 조각당 재포장 1회. 호스트 실측 p50 1.72ms·p95 2.36ms, 조각당 할당 p50 8.9MB
  (운영 장비 값은 미실측)

> **판단 대기** — 이 동작이 계약-세그먼트인덱스 5-5 6항("③ 추출도 같은 플래그로 컷오프
> 행부터")과 어긋나는지는 **kty·3번 판단 대기다(계획 부기 31 후보).** 결정이 나기 전까지
> 코드는 위 동작 그대로다.
> 〔추기 2026-09-25 — 닫혔다: 2026-09-24 kty 결정 (나)는 현 동작 유지다 — ③·init 실시간 추출은
> 플래그·컷오프와 무관하다. 계약 6항 문언을 이 동작에 맞게 고쳤고(팀 위키 PR #144 머지, main `7d12e55`)
> 3번에 통지했다(POK-195 코멘트 #10332). 편차가 사라져 계획 부기 31 은 결번이다.〕

**반면 재수거와 잔량 지표는 컷오프를 따른다.** ③ 스위퍼 조회와 ③ 잔량은 컷오프가 기록된
스트림의 컷오프 이후 조각만 본다(계약 5-5 6항 — `internal/index/upload_store.go`
`pendingPlaybackUploadsSQL`·`countPlaybackBacklogSQL`). 기본값(플래그 끔)에서는 새 스트림에
컷오프가 없으므로, 실시간 ③ 이 실패한 조각은 스위퍼가 다시 집지 않고
`upload_backlog axis=playback` 에도 잡히지 않는다 — 실패 로그(`upload_failed` 등)로만 보인다.
init 조회는 컷오프와 무관하다.

### ③ 축은 어떻게 도나

한 조각이 들어와 올라가기까지의 흐름이다.

```text
MediaMTX 녹화 파일(영상 1 + 소리 6)
  └ 인덱서: 크기 안정 → mtxi 판독 → 장부 INSERT → 사슬 전진(도장 계산)
      └ 업로드 요청 셋 — 형제라 하나가 거부돼도 나머지는 나간다
          ② 녹화 파일 그대로 PUT → ② CAS
          ③ 재포장 → init 대조 → PUT → ③ CAS
          init(회차의 행에서, 접수될 때까지) 재포장 → PUT → init CAS
  └ 스위퍼(30초): ② → ③ → init 순으로 남은 것을 재수거
```

#### 되감기 렌디션 — Go 재포장

③ 은 녹화 합본(영상 1 + 소리 6)에서 **첫 영상 트랙과 첫 소리 트랙만** 남긴 조각이다
(ADR-057 — 0번 소리가 종합 믹스다). 사이드카 프로세스 안에서 메모리로만 만든다. 디스크
쓰기·자식 프로세스·이미지 변경이 없다(`internal/playback/producer.go` `Remuxer.Produce`).

- **라이브러리 판을 녹화기와 맞췄다.** `github.com/bluenviron/mediacommon/v2` v2.9.3 은
  MediaMTX 녹화기가 이 파일을 쓸 때 쓴 판이다(포크 `v1.20.1-pokeclip.2` 실행 파일에 박힌 판).
  `TestRemuxMediacommonVersionMatchesRecorderPin` 이 `media/go.mod` 의 핀을 지키고,
  `TestRemuxOutputBytesGolden` 이 산출 바이트를 고정한다.
- **파트를 다시 자르지 않는다.** 입력 파트 수 = 산출 파트 수이고 `mfra` 도 붙이지 않는다.
- **init 과 조각을 한 번에 낸다.** init 은 트랙 파라미터만의 순수 함수라, 같은 송출 설정·같은
  재포장 산출(골든)이면 같은 회차의 어느 조각에서 만들어도 바이트가 같다(실측: 150조각·연결 3회 교체에 해시 1종).

#### 시간 도장 — mtxi 에 보정값을 더한다

③ 조각의 도장은 **그 조각의 mtxi + 보정값**이다(`internal/indexer/chain.go` `advanceChain`).
평소 보정값은 0 이라 조각 사이 빈틈이 없다. 보정값이 바뀌는 때는 둘이다(`stitchOffset`).

- **리셋** — 녹화기 표식이 바뀌거나(송출자 재접속·녹화기 드리프트 리셋) mtxi 가 뒤로 가면,
  직전 조각의 **먼저 끝난 트랙 끝**에 잇는다. 늦게 끝난 쪽에 이으면 소리 빈틈이 남는다(실측).
  직전 파일을 다시 읽지 못하면 장부 길이로 잇는다 — 최악 0.2초 소리 빈틈이고 역행은 없다.
- **구멍** — 같은 녹화기인데 mtxi 가 예상 끝보다 50ms 넘게 앞서면, 그 유실을 압축해 직전
  조각 끝에 잇는다(kty 결정 G).

**왜 이어 붙이나.** 회차 안에서는 끊김 표시(`EXT-X-DISCONTINUITY`)를 달지 않기로 했다(kty
결정 F · ADR-073). 표시가 있으면 hls.js 가 우리 도장을 버리고 경계마다 0.16–0.18초 멈췄다
(실측). 그래서 도장이 스스로 이어져야 한다. 표시 규칙의 코드는 ⓑ 렌더에서 들어온다.
재생목록의 달력 시각(PDT)은 M3 재귀식 그대로다 — 바뀐 것은 도장뿐이다.

사슬은 인덱서 루프 한 goroutine 에만 있다. 장부 행마다 정확히 한 번, 업로드 접수가 거부돼도
전진한다. 규칙은 넷이다.

- 회차가 바뀌면 보정값 0 에서 새로 연다. 그래서 TD 분할(목표 길이 초과로 회차를 가른 것)로
  연결 도중 열린 회차의 첫 도장은 0 이 아니라 그 시점의 mtxi 다.
- 회차에 귀속되지 않은 행은 사슬을 움직이지 않고, ③ 요청도 없다.
- mtxi 를 못 읽은 행은 직전 보관값이 있으면 기대값(직전 도장 + 장부 길이)에 고정해 올리고
  `segment_indexed` 로그에 `mtxi_pinned=true` 를 싣는다. 보관값이 없으면(회차 첫 행·재기동 뒤
  첫 행) ③ 요청을 내지 않는다 — 도장을 지어내지 않는다.
- mtxi 판독이 `FS_OP_TIMEOUT` 을 넘기면 그 조각은 커밋하지 않고 래치를 세운다(`mtxiT` — 길이
  판독과 같은 관문). 다음 수집이 다시 찾는다.

Idle·Scan 으로 확정된 꼬리 조각(아직 자랄 수 있는 조각)의 ③ 은 ② 와 같은 때 — 보류 해제·다음
조각 도착·포기 — 에 한 번만 요청한다. 유휴 추정 직후에 만들면 뒤늦게 써진 마지막 파트가 빠진
채 굳는다(`internal/indexer/upload.go` `releaseHeldPlayback`).

#### init 동일성 — 회차당 init 하나

init 이 같은가의 기준은 **재포장 산출 init 바이트의 sha256** 이다(설계 5.3ⓑ). 회차의 첫 init
업로드가 `init_s3_key`·`init_sha256`·`init_bytes`·`init_uploaded_at` 을 한 문장으로 쓰고, 결과를
넷으로 가른다(`internal/index/upload_store.go` `MarkInitUploaded`).

| 결과 | 뜻 | 워커의 처리(`internal/upload/axis_body.go` `markInitUploaded`) |
|---|---|---|
| `Success` | 이번에 처음 확정했다 | 해시를 `sessionInit` 에 적고, 기다리던 ③ 을 다시 넣는다 |
| `AlreadySame` | 같은 바이트로 이미 확정돼 있다(재시도·재기동) | 위와 같다 |
| `Mismatch` | 다른 해시로 이미 확정돼 있다 | `session_init_mismatch` ERROR + 백오프. S3 객체 보호는 아니다(알려진 한계) |
| `Missing` | 회차 행이 없다 | `upload_cas_rejected` ERROR(`init_mark=missing`) + 백오프 |

③ 조각은 **PUT 전에** 자기 산출 init 해시를 회차의 기대값과 대조한다(`playbackPayload`).
기대값은 스위퍼 작업이면 조회가 실어 온 회차 `init_sha256`, 실시간 작업이면 워커 메모리의
`sessionInit` 이다.

- **다르면** 올리지 않는다. 조각 `failed` 와 회차 `live → ending(init_mismatch)` 를 한 문장으로
  영속한다(`MarkPlaybackFailed` 의 결속 갈래). 다음 조각이 새 회차·새 init 을 연다.
- **기대값을 아직 모르면**(init 확정 전) 올리지도 실패로 적지도 않고 보류 목록에 둔다.

init 요청은 인덱서가 회차의 행에서 낸다. 이 프로세스에서 **접수될 때까지** 다음 행에서 다시
내고, 접수되면 멈춘다(`requestRowUploads` · `Indexer.initAdmitted`). 재기동 뒤 이어지는 회차도
첫 행에서 다시 내며, 장부에 이미 확정된 회차면 같은 송출 설정·같은 재포장 산출일 때 `AlreadySame` 으로
`sessionInit` 이 되살아난다(바뀌었으면 아래 알려진 한계 표의 `Mismatch` 행).
init 작업은 꼬리 크기 불일치에 `upload_size_mismatch` 경고만 남기고, PUT 하는 사이의 입력
변화는 무시하고 끝까지 간다. init 은 머리말만의 순수 함수라 잘린 실물 위험이 없다
(`internal/upload/worker.go` `measureInput`·`attemptOnce`).

#### 무결성 4항과 재시도 부류

재포장은 아래 넷 중 하나면 산출 없이 실패한다. 부분 산출이 없으므로 잘린 ③ 이 굳지 않는다
(`internal/playback/producer.go`). 재시도는 부류별이다(`axis_body.go` `produce`·`sameInputFailsAgain`).

| 오류 | 뜻 | 처리 |
|---|---|---|
| `ErrMalformedInput` | 입력을 해석할 수 없다(절단·손상) | 재시도 사다리(`SEGMENT_UPLOAD_RETRY_MAX`, 기본 4회). 쓰는 중에 잘린 입력이면 다시 읽어 넘는다. 소진하면 ③ `failed` |
| `ErrMissingTracks` | 영상 1 + 소리 1 을 남길 수 없다 | 첫 시도에서 ③ `failed` + 키 백오프 |
| `ErrEmptyTrack` | 남긴 트랙에 샘플이 없다 | 첫 시도에서 ③ `failed` + 키 백오프 |
| `ErrInputTooLarge` | 입력이 16MiB 를 넘는다(읽기 전에 거른다) | 첫 시도에서 ③ `failed` + 키 백오프 |

- `ErrMalformedInput` 을 뺀 셋은 같은 입력이면 늘 같은 결과라 사다리(1+2+4초)를 태우지 않는다.
  하나뿐인 업로드 워커가 조각마다 7초씩 묶이면 모든 스트림의 ②·③ 이 큐에서 넘친다. S3 에 닿지
  않았으므로 브레이커(연속 실패 차단기) 판정에도 넣지 않는다(`failUnproducible`).
- init 도 같은 규칙이다. 다만 init 에는 실패 CAS 가 없어 장부는 그대로 두고 백오프만 건다.
- ③ 을 만들 수 없는 조각도 **② 클립 소재는 그대로 올라간다.** 게이트·장부 열·결과 통지가
  축마다 따로다(회귀 `TestPlaybackProduceFailureLeavesArchiveOfSameSegmentUploading`).
- ③ 입력이 아직 자라는 꼬리면 재포장하지 않고, PUT 하는 사이 입력이 자라면 확정하지 않는다.
  ③ CAS 는 산출 길이를 `playback_bytes` 에 적는다(조건이 아니라 기록하는 값).

#### ③·init 재수거 — 스위퍼 3축

스위퍼는 회차(tick) 한 번에 **② → ③ → init** 을 차례로 돈다(`internal/upload/sweep.go`
`sweepAxes`). 축마다 조회·커서·정체 수·잔량을 따로 든다. ② 가 맨 앞인 이유: 세 축이 큐 하나를
나눠 쓰므로, ② 를 앞에 둬야 M3 의 ② 회차 계약(회차 시작에 큐가 비어 있으면 두 단계를 다
접수한다)이 그대로다.

| 축 | 무엇을 집나 | 조회 |
|---|---|---|
| ② | M3 그대로 | `pendingArchiveUploadsSQL` |
| ③ | `playback_upload_state` 가 `pending`·`failed` 인 조각 중 회차 있음 · 컷오프 기록 있음 ∧ seq ≥ 컷오프 · 회차가 `ended` 아님(`ending` 은 집는다) · 되감기 키·PDT 있음 | `pendingPlaybackUploadsSQL` |
| init | `init_uploaded_at` 이 NULL 이고 `ended` 가 아닌 회차. `local_path`·`bytes` 가 있는 그 회차의 최신 조각(`ORDER BY seq DESC`)에서 init 을 다시 만든다. 그 조각이 아직 자라는 꼬리면 시도마다 `upload_size_mismatch` WARN 1줄을 남기고 진행한다(init 은 머리말만 쓰므로 잘릴 위험이 없다). 컷오프와 무관 | `pendingInitUploadsSQL` |

- 스위퍼가 집은 ③ 작업은 장부에 도장이 없으므로 **회차 보정값 표로 다시 만든다** — 실시간과
  같은 값이다. 표에 그 조각의 줄이 없으면(재기동 뒤·24시간 경과 뒤) 도장을 지어내지 않고
  `failed` 로 거부한다(`upload_failed reason=no_offset_table`).
- 잔량 지표 `upload_backlog` 는 축마다 한 줄이고, 라벨(`axis=archive|playback|init`)과 수치가
  같은 축에서 나온다. ③ 잔량도 컷오프를 따른다(`countPlaybackBacklogSQL`).

#### 회차별 메모리 — 보정값 표·보류 목록·sessionInit

업로더가 회차마다 세 가지를 메모리에 든다(`internal/upload/sessions.go` `sessionMemory`).
재기동하면 사라진다 — 재기동 복구는 범위 밖이다(아래 알려진 한계).

| 이름 | 무엇 | 쓰는 자리 | 수명(`SessionTTL`, 기본 24시간)의 기준 |
|---|---|---|---|
| 보정값 표 | `{seq_from → 보정값}` 줄과, mtxi 를 못 읽은 조각의 고정 줄 `{seq → 도장}` | 인덱서의 실시간 ③ 요청(`RequestUpload`)만. 접수가 거부돼도 적는다. 스위퍼·보류 재요청은 적지 않는다 | 그 회차의 마지막 실시간 ③ 요청 |
| `sessionInit` | 워커가 확정한 그 회차 init 해시 | init CAS `Success`·`AlreadySame` | 확정과 마지막 ③ 요청 중 늦은 쪽 |
| 보류 목록 | 기대 init 을 몰라 기다리는 ③ 작업(도장 확정분만, 회차당 `HeldPerSession` 64개) | ③ 대조 보류. init 이 확정되면 seq 순으로 다시 넣는다. 넘친 몫은 `pending` 으로 남아 스위퍼 몫이다(컷오프가 있을 때). 다시 넣다가 큐가 차면 그 자리에서 멈추고 나머지를 목록에 되돌린다(다음 워커 완료·tick 에 다시 넣는다). ③ 이 확정되면 같은 조각의 보류 사본을 빼고 확정 표시를 남긴다(`dropHeld`) — 재요청이 그 조각을 꺼내 간 사이에 확정됐어도 목록에 되돌리지 않는다 | 마지막 보류 |

- 청소 자리는 스위퍼 tick 이다(`tidySessions` — 새 select case 없음).
- 회차 종료를 보고 `sessionInit`·보류 목록을 지우는 `ForgetSession` 은 있지만, ⓐ 에는 부르는
  자리가 없다. 수명이 같은 일을 한다. 배선은 ⓑ/ⓒ 몫이다. 보정값 표는 `ForgetSession` 도
  남긴다 — 정산 창(`ending`)의 스위퍼 재시도가 같은 보정값을 써야 한다.
- ③·init 결과 이벤트 집합(`Dirty`)은 ⓐ 에서 만들지 않는다. 소비자(발행 루프)가 ⓒ 라서다.

#### G7 skew 기록

G7(설계 9절 게이트 — PDT(벽시계 축)와 시간 도장(미디어 시계)의 어긋남 분포를 기록한다)은
ⓐ 에서 기록까지 들어왔다. 사슬이 전진한 직후 `rewind_pdt_media_skew_seconds` 를 남긴다
(`internal/indexer/chain.go` `recordSkew`). 한 줄의 값은 다음 식이다.

```text
skew(k) = (pdt(k) − pdt(k−1)) − (pos(k) − pos(k−1))
```

- 같은 회차의 **인접 쌍 중 보정값이 그대로인 쌍만** 잰다. 이어 붙인 쌍(리셋·구멍)의 차이는
  압축한 유실·재접속 시간이지 어긋남이 아니다. 사이에 비귀속 행이 낀 쌍과 회차 경계 쌍도 빠진다.
- 수준은 **Debug** 다. 조각마다 한 줄이라 Info 면 스트림당 시간 900줄이 된다. 분포를 모으려면
  `LOG_LEVEL=debug` 로 켠다. G7 완결 판정은 ⓑ 의 목록 검사(S7 — PDT 엄격 증가)가 들어올 때다.

### M4 ⓐ 운영 — 되돌리기·튜너블·로그·포크 태그

#### 되돌리기와 긴급 정지

- **되돌리기는 머지의 역순(ⓒ → ⓑ → ⓐ)으로만 한다.** ⓑ·ⓒ 가 ⓐ 의 API 에 기대므로 ⓐ 만
  떼면 빌드가 깨진다(계획 A5). 머지 커밋마다 역순으로 `git revert -m 1 <머지 커밋>` 한 뒤
  `docker compose build segment-indexer && docker compose up -d segment-indexer` 로 반영한다.
  compose 는 로컬 빌드라 "이미지 태그를 되돌리는" 손잡이가 없다.
- **되돌려도 이미 쓴 것은 남는다.** 장부의 ③ 상태·init 열, S3 의 ③·init 객체,
  `ending(init_mismatch)` 로 끝난 회차. develop(M3) 코드는 ③·init 열을 읽지 않고 live 회차에만
  조각을 붙이므로 동작에는 영향이 없다.
- **긴급 정지 = 업로더 전체 끄기(`S3_BUCKET` 빈값).** ③ 만 끄는 새 env 손잡이는 만들지 않았다.
  주의가 둘이다.
  - 권장 override 의 `${S3_BUCKET:-pokeclip-media-demo-2557}` 는 빈값을 기본 버킷으로 바꾼다.
    셸에서 `S3_BUCKET=""` 를 줘도 꺼지지 않는다. `docker-compose.override.yml` 의 그 줄을
    `S3_BUCKET: ""` 리터럴로 고친 뒤 `docker compose up -d --force-recreate segment-indexer`
    한다. 예시 파일의 `:-` 를 `-` 로 바꾸는 수정은 ⓒ 에 들어간다.
  - 업로더 전체가 서므로 **② 클립 소재 업로드(3번 소비자)도 멈춘다.**

#### 튜너블과 고정값

새 env 는 없다. 값은 코드에 있다.

| 이름 | 값 | 자리 | 뜻 |
|---|---|---|---|
| `HeldPerSession` | 64 | `upload.Options` 기본값 | 회차마다 보류 목록에 들 수 있는 ③ 작업 수 |
| `SessionTTL` | 24시간 | `upload.Options` 기본값 | 회차별 메모리의 수명. 녹화 파일 보존(MediaMTX `recordDeleteAfter` 기본 1일)과 같은 창이다 — 그 뒤의 재생성은 어차피 원본이 없다 |
| `maxInputBytes` | 16MiB | `internal/playback/producer.go` 상수 | 재포장 입력 상한. 조각을 통째로 메모리에 올리므로 점유 = 동시 워커 수 × 입력의 약 4.5배. 영상 8Mbps + 소리 6트랙인 6초 조각(6.5MiB)의 2.4배다 |
| `mtxiGapTolerance` | 50ms | `internal/indexer/chain.go` 상수 | mtxi 가 예상 끝보다 이 값을 넘게 앞서야 구멍으로 보고 잇는다 |

#### 로그 변화

새 이름은 둘이다 — 로그 키 `session_init_mismatch` 와, 설계가 이름을 정한 G7 신호
`rewind_pdt_media_skew_seconds`. 나머지는 기존 이름에 속성·사유 값이 붙었다. ③·init 작업의
로그는 전부 `axis=playback` 또는 `axis=init` 을 싣는다.

| 로그 | 바뀐 것 | 수준 |
|---|---|---|
| `session_init_mismatch` | **신설.** ③ 산출 init 이 회차 MAP 과 다르다(PUT 전 대조), 또는 init CAS 가 `Mismatch` 다 | ERROR |
| `rewind_pdt_media_skew_seconds` | **신설.** G7 skew 한 쌍(위 「G7 skew 기록」) | DEBUG |
| `segment_indexed` | `mtxi_pinned`(bool) — mtxi 를 못 읽어 도장을 기대값에 고정했으면 true | INFO |
| `segment_uploaded` | ③·init 에서도 나온다. `bytes` 는 올린 산출 길이다 | INFO |
| `upload_size_mismatch` | ③·init 은 크기 속성이 `input_bytes`(입력 파일 크기 — PUT 크기가 아니다). ② 는 종전대로 `put_bytes` | WARN |
| `upload_mark_skipped` | `reason=init_pending`(대조 보류, DEBUG) · `reason=held_list_full`(보류 목록 초과 — 회차당 첫 번만 WARN, 이후 DEBUG) | DEBUG·WARN |
| `upload_queue_full` | 보류 재요청(`requeueHeld`)의 포화는 drain(보류 목록을 꺼내 큐에 다시 넣는 한 차례 — init 확정·작업 완료·스위퍼 tick 마다 돈다) 한 번에 요약 1줄이다. 그 줄의 `stream_id`·`seq` 는 포화를 만나 멈춘 작업이고, `held_returned` 는 스트림·회차를 가리지 않은 되돌리는 총수다(그 사이 ③ 확정 표시로 걸러지는 몫은 실제 목록에 안 들어가 목록 수는 이보다 작을 수 있다). 여러 회차를 합친 수라 `session_id` 는 싣지 않는다. 실시간·스위퍼 접수의 포화는 종전대로 요청 1건마다 WARN 1줄이다 | DEBUG(재요청)·WARN(종전) |
| `upload_failed` | ③·init 산출 실패, 그리고 스위퍼 ③ 을 표 없이 거부(`reason=no_offset_table`) | ERROR |
| `upload_target_rejected` | `reason=no_session` — 회차 없는 ③ 작업(호출자 결함) | ERROR |
| `upload_cas_rejected` | `init_mark=missing` — init CAS 에 회차 행이 없다 | ERROR |
| `fs_op_stalled` | `op=read` 에 `site=mtxi` 또는 `site=track_ends` — 도장 재료 판독 시간 초과 | ERROR |
| `upload_sweep`·`upload_backlog`·`sweep_cursor_stalled`·`sweep_aborted_queue_full`·`upload_sweep_failed` | 스위퍼 회차가 축마다 한 벌씩 낸다(`axis` 라벨). 큐가 차면 `upload_backlog` WARN 이 회차당 세 줄일 수 있다(같은 `queue_len`) | 종전 |

#### 포크 태그를 올릴 때 — ③ 재포장 핀 대조

③ 재포장은 녹화기(포크 MediaMTX)와 **같은 라이브러리 판**을 쓴다는 전제에 선다. 그래서 포크
이미지 태그를 올릴 때마다 아래를 함께 한다(계획 7절 9).

1. 새 포크 실행 파일에 박힌 `mediacommon/v2` 판을 `go version -m <실행 파일>` 로 읽는다.
2. `media/go.mod` 의 핀(v2.9.3)과 다르면 핀과 `TestRemuxMediacommonVersionMatchesRecorderPin`
   의 기대 판을 함께 새 판으로 올리고, `internal/playback` 테스트를 다시 돌린다.
   골든 init 바이트(`TestRemuxOutputBytesGolden` 이 고정한 산출)가 바뀌면 배포 때 이어지는 모든 회차의 첫 행
   init 재요청이 아래 알려진 한계 일곱째 행(init CAS `Mismatch`) 경로에 닿는다 — 배포 뒤
   `session_init_mismatch` ERROR 는 이 원인부터 본다.
3. 새 포크로 녹화한 조각으로 `internal/playback/testdata/segment_1v6a.mp4` 를 바꾸고, 실물 판독
   테스트(`TestReadMtxiReadsRecorderValues` 등)를 다시 돌린다. 기대값은 테스트 대상 코드가 아닌
   별도 판독으로 다시 뽑는다. 녹화기 쪽 mtxi 형식 변화는 이 단계로만 잡힌다 —
   `TestMtxiLayoutContract` 는 우리 상자 정의의 변경만 잡는다.

### M4 ⓐ 알려진 한계 — 다음 버전 수리 대상

kty 결정(2026-09-19): *"사이드카가 재기동 되는 경우는 고려하지 않는다, 나중에 버전업하면서
고칠 것에 남겨둬"*. 아래 표는 계획 4.2-R 의 알려진 한계 표 전건이다. 공통 배경: 보정값 표·
사슬·`sessionInit` 은 프로세스 메모리에만 있다.

| 한계 | 영향 | 수리안 후보 |
|---|---|---|
| 한 회차 안에서 **보정값이 0 이 아니게 된 사건**(송출자 재접속 리셋·녹화기 드리프트 리셋·같은 녹화기 안 구멍)과 **사이드카 재기동이 둘 다** 일어난 경우. 사건 뒤에 재기동하면 보정값을 잃고, 재기동해 있는 동안 사건이 나면 사건 자체를 못 본다. 재기동 뒤 스위퍼 재생성은 표가 없어 거부한다(GAP). mtxi 는 MediaMTX 가 살아 있어도 송출자 연결이 바뀌면 0 으로 돌아간다(0.728초 재접속 실측) | 재기동 지점에서 도장이 뒤로 가 그 회차 되감기가 그 지점에서 깨질 수 있다 — 규격상 끊김 표시가 필요한 "시간 도장 순서 변경"인데 달지 못한다. 회차가 바뀌면 복구된다. **둘 중 하나만 일어난 회차는 도장 역행이 없다.** 다만 재기동만 일어나도 재기동 전 실패분은 GAP 이 될 수 있다(셋째 행) | 마지막으로 올린 ③ 의 첫 도장을 읽어 역산한다(보정값 = 그 도장 − 그 조각의 mtxi). 회차당 객체 1개 |
| **mtxi 를 읽지 못한 조각**(상자 부재·해석 실패). 직전 보관값이 있으면 고정 도장(직전 도장 + 장부 길이)으로 올리고, 없으면(회차 첫 조각·재기동 뒤 첫 행) ③ 요청이 없다(GAP) | 고정 발행: 그 조각 자리에 실제 구멍·리셋이 있었다면 그 이음매만 장부 길이로 잇는다(최대 0.2초 소리 빈틈, 역행 없음). 다음 조각에서 술어가 잡는다. 첫 조각 GAP: 회차 첫 조각 하나를 잃는다. 실측상 판독 실패는 154조각 중 0건 | 못 읽는 파일의 트랙 끝은 알 수 없으므로 지금 처방이 상한이다 — 기록용 |
| **재기동만 일어난 회차** — 재기동 전 조각의 업로드 실패분과 재기동 뒤 첫 행의 판독 실패는 표 줄이 없어 GAP 이 된다(도장 역행은 없다) | 그 조각들만 GAP. 실시간 경로는 영향이 없다. 컷오프가 있는 스트림이면 스위퍼가 파일이 지워질 때까지 그 행을 다시 집고 거부한다(잔량 잡음) | 표 영속화(재기동 복구 — kty 결정 B′ 로 범위 밖, 다음 버전) |
| **모호한 커밋** — INSERT 는 커밋됐는데 드라이버 오류로 실패처럼 보여 재시도하면 `InsertDuplicatePath` 로 끝나고 사슬 전진(`advance`)이 불리지 않는다. 다음 행이 seq 충돌 재적재를 거치면 사슬이 빠진 행을 비귀속 행처럼 보고 압축한다 | 빠진 행을 스위퍼가 표 줄(보정값 0)로 재생성하면 두 조각이 그 길이만큼 겹친다(회차 안·표시 없음). 창은 극히 좁다 | `TailRow` 에 회차 정보가 없어 구분할 수 없다 — 「빠진 행」 판정을 넣으려면 장부 재조회가 필요하다(다음 버전) |
| 재기동 뒤 이어지는 회차에서 **접수된 init 작업이 확정 없이 끝나면**(CAS DB 오류·`Missing`·산출 실패(사다리 소진·즉시 실패 3종)·PUT 사다리 소진·브레이커 거부·EMFILE·stat 실패·S3 일시 장애 등) 그 프로세스의 `sessionInit` 은 되살아나지 않는다. 루프는 접수 여부만 알고, 장부에 init 이 이미 확정된 회차는 스위퍼 init 조회도 집지 않는다 | 그 회차 실시간 ③ 은 대조 보류(회차당 64개) 뒤 스위퍼 몫이다. ⓐ 단독 국면(컷오프 없음)에서는 올라가지 않고, ⓒ 가 스위치를 켠 뒤로는 스위퍼(30초 주기)가 회차 기대값을 실어 올린다 | 워커→루프 결과 통로(`Dirty`)로 미복원을 감지해 재요청한다 — ⓒ 착지 뒤 검토 |
| **init 잔량이 M4 동안 영구히 남을 수 있다.** M4 에는 `ended` 전이가 없어 「`ended` 제외」가 실제로 거르는 것이 없고, init 조회·잔량 조건이 `init_uploaded_at IS NULL AND state <> 'ended'` 뿐이다(보존 창·컷오프 한정 없음). M3 로 운영된 DB 의 회차(init NULL), 원본이 1일 보존을 넘긴 회차, 확정 못 한 회차가 여기 쌓인다 | 데이터·재생 영향은 없고 경보 소음이다(M6 까지). 원본이 없는 회차는 프로세스가 뜰 때마다 `upload_file_missing` WARN + 격리되고, 30건을 넘으면 스위퍼 회차(30초)마다 `upload_backlog axis=init` WARN 이 난다 | **kty 회부**(계획 부기 31 후보와 같은 축): init 조회·잔량을 원본 보존 창(`start_wall_utc > now() − SessionTTL`)으로 한정하거나, 설계 5.5.4 #2 「컷오프-인지」를 init 에도 적용 〔추기 2026-09-25 — 부기 31 은 닫혔다(위 「M4 ⓐ 로 켜진 것과 아직 없는 것」의 추기). 이 회부는 그와 별개라 아직 판단 대기다.〕 |
| **init CAS `Mismatch` 는 S3 객체 보호가 아니다** — PUT 이 CAS 보다 먼저라, 다른 본문이 같은 키에 올라간 뒤에야 장부가 기존 해시를 지킨다 | 같은 송출 설정·같은 재포장 산출(골든)이면 오지 않는다. 다만 ⓐ 에서 회차는 방송을 넘어 이어진다(`session/registry.go` — live 회차가 있으면 TD(목표 길이) 초과가 아닌 한 계속 붙는다). 그래서 사이드카 재기동 뒤 그 회차에 처음 들어온 조각이 해상도 등 송출 설정을 바꾼(A → B) 방송의 것이면, 재기동 뒤 첫 행의 init 재요청이 이 분기에 닿는다 — B 의 init 을 먼저 PUT 하고 CAS 가 `Mismatch` 라, ERROR 1줄이 남고 그 회차 init 객체는 B 로 덮이며 그 회차 실시간 ③ 은 전부 대조 보류된다. 재포장 산출을 바꾸는 배포(골든 갱신 — 위 「포크 태그를 올릴 때」)로 재기동했으면 이어지는 모든 회차가 같은 경로에 닿는다. 송출 설정 변경 쪽은 ⓒ 의 송출 종료 전이가 회차를 끊어 원인째 닫고, 배포 쪽은 회차가 배포를 넘어 이어져 ⓒ 로도 닫히지 않으므로 오른쪽 수리안이 닫는다 | 조건부 PUT(`If-None-Match`)·내용 주소 게시 — ⓒ `publish.Store` 의 조건부 PUT 축과 함께 검토 |
| **ⓐ 단독 국면에서 방송 사이 휴지가 24시간을 넘으면 다음 방송의 실시간 ③ 이 보류에 묶인다.** 회차가 방송을 넘어 이어지는 동안 `sessionInit`(과 보정값 표)은 수명(`SessionTTL` 24시간)으로 지워지는데, 인덱서의 `initAdmitted`(이 프로세스에서 init 요청이 접수된 회차)는 프로세스 수명 동안 남아 init 재요청이 나가지 않는다. 그래서 다음 방송의 실시간 ③ 이 전부 `init_pending` 보류로 남고 drain 으로도 풀리지 않는다(검수 탐침 2026-09-24: 25시간 휴지 뒤 둘째 방송의 ③ 마킹 0건). 실시간 경로는 재기동해야 init 재요청이 `AlreadySame` 으로 `sessionInit` 을 되살린다(휴지 중 송출 설정이 바뀌었거나 재포장 산출이 바뀐 배포로 재기동했으면 위 `Mismatch` 행 경로) | 시청자 영향 0 — ⓐ 단독 국면에는 ③ 소비자가 없고, 그 행들은 ⓒ 가 켠 컷오프보다 앞이라 등재 대상이 아니다. 컷오프가 있는 스트림이면 보류분도 보류 상한 초과분도 스위퍼가 장부 기대값으로 집어 올린다(검수 탐침: 보류 1 → 스위퍼 성공 → 보류 0) — 남는 것은 스위퍼 주기만큼의 지연과 중복 작업(실시간 시도가 보류에서 멈춘 조각을 스위퍼가 다시 처리)이다. ⓒ 에서는 송출 종료 전이가 회차를 끊어 닫는다 | 기록용(코드 무변경) |
| **보류 재요청의 거울상 경합** — tick 이 ③ 작업을 꺼낸 뒤 재요청하기 전에 같은 조각의 스위퍼 사본이 먼저 확정되면(그 조각의 in-flight 가 이미 풀려 있다) tick 의 재요청이 그대로 접수돼, 같은 바이트를 한 번 더 PUT 하고 `upload_cas_rejected` WARN 1줄이 난다(µs 창 — ⓒ 처럼 컷오프가 있어 스위퍼가 ③ 을 집을 때만). 장부는 CAS 가 지킨다. 확정 표시(tombstone)는 반대 순서(재요청이 거부된 뒤 `claimReady` → `giveBack` 사이에 확정되는 경우)만 막는다 | 재PUT 1회·WARN 1줄, 데이터 영향 0. 확정 표시 메모리는 보류를 한 번이라도 겪은 회차에서 24시간 동안 최악 약 21,600개·약 538 KiB(검수 탐침)이고, 24시간 뒤 비운다 | 워커 PUT 직전 확정 표시 검사(다음 버전) · 기록용 |

그 밖의 한계:

- PDT(벽시계 축)와 도장(미디어 시계)은 이제 다른 축이다. 둘의 어긋남은 G7 skew 가 계속 본다.
- mtxi 는 상류가 문서화하지 않은 녹화기 내부 상자다(문서화 요청 `bluenviron/mediamtx#6153` 은
  종료됐다). 같은 연결 안에서 mtxi 는 미디어 시계 그 자체라(tfdt 2,770개 재기입 오차 0.000초
  실측 — ADR-042, 상류 재생 서버도 같은 축을 쓴다) 도장 재료로 쓴다. 배치(44바이트·판 0)가
  바뀌면 판독이 실패하고, 배치는 같은데 뜻이 바뀌는 변경은 위 「포크 태그를 올릴 때」 절차만
  잡는다.

### M4 ⓐ 설계와 다르게 한 것

계획 부기 27–30 이다. 부기의 나머지 행 가운데 ⓑ 몫은 아래 「되감기 M4 ⓑ 이관 기록」 절이 싣고, 그 밖의 행(ⓒ 몫
포함)은 ⓒ 의 M4 종합 이관 기록이 싣는다.

1. **시간 도장의 원점(부기 27 — kty 결정 B′, 설계 5.1 갱신 후보).** 설계 5.1·5.1.1 은 전역
   0점을 논리 세션 첫 조각의 첫 샘플로, 앵커를 장부 `playback_pdt` 재귀식으로 정했다. 구현은
   도장 = 조각 자기 mtxi + 보정값이다. 회차 첫 조각을 0 으로 빼지 않으므로, TD 분할로 연결
   도중 열린 회차는 첫 도장이 0 이 아니다 — 회차 경계에는 늘 DISCONTINUITY+MAP 이 있어 재생에
   무해하다. PDT 축은 설계 그대로다.
   〔추기 2026-09-25 — 「회차 경계에는 늘 DISCONTINUITY+MAP」은 ⓑ 가 넣은 표시 규칙과 다르다. TD 분할로
   연 회차는 계승하지 않아(`inherits_session` NULL) 첫 조각에 끊김 표시가 없고, 그 회차 목록은 앞 회차 조각
   없이 자기 조각과 MAP 으로 시작한다(목록 안에 회차 경계가 없다). 계획 부기 27 은 이 구절을 뺐다 — 아래
   ⓑ 절 「바꾸기 전에 이유를 볼 규칙」.〕
2. **③ 생산 수단(부기 28 — kty 결정 E, ADR-057 문언 갱신 필요).** ADR-057 결정 1 과 clarifier
   Q2 는 `ffmpeg -map 0:v:0 -map 0:a:0 -c copy` 였다. 착수 실측에서 ffmpeg 산출은 조각마다 init 이
   달랐고(비트레이트 칸 12바이트) 파트를 다시 잘랐다. Go 재포장에는 두 문제가 원인째 없다.
   만드는 곳(사이드카)·시점(조각 완성 뒤)·트랙 선택(첫 영상 + 첫 소리)·S3 정본은 그대로다.
3. **끊김 표시 규칙(부기 29 — kty 결정 F).** 설계 4.3·계약3 7절·계약-세그먼트인덱스는
   `is_discontinuity` 행과 회차 경계 첫 조각에 표시를 단다. 새 규칙은 「직전 회차를 잇는 회차의
   첫 조각에만」이다. 장부의 `is_discontinuity` 컬럼·DDL 은 그대로다. 재접속 이음매의 영상 도장
   겹침(최대 0.22초)은 규격 문언상 표시가 필요하지만, 실측(hls.js 1.5.15·1.7.3·Safari)을 근거로
   의도적으로 벗어난다. 설계·계약 2종 갱신과 3번 통지가 ⓑ 머지 전에 필요하다. 렌더 코드는 ⓑ
   에서 들어오고, ⓐ 는 그 전제인 이어 붙이기를 만든다.
   〔추기 2026-09-25 — ⓑ 머지 전 관문은 충족됐다: 팀 위키 PR #143 머지(main `a5faeef`, 2026-09-24 —
   계약3·계약-세그먼트인덱스의 결정 F 문언과 ADR-073) · 볼트 설계 r17 4.3 대체 표식(`3dc1ebc`) · 3번·2번
   통지(POK-195 코멘트 #10329·#10330, 2026-09-23). 렌더 코드도 들어왔다 — 아래 「되감기 M4 ⓑ 이관 기록」 절.〕
4. **G7 skew 산식(부기 30 — 설계 갱신 후보).** 설계는 「조각 간 증분 비교」로만 적었다. 구현은
   위 「G7 skew 기록」의 식이고, 같은 회차의 인접 쌍 중 보정값이 같은 쌍만 잰다. 기록 자리는
   사슬 전진 직후(PDT 와 도장을 둘 다 아는 유일한 자리)이고, PDT 원천은 INSERT 가 돌려주는
   carrier(`SeedResult.PlaybackPDT`)다.

### M4 ⓐ 미확인과 판단 대기 (정직 서술)

미확인 — 이 기록을 쓴 2026-09-24 기준이다.

- compose 실측은 **했다**(2026-09-24, dev compose + 가짜 S3(MinIO, https)): 실제 RTMP 송출로
  ③ 객체 24개·init 1개가 버킷에 생겼고, `docker diff segment-indexer` 는 프로세스가 없는 같은
  이미지의 대조군 컨테이너와 한 줄도 다르지 않았다(디스크 쓰기 0 — G4). `/recordings` 쓰기는
  `Read-only file system` 으로 거부된다. ③ 작업 한 건(열기·재포장·PUT·확정)은 컨테이너에서
  p50 약 23ms, PUT 만 p50 12ms 였다(로컬 MinIO 기준 — 운영 S3 와는 다르다).
- 실 파이프라인이 만든 ③ 객체의 hls.js 1.5.15 재생도 **확인했다**: 12조각을 정적 m3u8 에
  물려 해석 오류 0, 버퍼 단일 구간, seek·재생 진행. 같은 회차에 51분 뒤 다시 송출하면서 사이드카를
  재기동한 경우(알려진 한계 첫째 행의 실물)에는 경계에서 도장이 3,049초 앞으로 뛰었는데 1.5.15 는
  이를 재정렬해 재생했다 — 다른 플레이어·작은 점프의 반응은 재지 않았다.
- 실 S3 ③ PUT 스모크(선택 항목)는 하지 않았다(자격증명이 필요하다).
- 운영 장비의 재포장 소요·메모리는 모른다. 호스트·dev 컨테이너 실측값만 있다.
- 워커가 여럿일 때의 보류 목록 경합은 실측할 수 없다(워커가 하나다). 잠금 설계와 `-race`
  테스트로만 확인했다.
- 슬레이트가 켜진 dev 경로(`pokeclip-m4-test` — 이 PR 이 아니라 별도 dev 소PR 이 넣는 경로)에서는 송출자가 재접속해도 mtxi 누적값이 0 으로
  돌아가지 않았다(서버가 슬레이트를 내보내는 동안 녹화기 시간축이 계속 흐른다). 이 문서의
  「연결이 바뀌면 0 으로 돌아간다」는 슬레이트 없는 경로의 실측이다. 사이드카가 살아 있으면 같은
  녹화기 안 구멍 규칙이 이음을 맡으므로 결과는 같다.

판단 대기 — 코드는 지금 동작 그대로다.

- ③·init 의 플래그 무관 동작이 계약 5-5 6항과 어긋나는지(위 「M4 ⓐ 로 켜진 것과 아직 없는
  것」, 계획 부기 31 후보) — kty·3번.
  〔추기 2026-09-25 — 닫혔다: kty 결정 (나) · 계약 6항 개정(팀 위키 PR #144, main `7d12e55`). 위 인용 블록의
  추기와 같다.〕
- init 잔량 영구 잔존의 보정(알려진 한계 여섯째 행 — 보존 창 한정 또는 컷오프-인지) — kty,
  부기 31 과 같은 축.
  〔추기 2026-09-25 — 부기 31 은 닫혔지만 이 항은 별개라 아직 판단 대기다.〕

### M4 ⓐ 출처

- 설계·계약: 팀 위키 `PokeClip-LLM-WIKI` — `contracts/계약-세그먼트인덱스.md`(5-5 재수거 규범·
  6항 컷오프), ADR-042·ADR-044·ADR-057·ADR-073.
- 이번 마일스톤 계획: POK-195 M4 작업 산출물(계획 r27 — 2.1절·4절 PR ⓐ·4.2-R·5절·6절·7절 9·
  부기 27–31).
- 결정: kty 결정 E(Go 재포장)·B′(mtxi 도장)·F(회차 안 표시 생략) 2026-09-19 ·
  G(구멍도 이어 붙임)·H(상수 계수·입력 상한 16MiB) 2026-09-23.
- 실측: Go 재포장 스파이크와 10분 실측(150조각·영상 1 + 소리 6·연결 3회 교체·hls.js 1.5.15,
  2026-09-19), 재개 실측(꼬리 조각·입력 상한·회차 안 구멍 재생·Safari·hls.js 1.7.3, 2026-09-23).
- 코드: 이 절이 인용한 파일·함수(PR ⓐ 커밋 2–6·r4-fix·r5-fix 개발 산출 보고와 대조).

## 되감기 M4 ⓑ 이관 기록 (POK-195)

M4 의 둘째 PR(ⓑ 렌더·경계)이 **무엇을 넣었고, 무엇을 일부러 ⓒ 로 넘겼고, 설계와 어디가 다른지**를
적는다. 다음 사람이 「왜 이렇게 했나 · 무엇이 일부러 빠졌나」를 다시 묻지 않게 하는 것이 목적이다.
ⓑ 는 되감기 목록을 **만들고 검사하는 순수 코드**이고, 그 코드를 부르는 발행 루프는 ⓒ 가 만든다.
그래서 ⓑ 만 배포해도 시청자 화면·비용·장부는 바뀌지 않는다.

### M4 ⓑ 용어

위 M3·ⓐ 절에서 푼 말(세션·PDT·컷오프·init·③·GAP·CAS)은 다시 풀지 않는다.

| 말 | 뜻 |
|---|---|
| 되감기 목록 | 한 회차의 되감기 URL(`/dvr/{stream}/{session}/index.m3u8`)이 내주는 HLS 미디어 재생목록 — 조각마다 PDT·길이·URI 를 한 줄씩 적은 텍스트 |
| 렌더 | 장부 행을 목록 본문 바이트로 옮기는 것 |
| settled | 조각이 목록에 실릴 수 있는 상태. 컷오프 이상이고 회차·PDT·③ 키가 있으며, ③ 이 올라갔거나(`uploaded`) GAP 원장(빈칸으로 싣기로 확정한 조각의 기록 `stream_published_gaps`)에 있다(설계 4.1) |
| 창 | 목록에 싣는 seq 구간 [꼬리, 머리]. 머리 = 컷오프부터 끊김 없이 이어진 settled 행의 끝. 꼬리 = 머리에서 거꾸로 길이를 더해 1시간이 차는 가장 늦은 행이고, 1시간이 안 되면 컷오프다(설계 4.2) |
| 끊김 표시 | 목록의 `#EXT-X-DISCONTINUITY` 줄. 플레이어에게 "여기서 디코더를 다시 맞춰라"라고 알린다 |
| MSN | 목록 머리의 `#EXT-X-MEDIA-SEQUENCE` — 첫 줄 조각의 seq |
| DISC-SEQ · base | 목록 머리의 `#EXT-X-DISCONTINUITY-SEQUENCE` — 목록 앞에서 빠져나간 끊김 표시의 누계다. 회차 행의 `discontinuity_base`(줄여서 base)를 그대로 적는다 |
| 축출 증분 | 창이 앞으로 가 목록 앞에서 끊김 표시가 빠질 때 base 에 더할 수 |
| 계승 회차 | 직전 회차를 잇는 회차(`inherits_session` 이 NULL 이 아님). 목록 앞에 직전 회차의 조각(접두)이 실린다 |
| TD | target duration — 목록이 약속하는 조각 길이 상한(초, `#EXT-X-TARGETDURATION`). 회차 개시 때 정해진다 |
| 발행 전 검사 S1–S7 | 목록을 올리기 직전의 일곱 검사(설계 4.5.5). 걸리면 그 발행을 내지 않거나(발행 중단) 접두를 뺀다(계승 취소) |
| 봉인 | 목록 끝에 `#EXT-X-ENDLIST` 한 줄을 붙여 더는 자라지 않는다고 알리는 것 |
| 읽기 뷰(캐시) | 장부·GAP 원장·컷오프를 메모리에 든 사본. 평시에 목록을 만들 때 DB·S3 를 묻지 않게 한다 |
| 부팅 재구성 | 프로세스가 뜰 때 한 스트림의 뷰를 장부에서 통째로 다시 읽어 채우는 것 |
| 골든 | 기대 출력 바이트를 통째로 적어 둔 파일 — 여기서는 `internal/rewind/testdata/g6_f2.m3u8` |
| 뮤턴트 | 테스트의 판별력을 재려고 코드에 일부러 심는 작은 결함. 테스트가 실패하면 「죽었다」, 통과하면 「산다」(판별 공백) |

### M4 ⓑ 로 켜진 것과 아직 없는 것

- **운영 동작 변화 0.** ⓑ 의 새 코드는 부르는 곳이 없다. 인덱서에 캐시 push 두 줄을 넣었지만
  (`internal/indexer/indexer.go` `advance`·`correctTail`), 캐시를 만드는 조립 코드가 없어 캐시는 늘 nil
  이고 nil 캐시는 아무것도 하지 않는다(`upload.Dirty` 와 같은 nil 규약). `media/cmd` 는 건드리지 않았다.
  호출자 없이 먼저 들어오는 형식은 M3 의 `InitKey`(`internal/playback/key.go`) 전례와 같다 — M3 에서는 부르는
  곳이 없었고 ⓐ 가 호출자를 붙였다. 소비자 ⓒ 도 같은 마일스톤 안에서 온다(계획 4절 PR ⓑ 「시청자·비용 영향」).
- **운영 경로에서 달라진 것은 한 문장이다.** 회차 개시 때 새 회차 행을 되읽는 문장(`internal/index/store.go`
  `openedSessionSQL`)이 `target_duration` 한 열을 더 읽는다. 같은 트랜잭션·같은 왕복이고 개시 때만 돈다.
  읽은 값(`SeedResult.TargetDuration`)을 쓰는 곳은 캐시뿐이라 지금은 쓰이지 않는다.
- **설정·스키마**: 새 env 0 · DDL 0 · 장부 쓰기 0 · 새 로그·메트릭 0. 새 인터페이스는 `boundary.Snapshot`
  하나다(설계 3.2 가 이름을 준 것). 새 숫자 상수는 셋이다 — `boundary.WindowMS`(1시간) · S5 본문 상한
  512KiB · S7 PDT 여유 1ms. 셋 다 설계 4.2·4.5.5 가 값까지 정했고 kty 가 허용했다(2026-09-24).
- **아직 없다(ⓒ)**: 목록 발행(S3 조건부 PUT · 세대 규약 P0–P4 — 두 발행자가 서로를 덮지 못하게 하는 발행
  절차) · HTTP · 감시 tick · 캐시 조립과 부팅 Reload · ③ 확정·GAP 원장 push · 축출 증분 배선 · 봉인 호출 ·
  송출 종료 전이. 이유는 아래 「M4 ⓑ 에서 ⓒ 로 넘긴 것」에 있다.
- **게이트(계획 4절 PR ⓑ)**: G6(렌더·경계 단위 게이트 — 아래 테스트 지도) · G7(PDT 와 미디어 시계의 어긋남 —
  ⓐ 의 skew 기록에 S7 엄격 단조 검사가 더해져 완결) · G9 leaf `t3_cutoff_settles_after_uploaded`(설계 6.5.6
  계약 회귀 단언 67개 가운데 ⓑ 몫 하나). 같은 G9 의 `c2_no_flag_read`(발행 경로 `rewind/`·`publish/` 에 컷오프
  플래그 읽기 0건 — 정적 단언)는 `publish/` 가 생기는 ⓒ 에서 완결된다.

### M4 ⓑ 가 넣은 것

패키지 셋과 장부 읽기 하나, 운반 통로 둘이다. 패키지 셋은 DB·S3·HTTP·파일 시스템에 닿지 않고 로그를 남기지
않는다 — `boundary`·`rewind` 는 같은 입력이면 같은 출력을 내는 순수 계층이고, `cache` 는 메모리 상태만 든다.

| 자리 | 무엇 | 공개 API |
|---|---|---|
| `internal/rewind/boundary`(신설) | 어느 조각부터 어느 조각까지 실을 수 있나 — settled 판정의 유일한 자리와 창 산식(설계 4.1·4.2) | `Settled` · `Compute`(→ `Window{ScanFrom, HeadSeq, TailSeq}`) · 입력 `Snapshot` · 값 `Row` · `WindowMS` |
| `internal/rewind`(신설) | 목록의 문법 — 렌더 · 발행 전 검사 · 봉인 · 끊김 표시 술어 · 축출 증분 | `Render` · `Validate`(처치 `ErrHaltPublication`·`ErrRevokeInheritance`, 위반 `*Violation`) · `SealEndlist`(`ErrAlreadySealed`) · `HasDiscontinuityTag` · `EvictedDiscontinuityTags` · 값 `Playlist`·`Session`·`Published` |
| `internal/rewind/cache`(신설) | 목록의 입력 — 장부·GAP 원장·컷오프의 읽기 뷰. 인덱서 루프 goroutine 하나가 소유하고 락이 없다. push 넷 가운데 ③ 확정·GAP 원장 둘은 ⓒ 가 부른다 | `Cache` — 부팅 재구성 `Reload` · push `ApplyInsert`·`ApplyTailCorrection`·`ApplyPlaybackUploaded`·`ApplyPublishedGap` · 읽기 `Snapshot`·`Session`(→ 값 `Session`)·`Playlist`(세션 필터) |
| `internal/index/rewind_read.go`(신설) | 부팅 재구성 읽기 두 단 — ⑴ 컷오프 이후 조각 전량(③ 상태·GAP 원장 소속 포함) ⑵ 그 조각들이 참조하는 회차의 8열. 읽기 전용이고 settled 판정은 하지 않는다 | `LoadRewindLedger`(→ `RewindLedger` · `RewindRow` · `RewindSession`) |
| `internal/index/seed.go`·`store.go` | 회차 TD 운반 — 개시 되읽기가 `target_duration` 을 함께 읽어 INSERT 결과에 싣는다 | `SeedResult.TargetDuration`(+1 필드) |
| `internal/indexer/indexer.go` | 캐시 push 배선 — 장부 커밋 직후(`advance`)와 꼬리 교정 직후(`correctTail`) | 없음(비공개 필드 `Indexer.rewind` — 채우는 조립은 ⓒ) |
| `.github/workflows/media-ci.yml` | 커버리지 게이트에 `rewind/boundary`·`rewind`·`rewind/cache` 셋(아래 「테스트」 절) | — |

**바꾸기 전에 이유를 볼 규칙**

- **머리는 끊김 없는 settled 접두의 끝이다.** settled 행의 최댓값으로 읽으면 홀(아직 안 올라간 조각) 뒤
  조각이 실려 404 가 난다(계약-세그먼트인덱스 불변식 1). 홀 뒤 조각은 홀이 메워지거나 GAP 원장에 오르기
  전에는 싣지 않는다.
- **꼬리는 최솟값이 아니라 최댓값이다** — 머리에서 거꾸로 1시간이 차는 자리 가운데 **가장 늦은** 행이다.
  최솟값이면 늘 컷오프라 창이 끝없이 자란다(설계 r4 가 실제로 틀렸던 자리 — 계획 리스크 B1). 빈 창은
  `HeadSeq = ScanFrom − 1` 이다.
- **MSN 은 첫 줄 조각의 seq, DISC-SEQ 는 소유 회차 base 그대로다.** 렌더는 아무것도 더하지 않는다 — 더하는
  일은 축출 증분의 몫이다. 목록 첫 줄이 계승 접두여도 머리의 TD·DISC-SEQ 는 소유 회차(목록 URL 의 회차)
  값이다. 목록 안 seq 는 1씩 이어져야 하고(빈칸은 GAP 줄이 메운다) 아니면 렌더 오류다.
- **끊김 표시는 행 하나만 보고 정한다**(`HasDiscontinuityTag`). 그 행이 자기 회차의 첫 조각(= max(회차 최소
  seq, 컷오프))이고 그 회차가 계승 회차일 때만 단다. 목록 문맥을 보지 않으므로 같은 행은 어느 목록에서
  빠지든 base 에 같은 값을 더한다 — 그래서 렌더와 축출 증분이 이 함수 하나를 부른다. 연쇄 계승(O←P←S)에서
  P 의 첫 조각이 창 안이면 S 목록에는 표시가 둘 선다. 장부 `is_discontinuity` 는 근거가 아니고 캐시 행에
  싣지도 않는다(아래 부기 29).
- **계승 접두는 계승 회차에만, 직전 회차 하나만 싣는다**(캐시 `Playlist` 의 세션 필터). TD 분할 회차(계승
  없이 base 만 복사)나 비계승 회차에 앞 회차 조각을 실으면 MAP 만 바뀌고 표시가 빠진다.
- **조각 URI 는 장부 `playback_s3_key` 그대로다** — 회차·티어 축이 없는 영구 URL 이다(ADR-020 「세그먼트당
  정규 URL 단일 고정」). 본문은 순수 HLS 다 — `#PC-` 같은 우리 용도의 줄·주석·빈 줄이 없고, 세대 정보는 본문
  밖 메타데이터로 간다(ⓒ). `#EXT-X-VERSION` 은 6 이다(아래 부기 32).
- **검사 처치는 둘이다.** S4(계승 접두 범위) 위반은 계승 취소 — 접두를 빼고 다시 렌더한다. 나머지 여섯은
  발행 중단이다. 처음 걸린 검사 하나만 돌려주고, 부르는 쪽은 `errors.Is` 로 가른다.
- **봉인은 특권 경로다.** `SealEndlist` 는 렌더·검사를 타지 않고 발행된 본문 끝에 `#EXT-X-ENDLIST` 한 줄만
  붙인다(RFC 8216bis-22 6.2.1 이 허용한 변경). 이미 닫힌 본문이면 `ErrAlreadySealed` 다.
- **캐시가 장부와 어긋나면 되돌림은 `Reload` 하나다.** push 가 seq 를 건너뛰면 그 스트림 뷰는 거기서
  멈춘다 — 빈자리를 두고 이어 붙이면 경계가 빈자리를 모르고 넘어간다.
- **부르는 쪽(ⓒ)의 규칙**: `Snapshot` 은 뷰를 복사 없이 내주므로 캐시를 소유한 goroutine 안에서만 쓴다
  (발행 워커에는 `Playlist` 가 만든 새 값만 넘긴다). `Settled(r, cutoff)` 는 「컷오프 없음」을 나타내지
  못하므로 `Cutoff()` 의 ok 확인은 부르는 쪽 몫이다.

### M4 ⓑ 운영 영향 없음 · 되돌리기

- **운영 영향 없음** — 위 「켜진 것」 그대로다. 운영 경로의 변화는 개시 되읽기 한 열뿐이고,
  실 PG 테스트(`TestInsertReportsTargetDurationWrittenAtOpening`)가 그 열을 잰다. 그래서 compose 실측은 하지
  않았다 — 종단 확인은 ⓒ 몫이다(계획 4절 PR ⓑ 검증 표).
- **되돌리기** — `git revert -m 1 <ⓑ 머지 커밋>` 한 번이다. 장부 쓰기가 0 이라 되돌려도 남는 데이터가 없다.
  ⓒ 가 이미 머지됐으면 ⓒ 를 먼저 되돌린다(역순 — 위 ⓐ 절 「되돌리기와 긴급 정지」). 반영은
  `docker compose build segment-indexer && docker compose up -d segment-indexer` 다.

### M4 ⓑ 설계와 다르게 한 것

계획 부기(「이 계획이 설계와 다르게 적은 것」 표) 가운데 ⓑ 몫 다섯 행이다. 각 항의 자리 · 설계 문언 · 이 계획 ·
성격 네 줄은 계획 부기 표의 문언 그대로이고, 「ⓑ 기록」 줄은 이 기록이 덧붙인 현재 상태다. ⓑ 몫은 행의
「자리」를 ⓑ 커밋과 대조해 골랐다. 부기 29 는 위 ⓐ 절 3항과 나눠 싣는다(ⓐ = 도장 이어 붙이기 · ⓑ = 렌더).

1. **부기 15**
   - 자리: 설계 4.8.1 f2 · 4.9.1
   - 설계 문언: f2 B 세션 DISC-SEQ 3(계승 300초) · 승계는 TD 분할 갈래만 명시
   - 이 계획: **kty 확정으로 침묵 해소**: 300초 안 재접속 개시는 TD 분할과 같은 규칙으로 승계 — ⓒ 계승 갈래
   - 성격: 설계 갱신 후보(4.9.1 에 재접속 갈래 추기)
   - ⓑ 기록: ⓑ 몫은 f2 골든의 DISC-SEQ 3 이다 — 렌더는 소유 회차 B 의 base 3 을 그대로 적는다. B 가 A 의 base 를 옮겨 받는 300초 재접속 개시는 ⓒ 가 만든다.
2. **부기 16**
   - 자리: 제0원칙 2(이름 = 계약)
   - 설계 문언: —
   - 이 계획: `SeedResult` 가 주조 + 세션 개시를 함께 나른다(이름 부채, 파급 최소 근거) — 개명은 M5 축
   - 성격: 이름 부채 기록(cc r3 보완)
   - ⓑ 기록: ⓑ 커밋 ④ 가 개시 쪽에 `TargetDuration` 한 필드를 더 얹었다(회차 TD 운반 — 계획 「④ 캐시 착수 메모」). 부채가 한 필드만큼 커졌고, 개명은 여전히 M5 축이다.
3. **부기 25**
   - 자리: 설계 4.1:431
   - 설계 문언: 부팅 재구성 SELECT = 조각 3열
   - 이 계획: 세션 행 7열 적재를 2단으로 추가(캐시 전용 세션 축의 유일한 재기동 복원 경로)
   - 성격: 확인 패스 cc#1·cc 부수 7
   - ⓑ 기록: ⓑ 커밋 ④ 에서 `target_duration` 을 더해 지금은 8열이다(`index.RewindSession` — 계획 「④ 캐시 착수 메모」). 조각 축은 설계대로 `seq ≥ 컷오프` 전량이다.
4. **부기 29**
   - 자리: 설계 4.3 · 계약3 7절(:109) · 계약-세그먼트인덱스 :28
   - 설계 문언: `DISCONTINUITY(k) ⟺ is_discontinuity(k) ∨ 회차 경계 첫 조각` · "`is_discontinuity` → 매니페스트에 `EXT-X-DISCONTINUITY`"
   - 이 계획: **회차 경계 첫 조각에만** 단다. 회차 안 `is_discontinuity` 는 표시 근거가 아니다(장부 컬럼·대입·DDL 은 불변). 재접속 이음매의 영상 도장 겹침(≤0.22초)이 규격 문언상 "시간 도장 순서 변경"(표시 필수)에 해당하므로 실측 근거로 의도적으로 벗어난다(규격 예외)
   - 성격: **편차 있음**(kty 결정 F 2026-09-19) — 설계 4.3·계약 2종 갱신 + 3번 통지가 ⓑ 머지 전에 필요 〔r18: 술어는 행 단독 — 자기 회차 첫 조각 ∧ `inherits_session ≠ NULL`(RF)〕
   - ⓑ 기록: ⓑ 몫은 렌더 쪽이다 — 술어 `HasDiscontinuityTag` 를 렌더와 축출 증분이 함께 부른다(위 「바꾸기 전에 이유를 볼 규칙」). 「ⓑ 머지 전에 필요」한 설계·계약 갱신과 통지는 충족됐다(위 ⓐ 절 3항의 추기).
5. **부기 32**
   - 자리: 설계 4.8.2 실물(:801)
   - 설계 문언: `#EXT-X-VERSION:9`
   - 이 계획: **`#EXT-X-VERSION:6`** — kty 결정 2026-09-24(qa #39). RFC 8216bis-22 6.2.1 「필요 이상 높은 버전 SHOULD NOT」 · 8절 요구 최솟값 6(비 I-frame 목록의 EXT-X-MAP) · 9 = EXT-X-SKIP(델타 갱신) 전용이고 계약3 7-5 는 되감기 목록에 델타 갱신을 광고하지 않는다 · 발행 목록은 수명 중 VERSION 을 바꿀 수 없다(6.2.1 허용 변경 목록 밖) · 8절 「미지원 버전 재생 금지」 — 버전 6–8 전용 플레이어 위험 제거. hls.js 1.5.15/1.6.0/1.7.3 는 9 도 파싱했다(`126_`) — 6 은 b2-fix3 뒤 재실측
   - 성격: 편차 있음(규격 정합 방향) — 볼트 설계 4.8.2 머리에 대체 표식
   - ⓑ 기록: 설계 쪽 「:801」은 대체 표식 한 줄이 들어가기 전 줄 번호다 — 지금 VERSION 줄은 :802 다. 「b2-fix3 뒤 재실측」(VERSION 을 6 으로 바꾼 수정 뒤의 재실측)은 했다 — VERSION 6 골든도 세 판 모두 구문 오류 0 · 해석 수치 동일이다(2026-09-24). `126_` 은 작업 산출물 `126_hlsjs_parse_g6_f2.md`(저장소 밖)다.

### M4 ⓑ 에서 ⓒ 로 넘긴 것

ⓑ 가 일부러 남긴 것만 적는다. 계획 자리는 전부 계획 「ⓒ 착수 메모」 절이고, 칸에는 그 항의 개정 표식을
적는다.

| # | 넘긴 것 | 한 줄 요지 | 계획 자리 |
|---|---|---|---|
| 1 | 축출 증분 배선 · prev 계약 · 재기동 변형 | `EvictedDiscontinuityTags(prev, nextMSN)` 의 prev 는 「소유 회차의 지금 base 를 DISC-SEQ 로 적어 렌더한 직전 목록」(발행에 실패한 시도 포함)이다. 「마지막으로 발행된 목록」으로 잡으면 PUT 실패 뒤 같은 표시를 두 번 센다. 발행 예약(P0) 커밋 뒤 PUT 전에 죽으면 그 증분의 기준 창이 어디에도 남지 않는다 — ⓒ 복구 설계의 입력 | r32 |
| 2 | 재기동 뒤 `rewind.Published` | 직전 발행의 MSN·조각 수는 메모리에만 있다. 영값으로 넘기면 S1(줄 수)·S2(MSN 역행) 검사가 자명하게 통과한다 — 본문 GET 이나 재구성으로 채운다 | r29 |
| 3 | 유입 정지 판정의 입력 | 오프라인 전이의 입력은 캐시가 아니라 인덱서 커서다 — 캐시는 컷오프 전 행·컷오프 없는 스트림의 행과 `start_wall_utc` 를 들지 않는다 | r31 |
| 4 | DISC-SEQ 단조 그물 · 끊김 표시 줄 검사 · 모르는 태그 검사 | 셋 다 S1–S7 에 없다. 끊김 표시 줄 검사·모르는 태그 검사는 목록 본문을 쓰는 코드가 `Render`·`SealEndlist` 둘뿐이라 ⓑ 가 만들지 않았다(커밋 ⑥ 재판정). DISC-SEQ 단조 그물은 처음부터 ⓒ 몫이다 — DISC-SEQ 가 뒤로 가면 RFC 8216bis-22 6.2.2 MUST 위반이라, ⓒ 가 계승 취소를 영속하는 형상과 함께 정하고 앞의 둘도 그때 함께 판정한다 | r29 추기 · r33 |
| 5 | TD 초과 접두·꼬리 교정의 S5 정지(숨은 가정 11) | TD 분할 회차를 300초 안에 계승하면 접두 조각이 새 회차 TD 를 넘고, 꼬리 교정은 TD 를 다시 재지 않는다 — 두 길 모두 목록이 S5(본문 형식·TD 검사)로 최대 1시간 멈춘다(`TestValidateInheritedTDSplitPrefixHaltsOnS5` 가 지금 동작을 고정). 처방 후보 (A) 계승 개시 TD = max(직전 TD, 첫 조각 반올림) · (B) S4 에 TD 조항 · (C) 꼬리 교정 때 TD 재판정을 kty 에 회부한다. 판별 공백 VB-LASTROW(S5 TD 조항이 마지막 줄 회차의 TD 를 읽는 뮤턴트)도 그 결론과 함께 정한다 | r29 · r32 추기 |
| 6 | 발행 모형과 BASE 계열 뮤턴트 | 끝난 회차 목록을 창을 따라 계속 갱신할지 봉인할지(발행 모형)가 정해지지 않아, 접두 회차와 소유 회차의 base 가 갈리는 픽스처를 세울 수 없다. 그래서 머리 대조가 첫 줄·마지막 줄 회차의 base 를 읽는 뮤턴트(VH-FIRSTROW-BASE·VH-LASTROW-BASE)가 산다 — 그동안 DISC-SEQ 비기본값 대조는 f2 골든이 맡는다. 뒤 회차 행이 창 꼬리를 밀 때 앞 회차 목록이 S3(범위 검사)로 멈추는 국면도 같은 동결 순서 문제다 | r33 · r30 |
| 7 | S4 계승 취소의 영속 | ⓑ 의 S4 는 렌더 시점 판정이라 영속하지 않는다. 다음 tick 에 접두를 다시 실으면 MSN 이 뒤로 가 S2(MSN 역행 검사)가 발행을 멈춘다 — ⓒ 가 목록 수명 동안 붙든다. 접두 회차의 계승 취소가 계승 회차 목록 발행 뒤에 오는 국면(미확인)도 같은 축이다 | r29 · r32 |
| 8 | 소유 회차 줄의 절대 URI | S1–S7 어디에도 없다(S4 는 접두만 본다). `REWIND_PUBLIC_BASE_URL` 적재 검증이 유일한 방어다 | r29 |
| 9 | 빈 창 판정 | 세션 필터 뒤 행이 0개면 「아직 낼 것 없음」으로 먼저 거른다 — `Render` 는 빈 목록을 오류로 낸다 | r28 |
| 10 | 캐시 조립과 채우는 순서 | 부팅 때 어느 스트림을 Reload 할지 · `LoadRewindLedger`(워커)와 `Reload`(루프)를 다른 차례로 나누면 그 사이 push 가 버려져 뷰가 멈춘다 · 부팅 때 컷오프가 없던 스트림에서 부팅 전 회차의 행이 뒤늦게 주조하면 그 회차의 `Playlist` 가 거짓 · M6 만료 뒤 재주조(미확인) | r30 · r31 |
| 11 | 캐시가 아직 모르는 사실 | TD 분할이 끝낸 옛 회차의 state(캐시는 live 로 든다) · init 확정·분리를 반영할 메서드 · settled 범위형 SQL(정합성 감시 (a)) | r30 |
| 12 | 캐시 메모리 | 뷰는 컷오프 뒤 행을 스트림 수명 동안 든다(시간당 900행, 잘라 내기 없음). 창 꼬리 앞 행을 버릴지는 VOD 동결이 무엇을 읽느냐에 달렸다 | r30 |

### M4 ⓑ 알려진 한계와 판단 대기 (정직 서술)

알려진 한계 — 코드는 지금 동작 그대로다.

- **계승은 1단계만이다.** 계승 회차 목록에는 직전 회차 하나의 접두만 싣는다 — A←B←C 에서 C 의 목록에 A 는
  없다. 사슬과 1시간 상계는 M5/M6 회부다(계획 2.3 ⑸ⓕ). S4 의 「계승 접두 1시간 상한」은 1단계에서는 창
  자체(1시간)로 채워진다.
- **S7 의 1ms 여유는 규격보다 엄격하다.** RFC 8216bis-22 6.2.1 은 인코더 시계 드리프트를 흡수하는 1초 미만
  PDT 겹침을 허용하지만(MAY), S7 은 1ms 를 넘는 겹침이면 발행을 멈춘다(설계 4.5.5 `PDT_SLACK`). 장부 PDT 는
  재귀식이라(직전 PDT + 직전 길이가 하한) 겹침을 만들지 않으므로 의도한 선택이다(계획 「⑥ 착수 메모」 r29
  관찰 8).
- **f2 A 회차의 형상은 기록만 한다.** 골든 f2 의 A 회차(비계승 · base 3 · TD 6)는 문자 그대로는 개시 규칙과
  어긋난다 — 비계승 개시는 base 0 이고, TD 분할이면 TD 가 7 이상이다. kty 확정 형상이고, 렌더는 소유 회차
  B 의 값만 머리에 적어 출력과 무관하므로 바꾸지 않았다(계획 「⑥ 착수 메모」·「⑦ 착수 메모」 r33).
- **렌더와 검사가 같은 서식을 따로 적는다(교차 확인).** init URL · EXTINF 서식 · 조각 URI 조합 · 머리 줄
  서식이 `render.go` 와 `validate.go` 두 자리에 있다. 한쪽만 바뀌면 모든 발행이 멈추므로 조용히 지나가지 않는다 —
  EXTINF·조각 URI·머리 줄은 S5 의 「본문 = 목록 값」 대조(`checkBodyMatchesRows`)가 잡고, init URL 은 S5 의 MAP
  도달 검사(`checkBody` → `mapProblem`)가 잡는다. 계승 접두가 실린 목록이면 init URL 은 S4(계승 접두 범위)가 먼저
  잡는다. 두 자리를 함께 재는 테스트가 없던 「회차 첫 조각」 정의만 한 자리(비공개 메서드 `firstSeq`)로
  합쳤다(커밋 ⑥ — 동작 불변 리팩터).
- **본문 상한 512KiB 는 1KB = 1,024B 로 읽은 값이다**(설계 4.5.5 는 「512KB」만 적었다). 1시간 창을 4초 조각으로
  채운 본문은 약 104KiB(g6_f2 106,499B)라 다섯 배쯤 여유가 있고, 0.79초 조각으로 1시간을 채우면 닿는다.

판단 대기 — 위 「ⓒ 로 넘긴 것」 5번(TD 초과 접두·꼬리 교정의 처방)을 ⓒ 착수 때 kty 에 회부한다.
VB-LASTROW 판별 테스트는 그 결론을 따른다.

미확인:

- S4 「1시간 상한」의 본뜻 — 창의 성질(이 구현)인지 계승 사슬의 상계(M5/M6)인지 설계가 한 문장으로 말하지
  않는다. 1단계 계승에서는 둘이 같다.
- 실재생과 다른 플레이어 — hls.js 세 판에 **파싱**만 시켰다(아래 출처의 실측). 바이트 실재생·ExoPlayer 는
  ⓒ 이후다.
- 접두 회차의 계승 취소가 계승 회차 목록 발행 뒤에 오는 국면과 M6 만료 뒤 재주조(위 표 7·10)는 코드로
  재현하지 않았다.

### M4 ⓑ 테스트 지도

#### G6 대조표 — 설계가 이름을 준 11개 · 되살린 다섯 · 비운 셋

G6(설계 9절의 렌더·경계 단위 게이트)은 번호 붙은 픽스처 f1–f19 로 선다. 계획 4절의 정직 고지 그대로 적는다 —
설계 r17 이 이름을 준 것은 11개(f2 · f6·f7 · f9–f12 · f15 · f17–f19)이고, 나머지 여덟은 r17 원문에 없다.
없는 번호는 지어내지 않고 비우되, 채우면 근거와 함께 이 기록에 남긴다. 테스트 주석은 G9 leaf 이름(`f0_…`
같은 계약 회귀 단언)과 헷갈리지 않게 `g6_` 접두를 붙인다(예: `// g6_f8 — …`).

| 번호 | 출처 | 테스트 | 무엇을 단언하나 |
|---|---|---|---|
| f1 | r7–r14 「단일 세션 11조각」(골든) | — 비움 | 단일 세션 11조각의 골든이 없다 |
| f2 | r17 4.8.1·4.8.2 | `TestG6F2RenderMatchesGolden` · `TestG6F2RenderMatchesDesignFigures` · `TestG6F2BoundaryMatchesDesignFigures` · `TestValidateG6F2Passes` · `TestSealEndlistAppendsOneEndlistLine` | 골든과 바이트 동일 · MSN 398 · DISC-SEQ 3 · 900조각 · 3,600.000초 · 꼬리 398 · GAP 하나(seq 1297) · MAP 둘(398·1031) · 끊김 표시 하나(1031) · `#PC-` 줄 0 · URI 에 회차 축 없음 · S1–S7 통과 · 봉인은 ENDLIST 한 줄만 |
| f3 | r7–r14 「GAP 1」(골든) | — 비움 | GAP 한 줄 목록의 골든이 없다(GAP 줄 자체는 `TestRenderGapLineKeepsPDTAndURI` 와 f2 골든이 재지만 판정 형태가 달라 번호를 붙이지 않았다) |
| f4 | r7–r14 「GAP 3연속」(골든) | — 비움 | 대응 테스트가 없다(`TestValidateBodySizeLimitIs512KiB` 의 GAP 세 줄은 크기 판정의 재료다) |
| f5 | r7–r14 「축출 후 MSN·DISC-SEQ 증가」 | `TestEvictionKeepsDiscontinuitySequenceNumbers` — DISC-SEQ 절만 | 새 base = 직전 base + 축출 증분으로 렌더한 두 본문에서 공통 조각의 Discontinuity Sequence Number(DISC-SEQ + 그 조각 앞 표시 수)가 그대로다 |
| f6 | r17(r7 재정의부터 「입력 PDT 역행」) | `TestValidateG6F6PDTRecursionSurvivesWallClockStepBack` | 재귀식 PDT 는 통과 · 벽시계 그대로의 PDT 는 S7 |
| f7 | r17(r7 재정의부터 「입력 PDT 중복」) | `TestValidateG6F7OverlappingPDTHalts` | 중복·2.5초 겹침 → S7 |
| f8 | r7–r14 「EXTINF 6.6초 → S5」 | `TestValidateTargetDurationRoundsLikeTheTDSplit/TD_6_회차의_6500ms` | TD 6 회차의 6,500ms 조각(반올림 7) → S5 |
| f9 | r17 | `TestG6F9UnderAnHourTailIsCutoff` | 1시간이 안 되면 꼬리 = 컷오프 |
| f10 | r17 | `TestG6F10TailIsMaxSeqReachingAnHour` | 꼬리 = 1시간에 닿는 seq 의 최댓값 |
| f11 | r17 | `TestG6F11WindowWithinHourPlusMaxDuration` | 창 길이 ∈ [1시간, 1시간 + 최대 조각 길이) |
| f12 | r17 | `TestG6F12VariableDurations` | 비정수 초 길이가 섞여도 산식이 선다 |
| f13 | r7–r14 「백필 미정착 → S4 + 계승 취소」 | `TestValidateInheritedPrefix/접두_행_미settled` | S4 · `errors.Is(err, ErrRevokeInheritance)` |
| f14 | r7–r14 「terminal 뒤 비terminal → S6」 | `TestValidateTerminalOnlyGoesFalseToTrue/닫힌_목록_뒤_열린_목록` | S6 |
| f15 | r17 | `TestG6F15EmptyWindowHeadIsScanFromMinusOne` | 빈 창의 머리 = ScanFrom − 1 |
| f16 | r7–r14 「playback_pdt NULL 섞임 → S3」 | `TestValidateRangeRequiresSettledRows/playback_pdt_NULL` | S3(settled 아님) |
| f17 | r17 | `TestG6F17BelowCutoffIsOutOfRangeNotHole` | 컷오프 미만은 홀이 아니라 범위 밖 |
| f18 | r17 | `TestG6F18HoleStopsHeadAcrossComputations` | 홀 앞에서 머리가 멈춘다 · GAP 원장에 있으면 통과(음성 대조) |
| f19 | r17 | `TestG6F19NullSessionIsNotSettled` | 회차 없는 행은 settled 아님 · 미등재 |

#### G6 복원 기록 — 비어 있던 번호를 어디서 찾았나

- **왜 비어 있었나.** 설계 r7–r14 의 9절 G6 블록은 비운·되살린 여덟을 모두 정의했다(f19 는 r10 신설). r15 에서 그 블록이 빠지고 게이트
  표의 요약 한 줄만 남았다(`24_design_r15.md`:1934 · `26_design_r17.md`:1772). 그래서 계획은 r17 기준으로
  여덟을 「원문 침묵」으로 분류했다 — r17 기준으로는 맞다.
- **계보 좌표** — 볼트 `Projects/PokeClip/specs/POK-168-r15a-되감기경로-2026-08-30/50_r4산출_2026-09-01/` 의
  판마다 같은 블록이 있다: r7 `16_design_r7.md`:1978-1998 · r8 `17_design_r8.md`:1704-1716 · r9
  `18_design_r9.md`:1516-1528 · r10 `19_design_r10.md`:1621-1634 · r11 `20_design_r11.md`:1840-1853 · r12
  `21_design_r12.md`:1899-1911 · r13 `22_design_r13.md`:2045-2058 · r14 `23_design_r14.md`:1973-1986(픽스처
  목록 :1973-1977 · 공통 단언 :1978 · f5 :1982 · f8 :1984 · f13·f14 :1985 · f16·f19 :1986).
- **되살린 다섯(f5·f8·f13·f14·f16)** — 판정이 같은 기존 테스트에 번호 주석만 붙였다. 새 테스트는 없다.
  - f5 는 DISC-SEQ 절만 직접 잰다. MSN 절(「MSN 증가량 = 축출 개수」)은 MSN = 첫 줄 seq · 목록 안 seq 연속이라는
    `Render` 계약으로 선다(f2 의 MSN 398 · `TestRenderRejectsUnrenderableInput` 의 seq 건너뜀·역행 거부).
  - f8 은 설계의 6.6초 대신 반올림 경계 6.5초로 잰다 — 6,499ms 는 통과, 6,500ms 는 S5 다(반올림이 TD 분할
    판정처럼 가운데를 올린다).
- **비운 셋(f1·f3·f4)** — 설계 계보에서 골든 바이트 픽스처였는데 대응하는 골든이 없다. 비운 번호는 다른 것에
  다시 쓰지 않는다.
- 설계 계보의 공통 단언 「∀f Render == 골든 바이트 일치」는 f2 만 잰다. 되살린 다섯은 번호별 단언(검사 판정 ·
  DSN 불변)과 같은 테스트다.

#### G9 leaf t3 — ⓑ 몫 계약 회귀 단언

`t3_cutoff_settles_after_uploaded`(설계 6.5.6 ⒡-S3) = `TestPhaseT3CutoffSettlesAfterUploaded`
(`internal/indexer/phase_pg_test.go`, PG 필요). 실제 주조 경로(인덱서 → 장부 INSERT + 주조)로 컷오프를
만들고, 부팅 재구성(`LoadRewindLedger` → `Reload` → `Compute`) 위에서 창 머리가 먼저 `컷오프 − 1` 인지 본다 —
컷오프 행의 ③ 이 아직 pending 이라 빈 창이 정상이다. 그다음 ③ 업로드 확인 CAS 를 실행하고 머리가 컷오프
이상인지 본다. 설계가 부른 `boundary.Head` 는 코드의 `boundary.Compute(…).HeadSeq` 다.

- **t3 의 CAS 문장은 운영 문장의 사본이다.** 테스트가 SQL 텍스트로 직접 실행하는 문장은 운영
  `markPlaybackUploadedSQL`(`internal/index/upload_store.go`)과 공백만 다르다. 운영 문장이 바뀌어도 t3 은 모른다
  — 운영 문장의 변경은 `internal/index/playback_cas_pg_test.go` 가 잰다.

#### 판별이 한 테스트에 모인 자리 셋

아래 셋은 그 판별을 하는 테스트가 **하나뿐**이다. 줄이거나 지우면 판별이 조용히 사라진다 — 다른 테스트는
그대로 통과한다.

| 판별 | 유일한 테스트 | 왜 하나뿐인가 |
|---|---|---|
| 캐시가 0 이 아닌 base 를 옮기는가(개시 push · 재구성 · 목록의 회차 값) | `TestCacheReceivesInheritsOnOpen`(`internal/rewind/cache/playlist_test.go`) | 장부 규칙상 base 가 0 을 벗어나려면 한 시간 넘는 이력이 필요하다. 캐시 패키지에서 그 이력(901조각 · base 1)을 둔 픽스처가 이것 하나다 |
| 재구성 SQL 이 base 열을 읽는가 | `TestLoadRewindLedgerReadsRaisedDiscontinuityBase`(`internal/index/rewind_read_pg_test.go`, PG 필요) | 같은 까닭이다 — 한 시간 이력을 한 문장(`generate_series`)으로 심은 곳이 여기 하나다 |
| 발행 전 검사의 머리 대조가 DISC-SEQ 를 기본값 0 이 아닌 값으로 견주는가 | `TestValidateG6F2Passes`(f2 골든 — DISC-SEQ 3) | 통과를 기대하는 검사 픽스처 가운데 base 가 0 이 아닌 것은 f2 뿐이다. base 10 인 크기 경계 경우는 S5 위반을 기대하므로 머리 대조가 틀려도 같은 S5 로 끝나 가르지 못한다 |

#### 「N1」 이름의 두 뜻

검수 기록에서 「N1」이 서로 다른 두 항목을 가리킨다.

- **ⓑ 수정분 확인 패스의 N1**(작업 산출물 `phase3-cc-confirm-bfix.md`) — 렌더 머리 테스트
  `TestRenderHeaderComesFromOwnerSession` 의 픽스처를 개시 규칙과 맞추라는 지적이다. 커밋 ⑥ 에서 고쳤다(계획
  「⑥ 착수 메모」 첫 항).
- **r3 확인 패스의 cc N1**(작업 산출물 `phase3-cc-confirm-b3fix.md`) — 검사가 본문의 TD·MAP 태그를 렌더가
  쓰는 자리 한 곳에서만 읽고 개수·위치는 보지 않던 문제다. `TestValidateBodyHeaderAndMapLines` 주석의 「r3
  확인 패스 cc N1」이 이것이다.

### g6_f2 골든 생성·대조 스크립트

g6_f2 골든(`internal/rewind/testdata/g6_f2.m3u8` — 2,709줄 · 106,499B · sha256 `f6eb71a6…c9095`)은 렌더 코드가
아니라 아래 두 파이썬 스크립트가 만들고 대조했다. 생성기는 설계 4.8.1 표의 수치만 읽는다. 대조기는 설계 r17
4.8.2 실물 발췌의 세 덩어리와 축자로, 생략된 두 구간(631·264조각)과 줄 수로 견준다. VERSION 줄만 부기 32 에
따라 6 으로 바꿔 견주며, 설계 쪽에 대체 표식이 있을 때만 바꾼다.

저장소에는 스크립트 파일을 두지 않는다. 저장소에 추적되는 `.py` 가 없어, 새 언어를 들이지 않으려고 여기에
전문을 싣는다(계획 재량 14 · r28 처분). 다시 돌리려면 먼저 `T="$(mktemp -d)"` 로 **저장소 밖** 임시 디렉터리를
만들고 아래 두 파이썬 블록을 `"$T/gen_g6_f2.py"` · `"$T/check_g6_f2.py"` 로 저장한다 — 저장소 안에 두면
`git add -A` 가 `.py` 를 함께 싣는다. 그다음 `T` 가 남아 있는 같은 셸의 `media/` 에서 아래처럼 실행한다.
대조기는 설계 r17 원본이 있어야 도는데, 지금은 kty 볼트에만 있고 팀 위키에는 없다.

```bash
DESIGN=/path/to/26_design_r17.md   # 설계 r17 원본 경로로 바꾼다
python3 "$T/gen_g6_f2.py" "$T/g6_f2.m3u8" && cmp "$T/g6_f2.m3u8" internal/rewind/testdata/g6_f2.m3u8
python3 "$T/check_g6_f2.py" "$DESIGN" internal/rewind/testdata/g6_f2.m3u8
# OK 청크 3개 축자 일치(VERSION 줄 = 부기 32 대체) · 생략 [631, 264] · 줄 2709 · URI 900
```

2026-09-25 재실행: 생성 결과가 골든과 바이트 동일(`cmp` 무출력)했고, 대조기는 위 OK 줄을 냈다.

<details>
<summary>생성기 <code>gen_g6_f2.py</code> — 인자 = 출력 경로</summary>

```python
#!/usr/bin/env python3
"""g6_f2 골든 생성기 — 설계 r17 4.8.1 표의 수치만으로 900조각 전문을 적는다.

Go 렌더 코드와 무관한 독립 경로다(언어·서식 함수가 다르다). 규칙은 설계 4.8.2 실물의
줄 순서 그대로이고, 4.8.2 의 `# --` 설명 줄·`... 생략 ...` 줄·빈 줄은 싣지 않는다.
단 VERSION 줄은 kty 결정(2026-09-24)으로 6 이다 — 설계 4.8.2 의 9 에 대체 표식 · 계획 부기 32.
"""
import sys
from datetime import datetime, timedelta, timezone

BASE = "https://media.pokeclip.com"          # 4.8.1 조각 URI 행
STREAM = "str_7a"                             # 4.8.1 스트림
A_ID, B_ID = "S-20260831-0107", "S-20260831-0152"   # 4.8.1 세션 A·B
A_FIRST, A_LAST = 398, 1030                   # 633조각
B_FIRST, B_LAST = 1031, 1297                  # 267조각
A_PDT = datetime(2026, 8, 31, 1, 7, 59, tzinfo=timezone.utc)   # A 첫 PDT
B_PDT = datetime(2026, 8, 31, 1, 52, 11, tzinfo=timezone.utc)  # B 첫 PDT(120초 순단 뒤)
GAP_SEQ = 1297                                # upload_stall GAP 1개
TD, MSN, DISC_SEQ = 6, 398, 3                 # 4.8.2 머리 · 4.8.1 MSN·DISC-SEQ

out = [
    "#EXTM3U",
    "#EXT-X-VERSION:6",                       # 부기 32 — 설계 4.8.2 의 9 대체
    f"#EXT-X-TARGETDURATION:{TD}",
    f"#EXT-X-MEDIA-SEQUENCE:{MSN}",
    f"#EXT-X-DISCONTINUITY-SEQUENCE:{DISC_SEQ}",
]
for seq in range(A_FIRST, B_LAST + 1):
    if seq == A_FIRST:
        out.append(f'#EXT-X-MAP:URI="{BASE}/dvr/{STREAM}/init/{A_ID}.mp4"')
    if seq == B_FIRST:
        out.append("#EXT-X-DISCONTINUITY")
        out.append(f'#EXT-X-MAP:URI="{BASE}/dvr/{STREAM}/init/{B_ID}.mp4"')
    if seq == GAP_SEQ:
        out.append("#EXT-X-GAP")
    if seq <= A_LAST:
        pdt = A_PDT + timedelta(seconds=4 * (seq - A_FIRST))
    else:
        pdt = B_PDT + timedelta(seconds=4 * (seq - B_FIRST))
    out.append("#EXT-X-PROGRAM-DATE-TIME:" + pdt.strftime("%Y-%m-%dT%H:%M:%S") + ".000Z")
    out.append("#EXTINF:4.000,")
    out.append(f"{BASE}/dvr/{STREAM}/seg/{seq:06d}.m4s")

with open(sys.argv[1], "w", encoding="ascii", newline="\n") as f:
    f.write("\n".join(out) + "\n")
```

</details>

<details>
<summary>대조기 <code>check_g6_f2.py</code> — 인자 = 설계 파일 · 골든 파일</summary>

```python
#!/usr/bin/env python3
"""g6_f2 골든을 설계 r17 4.8.2 실물(발췌)과 4.8.1 수치에 대조한다 — Go 코드와 무관."""
import re
import sys

design, golden_path = sys.argv[1], sys.argv[2]
text = open(design, encoding="utf-8").read()
# 제목과 코드 블록 사이에 대체 표식(인용 줄)이 올 수 있다 — 제목 뒤 첫 코드 블록을 잡는다.
m = re.search(r"#### 4\.8\.2\. 실물 \(발췌\)\n(?:[^\n]*\n)*?```\n(.*?)\n```", text, re.S)
assert m, "4.8.2 블록을 못 찾음"
# 부기 32(kty 결정 2026-09-24): 발췌 둘째 줄 VERSION 9 → 6. 설계에 대체 표식이 있을 때만 치환한다.
MARK = "`#EXT-X-VERSION:9` 는 **6** 으로 대체"
assert MARK in text[m.start():m.start(1)], "4.8.2 대체 표식(VERSION 9 → 6)이 없다"
chunks, cur = [], []
for line in m.group(1).split("\n"):
    if "생략" in line:            # "... 생략: seq a ~ b (n조각) ..." — 청크 경계
        chunks.append(cur); cur = []
        continue
    if line.strip() == "" or line.startswith("# --"):
        continue                  # 설명 줄·빈 줄은 본문이 아니다
    cur.append(line)
chunks.append(cur)
assert chunks[0][1] == "#EXT-X-VERSION:9", chunks[0][1]   # 설계 발췌 원문
chunks[0][1] = "#EXT-X-VERSION:6"                          # 부기 32 대체
omitted = [int(x) for x in re.findall(r"\((\d+)조각\)", m.group(1))]
assert omitted == [631, 264], omitted

g = open(golden_path, encoding="ascii").read()
assert g.endswith("\n") and not g.endswith("\n\n")
lines = g[:-1].split("\n")
pos = 0
for i, ch in enumerate(chunks):
    assert lines[pos:pos + len(ch)] == ch, f"청크 {i} 불일치 @ {pos}"
    pos += len(ch)
    if i < len(omitted):
        pos += omitted[i] * 3     # 생략 조각 × (PDT·EXTINF·URI)
assert pos == len(lines), (pos, len(lines))

uris = [l for l in lines if not l.startswith("#")]
assert len(uris) == 900
assert sum(l.startswith("#EXT-X-PROGRAM-DATE-TIME:") for l in lines) == 900
assert sum(l == "#EXTINF:4.000," for l in lines) == 900     # 900 × 4.000 = 3,600.000초
assert sum(l == "#EXT-X-DISCONTINUITY" for l in lines) == 1
assert sum(l.startswith("#EXT-X-MAP:") for l in lines) == 2
assert sum(l == "#EXT-X-GAP" for l in lines) == 1
assert not any(l.startswith("#PC-") for l in lines)
assert not any(l == "" for l in lines)
assert all(re.fullmatch(r"https://media\.pokeclip\.com/dvr/str_7a/seg/\d{6}\.m4s", u) for u in uris)
assert [int(u[-10:-4]) for u in uris] == list(range(398, 1298))
print(f"OK 청크 {len(chunks)}개 축자 일치(VERSION 줄 = 부기 32 대체) · 생략 {omitted} · 줄 {len(lines)} · URI {len(uris)}")
```

</details>

### M4 ⓑ 출처

- 설계: 볼트 설계 r17(`Projects/PokeClip/specs/POK-168-r15a-되감기경로-2026-08-30/50_r4산출_2026-09-01/26_design_r17.md`
  — 3.2·4.1·4.2·4.3·4.5.5·4.8·6.5.6·9절)과 r7–r14(G6 계보 — 위 복원 기록).
- 계약·ADR: 팀 위키 `PokeClip-LLM-WIKI` — `contracts/계약-세그먼트인덱스.md`(불변식 1 · `is_discontinuity` 행) ·
  `contracts/계약3-LLHLS-DVR재생규약.md` 7절 · ADR-020 · ADR-073(위키 PR #143, main `a5faeef`).
- 규격: draft-pantos-hls-rfc8216bis-22 — 4.4.3.1(TARGETDURATION) · 4.4.3.4(ENDLIST) · 6.2.1(허용 변경 · PDT 1초
  미만 겹침) · 6.2.2(끊김 표시를 빼면 DISC-SEQ 를 올린다 · 줄이지 않는다) · 8절(버전 호환).
- 계획: POK-195 M4 작업 산출물 — 계획(개정 r33 까지)의 4절 PR ⓑ · 2.3 ⑸ⓕ · 6.2 · 부기 15·16·25·29·32 · 착수
  메모 넷(「④ 캐시」·「⑥ G6 픽스처·뮤테이션」·「⑦ 이관 기록」·「ⓒ」).
- 결정: kty 결정 F(2026-09-19 — 끊김 표시는 계승 경계에만) · VERSION 6 · ⓑ 숫자 상수 셋(2026-09-24) · 부기 15
  (300초 안 재접속도 base 승계) · f2 형상.
- 실측: hls.js 1.5.15·1.6.0·1.7.3 파서에 g6_f2 골든을 넣어 구문 오류 0, 해석 수치가 설계 4.8.1 과 같았다
  (VERSION 9·6 두 번, 2026-09-24 — 작업 산출물 `126_hlsjs_parse_g6_f2.md`). 바이트 실재생·compose 실측은 ⓒ 몫이다.
- 코드: 이 절이 인용한 파일·함수·테스트(PR ⓑ 커밋 ①–⑥ 개발 보고 · 리뷰 통합 r1–r6 과 대조).

## 테스트

```bash
cd media && go test ./...
```

`internal/index`·`internal/session`·`internal/indexer`·`cmd/segment-indexer`·`internal/upload`·`internal/rewind/cache`의 통합 테스트와
`internal/pgtest`의 자기 테스트는 **실제 PostgreSQL이 필요**하다. `PG_DSN`이 없으면 해당 케이스는 전부 `skip`되고 나머지는 그대로
돈다 — 즉 `PG_DSN` 없이 돌린 결과만으로는 DB 계층이 검증되지 않는다.

```bash
set -a; . ../.env; set +a
export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
go test ./internal/index/ ./internal/session/ ./internal/indexer/ ./cmd/segment-indexer/ ./internal/upload/ ./internal/rewind/cache/ ./internal/pgtest/ -v
```

이 통합 테스트들은 `PG_DSN`이 가리키는 DB에 **쓰지 않는다.** `PG_DSN`은 관리 접속으로만
쓰이고, 같은 서버에 전용 테스트 DB(`PG_TEST_DB`, 기본 `pokeclip_uploadtest`)를 만들어 그 안에서만
돌며 테스트 함수마다 비운다. **전용 DB는 패키지마다 하나씩이다**(`internal/pgtest`) —
`go test ./...`는 패키지별 테스트 바이너리를 병렬로 띄우므로 한 DB를 나눠 쓰면 서로의 표를
비운다. 실제 이름은 `PG_TEST_DB`에 패키지 접미를 붙인 것으로, `internal/index`만 무접미
(`pokeclip_uploadtest`)이고 `internal/session`은 `_session`, `internal/indexer`는 `_indexer`,
`cmd/segment-indexer`는 `_cmd`, `internal/upload`는 `_upload`, `internal/rewind/cache`는 `_rewindcache`, `internal/pgtest` 자기 테스트는 `_selftest`다. 그래서 개발 DB의 `stream_segments`는 오염되지 않는다(대신 `PG_DSN`
롤에 `CREATEDB` 권한이 필요하고 — **로컬 compose와 CI(`media-ci`)의 postgres 서비스 컨테이너 둘 다 superuser라 이미 갖고 있다**, `PG_TEST_DB`를 개발 DB 이름과 같게
주면 테스트가 기동 즉시 실패한다. `PG_DSN`에 DB 이름 자체를 안 적었을 때도 같은데, 단
`PGDATABASE`가 설정된 환경에서는 그 값이 DB 이름으로 채워져 "이름이 비었다" 가드 대신 동일 이름
가드가 판정한다).
**이미 있는 DB는 이 테스트가 심어 둔 소유 표식(빈 표 `pokeclip_testdb_marker`)이 있을 때만
채택한다 — 없으면 남의 DB일 수 있으므로 아무것도 건드리지 않고 실패한다.** 표식은 테스트가 DB를
새로 만들 때만 심으므로, **표식 도입 전에 만들어 둔 기존 전용 DB는 한 번 `DROP DATABASE` 후 다시
돌려야 한다**(한 번만 겪는 마이그레이션이고 실패 메시지가 그대로 안내한다). 다만 표식이 없는 까닭은 남의 DB나
중단된 부트스트랩 잔재일 수도 있으므로, 지우기 전에 그 DB가 전용 테스트 DB가 맞는지 직접 확인한다.
**`PG_DSN`에는 로컬 compose의 개발 DB만 준다 — 공유·원격·프로덕션 DSN을 주지 않는다.**
**같은 `PG_TEST_DB`로 두 실행을 동시에 돌리면 서로의 데이터를 지운다 — CI나 병렬 실행에서는
실행마다 고유한 `PG_TEST_DB`를 주고, 실행이 끝나면 그 이름의 접미 DB 전부를 `DROP DATABASE`로
정리한다**(정리 없이 고유 이름만 늘리면 DB가 무한히 쌓인다). 이 규약은 **PG를 공유하는 실행이 있을 때** 적용된다 —
`media-ci`의 postgres는 잡마다 뜨고 함께 사라져 공유가 없다 — 규약을 완화한 것이 아니라 적용
조건을 드러낸 것이다. `ddl.go`를 바꾼 뒤에는 전용 DB가 옛 스키마를 유지하므로
(`CREATE TABLE IF NOT EXISTS`) **접미 DB 전부**를 지운 뒤 다시 돌린다 — `pokeclip_uploadtest`
하나만 지우면 `internal/index`는 새 스키마로 통과하고 `_session`·`_indexer`·`_cmd`·`_upload`·`_rewindcache`는 옛
스키마 그대로라 그 다섯 패키지만 "does not exist" 계열로 실패한다.

**아래 루프는 소유 표식을 확인하지 않고 이름만 보고 지운다** — 밑이름이 기본 이름이거나 테스트가 새로 만든
고유 이름일 때만 쓰고, 표식이 없어 실패한 DB는 실패 메시지대로 전용 테스트 DB가 맞는지 직접 확인한 뒤 하나씩 지운다.

```bash
# PG_TEST_DB 를 바꿔 돌렸다면 그 이름이 밑이름이다 — 기본 이름만 지우면 실제 DB 는 남는다.
base="${PG_TEST_DB:-pokeclip_uploadtest}"
for suffix in "" _session _indexer _cmd _upload _rewindcache _selftest; do
  psql "$PG_DSN" -c "DROP DATABASE IF EXISTS ${base}${suffix}"
done
```

`internal/fmp4meta` 테스트는 `testdata/`의 커밋된 파일만 쓰므로 Docker가 꺼져 있어도 돈다.

`cmd/mtxhookwrite` 테스트는 **바이너리를 직접 빌드해 프로세스 8개를 동시에 띄운다**(줄 섞임 검증).
`go test`만 있으면 되고 Docker는 필요 없지만, 다른 테스트보다 몇 초 더 걸린다.

CI(`media-ci`)는 `go test`에 `-coverprofile`을 붙여 패키지별 커버리지를 함께 재고,
`internal/index`·`internal/upload`·`internal/indexer`·`internal/session`·`internal/mtxstate`·`internal/playback`·`internal/rewind/boundary`·`internal/rewind`·`internal/rewind/cache`
**아홉 패키지 중 하나라도 80% 미만이면 잡을 실패시킨다**(`session`·`mtxstate` 는 POK-195 M3에서,
`playback` 은 M4 ⓐ 에서, `rewind/boundary`·`rewind`·`rewind/cache` 는 M4 ⓑ 에서 추가 — 되감기 판정 로직과
③ 바이트를 만드는 층, 되감기 목록의 경계·본문·입력을 정하는 층이 그 안에 있다. 발행된 목록 줄은 고칠 수 없어
여기서 틀린 것은 그 목록이 사는 동안 남는다). 나머지 패키지는 수치만 로그에 남고 게이트 대상이 아니다.

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

**되감기 ③ 재포장 핀도 같은 때 대조한다.** 재포장 라이브러리(`mediacommon/v2`)의 판이 새 포크
실행 파일에 박힌 판과 같아야 하고, 녹화기의 mtxi 형식이 그대로여야 한다. 절차는 「되감기 M4 ⓐ
이관 기록」 절의 「포크 태그를 올릴 때」에 있다.

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
