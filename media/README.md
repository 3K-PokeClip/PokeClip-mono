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
- **← MediaMTX 송출 인증**: ingest-auth 응답기 — prod 계약은 [`cmd/ingest-auth/CONTRACT.md`](cmd/ingest-auth/CONTRACT.md)

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
| DB 권한 | 사이드카가 `POSTGRES_USER`를 그대로 쓴다. 이 표만 다루는 전용 롤이 바람직하다 | **해소** — POK-311. 아래 "사이드카 DB 계정 media_indexer 권한" 절(GRANT 목록 · 실 PG 역할 시험) |
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

- 구명칭 `runOnReady`는 **쓰지 않는다.** v1.19.3·v1.20.1·v1.21.1에서 그것은 `runOnAvailable`로
  매핑되며 "읽기 가능" 축이지 세션 축이 아니다.
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

### 강등 게이지 — 워처 · 파일 시스템

두 게이지는 세움(value 1)과 내림(value 0)이 둘 다 ERROR 다. 내림 뒤에는 다시 쓰지
않으므로 `LOG_LEVEL=error` 로 걸러도 해소가 보여야 한다(정정 2026-10-26 — 그 전에는
내림이 INFO 라 error 운영에서 게이지가 1 에 굳었다).

| 게이지 | 세움 | 내림 | 등급 |
|---|---|---|---|
| `watcher_degraded` | 기동 때 워처 조립 · 기동 실패(훅 · 스캔 유입은 계속) | 재스캔 주기의 재장착 성공(`watcher_recovered` 와 함께) | ERROR / ERROR |
| `fs_degraded` | 파일 작업 시간 초과로 FS 래치 트립(`path` · `site`) | 주기 머리 root 프로브가 응답해 래치 해제(트립 때 좌표를 싣는다) | ERROR / ERROR |

## segment-indexer 사이드카가 인식하는 환경변수

`cmd/segment-indexer`가 읽는 값 전부다. 코드를 읽지 않고도 무엇을 켤 수 있는지 알 수 있게
여기에 모아 둔다. 튜닝값은 `.env`가 아니라 `docker-compose.yml`의 서비스 블록에 인라인으로 둔다.

**필수 — 없거나 빈 문자열이면 즉시 종료(코드 1)한다.**

| 이름 | 의미 |
|---|---|
| `POSTGRES_USER` | DB 사용자 |
| `POSTGRES_PASSWORD` | DB 비밀번호 — **password 모드에서만 필수**(빈 문자열도 누락으로 취급한다). `POSTGRES_AUTH=iam` 이면 **비워야 한다**(있으면 기동 거부) |
| `POSTGRES_DB` | DB 이름 |

**선택 — 기본값이 있다.**

| 이름 | 기본값 | 의미 |
|---|---|---|
| `POSTGRES_HOST` | `postgres` | DB 호스트. **iam 이면 TCP 호스트 하나여야 한다** — `/`(유닉스 소켓) · 쉼표(여러 호스트) · 공백 · 대괄호 없는 IPv6 는 기동 거부 |
| `POSTGRES_PORT` | `5432` | DB 포트 |
| `POSTGRES_AUTH` | (빈값 = `password`) | DB 로그인 방식. `password`(빈 값) = `POSTGRES_PASSWORD` 로 로그인 — 로컬 compose 의 방식. `iam` = 연결마다 RDS IAM 인증 토큰(15분 유효)을 새로 만들어 로그인한다(자격 증명 = AWS 기본 사슬 · 인스턴스 역할). 그 밖 값(대소문자가 다른 `IAM` 포함)은 기동 거부 |
| `POSTGRES_SSLMODE` | `prefer` (iam 은 `verify-full`) | DB 접속 TLS 모드. `prefer`는 가능하면 TLS, 안 되면 평문. 네트워크를 건너는 배포에서는 `verify-full` + `POSTGRES_SSLROOTCERT` 로 올린다(CA 없는 `require` 는 암호화만 하고 서버를 확인하지 않는다). **iam 은 `verify-full` 만 받는다** — 그 밖 값은 기동 거부 |
| `POSTGRES_SSLROOTCERT` | (빈값 = 지정 없음) | 서버 인증서를 검증할 CA 번들의 **절대 경로**. 기동 때 일반 파일 · PEM 인증서 1개 이상을 확인한다. **iam 이면 필수.** password 모드에서도 이 값을 주면 `POSTGRES_SSLMODE` 는 `verify-ca` · `verify-full` 만 받는다 — 그 밖 값은 검증 강도를 값으로 밝히지 않으므로 기동 거부. **`verify-ca` 는 체인만 검증하고 호스트 이름은 보지 않는다 — 운영은 `verify-full`.** `PGSSLROOTCERT` 는 쓰지 않는다 — 이 값이 빈 채 `PGSSLROOTCERT` 만 있으면 기동 거부 |
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
| `REWIND_SEED_ENABLED` | `true` | 되감기 컷오프 **주조** 스위치(계약 6항 2단계). 켜져 있으면 각 스트림의 첫 자격 유입이 컷오프 하나를 만든다. M4 ⓒ 부터 기본 켜짐이다(kty 결정 2026-09-02 — 발행 층과 함께 켠다). **명시 `false` 가 롤백 손잡이다** — 컷오프를 새로 만들지 않는다. 빈 값은 기본값(켜짐)으로 읽힌다. 되돌려 꺼도 **이미 기록된 컷오프는 유효하고 그 스트림은 계속 발행된다** — 판정은 이 플래그가 아니라 장부를 읽는다 |
| `REWIND_GAP_RUN_END` | `false` | 정체 사다리 L3 스위치(POK-196 M6 · kty K5 — 기본 끔). 켜면 송출 중 회차에서 업로드 정체 GAP 이 조각 번호로 이어서 셋 서면(약 12초) 그 회차를 `ending`(사유 `gap_run`)으로 끝낸다. 재접속 창 안의 다음 조각은 새 회차를 열어 이어받는다. **켜는 조건**: 운영 송출의 종단 지연 실측(F-7)과 S3 짧은 장애 주입 실측이 둘 다 참이고 kty 가 승인한 뒤다. 기동 때만 읽는다(바꾸려면 재기동). 두 스위치 값은 기동 INFO `rewind_end_switches` 한 줄(`gap_run_end` · `stall_seal`)에 남는다. 참 · 거짓으로 읽을 수 없는 값(Go `strconv.ParseBool` 규칙)은 기동 거부다 |
| `REWIND_STALL_SEAL` | `false` | 정체 사다리 L2′(정체 마감) 스위치(POK-196 M6 · kty K5 — 기본 끔). 켜면 송출 중 회차의 되감기 목록이 5.5초(D_act = 갱신 의무 9초 − 엣지 1.5초 − 발행 예산 2초) 넘게 자라지 못했는데 GAP 도 설 수 없을 때(다음 조각이 없음 · 부적격 · 직전 GAP 실패) 목록 끝에 `#EXT-X-ENDLIST` 를 붙여 봉인하고, 봉인이 서면 그 회차를 `ending`(사유 `stall_seal`)으로 끝낸다(kty 결정 2026-10-27). 재접속 창 안의 다음 조각은 새 세션을 열어(같은 `round_id` — 봉인한 세션을 계승 · 4D 표식이 있어 사유가 `intentional` 이면 계승 · 합류하지 않는다) 그 새 세션의 목록으로 이어받는다. **켜는 조건**은 `REWIND_GAP_RUN_END` 와 같다. 기동 때만 읽는다(바꾸려면 재기동). 두 스위치 값은 기동 INFO `rewind_end_switches` 한 줄(`gap_run_end` · `stall_seal`)에 남는다. 참 · 거짓으로 읽을 수 없는 값은 기동 거부다 |
| `REWIND_PLAYBACK_READ_MAX` | `2` | 상태 전달 읽기(되감기 상태판에 넘길 스트림 상태를 장부에서 읽는 일)의 전체 동시 상한(POK-196 M6 · kty 결정 2026-10-29). 읽기 하나가 인덱서 풀 연결 하나를 쥐므로, 일제 종료 때 스트림 수만큼 읽기가 겹쳐 핫 경로와 풀을 다투지 않게 묶는다. 넘친 스트림은 버리지 않고 다음 보류 틱에 다시 읽는다. 이미 읽고 있는 스트림의 새 표지는 상한과 무관하게 그 읽기에 합쳐진다. 양의 정수만 받는다(0 · 음수 · 문자는 기동 거부) |

**되감기 상태 관측(POK-195) — MediaMTX Control API 폴링.**
용어: **관측**은 "지금 이 스트림이 실제로 송출 중인가"를 Control API(MediaMTX가 자기 상태를
알려주는 HTTP 창구)에서 주기적으로 읽어 오는 것이고, **주조**는 되감기 재생의 시작점(컷오프)을
장부에 처음 기록하는 것이다. `MTX_API_URL`이 비면 폴러를 아예 기동하지 않는다 —
관측이 없으면 두 가지가 멈춘다(둘 다 안전한 방향으로 잠긴다). 하나는 **관측 기반
주조(스캔 유입 ⓐ2)**다. 워처·훅 유입(ⓐ1) 주조는 계속되며 그것은
`REWIND_SEED_ENABLED`로만 멈춘다. 다른 하나는 **회차 종료 전이**(M4 ⓒ — 방송이
끝난 회차를 `live`에서 `ending`으로 보내는 것)다. 종료 판정이 신선한 관측을
요구해 서지 않으므로 같은 스트림의 연속 방송이 한 회차로 이어진다(아래 「되감기
M4 ⓒ 이관 기록」의 알려진 한계 「관측 끔」). 인덱싱·업로드는 그대로 돈다.

| 이름 | 기본값 | 의미 |
|---|---|---|
| `MTX_API_URL` | (빈 값 = 관측 끔) | Control API 베이스 URL. compose 기본값은 `http://media:9997`. **9997은 호스트에 공개하지 않는다** — compose 내부망에서만 도달한다. scheme+호스트가 필요하고 자격증명(`user:pass@`)은 거부한다(그 원문이 실패 로그에 실리기 때문 — 자격은 아래 두 env로 넣는다). query(`?`)·fragment(`#`)도 거부한다 — 이 값 뒤에 조회 경로가 이어 붙으므로 넣으면 폴이 영구 실패한다. 어느 거부 에러에도 URL 원문은 싣지 않는다. 자격을 넣으면 호스트는 **IP 리터럴**이어야 한다(M2 상자는 `http://127.0.0.1:9997`) |
| `MTX_API_USER` | (빈 값 = 무인증) | Control API Basic 자격의 사용자 이름. `MTX_API_PASS_FILE`과 **짝**이다 — 둘 다 넣거나 둘 다 빼며, 하나만이면 기동하지 않는다. 둘 다 비면 지금처럼 무인증으로 붙는다(dev compose — MediaMTX의 `user: any` 항목). `:`·제어 문자는 넣을 수 없다(Basic은 첫 `:`에서 사용자와 비밀번호를 가른다). 응답기의 `MTX_API_PASS`(env 값)는 **읽지 않는다** — 그 값만 있으면 무인증으로 뜬다. 그것이 `auth_rejected`로 드러나는 것은 사이드카가 붙는 주소에 맞는 무인증 `api` 항목(`user: any`)이 MediaMTX에 없을 때뿐이다(표 아래 「관측 자격 파일 규칙」) |
| `MTX_API_PASS_FILE` | (빈 값 = 무인증) | 비밀번호 파일의 **컨테이너 안 절대 경로**(예: `/run/secrets/mtx_api_pass`). 상대 경로는 기동 거부. 비밀번호를 env 값으로 받지 않는 이유: env 값은 `docker inspect`에 남는다. 파일 규칙은 표 아래. 기동 때 한 번 읽으므로 **교체 = 재기동**이다(M2 상자에서는 `systemctl restart pokeclip-media` — compose 전체 재시작). 자격이 거부되면(401) `mtxstate_poll_failed reason=auth_rejected`가 ERROR로 폴마다 나온다. 단 사이드카에 맞는 무인증 `api` 항목이 MediaMTX에 남아 있으면 틀린 자격도 거부되지 않아 이 줄이 나오지 않는다(표 아래 「관측 자격 파일 규칙」). `auth_rejected`가 이어지는 동안은 관측 끔과 같다 — 스캔 유입(ⓐ2) 주조와 회차 종료 전이(M4 ⓒ)가 멈추고 그동안 같은 스트림의 연속 방송이 한 회차로 이어진다 |
| `OBS_POLL` | `10s` | 관측 주기이자 **폴 1회의 상한**이다. 주기가 곧 재시도라 따로 재시도를 두지 않으며, 헤더조차 오지 않는 응답을 이 시간에 끊는다. `OBS_FRESH`보다 짧아야 기동한다 |
| `OBS_FRESH` | `30s` | 관측이 이보다 낡으면 방증으로 쓰지 않는다. 회차 종료 전이(M4 ⓒ)도 이 값으로 관측의 신선함을 본다 — 관측 나이 상한은 이 값 하나다. 주조 트랜잭션 상한(10s, `index.TxnDeadline`)보다 길어야 기동한다 — 같거나 짧으면 기동 거부 |
| `OBS_BACKFILL` | `60s` | 관측 시점보다 이만큼 더 과거인 조각은 밀린 백로그의 머리로 보아 주조하지 않는다 |
| `OBS_BOOT_WAIT` | `3s` | 기동 시 첫 관측을 기다리는 상한. 타임아웃이어도 **기동은 계속된다** — 관측 없이 뜨면 첫 관측이 올 때까지 **관측 기반 주조(스캔 유입 ⓐ2)와 회차 종료 전이(M4 ⓒ)가** 잠긴다(워처·훅 유입 ⓐ1 주조는 이 값과 무관하게 계속된다) |
| `SESSION_FLOOR_SLACK` | `1s` | 세션 귀속 하한의 여유(시계 역행 방어). 세션 시작보다 이보다 더 과거인 조각은 그 세션에 귀속시키지 않는다 |

**관측 자격 파일 규칙(POK-373).** 사이드카가 `MTX_API_PASS_FILE`을 읽는 규칙이다.

*   비밀번호 한 줄이다. 끝 줄바꿈 하나(`\n`·`\r\n`)만 걷고 앞뒤 공백·`:`는
    그대로 쓴다. 걷은 뒤 비었거나 줄바꿈·CR·NUL이 남으면 기동하지 않는다.
    파일은 4 KiB 이하.
*   일반 파일만 받는다(대상이 일반 파일인 링크는 허용). other 권한 비트나
    group 쓰기 비트가 있으면 기동하지 않는다 — 같은 그룹의 계정이 비밀번호를
    바꿔 끼우지 못하게 한다. group 읽기는 허용한다. 권장 배치는 소유
    10001(사이드카 실행 계정) `0400`, 또는 `root:10001` `0440`이고 읽기 전용으로
    마운트한다.
*   이 규칙은 표준이 아니라 선택이다. postgres 공식 이미지의 `*_FILE`은 끝
    줄바꿈을 전부 걷고, libpq의 `.pgpass`는 group 권한을 전부 거부한다. 우리는
    끝 공백을 보존하려고 "끝 하나만", `root:10001` `0440` 배치를 쓰려고 "group은
    쓰기만 거부"를 골랐다.
*   **자격 파일 실패 = 기동 실패**다. 관측의 다른 실패는 강등이지만(ADR-063),
    이것은 그 예외다(kty 2026-10-19). 자격이 틀린 채 강등으로 뜨면 끄지도 않은
    관측이 조용히 사라지기 때문이다. 원인은 stderr 한 줄로 나오고 비밀번호
    값은 싣지 않는다. 파일을 열지 못하면 env 이름과 원인(예: `no such file or
    directory`)만 나오고 경로는 빠진다 — 이 자리에 비밀번호를 잘못 넣었으면
    경로가 곧 그 값이기 때문이다. 열린 뒤의 거부(일반 파일·권한·크기·내용)는
    env 이름과 경로를 함께 싣는다. `MTX_API_URL`이 비면 파일을 읽지 않는다 —
    즉시 롤백이 파일 문제에 막히지 않는다.
*   한계: MediaMTX는 틀린 자격에 0–4초 늦게 401을 준다. `OBS_POLL`이 4초
    이하면 `auth_rejected` 대신 `request_failed`(폴 시한 만료)로 보일 수 있다.
    기본 10초는 안전하다.
*   **정상 신호는 인증 강제의 증거가 아니다.** MediaMTX의 `authInternalUsers`에
    `user: any`이면서 `api` 권한인 항목이 남아 있으면 사이드카는 자격과
    무관하게 붙는다 — 틀린 자격도 200을 받는다. 기본값의 localhost 관리자
    항목(`ips: ["127.0.0.1", "::1"]`)도 여기에 든다. M2처럼 같은 네트워크
    네임스페이스에서 `127.0.0.1`로 붙으면 그 항목에 걸린다. 그래서 기동 줄의
    `auth=basic first_observation=true`와 `auth_rejected` 0건은 강제의 증거가
    아니다. 강제 여부는 따로 확인한다 — 무자격 요청과 **같은 사용자 이름 +
    틀린 비밀번호** 요청이 **둘 다 401**이어야 한다. 다른 이름으로 시험하면
    아래 빈 비밀번호 함정(그 사용자는 어떤 비밀번호든 통과)을 못 잡는다.
*   자격을 싣는 요청은 평문 `http`다. 루프백 밖 주소로 보내면 비밀번호가 망에
    그대로 노출된다. M2는 같은 네임스페이스의 `http://127.0.0.1:9997`로 보낸다.
*   비밀번호 글자는 MediaMTX 쪽이 제한한다. v1.21.1은 `authInternalUsers`의
    평문 `pass`에 영문 대소문자 · 숫자 · `! $ ( ) * + . ; < = > [ ] ^ _ - { } @ # &`
    만 받고 `:` · 공백 · `,`가 든 값은 기동 때 거부한다(상류
    `internal/conf/credential.go`, 실측 2026-10-19). 16진 난수가 가장 안전하다.
*   MediaMTX YAML의 `user:` · `pass:` 값은 **작은따옴표로 감싼다**
    (`pass: '…'`). 맨 앞이 `#`(YAML 주석)이나 `!`(YAML 태그)이면 따옴표 없이
    쓸 때 값이 null, 곧 빈 자격이 된다. 상류 v1.21.1의 `Credential.Check`는 빈
    값이면 무엇이든 통과시킨다 — `pass:`가 비면 그 사용자는 사실상 무인증이고,
    `user:`가 비면 아무 사용자 이름이나 받는다. MediaMTX는 경고 없이 뜬다(상류
    `internal/conf/credential.go` · `internal/conf/yamlwrapper`, go-yaml
    v1.19.2 — 보안 리뷰 실측 2026-10-19).
*   MediaMTX 쪽에는 평문 대신 `sha256:<base64>` 해시 표기를 쓸 수 있다. 솔트 없는
    SHA-256 한 번이라 짧은 비밀번호는 설정 파일이 새면 오프라인 대입에 약하다 —
    16진 32자(128비트) 이상을 쓴다. 상류는 argon2 표기도 받는다 —
    `argon2:$argon2id$v=19$m=…,t=…,p=…$<솔트>$<해시>`처럼 `argon2:` 뒤에 표준
    인코딩 문자열을 붙인다(상류 v1.21.1 `internal/conf/credential.go`의
    `IsArgon2` · `validate`와 같은 디렉터리의 시험 값, 원문 확인 2026-10-19).

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
> 유입 ⓐ2)와 회차 종료 전이(M4 ⓒ)가 아무 증상 없이 멈춘다**(워처·훅 유입 ⓐ1 주조는
> `REWIND_SEED_ENABLED`가 켜진 한 계속된다 — 위 정의와 같다). 사이드카 로그의
> `mtxstate_poll_failed`만이 유일한 흔적이다.

**S3 업로더(POK-30) — `S3_BUCKET`이 비어 있으면 업로더가 꺼지고 아래 값은 무시된다(단 `SEGMENT_UPLOAD_TAIL_HOLD`만 예외 — 표 안 설명 참조).**

| 이름 | 기본값 | 의미 |
|---|---|---|
| `S3_BUCKET` | (빈값 = 비활성) | 업로드 대상 버킷. **버킷 판정이 다른 S3 값 검증보다 앞이라, 비워 두면 나머지 S3 설정이 틀려도 인덱싱은 산다** |
| `AWS_REGION` | `ap-northeast-2` | 버킷 리전. **DB IAM 토큰(`POSTGRES_AUTH=iam`)을 서명하는 리전도 같은 값이다** — 이 값은 버킷이 비어 있어도 그 용도로 읽힌다 |
| `S3_ENDPOINT` | (미설정 = AWS 기본) | 호환 스토리지(MinIO 등)용 엔드포인트 URL. scheme+호스트 필수. https 여야 한다 — http 는 SDK 서명 단계에서 PUT 이 거부된다(2026-09-24 실측) |
| `S3_FORCE_PATH_STYLE` | `false` | 호환 스토리지용 path-style 주소 지정 |
| `SEGMENT_UPLOAD_RETRY_MAX` | `4` | 조각 1개당 PUT 시도 상한(지수 백오프). 소진하면 `failed` 기록 후 스위퍼가 회수 |
| `SEGMENT_UPLOAD_SWEEP_EVERY` | `30s` | 스위퍼 회차 간격 — `pending`·`failed` 잔여를 주기 회수 |
| `SEGMENT_UPLOAD_CIRCUIT_MAX` | `3` | 연속 실패가 이 값에 닿으면 브레이커가 열려 PUT을 멈추고 다음 회차 탐침으로 복구를 살핀다. `0`이면 브레이커 없음 |
| `SEGMENT_UPLOAD_TAIL_HOLD` | `5s` | 꼬리(마지막) 조각을 이만큼 보류해 교정 창과의 충돌을 피한다. 꼬리 유예(2m)보다 짧고 `SEGMENT_SETTLE_WAIT` 이상이어야 기동한다. **예외: 이 값만은 버킷이 비어 있어도 항상 읽고 검증한다** — 보류 시계는 업로더가 아니라 인덱서 소유라 비활성에서도 돈다. 잘못된 값이면 버킷과 무관하게 기동이 거부된다 |
| `REWIND_PUBLIC_BASE_URL` | (빈값) | 되감기 목록(M4 ⓒ)의 조각 · MAP URI 앞머리 — 목록 줄은 앞머리 + `/` + S3 키다. **`S3_BUCKET` 이 있으면 필수다 — 비거나 틀리면 기동을 거부해 인덱싱 · 업로드도 함께 멈춘다**(종료 코드 1). 버킷이 비면 읽지 않는다. https 만 받고 http 는 호스트가 루프백 IP 리터럴(127.0.0.0/8 · `::1`)일 때만 받는다(`localhost` 는 예외가 아니다). 호스트가 있어야 하고 userinfo · query · fragment · 공백 · 제어 문자 · 따옴표 · 역슬래시 · 비ASCII · 범위 밖 포트(1–65535 아님) · 경로의 dot 세그먼트(`.` · `..` · `%2e` 꼴)를 거부한다(역슬래시 · 범위 밖 포트 · dot 세그먼트 셋은 PR ⓒ 종결 라운드에서 더한 것 — 이런 값을 두고 있던 사본은 다음 기동에서 거부된다). 거부 문구는 URL 원문 · userinfo · query 를 싣지 않는다(위반 종류와 포트 숫자 · scheme · 금지 바이트의 위치와 값만 싣는다). 끝 `/` 하나는 떼고 둘 이상이면 거부한다. 다른 env 와 달리 앞뒤 공백을 떼지 않는다. 켜짐 로그 `rewind_publish_started` 의 `base_url` 로 확인한다. live 목록이 있는 동안에는 바꾸지 않는다(아래 「되감기 M4 ⓒ 이관 기록」) |
| `REWIND_PUBLISH_POOL_MAX_CONNS` | `4` | 되감기 발행자 풀의 연결 상한(kty 결정 C2 — 아래 「사이드카 DB 연결 풀」). 양의 정수만 받고 틀리면 기동 거부. `S3_BUCKET` 이 비면 읽지 않는다 |
| `CONTROL_LISTEN` | (빈값) | 되감기 상태 제어면(M5)의 듣기 주소 `host:port`. **빈 값이면 서버를 띄우지 않는다** = 롤백 스위치(`HOOK_SPOOL_PATH` 와 같은 꼴). 로컬 compose 는 `0.0.0.0:8086`(호스트 게시 `127.0.0.1:8086`) · prod 는 `127.0.0.1:8086`. 포트 0 · 범위 밖 포트는 기동 거부다. 듣기에 실패하면(점유 포트) 기동은 잇고 ERROR `control_server_failed stage=bind` 한 줄이다 — 아래 「되감기 상태 제어면」 |
| `CONTROL_CORS_ORIGIN` | (빈값) | 제어면 응답에 CORS 를 여는 Origin 하나(`http(s)://호스트[:포트]` — 경로 · 끝 `/` · 질의 없음 · 글자 그대로 비교). 로컬 전용이다(compose 는 `http://localhost:3000`). prod 는 엣지 응답 머리 정책이 덮어 비워 둔다. 빈 값이면 CORS 머리를 싣지 않는다 |
| `CONTROL_STREAM_RPS` | `10` | 스트림 하나에 초당 답하는 상태 요청 수. 스트림 경로와 회차 경로가 한 칸을 나눠 센다 — 넘으면 503 · `Retry-After: 1` · `control_rate_limited` 게이지. 양의 정수만 받는다 |

자격증명은 SDK 기본 체인(env 3종 → 공유 프로필)을 쓴다. 로컬에서는
`eval "$(aws configure export-credentials --format env)"` 후
`docker-compose.override.yml.example`을 복사해 기동한다(파일 안 주석 참조).
`AWS_PROFILE`은 `~/.aws` 마운트 폴백을 켤 때만 함께 넣는다 — 마운트 없이 넣으면
SDK가 존재하지 않는 프로필을 요구해 기동이 죽는다(2026-08-04 실측).

**60일 만료(POK-196 M6-4) — clip 만료 창구 폴링.** 사이드카가 10분마다
clip 의 `GET /internal/broadcasts/expired` 를 불러, 보존 기간(60일)이 지난
방송의 장부 행과 S3 객체를 지운다. 켜는 조건은 주소 · `S3_BUCKET` · 발행 몫
정상 · 운영 권한(`30_grants.sql` — 아래 「사이드카 DB 계정 media_indexer
권한」)이다. 하나라도 빠지면 `expiry_disabled` 한 줄(`reason` = `no_url` ·
`no_bucket` · `publish_degraded` · `privilege` · `privilege_check` ·
`token_file` · `s3_client` · `assembly`)을 남기고 만료만 끈다 — 인덱싱 ·
업로드 · 발행은 그대로 돈다. 켜지면 `expiry_started` 가 나온다. 주기(10분) ·
하루 한 번 처음부터 훑기 · 창구 요청 시한(10초)은 고정값이고 env 가 없다.

| 이름 | 기본값 | 의미 |
|---|---|---|
| `CLIP_INTERNAL_URL` | (빈값 = 만료 끔) | clip 내부 주소 `http(s)://host[:port]`. 경로 · 끝 `/` · 질의 · 조각 · 자격증명이 있으면 기동 거부. **빈 값이 롤백 스위치다** — 비우고 사이드카만 재기동한다(`CONTROL_LISTEN` 과 같은 꼴). 주소가 비어도 아래 토큰 값의 규칙은 본다 |
| `CLIP_EXPIRY_TOKEN` | (빈값) | 만료 창구 토큰 값. 로그 · 오류 문구에 나오지 않는다. `CLIP_EXPIRY_TOKEN_FILE` 과 함께 넣으면 기동 거부. 둘 다 없이 켜면 clip 이 401 로 답하고, 연속 실패가 `expiry_stalled` 게이지(ERROR)로 드러난다 |
| `CLIP_EXPIRY_TOKEN_FILE` | (빈값) | 토큰 한 줄이 든 파일의 **컨테이너 안 절대 경로**(prod 는 파일로만 공급한다). 상대 경로는 기동 거부. 파일 규칙은 `MTX_API_PASS_FILE` 과 같다(위 「관측 자격 파일 규칙」). 다만 읽기 실패는 기동 실패가 아니라 **만료만 끈다**(`expiry_disabled reason=token_file`). 기동 때 한 번 읽으므로 교체 = 재기동이다 |

*   **한계 — CDN 캐시본:** 만료는 장부 행과 S3 객체만 지우고 CloudFront 캐시를
    무효화하지 않는다. 서명 쿠키가 살아 있는 시청자에게는 지운 조각 · 목록의
    엣지 캐시본이 캐시 TTL 동안 더 보일 수 있다.
*   **첫 적용 때 쓰기 정지:** M6 운영 SQL(`stream_schema.sql` ·
    `20_schema.sql`)의 인덱스는 `CREATE INDEX` 이고 `CONCURRENTLY` 가 아니다.
    처음 적용하거나 인덱스가 빠진 DB 에서는 인덱스를 만드는 동안 그 표의 쓰기가
    잠깐 멈춘다. 절차는 [prod 런타임 1단-가 런북](../infra/terraform/runbooks/runtime-1a.md)
    「M6 스키마 재실행」 이다.

**4D 종료 신호(POK-472) — resolve 설정.** 사이드카가 플러그인의 의도적 종료
신호(계약4 3-1 4D — `POST /api/ingest/end-signal`)를 제어면에서 받는다. 받는
조건은 `CONTROL_LISTEN` 이 있고 아래 두 값이 **함께** 있을 때다. 신호의 Bearer 는
auth 의 4C resolve(`/internal/stream-keys/resolve`)로 키를 풀어 대조한다.

*   **짝 규칙:** 두 값은 함께 넣거나 함께 비운다. 하나만 있으면 기동 거부다.
*   **둘 다 비면:** 4D 경로를 등록하지 않는다 — 404 이고 플러그인은 재시도하지
    않는다. 기동 때 WARN `end_signal_disabled reason=no_resolve` 한 줄이 나오고
    종료는 보장 시간 판정만으로 돈다. prod 에서 이 줄이 나오면 경보 대상이다.
*   **`CONTROL_LISTEN` 이 비면:** 제어면이 없으니 4D 경로도 없다. 그래도 두 값이
    있으면 토큰 파일은 기동 때 읽고, 읽기 실패는 기동 실패다.
*   **수신부 상한:** 키당 초당 1(429) · 전체 초당 10(503)은 고정값이고 env 가
    없다. 이 상한은 `control_rate_limited` 게이지를 세우지 않는다. 키당 초과는
    요청마다 `end_signal reason=rate_key` 줄이 센다. 전체 초과는 0 단계에서
    끊겨 묶음 줄 `end_signal_shed reason=rate_global count=…` 이 센다.
*   **회전 = 재기동:** 토큰은 기동 때 한 번 읽는다. 파일을 바꾼 뒤 사이드카를
    재기동해야 새 토큰이 쓰인다(보안 검수 L2).

| 이름 | 기본값 | 의미 |
|---|---|---|
| `STREAM_KEY_RESOLVE_URL` | (빈값 = 4D 끔) | auth 의 주소 `http(s)://host[:port]`. http · https 절대 URL 이어야 하고 호스트가 있어야 한다. 사용자 정보 · 질의 · 조각이 있으면 기동 거부. 경로 `/internal/stream-keys/resolve` 는 코드가 붙인다 — 주소에 경로가 있으면 그 뒤에 이어 붙는다. 3xx 는 따라가지 않고 실패로 본다. 프록시 env(`HTTP_PROXY` 등)를 타지 않고 auth 에 바로 붙는다. `STREAM_KEY_RESOLVE_TOKEN_FILE` 과 짝이다 |
| `STREAM_KEY_RESOLVE_TOKEN_FILE` | (빈값 = 4D 끔) | resolve 토큰 한 줄이 든 파일의 **컨테이너 안 절대 경로**. 상대 경로는 기동 거부. 토큰은 `X-Stream-Key-Resolve-Token` 머리로 실린다. 파일 규칙은 `MTX_API_PASS_FILE` 과 같다(위 「관측 자격 파일 규칙」 — 일반 파일 · other 비트와 group 쓰기 비트 거부 · 권장 소유 10001 `0400` 또는 `root:10001` `0440` · 4 KiB 이하 · 끝 줄바꿈 하나만 걷음). 앞뒤 공백 · 제어 문자가 남으면 기동 거부. 오류 문구에는 env 이름만 싣고 경로 · 내용은 싣지 않는다. 읽기 실패는 **기동 실패**다(만료 토큰과 다르다). 토큰 값을 env 로 받는 칸은 없다 |

**계약9 발행(POK-196 M6-8) — 방송 생명주기 이벤트.** 사이드카가 회차마다
`broadcast.started` · `broadcast.ended` 를 SNS FIFO 주제로 낸다. 장부 트랜잭션이
outbox 표(`broadcast_lifecycle_outbox`)에 행을 넣고, 발행 고루틴이 커밋된 행만
2초마다 낸다. 켜는 조건은 주제 ARN · 허용 목록 · 운영 권한(`30_grants.sql` 의
outbox 네 권한)이다. 하나라도 빠지면 `lifecycle_disabled` 한 줄(`reason` =
`no_topic` · `no_streams` 는 INFO, `privilege` · `privilege_check` ·
`sns_client` · `assembly` 는 ERROR)을 남기고 발행만 끈다 — 꺼지면 outbox 행도
넣지 않는다. 켜지면 `lifecycle_started` 가 나온다(`resolve` 칸 = 4C resolve
배선 여부).

*   **streamerId:** started 의 `streamerId` 는 위 4C resolve 설정으로 얻는다.
    resolve 설정이 비면 그 회차를 내지 않고 `lifecycle_abandoned
    reason=resolve_unwired` 로 버린다. 그래서 응답기 모드 뒤에 켠다.
*   **완전히 끌 때는 진행 중 회차가 없을 때 끈다.** 주제나 허용 목록을 비우면
    ended 판정 문장도 보내지 않는다. 그러면 started 만 나간 회차의 ended 는
    영영 적재되지 않는다. 허용 목록을 좁히기만 하면 생기지 않는다.
*   **기동 때만 읽는다.** 바꾸려면 사이드카를 재기동한다.

| 이름 | 기본값 | 의미 |
|---|---|---|
| `BROADCAST_TOPIC_ARN` | (빈값 = 발행 끔) | 계약9 SNS FIFO 주제 ARN `arn:aws:sns:<지역>:<12자리 계정>:<이름>.fifo`. 꼴이 틀리면 기동 거부. SNS 클라이언트는 이 ARN 의 지역으로 붙고 자격증명은 SDK 기본 체인이다. **빈 값이 롤백 스위치다** |
| `LIFECYCLE_PUBLISH_STREAMS` | (빈값 = 발행 끔) | outbox 에 넣을 stream_id 허용 목록(쉼표로 가름 · 앞뒤 공백 · 빈 항목 버림). `*` 는 전부다. 목록 밖 회차는 outbox 를 만들지 않는다 — 첫 배포는 kty 계정 하나로 드라이런한다 |

위 표가 `internal/config/config.go`가 읽는 전부다(`TZ` 제외 60개 —
`POSTGRES_*` · DB 몫 `AWS_REGION` 은 `internal/pgconf` 가 janitor 와 같은
규칙으로 읽는다). 잘못된 값(숫자 자리에 문자, 음수, 파싱 불가한 기간)은
조용히 기본값으로 넘어가지 않고 기동에 실패한다.
`SEGMENT_SUSPECT_BELOW_MS`가 `SEGMENT_EXPECTED_DURATION_MS`보다 크면 기동 단계에서 거부한다.

### DB 연결 성공 줄 `db_connected` (POK-311)

사이드카(인덱서 풀)와 janitor 는 기동 때 DB 연결 하나를 잡아 Ping 하고, 성공하면
INFO `db_connected` 한 줄을 남긴다. 실패하면 이 줄 없이 기동이 실패한다(사이드카 =
종료 코드 1 · janitor = `janitor_failed`). 배포 확인은 이 줄로 한다 — 예를 들어 RDS IAM
배포는 `"msg":"db_connected"` 이면서 `"auth":"iam"` · `"tls":true` 인 줄이 있어야 한다.
사이드카의 발행자 풀은 같은 설정으로 따로 열리지만 이 줄을 내지 않는다(인덱서 풀 한 줄로
판정한다).

| 필드 | 뜻 |
|---|---|
| `pool` | `indexer`(사이드카) · `janitor` |
| `auth` | `password` · `iam` |
| `sslmode` | 설정한 TLS 모드 |
| `tls` | **실제 연결이 TLS 인가** — `prefer` 는 평문으로 내려갈 수 있어 설정값만으로는 모른다 |
| `user` · `host` · `port` · `db` | 접속 대상 |

비밀번호 · IAM 토큰 · DSN · CA 파일 내용은 싣지 않는다.

한계: 사이드카 쪽 시험은 `openIndexerPool` 단위 시험(실 PG 에서 줄 한 줄 ·
닿지 않는 DB 에서 줄 0)과, `run()` 이 비밀번호 파일을 읽은 뒤 이 단계를
부르는지 보는 소스 순서 시험뿐이다. `run()` 을 끝까지 돌려 줄을 확인하는
시험은 없다 — 배포 확인과 로컬 compose 실측이 그 몫이다.

### DB 접속 운영 전제 (POK-311)

로컬 compose 밖(운영)에서 사이드카 · janitor 가 DB 에 붙을 때 지킬 것이다.
코드가 기동 때 거부하지 못하는 조건이라 배포 정의 · 인스턴스 설정이 맡는다.

- **운영은 반드시 `POSTGRES_AUTH=iam` + `POSTGRES_SSLMODE=verify-full`.**
  password 모드의 기본 `prefer` 는 서버가 TLS 를 받지 않으면 평문으로
  내려간다. 배포 뒤 `db_connected` 줄의 `tls` 가 `true` 인지 본다.
  IAM 토큰의 공식 유효 시간은 15분이지만 RDS 는 그 뒤에도 얼마간 받는다
  (2026-10-22 실험 RDS 서울 1대 · 1회 실측: 서명 뒤 19분 30초 수용 · 20분 30초
  거부 — 보장 값이 아니고 더 길 수 있다). 토큰이 새면 그동안 쓸 수 있으므로
  중간자가 토큰을 못 보게 `verify-full` 이 필요하다.
- **정적 키 환경 변수는 0개.** AWS SDK 의 자격 증명 사슬(credential chain —
  SDK 가 자격 증명을 찾는 차례)은 환경 변수의 정적 키를 맨 앞에, 인스턴스
  역할(IMDS — EC2 메타데이터 서비스)을 맨 뒤에 둔다. 컨테이너에
  `AWS_ACCESS_KEY_ID` · `AWS_SECRET_ACCESS_KEY` · `AWS_SESSION_TOKEN` 이 있으면
  인스턴스 역할 대신 그 키로 토큰을 서명한다. 공유 자격 증명 파일
  (`~/.aws/credentials`)의 정적 키도 인스턴스 역할보다 앞서므로, 운영
  컨테이너에는 `~/.aws` 를 마운트하지 않는다(로컬 실험용 마운트를 운영 정의에
  옮기지 않는다).
- **`AWS_EC2_METADATA_V1_DISABLED=true`** 를 준다 — 토큰 없는 옛 메타데이터
  요청(IMDSv1)으로 내려가는 길을 막는다.
- **`credential_process`(외부 명령에서 자격 증명을 받는 SDK 설정)를 쓴다면
  그 명령은 stderr 에 비밀을 쓰지 않아야 한다.** SDK 는 그 명령의 stderr 를
  컨테이너 stderr 에 그대로 잇는다. pgconf 의 오류 정제(원인 오류의 타입
  이름만 싣기)는 이 출력에 닿지 않는다.
- **`rds-db:connect` 는 DB 사용자별 ARN 으로 준다** —
  `arn:aws:rds-db:<리전>:<계정>:dbuser:<리소스 ID>/media_indexer` 처럼 사용자
  이름까지 적는다. `dbuser:<리소스 ID>/*` 면 인스턴스 역할이 그 DB 의 어느
  `rds_iam` 사용자로도 로그인한다.
- **기동 상한은 SDK 설정 파일 읽기를 덮지 못한다.** 풀 설정 조립과 연결
  확인에는 단계마다 20초 상한이 서지만, AWS 설정을 적재하며 파일을 읽는
  동작은 취소를 따르지 않는다. `AWS_CONFIG_FILE` ·
  `AWS_SHARED_CREDENTIALS_FILE` 이 FIFO 나 멈춘 네트워크 파일 시스템을
  가리키면 기동이 상한 뒤에도 멈춰 있다. 운영에서는 이 변수를 두지 않거나
  일반 파일을 가리킨다.

### 사이드카 DB 연결 풀 (POK-472)

사이드카는 같은 `media_indexer` 계정으로 풀 둘을 연다. 두 풀의 최대 연결 수
합이 그 역할의 `CONNECTION LIMIT` 안이어야 한다(지금 20 —
[10_roles.sql](../infra/terraform/prod/runtime/sql/10_roles.sql)).

| 풀 | 연결 상한 | 쓰는 곳 |
|---|---|---|
| 인덱서 풀 | 6 고정(`indexerPoolMaxConns`) | 핫 경로(메인 루프 · 업로드 워커 · 스위퍼) · 회차 조회 · 4D 종료 신호 적용 · 60일 만료 · 계약9 발행기 · 정산 차선 · 기동 걸음(스키마 확인 · 상태판 적재) |
| 발행자 풀 | 4(`REWIND_PUBLISH_POOL_MAX_CONNS` 로 조정 — kty 결정 C2) | 되감기 발행 · 정산 걸음의 DVR · VOD 동결 |

*   **인덱서 풀 6 은 몫별 예약이 아니라 합의 상한이다.** 산식은 핫 경로 3
    (메인 루프 · 업로드 워커 · 스위퍼) · 회차 조회 동시 2 · 4D 1 이다. 산식
    밖의 소비자도 같은 6 을 나눠 쓴다 — 60일 만료(동시 1) · 계약9 발행기(동시
    1)와 정산 차선(정산 스캔 1 · 회차 정산 걸음 · 상태 전달 읽기)이다. 기동 걸음(스키마 확인 ·
    상태판 적재)은 업로더 · 만료 · 제어면이 뜨기 전에 끝나 겹치지 않는다.
*   **정산 걸음은 전체 2 · 같은 스트림 1 이다(kty 결정 2026-10-27).** 상한
    6 은 그대로 두고 걸음 쪽을 줄였다. 같은 스트림 걸음끼리 잠금을 다투면 진
    쪽이 연결을 쥔 채 기다려 핫 경로가 굶었다. 이 풀을 쓰는 걸음(빈 동결 ·
    사슬 닫힘 · VOD 실패 확정 · ended 표시)의 연결은 많아야 둘이다. DVR ·
    VOD 동결은 발행자 풀에서 연결을 쥔다.
*   **상태 전달 읽기는 전체 2 다(kty 결정 2026-10-29 ·
    `REWIND_PLAYBACK_READ_MAX`).** 읽기 하나가 연결 하나를 쥐어, 일제 종료
    때 스트림 수만큼 읽기가 겹치지 않게 한다. 넘친 스트림의 표지는 남아 다음
    보류 틱(1초)에 다시 나간다. 이미 읽는 스트림의 표지는 상한과 무관하게 그
    읽기에 합쳐져 결과 뒤 곧바로 다시 읽는다.
*   **동시 수요가 6 을 넘을 수 있다.** 넘친 요청은 풀 획득을 기다린다.
    회차 조회는 획득 대기와 질의를 합친 2초 시한을, 4D 는 적용 시한 1.5초를
    다 쓰면 503 이다. 교착은 아니다 — 잡은 연결은 일이 끝나면 돌아온다. 예로,
    회차 조회 2 · 4D 1 · 만료 1 이 모두 연결을 쥐고 있으면 핫 경로 셋이 나눠
    쓸 수 있는 것은 2 다. 정산 걸음 2 와 정산 스캔 1 까지 겹치면 핫 경로
    몫은 0 이라 핫 경로도 획득을 기다린다(최악).
*   **교체 창의 여유는 0 이다.** 사이드카 한 대는 6 + 4 = 10 이다.
    [Media 교체](../infra/terraform/runbooks/media-swap.md) 동안 A · B 두
    상자의 사이드카가 겹치면 합이 20 으로 역할 상한과 같다.
*   **예고 — 역할 상한 30(kty 결정 C1).** 고치는 곳은 M6-6b 의
    `10_roles.sql` 이다. 그 전까지 상한은 20 이다. 올린 뒤 교체 창의 여유는
    10 이다.
*   **발행자 풀은 4 로 고정한다(kty 결정 C2).** 상자 CPU 수를 따르는 pgx
    기본값이면 큰 상자에서 교체 창 합이 역할 상한을 넘는다. 늘릴 때는
    `REWIND_PUBLISH_POOL_MAX_CONNS` 를 바꾸고, 두 상자 합 2 × (6 + 이 값)이
    역할 상한 안인지 본다.

## 되감기 상태 제어면 (POK-200 M5)

사이드카가 띄우는 작은 HTTP 서버다. 웹이 「지금 되감기를 볼 수 있나 · 어느 목록을 보나」를
묻는다. 답은 루프가 1초 holdTick 마다 만드는 불변 스냅숏(Board)에서 나온다 — 요청마다
DB · S3 · 캐시를 보지 않는다. 회차 경로만 Board 가 모르는 회차를 DB 에서 한 번 읽는다
(POK-196 M6 — 인덱서 풀 · 동시 2 · 초당 20 · 2초 시한 · 1초 캐시).

| 경로 | 지금 | 답 |
|---|---|---|
| `GET /api/streams/{streamId}/rewind` | 켜짐 | 그 스트림 키의 지금 국면 |
| `GET /api/streams/{streamId}/rounds/{roundId}/rewind` | 켜짐(M6) | 그 회차의 국면 — 웹이 끝남 대기의 종국 판정에 쓴다 |

본문은 키 다섯이다 — `{"ready":bool,"mode":"live"|"ending"|"vod"|"ended"|null,"url":…,"firstPdt":…,"headPdt":…}`
(값이 없으면 `null` · 계약3 7-2절). 미준비도 200 본문으로 낸다(엣지 오류 캐시에 기대지 않게).

| 상태 | 언제 |
|---|---|
| 200 | 캐시나 종료 항목이 그 스트림을 안다 — `ready:true` 는 소유 회차가 live · init 확정 · 발행 판이 있을 때뿐이라 url 은 늘 S3 에 있다 |
| 404 `{"error":"not_found"}` | 캐시 · 종료 항목 둘 다 모른다(부팅 감시 뒤) · 발행 꺼짐(`S3_BUCKET` 빈 값 — 모든 스트림) · id 가 `[A-Za-z0-9._-]{1,128}` 밖 · 퍼센트 인코딩한 경로(`%61bc` 처럼 `RawPath` 가 생기는 요청 — 풀린 글자가 규칙 안이어도) |
| 503 `{"error":"unavailable"}` · `Retry-After: 1` | 첫 스냅숏 전 · 부팅 감시 전의 모르는 스트림 · 스냅숏이 30초 넘게 낡음 · 스트림별 한도 초과 |

회차 경로는 Board 의 종료 항목이 그 회차를 알면 그 값을, 모르면 DB 의 그 회차 하나를 답한다(계약3 7-2절
회차별 조회 — 같은 키의 다른 회차 값을 섞지 않는다). 위 표에 더해지는 갈래는 아래다.

| 상태 | 회차 경로에서 언제 |
|---|---|
| 200 | 그 회차에 송출 중인 세션이 있다(`mode:"live"` · `url` null) · 끝남 대기(`ending`) · 다시보기 있음(`vod` — url 과 범위는 그 회차 VOD 목록) · 다시보기 없음(`ended`) |
| 404 | DB 도 그 회차를 모른다 — 없는 회차 · 다른 스트림의 회차 · 60일 만료 |
| 503 | DB 읽기 실패 · 2초 시한 초과 · 전역 예산(동시 2 · 초당 20) 초과 · 장부 값을 응답으로 옮기지 못함(아래 `control_round_lookup_invalid`) — 같은 회차 키로 동시에 온 요청은 lookup 하나의 답을 함께 받는다(singleflight). 503 은 내부 1초 캐시에 저장하지 않으며, 예산이 허용된 다음 miss 에서 다시 읽는다 |

전역 예산은 인증 없는 공개 경로에 걸려 있어, 무작위 회차 id 를 쏟는 한 클라이언트가 예산을 다 쓰면 다른
시청자의 DB 조회 갈래(Board 가 모르는 회차)도 503 이 된다(설계 R54 절충 · 보안 c5 L1). 완화는 엣지의
IP 속도 제한(WAF 후속)이다.

머리는 상태와 무관하게 `Cache-Control: public, max-age=1` · `Content-Type: application/json` 이다.
`CONTROL_CORS_ORIGIN` 이 설정돼 있으면 `Vary: Origin` 을 Origin 과 무관하게 늘 싣고, 요청
`Origin` 이 그 값과 글자 그대로 같을 때만 `Access-Control-Allow-Origin` 을 싣는다.
서버 한도는 머리 5초 · 읽기 5초 · 쓰기 5초 · 유휴 60초 · 머리 8KiB 이고, 종료 때 처리 중 요청을
3초까지 기다린다(그 뒤 풀이 닫힌다).

*   **발행 강등(버킷은 있는데 발행자 조립 실패):** 서버는 정상이다. 캐시가 없어 live 행이 없고
    M6 가 보낸 종료 항목만 답한다. 같은 키로 새 방송이 시작돼도 지난 방송의 `vod` · `ended` 가
    남는다(한계 — 계약3 7-2 단서).
*   **M5 단독의 종료 국면:** 끝난 스트림은 뷰를 버릴 때(Forget · 300초) 잠정 `ending` 이 되고,
    재기동하면 사라진다(재기동 적재는 M6 가 채운다 — 지금은 `rewind_final_seeded count=0`).
*   **로그:** `control_server_started` · `_disabled` · `_failed`(bind · serve) · `_stopped` ·
    `control_status_stale` · `control_rate_limited` · `rewind_ready_delayed`(송출 중인데 60초 넘게
    준비 안 됨 · `reason` 하나) · `rewind_status_range_missing` · `rewind_final_applied` ·
    `rewind_final_invalid` · `rewind_final_seeded` · `_seed_failed` ·
    `control_round_lookup_failed`(회차 lookup 실패 · `reason` 은 `error` · `timeout`
    · `panic` · 사유마다 10초에 한 줄로 묶고 `count` 로 센다) ·
    `control_round_lookup_invalid`(ERROR — 회차 장부 값을 응답으로 옮기지 못했다 ·
    `stream_id` · `round_id` · `reason` 은 `vod_incomplete` · `out_of_range` · 같은
    회차 키 동시 요청은 lookup 하나라 한 줄이다(singleflight) · 그
    요청은 503 이고 `control_round_lookup_failed reason=error` 에도 센다. 장부가
    뒤틀린 결함 표지라 묶지 않는다 — 줄 수는 전역 예산 초당 20 이 묶는다). 요청마다의
    접근 로그는 없다.
    게이지(`control_status_stale` · `control_rate_limited` · `control_round_shed` ·
    `rewind_ready_delayed`)는 세움(value 1)과 내림(value 0)이 둘 다 WARN 이다.
    내림 뒤에는 다시 쓰지 않으므로 `LOG_LEVEL=warn` 으로 걸러도 해소가 보여야
    한다.
*   **되돌리기:** `CONTROL_LISTEN` 을 비우고 사이드카만 재기동한다. 인덱싱 · 업로드 · 발행은
    영향이 없다.
*   **로컬에서 바이트까지 보기:** 기본 compose 는 발행이 꺼져 있어 모든 스트림이 404 다.
    [`infra/compose/rewind-minio/`](../infra/compose/rewind-minio/README.md) 를 덧붙이면 MinIO 로
    발행하고 `ready:false` → `true` 전이와 url 200 을 볼 수 있다.

## 사이드카 DB 계정 media_indexer 권한

운영 사이드카(segment-indexer · 업로더 · 세션 결정 · 되감기 발행자)는 DB 에
`media_indexer` 로 붙는다. 이 계정에는 아래 권한만 준다(POK-311). 단위는
표다 — 열 단위로 주면 3번이 승인한 열이 추가될 때마다 운영 GRANT 가 깨진다.

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_segments TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_sessions TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_cutoffs TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON stream_published_gaps TO media_indexer;
GRANT SELECT ON stream_seq_floors TO media_indexer;
GRANT SELECT, INSERT, UPDATE, DELETE ON broadcast_lifecycle_outbox TO media_indexer;
```

- **DELETE 는 M6 만료 · outbox 몫만이다(ADR-0006 개정 —
  `docs/decisions/2026-10-26-media-indexer-m6-delete-grants.md`).** 60일
  만료가 조각 · GAP · 세션 행과 그 회차 outbox 행을 지우고, 발행기가 끝난
  outbox 행을 지운다. `stream_cutoffs` 의 UPDATE · DELETE 는 만료의 컷오프
  전진 · 제거 몫이다 — 주조는 여전히 한 번 넣고(`ON CONFLICT DO NOTHING`)
  고치지 않는다. 쓰는 문장은 M6-4 · M6-8 이 들이고, 권한은 미리 준다.
- **TRUNCATE 없음 · `stream_seq_floors` 는 SELECT 만.** seq 하한은
  `stream_segments` 의 `AFTER DELETE` 트리거가 정의자(표 소유자) 권한으로
  올린다. 사이드카가 하한을 내리거나 지울 수 없다.
- **DDL 없음 — `ENSURE_SCHEMA=false`.** 스키마(`internal/index/ddl.go`)는
  별도 역할이 적용한다. 이 계정으로 `ENSURE_SCHEMA=true` 를 켜면 기동 때
  42501 로 막힌다.
- **그 밖에 이 계정에 따로 주는 권한은 없다 — 대신 PUBLIC(모든 계정)
  기본 권한에 기댄다.** 시퀀스는 없고(DDL 에 `SERIAL` · `IDENTITY` 0),
  트리거 함수 · advisory 잠금 · `set_config` 는 PUBLIC 기본 권한으로 돈다(하한
  갱신 함수만 PUBLIC EXECUTE 를 회수했다 — 트리거 발화는 EXECUTE 를 보지 않는다).
  `public` 스키마 `USAGE` 도 PUBLIC 기본값에 기댄다. DB `CONNECT` 는 다르다
  — 운영 DB(`pokeclip`)는 CONNECT 를 PUBLIC 에서 거두고 계정마다 따로
  준다(`infra/terraform/prod/runtime/sql/10_roles.sql`). 그렇게 하지 않은
  DB 는 PUBLIC 기본값으로 붙는다. 반대로 PUBLIC 기본값이 이 목록보다 넓은
  DB 가 있으므로 아래 사전 확인을 한다.
- **`FOR UPDATE` 는 `stream_sessions` 에 있다**(init 업로드 확정). 행 잠금에도
  UPDATE 권한이 필요하다. `stream_segments` 의 `FOR UPDATE NOWAIT` 는
  janitor(`media_janitor`) 몫이다.
- **`stream_published_gaps` 는 GAP 원장(되감기 목록에 끊김 표시로 발행한
  조각의 장부)이다.** 되감기 발행자가 발행 전에 INSERT 로 `recorded` 를
  기록하고, P4(목록 PUT 확정) 단계가 UPDATE 로 `put_confirmed` 를 찍는다.

계정(`CREATE ROLE media_indexer LOGIN …`, IAM 인증이면 `rds_iam` 부여)은 DB
관리자가 만든다. 비밀번호를 쓴다면 비밀 관리자에만 둔다.

**운영 DB 사전 확인.** GRANT 전에 DB 관리자가 본다.

```sql
SELECT has_schema_privilege('media_indexer', 'public', 'CREATE');  -- false 여야 한다
```

- 결과가 true 면(PostgreSQL 14 이하이거나 누가 되돌린 DB) 이 계정도 표를
  만들 수 있다 — PG15 부터 `public` 스키마 CREATE 가 PUBLIC 에서 빠졌다.
  `REVOKE CREATE ON SCHEMA public FROM PUBLIC;` 을 한다. 임시 표는 운영
  DB(`pokeclip`)에서 막았다 — `10_roles.sql` 이 `TEMP` 를 PUBLIC 에서
  거두고 아무에게도 주지 않는다(`40_verify.sql` 의 `public_temp_revoked` ·
  `temp:<계정>`). 다른 DB 에서도 막으려면
  `REVOKE TEMP ON DATABASE <DB 이름> FROM PUBLIC;` 을 한다. 역할 시험의
  「CREATE TABLE 42501」 도 이 PG15+ 기본값을 전제로 한다.
- **계정 하드닝**(만들 때 확인): `NOSUPERUSER` · `NOCREATEDB` ·
  `NOCREATEROLE` · `rds_superuser` 비소속 · `CONNECTION LIMIT`(사이드카 두
  풀의 최대 연결 수에 여유를 더한 값) · 역할 단위 `statement_timeout`
  (`ALTER ROLE media_indexer SET statement_timeout = …`).
- **표 소유자는 DDL 역할이다.** `media_indexer` 가 표를 소유하면 소유자는
  GRANT 와 상관없이 모든 권한을 가지므로 이 목록이 무의미해진다.
- **새 표 · 열이 생기면 이 목록을 다시 본다.** 새 표는 GRANT 를 더하고
  (`internal/pgtest/role.go` · 위 블록 · 운영 GRANT 를 함께), 새 열은 표
  단위 권한을 자동으로 받으므로 사이드카가 그 열을 고쳐도 되는지 본다.

**SQL 규약 — 내장 함수는 `pg_catalog` 로 한정한다.** 심층 방어다. 운영
DB 의 앱 사용자 넷은 1단-나 ①(POK-428) 뒤로 `public` CREATE 가 없고 자기
스키마에만 표를 만든다. 그래도 누가 `public` CREATE 를 다시 연 DB 에서는
거기 심은 같은 이름의 판이 맨 이름 함수 · 연산자를 가로챌 수 있다
(CVE-2018-1058 계열). 새 SQL(사이드카 · janitor · `ddl.go`)은
`pg_catalog.now()` 꼴로 쓰고, `pg_catalog` 에 정확한 판이 없는 형은 캐스트로
맞춘다(`::bigint` · `::double precision` · `::oid` · `::name`). 시험은
`internal/pgtest/searchpath.go` 의 덫이다.

**잔여 위험.** 이 목록이 막지 못하는 것이다.

- **표 단위 UPDATE 는 사이드카가 쓰지 않는 열도 연다.** 예:
  `stream_sessions` 의 `dvr_*` · `vod_*`, `stream_segments` 의 `s3_key`.
  사이드카 자격이 새면 이 열을 고칠 수 있다. 열 단위 GRANT · 트리거는 열
  추가마다 운영 GRANT 가 깨지므로 넣지 않았다.
- **advisory 잠금은 이 목록이 막지 않는다.** 잠금 함수(`pg_advisory_*`)는
  기본값으로 PUBLIC 이 실행할 수 있고, 키 단위 권한은 없다. 그래서 이 DB 에
  로그인하는 다른 계정이 컷오프 주조 잠금 키(`hashtext('pc_cutoff_' || stream_id)`)를
  쥐고 놓지 않으면, 그 스트림의 컷오프 주조가 `lock_timeout` 으로 실패를
  되풀이한다. 방어는 둘이다: 이 DB 의 로그인 계정 수를 줄이는 것, 그리고 잠금
  함수의 `EXECUTE` 를 PUBLIC 에서 회수하고 사이드카 계정에만 주는 것(함수 소유자
  권한이 필요하다 — RDS 의 마스터 사용자로 되는지는 아직 확인하지 않았다).
  **운영은 회수하지 않는다(K7 · kty 2026-10-24).** 이 DB 에서 잠금을 쓰는
  계정이 다섯이라(사이드카 · 앱 사용자 넷의 Flyway · auth · clip 의 잠금 —
  `media_janitor` 만 쓰지 않는다) 그 계정들에 다시 주어도 컷오프 열쇠를 막는
  위협이 그대로이고, Flyway 판이 잠금 함수를 바꾸면 앱 부팅이 42501 로
  멈추기 때문이다(`docs/decisions/2026-10-24-app-schemas.md`). 마스터가
  회수할 수 있는지는 `infra/terraform/prod/runtime/sql/50_advisory_probe.sql`
  이 관측만 한다. 아래는 다시 회수를 검토할 때의 조건이다. 회수에는 두
  전제가 붙는다. 이 DB 에서 advisory 잠금을 쓰는 다른 계정(마이그레이션
  도구 등)에도 함께 허용해야 그 계정이 깨지지 않는다. `GRANT` · `REVOKE` 는 DB
  마다 따로 적용된다 — 이 DB 하나에만 하면 다른 DB 는 그대로다. 새로 만드는
  DB 는 `template1` 을 복제하므로 새 DB 까지 막으려면 `template1` 에도 한다.
  운영에 앞서 스테이징 DB 에서 회수와 관련 도구 동작을 먼저 확인한다.
  함수를 허용받은 계정끼리는 키 단위로 막을 수 없다.
- **표 단위 DELETE 는 장부 행 삭제를 연다.** 사이드카 자격이 새면 조각 ·
  세션 · GAP · outbox 행과 컷오프를 지울 수 있다. 하한 트리거는 지운 뒤의
  seq 재사용만 막고(불변식 4), 지운 행 자체는 되살리지 못한다 — 복구는 백업
  뿐이다. 만료 전용 계정으로 나누는 안은 기각했다(ADR-0006 개정).
- **하한 갱신 함수는 정의자(`media_owner`) 권한으로 돈다.** 다른 표에 붙이면
  그 표의 행으로 남의 하한을 올릴 수 있으므로, EXECUTE 를 PUBLIC 에서 회수해
  새 부착을 막고(42501) 함수가 `stream_segments` 밖에서는 멈춘다(TG_RELID
  검사). `40_verify.sql` 이 소유자 밖 EXECUTE 없음(PUBLIC · 특정 계정
  GRANT 모두)을 단언한다.
- **`stream_cutoffs` 가 오염되면 DBA 가 손으로 고친다.** 사이드카의 UPDATE
  · DELETE 는 만료 문장(장부 값으로만 전진)용이고, 잘못 들어간 컷오프를
  고치는 경로는 아니다.

**시험 경로.** 목록의 정본은 `internal/pgtest/role.go` 한 곳이고, 위 블록과
같다는 것을 `internal/pgtest/role_test.go` 가 단언한다. SQL 이 사는 패키지마다
같은 권한만 가진 시험 역할로 사이드카 저장 경로 전부를 실 PG 에서 돌린다
(문장마다 성공 확인 · 상태 전이 단언 · 권한 하나를 거두면 42501 인 음성 대조).

- `internal/index/media_indexer_role_pg_test.go` — 장부 기록 · 업로드 장부 ·
  되감기 읽기
- `internal/session/media_indexer_role_pg_test.go` — 세션 결정 · 개시
- `internal/rewind/publish/media_indexer_role_pg_test.go` — fence · P0 ·
  P2′ · P4 · 화해 · GAP 원장

로컬 compose 는 소유자 계정(`POSTGRES_USER`)으로 붙어 이 경계를 확인하지
못한다 — 위 시험이 그 몫이다.

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
- **플래그와 무관하게 쓰이는 것이 있다**: `REWIND_SEED_ENABLED`(M3 당시 기본
  `false` — M4 ⓒ 부터 기본 `true`)가 가르는 것은 **컷오프 주조 권한 하나**다.
  세션 행·carrier 3열·init 준비는 플래그가 꺼져 있어도 쓰인다
  (설계대로이며, 계보에 구멍을 내지 않기 위해서다 — kty 확인 2026-09-02).
- **관측 축 롤백 손잡이**: `MTX_API_URL`을 비우면 폴러를 아예 기동하지 않는다 → 관측 없음 →
  스캔 유입은 세션을 열지 않는다(안전한 방향으로 잠긴다). M4 ⓒ 부터는 회차
  종료 전이도 멈춘다(위 env 절). 위 env 절의 **팀 1회 조치**도 함께 본다.

### M4(발행 층)로 넘어간 것

셋째 칸은 PR ⓐ(생산 층)·ⓑ(렌더·경계)가 들어온 뒤의 반영 상태다. 자세한 것은 아래 「되감기 M4 ⓐ 이관 기록」·「되감기 M4 ⓑ 이관 기록」 절에 있다.

| 항목 | 왜 M3가 아닌가 | M4 반영 |
|---|---|---|
| `playback.Producer`(③·init 바이트 생산자)와 그 호출 통로 | 생산자가 없으면 M3에 두는 호출부는 호출자 0인 코드가 된다. M3에는 순수 키 파생(`playback/key.go`)만 남겼다 | **완료(PR ⓐ)** — `internal/playback/producer.go` `Producer`·`Remuxer`(Go 재포장). 호출 통로는 업로드 워커(`internal/upload/axis_body.go` `produce`)이고 주입 자리는 `upload.Options.Producer` 다 |
| `init_s3_key`·`init_sha256`·`init_bytes` 세 열의 **쓰기 경로**와 워커 배선 | 값의 유일한 생산자가 위 `Producer.Init`이다. M3는 확정 CAS 문장·계약과 픽스처까지 | **완료(PR ⓐ)** — 첫 init CAS(`internal/index/upload_store.go` `MarkInitUploaded`)가 세 열과 `init_uploaded_at` 을 한 문장으로 쓴다. 워커 배선은 `axis_body.go` `markInitUploaded` |
| ③ 바이트 추출·③ 재수거·init 재수거 | 바이트가 없는 상태에서 수거를 켜면 없는 파일을 집어 영구 격리된다 | **완료(PR ⓐ)** — 추출은 `axis_body.go` `playbackPayload`, 재수거는 `internal/upload/sweep.go` `sweepAxes`(② → ③ → init) |
| 스위퍼의 init 조회 · backlog 축별 집계 | 회수기가 M4라 지표만 켜면 행동 없는 신호가 된다. **M3 동안 backlog 지표는 ② 축만 보여 준다**(③·init은 프로덕션 동작이 0건이라 숨는 장애가 없다) | **완료(PR ⓐ)** — init 조회 `pendingInitUploadsSQL`, 잔량 3벌(`countArchiveBacklogSQL`·`countPlaybackBacklogSQL`·`countInitBacklogSQL`). `upload_backlog` 가 축 라벨을 단다 |
| 발행 게이트 실물(`init_uploaded_at IS NULL` → `ready:false`)·G7 skew·`rewind_cutoff_absent` 알람·writer fence | 전부 발행 층 소유다. M3는 그 **장부 축**만 픽스처로 확인했다 | **완료(PR ⓐ·ⓑ·ⓒ)** — G7 완결: ⓐ 의 skew 기록(`internal/indexer/chain.go` `recordSkew`, Debug)에 ⓑ 의 S7 PDT 엄격 증가 검사(`internal/rewind/validate.go` `checkPDT`)가 더해졌다. 발행 게이트·알람·fence 는 ⓒ(ⓒ 에서 들어왔다 — 게이트 `internal/rewind/publish/gate.go` `ShouldTick` · 알람 `rewind_cutoff_absent`(30초 감시 (c)) · fence `internal/rewind/publish/fence.go`) |
| 세션 종료 전이(`live` → `ending` → `ended`) | M4/M6. **그래서 M3에서는 같은 스트림의 연속 방송이 첫 회차에 계속 귀속된다** — 발행 층이 없어 무해하며, 테스트가 현 동작을 문서화한다 | **일부(PR ⓐ · ⓒ)** — ⓐ 가 init 불일치에 의한 `live → ending(init_mismatch)` 를 넣었고(`upload_store.go` `SettlePlaybackFailure` 결속 갈래), ⓒ 가 송출 종료 전이 `live → ending(offline)` 을 넣었다(판정 `internal/indexer/judgment.go` `OfflineDue` · 전이 `internal/index/store.go` `EndLive`). `ended` 는 M6 이다. 연속 방송이 첫 회차에 귀속되는 동작은 관측이 켜져 있으면 닫혔고, `MTX_API_URL` 을 비우면 그대로다(아래 「되감기 M4 ⓒ 이관 기록」 알려진 한계) |
| `REWIND_SEED_ENABLED` 기본값 `true` 전환 | 발행 층이 오는 PR에서 함께 켠다(kty 결정 2026-09-02). 스위치 자체는 롤백 손잡이로 남는다 | **완료(PR ⓒ)** — `internal/config/config.go` 의 기본값이 `true` 다. 명시 `false` 가 롤백 손잡이이고 새 컷오프 주조만 멈춘다(이미 주조된 스트림은 계속 발행된다) |
| `discontinuity_base` 증분 산식 | 렌더 축 값이라 소비자(M4)와 같은 커밋에서 정한다. M3는 명시적 기본값 `0`("승계 없음") | **완료(PR ⓑ)** — `rewind.EvictedDiscontinuityTags`(순수 함수 — 목록 앞에서 빠지는 끊김 표시 수 = base 에 더할 증분). 배선(발행 예약 문장 P0 에 동봉)은 ⓒ(ⓒ 에서 배선했다 — P0 동봉이 아니라 마지막 발행본 기준 절대식이고 base 는 P4 · 화해가 쓴다. 부기 35) |
| 상태 관측 등급(tier) ⓘⓘ·ⓘⓘⓘ 상수 | 폴러가 실측상 ⓘ만 산출한다. 소비자가 생길 때 같이 넣는다 | 대기 — 슬레이트 유입이 착지하는 마일스톤(계획 8절) |
| **부채** — `indexer.go`가 1228줄(M3 전 1074)이다. 판정 묶음(`observation`·`buildSeed`·`corroborates`·`withStateObs`·`sessionOp` = `:595`–`:726`, 약 130줄)을 `judgment.go`로 분리한다 | 응집이 하나(유입 → 방증 → 연산)라 지금 나눠도 읽기가 나아지지만, 그 묶음을 실제로 건드리는 것이 M4(발행 축 판정 편입)라 **그때 같은 커밋에서** 옮긴다. 지금 옮기면 M4 리뷰가 이동과 변경을 함께 읽어야 한다 | **완료(PR ⓒ)**(ⓒ 커밋 1 이 판정 묶음을 `internal/indexer/judgment.go` 로 옮겼다 — 부기 4. `indexer.go` 는 지금 1,135줄로 800줄 상한을 아직 넘고, 더 나눌 계획은 정하지 않았다) |

### M5(보안 강화)로 넘어간 것

v3의 `api` 권한은 조회 전용이 아니라 설정 변경·경로 추가·강제 끊기·녹화 삭제까지 포함한다.
사이드카 쪽 자격 수단은 POK-373으로 생겼다 — `MTX_API_USER`·`MTX_API_PASS_FILE`로 Basic
자격을 싣는다(위 env 절). 남은 것은 MediaMTX 쪽이고 인프라 몫이다.

*   dev compose(`infra/compose/mediamtx.yml`)의 셋째 항목(`user: any` + `api`)은
    그대로라 **자격증명도 IP 제한도 없다**. dev는 지금도 **⑴ compose 전용 브리지
    네트워크 격리 ⑵ 9997 포트 미공개** 두 겹에만 의존한다. IP 대역으로 좁히는
    것은 IPAM 신설을 동반해 인프라 축이 넓어진다.
*   M2 상자에서 인증을 강제하려면 무인증 `api` 항목(기본값의 localhost 관리자
    항목 포함)을 지우는 일이 따라야 한다. 지우기 전에는 사이드카가 자격을 실어도
    강제되지 않는다(위 「관측 자격 파일 규칙」).

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
`REWIND_SEED_ENABLED`(ⓐ 당시 기본 `false` — ⓒ 부터 기본 `true`)가 가르는 것은 컷오프
주조 권한 하나다(위 M3 절과 같다).
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
`pendingPlaybackUploadsSQL`·`countPlaybackBacklogSQL`). ⓐ 당시 기본값(플래그 끔)에서는 새
스트림에 컷오프가 없으므로, 실시간 ③ 이 실패한 조각은 스위퍼가 다시 집지 않고
`upload_backlog axis=playback` 에도 잡히지 않는다 — 실패 로그(`upload_failed` 등)로만 보인다.
ⓒ 부터는 기본 켜짐이라 첫 자격 유입 뒤의 조각을 스위퍼가 다시 집는다(컷오프 앞 조각은 그대로다).
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

- **라이브러리 판을 녹화기와 맞췄다.** `github.com/bluenviron/mediacommon/v2` v2.9.5 는
  MediaMTX 녹화기가 이 파일을 쓸 때 쓴 판이다(포크 `v1.21.1-pokeclip.1` 실행 파일에 박힌 판).
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
  영속한다(`SettlePlaybackFailure` 의 결속 갈래). 다음 조각이 새 회차·새 init 을 연다.
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
  - 예시 파일의 `S3_BUCKET` 줄은 ⓒ 에서 `${S3_BUCKET-…}` 로 고쳤다. 그 예시를 복사한
    새 사본은 `S3_BUCKET=` 를 명령 앞에 붙여 끈다
    (`S3_BUCKET= docker compose up -d --force-recreate segment-indexer`).
    따로 한 줄로 치면 내보내지 않은 변수라 compose 에 닿지 않는다.
    옛 사본의 `${S3_BUCKET:-pokeclip-media-demo-2557}` 는 빈값을 기본 버킷으로
    바꾸므로 그 줄을 `S3_BUCKET: ""` 리터럴로 고친 뒤 같은 명령을 앞붙임 없이 친다.
    override 를 빼고 끄는 길은 사본과 무관하다. 그 길과 확인 줄은
    아래 「ⓒ 되돌리기와 긴급 정지」에 있다.
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
2. `media/go.mod` 의 핀(v2.9.5)과 다르면 핀과 `TestRemuxMediacommonVersionMatchesRecorderPin`
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
| **init 잔량이 M4 동안 영구히 남을 수 있다.** M4 에는 `ended` 전이가 없어 「`ended` 제외」가 실제로 거르는 것이 없고, init 조회·잔량 조건이 `init_uploaded_at IS NULL AND state <> 'ended'` 뿐이다(보존 창·컷오프 한정 없음). M3 로 운영된 DB 의 회차(init NULL), 원본이 1일 보존을 넘긴 회차, 확정 못 한 회차가 여기 쌓인다 | 데이터·재생 영향은 없고 경보 소음이다(M6 까지). 원본이 없는 회차는 프로세스가 뜰 때마다 `upload_file_missing` WARN + 격리되고, 30건을 넘으면 스위퍼 회차(30초)마다 `upload_backlog axis=init` WARN 이 난다 | **kty 회부**(계획 부기 31 후보와 같은 축): init 조회·잔량을 원본 보존 창(`start_wall_utc > now() − SessionTTL`)으로 한정하거나, 설계 5.5.4 2번 「컷오프-인지」를 init 에도 적용 〔추기 2026-09-25 — 부기 31 은 닫혔다(위 「M4 ⓐ 로 켜진 것과 아직 없는 것」의 추기). 이 회부는 그와 별개라 아직 판단 대기다.〕 〔추기 2026-10-19 — ⓒ 에서 닫혔다: kty K3 결정으로 한계 유지 · M6 이월(아래 「M4 ⓒ 에서 M5/M6 로 넘긴 것」 표).〕 |
| **init CAS `Mismatch` 는 S3 객체 보호가 아니다** — PUT 이 CAS 보다 먼저라, 다른 본문이 같은 키에 올라간 뒤에야 장부가 기존 해시를 지킨다 | 같은 송출 설정·같은 재포장 산출(골든)이면 오지 않는다. 다만 ⓐ 에서 회차는 방송을 넘어 이어진다(`session/registry.go` — live 회차가 있으면 TD(목표 길이) 초과가 아닌 한 계속 붙는다). 그래서 사이드카 재기동 뒤 그 회차에 처음 들어온 조각이 해상도 등 송출 설정을 바꾼(A → B) 방송의 것이면, 재기동 뒤 첫 행의 init 재요청이 이 분기에 닿는다 — B 의 init 을 먼저 PUT 하고 CAS 가 `Mismatch` 라, ERROR 1줄이 남고 그 회차 init 객체는 B 로 덮이며 그 회차 실시간 ③ 은 전부 대조 보류된다. 재포장 산출을 바꾸는 배포(골든 갱신 — 위 「포크 태그를 올릴 때」)로 재기동했으면 이어지는 모든 회차가 같은 경로에 닿는다. 송출 설정 변경 쪽은 ⓒ 의 송출 종료 전이가 회차를 끊어 원인째 닫고, 배포 쪽은 회차가 배포를 넘어 이어져 ⓒ 로도 닫히지 않으므로 오른쪽 수리안이 닫는다 | 조건부 PUT(`If-None-Match`)·내용 주소 게시 — ⓒ `publish.Store` 의 조건부 PUT 축과 함께 검토 |
| **ⓐ 단독 국면에서 방송 사이 휴지가 24시간을 넘으면 다음 방송의 실시간 ③ 이 보류에 묶인다.** 회차가 방송을 넘어 이어지는 동안 `sessionInit`(과 보정값 표)은 수명(`SessionTTL` 24시간)으로 지워지는데, 인덱서의 `initAdmitted`(이 프로세스에서 init 요청이 접수된 회차)는 프로세스 수명 동안 남아 init 재요청이 나가지 않는다. 그래서 다음 방송의 실시간 ③ 이 전부 `init_pending` 보류로 남고 drain 으로도 풀리지 않는다(검수 탐침 2026-09-24: 25시간 휴지 뒤 둘째 방송의 ③ 마킹 0건). 실시간 경로는 재기동해야 init 재요청이 `AlreadySame` 으로 `sessionInit` 을 되살린다(휴지 중 송출 설정이 바뀌었거나 재포장 산출이 바뀐 배포로 재기동했으면 위 `Mismatch` 행 경로) | 시청자 영향 0 — ⓐ 단독 국면에는 ③ 소비자가 없고, 그 행들은 ⓒ 가 켠 컷오프보다 앞이라 등재 대상이 아니다. 컷오프가 있는 스트림이면 보류분도 보류 상한 초과분도 스위퍼가 장부 기대값으로 집어 올린다(검수 탐침: 보류 1 → 스위퍼 성공 → 보류 0) — 남는 것은 스위퍼 주기만큼의 지연과 중복 작업(실시간 시도가 보류에서 멈춘 조각을 스위퍼가 다시 처리)이다. ⓒ 에서는 송출 종료 전이가 회차를 끊어 닫는다 | 기록용(코드 무변경) |
| **보류 재요청의 거울상 경합** — tick 이 ③ 작업을 꺼낸 뒤 재요청하기 전에 같은 조각의 스위퍼 사본이 먼저 확정되면(그 조각의 in-flight 가 이미 풀려 있다) tick 의 재요청이 그대로 접수돼, 같은 바이트를 한 번 더 PUT 하고 `upload_cas_rejected` WARN 1줄이 난다(µs 창 — ⓒ 처럼 컷오프가 있어 스위퍼가 ③ 을 집을 때만. ⓒ 에서 주조 스위치가 기본으로 켜져 이 조건이 평시다). 장부는 CAS 가 지킨다. 확정 표시(tombstone)는 반대 순서(재요청이 거부된 뒤 `claimReady` → `giveBack` 사이에 확정되는 경우)만 막는다 | 재PUT 1회·WARN 1줄, 데이터 영향 0. 확정 표시 메모리는 보류를 한 번이라도 겪은 회차에서 24시간 동안 최악 약 21,600개·약 538 KiB(검수 탐침)이고, 24시간 뒤 비운다 | 워커 PUT 직전 확정 표시 검사(다음 버전) · 기록용 |

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
  〔추기 2026-10-19 — ⓒ 에서 닫혔다: kty K3 결정으로 한계 유지 · M6 이월.〕

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

〔추기 2026-10-19 — 열둘 모두 ⓒ 에서 닫혔다. 닫힌 자리는 아래 ⓒ 절의 「ⓑ 가 넘긴
열둘이 닫힌 자리」 표다(번호가 같다).〕

| # | 넘긴 것 | 한 줄 요지 | 계획 자리 |
|---|---|---|---|
| 1 | 축출 증분 배선 · prev 계약 · 재기동 변형 | `EvictedDiscontinuityTags(prev, nextMSN)` 의 prev 는 「소유 회차의 지금 base 를 DISC-SEQ 로 적어 렌더한 직전 목록」(발행에 실패한 시도 포함)이다. 「마지막으로 발행된 목록」으로 잡으면 PUT 실패 뒤 같은 표시를 두 번 센다. 발행 예약(P0) 커밋 뒤 PUT 전에 죽으면 그 증분의 기준 창이 어디에도 남지 않는다 — ⓒ 복구 설계의 입력 | r32 |
| 2 | 재기동 뒤 `rewind.Published` | 직전 발행의 MSN·조각 수는 메모리에만 있다. 영값으로 넘기면 S1(줄 수)·S2(MSN 역행) 검사가 자명하게 통과한다 — 본문 GET 이나 재구성으로 채운다 | r29 |
| 3 | 유입 정지 판정의 입력 | 오프라인 전이의 입력은 캐시가 아니라 인덱서 커서다 — 캐시는 컷오프 전 행·컷오프 없는 스트림의 행과 `start_wall_utc` 를 들지 않는다 | r31 |
| 4 | DISC-SEQ 단조 그물 · 끊김 표시 줄 검사 · 모르는 태그 검사 | 셋 다 S1–S7 에 없다. 끊김 표시 줄 검사·모르는 태그 검사는 목록 본문을 쓰는 코드가 `Render`·`SealEndlist` 둘뿐이라 ⓑ 가 만들지 않았다(커밋 ⑥ 재판정). DISC-SEQ 단조 그물은 처음부터 ⓒ 몫이다 — DISC-SEQ 가 뒤로 가면 RFC 8216bis-22 6.2.2 MUST 위반이라, ⓒ 가 계승 취소를 영속하는 형상과 함께 정하고 앞의 둘도 그때 함께 판정한다 | r29 추기 · r33 |
| 5 | TD 초과 접두·꼬리 교정의 S5 정지(숨은 가정 11) | TD 분할 회차를 300초 안에 계승하면 접두 조각이 새 회차 TD 를 넘고, 꼬리 교정은 TD 를 다시 재지 않는다 — 두 길 모두 목록이 S5(본문 형식·TD 검사)로 최대 1시간 멈춘다(`TestValidateInheritedTDSplitPrefixHaltsOnS5` 가 지금 동작을 고정). 처방 후보 (A) 계승 개시 TD = max(직전 TD, 첫 조각 반올림) · (B) S4 에 TD 조항 · (C) 꼬리 교정 때 TD 재판정을 kty 에 회부한다. 판별 공백 VB-LASTROW(S5 TD 조항이 마지막 줄 회차의 TD 를 읽는 뮤턴트)도 그 결론과 함께 정한다(ⓒ 에서 닫혔다 — kty K1 · K2) | r29 · r32 추기 |
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
〔추기 2026-10-19 — ⓒ 에서 닫혔다: 접두 길은 kty K1(계승 개시 TD), 꼬리 교정 길은
kty K2(알려진 한계)다. VB-LASTROW 는 `TestValidateTDIsOwnerSessionsOutsideWriteRule` 이
가른다.〕

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

## 되감기 M4 ⓒ 이관 기록 (POK-195)

M4 의 셋째 PR(ⓒ 발행)이 **무엇을 넣었고, 무엇을 M5/M6 로 넘겼고, 운영자가 무엇을
알아야 하는지**를 적는다. ⓒ 는 ⓑ 가 만든 되감기 목록을 S3 에 올리는 발행 층과,
방송이 끝난 회차를 닫는 종료 전이를 넣었다. 버킷(`S3_BUCKET`)을 준 환경에서는
회차마다 목록 객체가 S3 에 생기고 조각이 실릴 때마다 갱신된다. 목록을 시청자에게
내주는 경로(제어면 · CDN)는 M5 라서 시청자 화면은 아직 그대로다. 위 ⓐ 절이 「ⓒ 의
M4 종합 이관 기록이 싣는다」고 미룬 설계 편차 행도 이 절이 싣는다.

### M4 ⓒ 용어

위 M3 · ⓐ · ⓑ 절에서 푼 말(세션 · 컷오프 · init · ③ · GAP · CAS · 렌더 · settled · 창 ·
DISC-SEQ · base · 계승 회차 · S1–S7 · 봉인 · 읽기 뷰)은 다시 풀지 않는다.

| 말 | 뜻 |
|---|---|
| 발행 | 검사를 지난 목록 본문을 S3 의 목록 객체 `dvr/{stream}/{session}/index.m3u8` 에 조건부 PUT 으로 올리는 것. 첫 판은 `If-None-Match: *`, 그 뒤는 직전 판의 ETag 를 건 `If-Match` 다 |
| 틱 | 한 회차 목록을 한 번 발행하는 작업. 세대 규약 P0–P4 를 차례로 돈다 — P0 세대 예약(DB) · P1 렌더 · P2 발행 전 검사 · P3 조건부 PUT · P4 확정 기록(DB)(설계 4.4.3절) |
| P | 그 목록 URL 에 마지막으로 발행된 판(본문 · ETag · MSN · DISC-SEQ). 다음 틱이 견주는 기준이다 |
| writer fence | 한 회차 목록을 쓸 수 있는 프로세스를 하나로 정하는 장부 칸(`stream_sessions.writer_fence` · lease 5초). 프로세스마다 부팅 때 새 토큰을 만든다 |
| 화해 | S3 의 현재 판을 Head(ETag · 메타 8키)로 읽어 P 와 장부를 그 판에 맞추는 것. 본문은 읽지 않는다 |
| 메타 8키 | 목록 객체에 함께 싣는 사용자 메타 — `pc-gen` · `pc-pub-seq` · `pc-session` · `pc-terminal` · `pc-msn` · `pc-seg-count` · `pc-disc-seq` · `pc-body-sha256` |
| 발행 억제 게이트 | 회차가 `live` 이고 그 init 이 올라갔고 그 스트림이 적재 중이 아닐 때만 틱을 내는 판정(`publish.ShouldTick`) |
| GAP 원장 | GAP 으로 싣기로 확정한 조각의 기록 `stream_published_gaps`. `recorded` 로 들어가고, 그 GAP 을 실은 판이 P4 로 확정되면 `put_confirmed` 가 된다 |
| 정체 사다리 | 목록이 멈췄을 때 1초마다 보는 단계 — L1 그 조각의 ③ 재시도를 당긴다(실렸어야 할 시각부터 0.5초) · L2 그 조각을 GAP 으로 싣는다(2초) · L4 경보(목록 정체 7.5초) |
| 보류 틱 | 메인 루프의 1초 주기(`Indexer.HoldTick`). 발행 배선 · 사다리 · 종료 판정이 이 주기에 돈다 |
| 30초 감시 | 30초마다 DB 를 한 문장 읽어 캐시와 견주는 작업 — (a) 캐시 머리 다음 행과 컷오프 대조 · (c) 컷오프 부재 알람 · 잃은 init 결과 화해 |
| 차선 | 스트림마다 작업 하나만 도는 작업 통로(`publish.Lanes`). 루프는 작업을 내고 기다리지 않으며 결과는 되감기 완료 case 로 받는다 |
| 적재 | 한 스트림의 캐시 뷰를 장부에서 다시 읽어 채우는 것 — ⓑ 의 부팅 재구성을 워커로 낸 형이다 |
| 종료 전이 | 송출이 끝난 회차를 `live` 에서 `ending(offline)` 으로 보내는 것(계획 4.1). 관측이 신선한데 송출 중이 아니고, 유입이 멈췄고(마지막 조각 끝에서 그 길이의 세 배), 그 뒤에 시작한 파일 점검 수집이 결손 없이 완주했을 때다 |
| 계승(이어받기) | 직전 회차 마지막 조각 끝에서 300초 안에 같은 스트림의 새 조각이 오면, 새 회차가 직전 회차를 잇는 것(ADR-044 · `ReconnectWindow`) |
| stsd 지문 | 녹화 조각 머리말의 트랙별 코덱 사양(stsd 상자) 해시. 계승 회차의 첫 init 확정 때 직전 회차 첫 조각과 견줘 다르면 계승을 푼다 |
| P2′ 계승 취소 | 첫 PUT 전에 S4(계승 접두 검사)가 걸리면 계승을 장부에서 푸는 문장. 첫 PUT 뒤의 S4 위반은 발행 중단이다 |
| 강등 | 발행 몫 조립이 실패하면 발행 몫만 버리고 업로드 · 인덱싱은 계속 도는 것. 회복은 재기동이다 |
| 공개 표면 | 패키지 밖에서 부를 수 있는 이름(대문자로 시작하는 타입 · 함수 · 메서드 · 칸)의 모음. 아래 「설계와 다르게 한 것」이 그 수를 센다 |
| 드리프트 | 캐시(메모리 뷰)가 DB 장부와 어긋난 상태. 30초 감시 (a) 가 찾고 적재로 고친다. 위 ⓐ 절의 벽시계 드리프트(녹화기 시계가 벽시계와 벌어지는 것)와 다른 말이다 |
| 재귀 CTE | SQL 의 `WITH RECURSIVE` 절 — 앞 단계 결과로 다음 단계를 다시 부르는 질의. 30초 감시가 장부의 스트림 ID 를 하나씩 건너뛰며 모으는 데 쓴다 |
| 라이브 에지 | 라이브 재생 목록의 맨 끝(가장 최근 조각 쪽). `EXT-X-ENDLIST` 가 없는 목록이면 플레이어가 실시간으로 보려고 여기서 시작한다 |
| ETag | S3 가 객체의 판마다 돌려주는 식별 값(HTTP 응답 헤더). 조건부 PUT 은 「내가 본 판의 ETag 그대로일 때만 덮어쓴다」를 이 값으로 건다 |
| lease | writer fence 의 유효 기한(5초). 기한 안에 늘리지 않으면 다른 프로세스가 fence 를 가져갈 수 있다 |
| lazy 갱신 | 마지막 갱신 뒤 3.5초(`LazyRenewAfter`)가 지났을 때만 lease 를 늘리는 보조 갱신. P0 도 같은 문장에서 lease 를 새로 쓴다 |

### M4 ⓒ 로 켜진 것과 아직 없는 것

*   **켜졌다 — 버킷과 무관**: 컷오프 주조 스위치 기본 켜짐(`REWIND_SEED_ENABLED`) ·
    종료 전이 `live` → `ending(offline)` · 300초 안 재접속 계승 개시와 계승 개시 TD.
    버킷이 비어도 종료 전이는 돈다(기동 로그 `rewind_publish_disabled` 의 문구).
*   **켜졌다 — 버킷이 있을 때**: 목록 발행(세대 규약 · writer fence · 화해) ·
    GAP 원장 · 정체 사다리 L1 · L2 · L4 · 30초 감시 · stsd 게이트(업로더가 돌아야
    선다).
*   **아직 없다**: 시청 경로(제어면 · CDN)는 M5 다. `ended` 전이 · ENDLIST 봉인
    호출 · 동결 · L3 · VOD 는 M6 다. 끊김과 의도적 종료의 구분(계약4 4D 종료
    신호 수신부)은 PR ⓒ 머지 뒤 별도 티켓이다(kty 결정).
*   **설정 · 스키마**: 새 env 1(`REWIND_PUBLIC_BASE_URL`) · DDL 0 · 장부 어휘 +1
    (`end_reason='offline'`) · 메인 루프 select case +1(되감기 완료 case) · 숫자
    상수 +1(`offlineRetryAfter` 30초) · 인터페이스 +1(`publish.Store`) · `go.mod`
    변화 0.
*   **비용 — 버킷이 있을 때**: 목록 PUT 이 스트림당 시간 약 900회 는다. 조각 축과
    합치면 약 2,700회다(② 900 · ③ 900 · 목록 900 — 계획 부기 3). DB 왕복은 상한이
    스트림당 시간 약 2,820회다(감시 120 · P0 900 · P4 900 · lazy 갱신 900 이하 —
    부기 41).
*   **위 M3 이관 표에서 「ⓒ」로 남겨 둔 항목**: 발행 게이트 · `rewind_cutoff_absent`
    알람 · writer fence 가 이 PR 에 들어왔다. `discontinuity_base` 증분은 「P0 에
    동봉」이 아니라 「마지막 발행본 기준 절대식 — base 는 P4 · 화해가 쓴다」로
    바뀌었다(부기 35). `indexer.go` 판정 묶음은 커밋 1 이
    `internal/indexer/judgment.go` 로 옮겼다(부기 4). tier 상수는 여전히 대기다.

### M4 ⓒ 가 넣은 것

| 자리 | 무엇 |
|---|---|
| `internal/rewind/publish`(신설) | 발행자 — `Store`(조건부 PUT · 메타 · Head 인터페이스, 운영 구현 `S3Store`) · 틱(P0–P4 · P2′ · RC-19) · 화해(R1–R4) · writer fence(획득 · lazy 갱신 · 반납) · 발행 억제 게이트 · GAP 원장 · 정체 사다리 · 30초 감시와 컷오프 부재 판정 · 신호 래퍼 · 차선 |
| `cmd/segment-indexer/rewind_assembly.go`(신설) | 발행 몫 조립 — 버킷이 있으면 발행용 S3 클라이언트 · https 검사(실효 엔드포인트) · 부팅 버킷 확인(HeadBucket) · 발행자 풀(인덱서 풀과 따로) · fence 토큰을 만든다. 실패하면 발행 몫만 강등한다 |
| `cmd/segment-indexer/rewind_loop.go` · `rewind_publish.go`(신설) | 루프 보조 타입 — 보류 틱마다 Dirty 반영 · 사다리 · 틱 · 요구 적재 · lazy 갱신 · 감시를 차선으로 내고 결과를 받아 캐시 · 발행 상태에 반영한다. 종료 전이를 실행한다 |
| `cmd/segment-indexer/main.go` | 조립을 업로더 기동 앞에 두고, 되감기 완료 case 를 더하고, 종료 순서(차선 취소 · 작업 대기 · fence 반납 → 발행자 풀 → 관측 폴러 → 업로더 → 인덱서 풀)를 defer 로 건다 |
| `internal/indexer/judgment.go`(신설) | 판정 묶음 이동(부기 4)과 종료 판정(`OfflineDue` — 관측 · 유입 정지 · 파일 점검 완주 · 재시도 간격) |
| `internal/indexer/collect.go` · `scan.go` | 파일 점검의 결손 기록(스트림 디렉터리 순회 오류 · 꼬리보다 늦은 미기록 파일) |
| `internal/index/store.go` · `rewind_read.go` | `EndLive`(스트림 단위 종료 문장) · 적재 읽기(되짚기 하한 · 행 상한 · 읽기 전용 REPEATABLE READ 한 트랜잭션 · 회차 최소 seq 열) · 30초 감시 한 문장 |
| `internal/session/registry.go` · `cmd/segment-indexer/session_wire.go` | 300초 안 재접속 계승 개시(직전 회차 조회 · 계승 개시 TD) |
| `internal/upload` | init 확정 CTE 의 stsd 게이트 입력 · Dirty 붙이기(`AttachDirty`) · L1 손잡이(`Expedite`) · 키 문법 한 벌 |
| `internal/playback` | `ParseSegKey`(③ 조각 키 문법) · `StsdFingerprint` |
| `internal/rewind/cache` | 적재 토큰 · 적재 중 push 로그와 재생 · 요구 적재 · 부정 표식 · 잘라내기(`Trim`) · 쉬는 뷰 버리기(`Forget`) · 감시 대조 · 화해 · init 확정 반영 |
| `internal/rewind/validate.go` | S2 의 DISC-SEQ 비감소 조항 · S5 의 조각 줄 조항(③ 키 문법 · 같은 스트림 · 길이 > 0) |
| `internal/config/config.go` | `REWIND_PUBLIC_BASE_URL` 적재 · 검증 · 스위치 기본값 `true` |
| `.github/workflows/media-ci.yml` · `docker-compose.override.yml.example` | 커버리지 게이트에 `rewind/publish` · 예시 사본에 베이스 URL 한 줄과 `S3_BUCKET` 의 `-` 치환 |

#### ⓑ 가 넘긴 열둘이 닫힌 자리

위 「M4 ⓑ 에서 ⓒ 로 넘긴 것」 표의 번호 그대로다.

| # | 넘긴 것 | 닫힌 자리 |
|---|---|---|
| 1 | 축출 증분 배선 · prev 계약 · 재기동 변형 | 절대식이다 — 다음 DISC-SEQ = P 의 DISC-SEQ + P 에서 빠지는 끊김 표시 수. base 는 P4 · 화해가 쓰는 파생값이고 P0 은 건드리지 않는다(부기 35 · `rewind.EvictedDiscontinuityTags` 문서) |
| 2 | 재기동 뒤 `rewind.Published` | 화해가 Head 의 메타 8키로 P 를 되살린다 — 본문 GET 은 하지 않는다(부기 35) |
| 3 | 유입 정지 판정의 입력 | 인덱서 커서 꼬리다(`indexer.OfflineDue`) |
| 4 | DISC-SEQ 단조 그물 · 끊김 표시 줄 검사 · 모르는 태그 검사 | S2 에 DISC-SEQ 비감소 조항을 넣었다(`disc_seq_decrease`). 나머지 둘은 넣지 않았다 — 목록 본문을 쓰는 코드는 여전히 `Render` · `SealEndlist` 둘뿐이다 |
| 5 | TD 초과 접두 · 꼬리 교정의 S5 정지 | 접두 길은 계승 개시 TD = max(6, 직전 회차 TD, round(첫 조각))로 닫았다(부기 33 · kty K1). 꼬리 교정 길은 알려진 한계다(kty K2 — 아래 한계 표) |
| 6 | 발행 모형과 BASE 계열 뮤턴트 | ending 회차는 발행을 멈춘다(부기 34). 두 뮤턴트를 죽이는 시험이 섰다(`TestValidateHeaderUsesOwnerBaseAfterPrefixEviction` · `TestValidateHeaderUsesOwnerBaseOnPrefixOnlyList`) |
| 7 | S4 계승 취소의 영속 | 첫 PUT 전 · live 에서만 P2′ 로 장부에 영속한다. 첫 PUT 뒤 S4 위반은 발행 중단이다(부기 36) |
| 8 | 소유 회차 줄의 절대 URI | `REWIND_PUBLIC_BASE_URL` 기동 검증과 S5 조각 줄 조항이 맡는다 |
| 9 | 빈 창 판정 | 세션 필터 뒤 행이 0 이면 틱이 P1 에서 끝나고, 루프는 빈 목록이면 틱을 내지 않는다 |
| 10 | 캐시 조립과 채우는 순서 | 조립이 캐시 하나를 인덱서와 루프에 끼운다. 적재는 워커가 읽고 루프가 토큰으로 반영하며, 적재 중 push 는 로그에 쌓였다가 재생된다(부기 37) |
| 11 | 캐시가 아직 모르는 사실 | TD 분할로 끝난 옛 회차는 개시 결과(`SeedResult.EndingSessionID`)로, init 확정 · 분리는 Dirty 로 받는다. settled 대조는 30초 감시 (a) 가 맡는다(부기 43) |
| 12 | 캐시 메모리 | 적재를 되짚기 2시간 · 1만 행으로 묶고, 발행이 성공하면 창 꼬리와 P.MSN 가운데 앞쪽 앞의 행을 잘라 낸다(`Cache.Trim`). live 회차가 없는 뷰는 재접속 창(300초)이 지나면 버린다(`Cache.Forget`) |

### M4 ⓒ 운영 — 팀 1회 조치 · 되돌리기 · 튜너블 · 로그 · 실측

#### ⓒ 팀 1회 조치 — 재빌드와 override 사본 두 줄

pull 한 뒤 사이드카를 다시 빌드한다. 코드가 바뀌어 옛 이미지로는 ⓒ 가 돌지
않는다. **버킷을 쓰는 사람은 빌드보다 override 사본 수정이 먼저다** — 고치기 전에
새 이미지로 올리면 기동을 거부한다(아래).

**버킷을 쓰는 사람(1번)**: ① override 사본을 고치고 자격증명을 내보낸 뒤 ② 다시
빌드한다. 기본 compose 는 사이드카에 `S3_BUCKET` 을 넘기지 않아 발행 몫이
꺼진다(기동 로그 `rewind_publish_disabled` 한 줄). 버킷을 쓰는 사람은 각자의
`docker-compose.override.yml`(gitignore 된 사본)을 예시 파일과 맞춘다.

*   `REWIND_PUBLIC_BASE_URL` 한 줄을 더한다(예시 파일 29행). **없으면 `S3_BUCKET`
    이 있을 때 사이드카가 기동을 거부한다** — 업로드 · 인덱싱도 함께 멈춘다(종료
    코드 1). 빠뜨렸으면 `docker compose logs segment-indexer` 에
    `segment-indexer 종료: REWIND_PUBLIC_BASE_URL 이 비었다 — …` 가 찍힌다.
*   `S3_BUCKET` 줄의 `${S3_BUCKET:-…}` 를 `${S3_BUCKET-…}` 로 바꾼다. 그래야 명령
    앞에 붙인 `S3_BUCKET= docker compose …` 의 빈 값이 그대로 가서 업로더 · 발행이
    꺼진다. `:-` 는 빈 값도 실버킷으로 채운다(긴급 정지는 아래 절).

예시 파일의 두 줄은 아래와 같다(뒤붙임 주석은 뺐다).

```yaml
S3_BUCKET:                  ${S3_BUCKET-pokeclip-media-demo-2557}
REWIND_PUBLIC_BASE_URL:     ${REWIND_PUBLIC_BASE_URL-http://127.0.0.1:8090}
```

고친 뒤 자격증명을 내보내고 사이드카를 다시 만든다. 켜짐 줄의 `base_url` 이
루프백 기본값이면 로컬 실측 전용이다 — 운영에 실리면 시청자 브라우저가 자기
컴퓨터를 부른다. macOS 기본 셸 zsh 에 이 README 의 bash 블록을 붙여 넣을 때 줄 끝
`#` 주석이 명령 인자로 넘어가면 `setopt interactivecomments` 를 먼저 친다.

```bash
eval "$(aws configure export-credentials --format env)"
docker compose up -d --build --force-recreate segment-indexer
docker compose logs segment-indexer | grep -E 'rewind_publish_(started|degraded)'
```

**버킷을 쓰지 않는 사람(2 · 3번)**: override 없이 다시 빌드만 한다.

```bash
docker compose up -d --build segment-indexer
```

#### ⓒ 되돌리기와 긴급 정지

*   **되돌리기는 머지의 역순(ⓒ → ⓑ → ⓐ)으로만 한다**(위 ⓐ 절과 같다). ⓒ 만
    되돌리면 머지 커밋을 되돌린 뒤 사이드카를 다시 빌드해 반영한다.

    ```bash
    git revert -m 1 <ⓒ 머지 커밋>
    docker compose build segment-indexer && docker compose up -d segment-indexer
    ```

*   **되돌려도 남는 것**:
    *   장부 — 회차 행의 발행 열(`manifest_gen` · `manifest_etag` ·
        `published_seq` · `discontinuity_base`) · `writer_fence` · 계승 행
        (`inherits_session`) · `ending(offline)` 으로 끝난 회차 · GAP 원장 행 ·
        기본 켜짐 동안 주조된 컷오프.
    *   S3 — 목록 객체 `dvr/{stream}/{session}/index.m3u8`. **`dvr/` 객체를 지우는
        코드는 없다.**
*   **되돌린 뒤의 동작**: ⓑ 코드는 발행 열을 읽지 않는다. `ending(offline)` 회차는
    `ending` 그대로 남는다(ⓑ 코드에는 종료 전이도 `ended` 도 없다). 이미 주조된
    컷오프는 유효해 그 스트림의 ③ 재수거는 계속 돈다. ⓒ 가 쓴 계승 행이 남은
    회차는 되돌린 코드가 첫 init 을 stsd 게이트 없이 확정한다(추론 · 미실측).
*   **발행만 끄는 손잡이는 없다.** 발행 몫은 `S3_BUCKET` 하나에 묶인다 — 비우면
    업로더와 발행이 함께 꺼지고 **② 클립 소재 업로드(3번 소비자)도 멈춘다**. 종료
    전이는 버킷과 무관하게 계속 돈다. 끌 때는 override 를 빼고 사이드카를 다시
    만든다. 기본 compose 는 `S3_BUCKET` 을 넘기지 않으므로 사본이 새것이든
    옛것(`:-`)이든 통한다. 확인 줄 둘이 각각 1 이어야 꺼진 것이다.

    ```bash
    docker compose -f docker-compose.yml up -d --force-recreate segment-indexer
    docker compose -f docker-compose.yml logs segment-indexer | grep -c uploader_disabled   # 1
    docker compose -f docker-compose.yml logs segment-indexer | grep -c rewind_publish_disabled   # 1
    ```

    *   셸에서 `S3_BUCKET=` 를 따로 한 줄로 치고 `docker compose up` 을 하면
        꺼지지 않는다. 내보내지(export) 않은 변수는 compose 에 닿지 않아
        override 가 실버킷으로 채운다. 빈 값은 명령 앞에 붙여야 닿고
        (`S3_BUCKET= docker compose up -d --force-recreate segment-indexer`), 그
        꼴은 새 예시 사본(`${S3_BUCKET-…}`)에서만 통한다.
    *   `-f` 없이 다시 `docker compose up` 하면 override 가 다시 실려 켜진다. 계속
        꺼 두려면 override 의 그 줄을 `S3_BUCKET: ""` 리터럴로 고친다.
*   **`REWIND_SEED_ENABLED=false` 는 새 컷오프 주조만 멈춘다.** 이미 주조된
    스트림은 계속 발행된다 — 발행 경로는 이 플래그를 읽지 않는다(설계 c2 ·
    `TestC2NoFlagRead` 가 정적으로 지킨다). 버킷이 있으면 끈 동안 최근 10분 안에
    조각이 있고 컷오프가 없는 스트림에 `rewind_cutoff_absent` WARN 이 뜨고 40번째
    감시(20분)에 ERROR 로 오른다. 감시가 플래그를 읽지 않아서이고 정상이다.
*   **베이스 URL 은 live 목록이 있는 동안 바꾸지 않는다**(운영 규칙). 바꾸면 다음
    판의 모든 조각 · MAP 줄 URI 가 바뀌는데, 발행된 목록 줄은 바꿀 수 없다
    (RFC 8216bis-22 6.2.1).

#### ⓒ 튜너블과 고정값

새 env 는 하나다(`REWIND_PUBLIC_BASE_URL` — 위 env 표). 나머지는 코드에 있다.

| 이름 | 값 | 자리 | 뜻 |
|---|---|---|---|
| `ReconnectWindow` | 300초 | `config.Load` | 재접속 계승 창 · 쉬는 뷰를 버리는 창(ADR-044 결정 3) |
| `RewindWatchEvery` | 30초 | `config.Load` | 30초 감시 주기 · 요구 적재의 반복 제한 간격 |
| `HoldTick` | 1초 | `indexer.Options` 기본값 | 보류 틱 — 발행 배선 · 사다리 · 종료 판정의 주기 |
| `offlineRetryAfter` | 30초 | `internal/indexer/judgment.go` 상수 | 종료 전이 실패 뒤 쉼 · 결손 재점검 간격 |
| `Lease` · `LazyRenewAfter` · `PublishTimeout` | 5초 · 3.5초 · 2초 | `publish.DefaultOptions` | fence lease · lazy 갱신 간격 · PUT 한 번의 예산(T_pub) |
| `E2EBudget` · `ExpediteAfter` · `GapHold` | 2초 · 0.5초 · 2초 | 같은 곳 | 조각이 목록에 실릴 예산 · L1 문턱 · L2 문턱 |
| `RefreshObligation` · `EdgeDelay` | 9초 · 1.5초 | 같은 곳 | 목록 갱신 의무(1.5 × TD 6) · 엣지 지연 — L4 는 정체 7.5초에 선다 |
| `SeedAlarmAfter` · `SeedAlarmErrorTicks` | 10분 · 40 | 같은 곳 | 컷오프 부재 후보 창(조립이 `SEGMENT_RESCAN_EVERY` 의 두 배 이상으로 올린다) · ERROR 로 오르는 연속 감시 수 |
| `LedgerLookback` · `LedgerRowCap` · `LoadPushBuffer` | 2시간 · 10,000 · 1,024 | `cache.Options` 기본값(조립은 영값을 넘긴다) | 적재 되짚기 · 적재 행 상한 · 적재 중 push 로그 상한(넘으면 적재를 다시 연다) |
| `clipErr` 상한 | 1,024바이트 | `internal/rewind/publish/tick.go` | 로그 `err` 속성에 남기는 접두 길이 — 넘으면 접두 뒤에 잘림 표식(원래 길이)이 붙는다 |
| `MaxBodyBytes` | 512KiB | `internal/rewind/validate.go` | 목록 본문 상한 — `S3Store.Get` 이 같은 상한까지만 읽으려고 ⓑ 의 비공개 상수를 공개로 바꿨다 |

#### ⓒ 로그 변화

새 이름은 열셋이다. 셈 단위가 아래 「설계와 다르게 한 것」의 「로그 키 ⓒ 8」과
다르다 — 열셋은 계획 로그 키 ⓒ 8 에 설계가 이름 지은 신호 넷(기정 신호 둘 · 감시
신호 둘)과 ⓐ 가 센 `session_inherit_declined` 하나를 더한 수다. 메트릭 기반이
없어 설계가 메트릭으로 이름 지은 신호
(`rewind_publish_stall_seconds` · `rewind_gap_published_total` ·
`cache_drift_repaired` · `rewind_cutoff_absent`)도 로그 키로 남긴다. 발행 층 줄
(`rewind_publish_aborted` · `rewind_ledger_load_degraded` ·
`rewind_job_failed`)의 `err` 속성은 앞 1,024바이트만 남기고 잘림 표식(원래 길이)을
붙인다.

| 로그 | 언제 | 수준 | 속성 |
|---|---|---|---|
| `rewind_publish_disabled` | 기동 때 버킷이 비어 발행 몫을 조립하지 않음 — 종료 전이는 돈다 | INFO | note |
| `rewind_publish_started` | 발행 몫 조립 성공 — 기동 한 번 | INFO | bucket · region · endpoint(userinfo 를 뗀 실효 엔드포인트, 빈 값 = AWS 기본) · provider(자격증명 출처 이름) · base_url · writer(이 프로세스의 fence 토큰 — 장부 `writer_fence` 와 견준다) |
| `rewind_publish_degraded` | 조립 실패(AWS 설정 · https 위반 · 자격증명 · HeadBucket · 발행자 풀 · 발행자 생성) — 기동 한 번. 발행 몫만 꺼지고 회복은 재기동이다 | ERROR | bucket · region · endpoint · err · note |
| `rewind_fence_release_failed` | 종료 때 fence 반납 실패 — 종료 한 번. 후임 writer 는 lease 가 끝난 뒤 이어 쓴다 | ERROR | err · note |
| `rewind_publish_aborted` | 틱의 중단 · 포기 — 사유 19가지(`reason`). 같은 사유 · stage 가 이어지면 한 줄이다 | 틱만 포기하는 13 사유는 WARN. 발행이 서는 6 사유(`meta_invalid` · `meta_out_of_range` · `s4_after_publish` · `validate` · `disc_seq_decrease` · `head_failed`)는 ERROR | stream · session · reason(· stage · check · err). **`reason=gap_tx_failed` 줄에는 seq 가 없다** — GAP 트랜잭션 실패를 추적할 출처는 GAP 원장의 `recorded` 행이다 |
| `rewind_gap_published_total` | GAP 원장에 GAP 이 새로 섬 — 트랜잭션 커밋 뒤 한 줄 | WARN | stream · session · seq · reason(`upload_stall`). **`session` 은 그 틱의 소유 회차다** — 원장 행의 회차(계승 접두면 직전 회차)와 다를 수 있고, 두 기록을 잇는 키는 (stream, seq) 다 |
| `rewind_publish_stall_seconds` | L4 — 발행 이력이 있는 live 소유 회차의 정체가 7.5초에 닿았는데 L2 가 서지 못함(정체 한 번에 한 줄). C3 — 소유 회차가 live 가 아니게 되어 발행을 멈춤(회차마다 한 번) | L4 는 ERROR, 다만 `gap=no_row` 면 WARN(kty 결정 ④ A). C3 는 WARN | L4: stream · session · seconds · gap(`no_row` · `incomplete` · `before_cutoff` · `holding` · `unstood`) · last_abort / C3: stream · session · state · seconds |
| `cache_drift_repaired` | 드리프트 까닭 적재가 적용됨(`kind=head`) · 잃은 init 결과를 감시가 되살림(`kind=init_uploaded`) | WARN | head: stream · cutoff · floor / init_uploaded: stream · session · revoked |
| `rewind_cutoff_absent` | 30초 감시 (c) — 최근 10분 안에 조각이 있는데 컷오프가 없는 스트림. 전이만 남긴다 | 들어올 때 WARN(value 1) · 연속 40번째 감시에 ERROR(value 1) · 사라질 때 WARN(value 0 — 들어올 때와 같은 등급이라 `LOG_LEVEL=warn` 으로 걸러도 해소가 보인다) | stream · ticks · last_wall · value |
| `rewind_ledger_load_degraded` | 적재 실패(`load_failed`) · 행 상한 도달(`row_cap`) · 다시 뽑은 P 의 행이 P 의 자기기술과 다름(`prev_mismatch`) | ERROR | load_failed: stream · err / row_cap: stream · floor · rows / prev_mismatch: stream · hint · mismatch |
| `rewind_job_failed` | 차선 작업이 중단 · 포기 표 밖의 실패로 끝남 — 발행 작업은 같은 스트림의 이어지는 같은 오류에 한 줄, 감시 작업은 감시마다 한 줄 | ERROR | publish: job · stream · session · err / watch: job · first(부팅 첫 감시인가) · err |
| `session_offline` | 종료 판정이 점검 수집을 기다림(`result=awaiting_check` — 기다림 한 번에 한 줄) · 전이 실패(`result=failed` — 결과 모름, 30초 쉼) | INFO · ERROR | awaiting_check: stream_id · seq · wait_started_at / failed: stream_id · err(**꼬리 seq 없음**) |
| `session_inherit_declined` | 워커 — 계승 후보의 stsd 를 견줄 수 없어 비호환으로 판정(시도마다). 루프 — CAS 가 실제로 계승을 풂(회차마다 많아야 한 번). 지문이 달라 푸는 갈래는 워커 줄이 없고 루프 줄만 있다 | WARN | 워커: session_id · inherits_session · reason · prev_path(· err) / 루프: stream_id · session_id · confirmed |

*   **종료 전이 성공은 로그 줄이 없다.** 장부 `stream_sessions` 의
    `state='ending'` · `end_reason='offline'` 으로 확인한다.
*   **`gap=no_row` 는 까닭을 가리지 않는다.** 머리 다음 행이 캐시에 없으면 방송
    종료 구간 · 슬레이트 구간(녹화가 멈춘 끊김 — F-33 ⑴) · 녹화가 멈춘 이상이 모두
    WARN 이다. ERROR 는 다음 행이 있는데 정착하지 못할 때만 선다.
*   **재기동 ② 뒤의 경보**는 뒤 회차가 계승 회차인가로 갈린다 — 아래 한계 표.

#### ⓒ 실측 기록

*   **(c) 실 S3 발행**(2026-10-17 02:10–02:13Z · 데모 버킷 · 승인된 두 접두 ·
    끝나고 삭제): 켜짐 줄의 writer 가 장부 `writer_fence` 와 같았다. 4–5초 간격
    표본 일곱 번 모두 ETag 가 바뀌고 `pc-gen` · `manifest_gen` · `published_seq` 가 늘었다.
    평시 `rewind_publish_aborted` · `rewind_cutoff_absent` 는 0 이다. 사이드카를
    재기동해도 같은 URL 에 이어 발행했다(`pc-gen` 단조 · `pc-msn` 비역행 · 새
    writer). 송출이 끝나자 회차가 `ending(offline)` 이 되고 ETag 가 고정됐으며 WARN
    두 줄(L4 `gap=no_row` · C3 `state=ending`)이 났다.
*   **M1 compose 전건**(2026-10-17 09:05–09:18Z · 전용 compose 프로젝트 · 실 S3):
    송출 끝에서 `ending` 까지 13.7초였다. 120초 뒤 재송출은 계승했다(직전 회차
    조각 접두 · `EXT-X-DISCONTINUITY` · 새 MAP · DISC-SEQ 0). 400초 뒤 재송출은
    계승하지 않았다. 슬레이트 없는 대조군 스트림도 같은 전이를 거쳤다.
    `invalid_duration` 0 · ERROR 0 이다.
*   **M2 Content-Type**: 목록은 `application/octet-stream` 이다 — `S3Store.Put` 이
    값을 싣지 않아 SDK 기본값이 붙는다. ③ · init 은 `video/iso.segment` 다 — 업로더
    하드코딩이라 init `.mp4` 에도 붙는다(POK-28 이월). 값을 정할지는 M5 회부다(계획
    7절 13).
*   **M3 hls.js 실재생**(1.6.0 · 1.7.3 × 회차 셋): fatal 0 · seek 세 점 모두 전진.
    계승 회차는 끊김 경계(cc 0 → 1)를 넘어 재생했고, A/V 위상 0.023초가 원본과 같다.
*   **M4 5초 끊김 · 회차 안 끊김 경계**(2026-10-19 03:36–03:38Z · 전용 compose
    프로젝트 · 실 S3 · 슬레이트 ON · OFF 두 경로 동시): 송출 40초 → 5초 끊김 → 같은
    키로 40초. 두 경로 모두 **회차 하나**에 조각 20 이 귀속됐다(5초는 종료 판정 지연
    13.7–17초보다 짧다). 목록에 `EXT-X-DISCONTINUITY` 0 · 끊김은 PDT 가 약 5초 뛰는
    것으로만 보인다 · 경계 양쪽 조각의 첫 tfdt 차는 EXTINF 와 0.114초 안에서 이어진다
    (도장 연속). hls.js 1.6.0 · 1.7.3 × 목록 둘 × 3회 = 12 실행에서 경계 직전 seek
    두 점 24/24 가 경계를 넘어 전진했고 fatal 0 이다. duration 은 1.6.0 이 6회 가운데
    5회 EXTINF 합과 같고 1회는 79.70초(−0.98초)다. 1.7.3 은 EXTINF 합보다 0.74초에서
    1.04초 짧다. 비치명 `bufferSeekOverHole` 은 12 실행 가운데 5회(1.7.3 4/6 · 1.6.0
    1/6) 났다(fatal 은 위대로 0). 목록이 `EXT-X-ENDLIST` 없는 라이브
    형이라(동결은 M6) 두 판 모두 라이브 에지에서 시작하고 1.6.0 은 머리 seek 가
    에지로 되돌아간 실행이 2/6 있다 — 끊김 경계와 무관한 라이브 동기화 동작이다.
*   **기록 자리**: kty 실험 루트 `pokeclip-empirical/runs/2026-10-17-c8m1/record.md` ·
    `runs/2026-10-19-c10k4/record.md`(저장소 밖) · (c) 원문은 작업 산출물
    `c7c_c_run/`(저장소 밖 — 볼트 raw 이관 대상).

### M4 ⓒ 설계와 다르게 한 것

계획 부기(「이 계획이 설계와 다르게 적은 것」 표) 가운데 위 ⓐ 절(27–30)과 ⓑ 절(15 ·
16 · 25 · 29 · 32)이 싣지 않은 행 전부다. 31 은 결번이다(kty 결정 (나)로 편차가
사라졌다). 칸은 계획 행을 README 독자의 말로 줄여 옮겼다.

| 부기 | 자리 | 설계 문언 | 구현 | 성격 |
|---|---|---|---|---|
| 1 | ADR-057 「정규화 선행」 | 원본 파일 제자리 재기입으로 읽힌다 | 정규화는 ③ 바이트 안의 시각 축이다(설계 r17 5.1). Go 재포장이라 「추출 뒤 재기입」 단계가 없고 도장은 재포장 때 더한다(부기 28) | 문언 정정 후보 — 설계 이탈 아님 |
| 2 | 설계 3.3절 그림 | 정규화 → `duration_ms` 확정(INSERT 앞) | `duration_ms` 원천은 프로브이고 정규화는 ③ 축(INSERT 뒤)이다 | 그림 정정 후보 |
| 3 | 설계 4.0절 표 | PUT 시간당 1,800회 | 조각 축만 센 값이다 — 목록 900 을 더해 총 2,700 | 산출 명시 |
| 4 | 위 M3 이관 표의 부채 행 | 판정 묶음 이동 5개 | 같은 커밋(커밋 1)에서 옮겼고 이동은 6개다(`seedChannel` 추가) | 집합 편차 기록 |
| 5 | clarifier 기록 | 스위치를 켜면 PUT 1,800/h · 저장 1.7배 | 그 증가는 ⓐ 배포 시점부터다(③ 추출은 플래그와 무관) | 축 정정 |
| 6 | 설계 4.7절 · F-33 | 슬레이트 조각을 GAP 으로 싣는다 | 슬레이트는 녹화되지 않는다(ADR-050). 목록은 경계 표시 · 시각 점프만 남기고 라이브 전환은 제어면(M5) 몫이다 — M4 코드 0 | kty 확정(2026-09-04) · 설계 4.7절 갱신 후보 |
| 7 | 설계 9절 G8 | P · F · E 세 프로토콜 | M4 = G8-P 완결 · F/E 는 M6 | 계상 분할 |
| 8 | 설계 4.0절 · ADR-057 정규화 산식 | 원점만 정의 | 트랙별 timescale · 위상 보존 · 재접속 연속 산식을 명시했다(위 ⓐ 절 「시간 도장」) | 구체화 |
| 9 | 설계 4.6.3절 · 4.6.5절 사다리 | L1 · L2 · L4 는 세션 상태와 무관하게 서고, 1단계는 L4 경보만 남긴다 | live 소유 회차에서만 돈다. live 가 아니게 되면 회차마다 WARN 한 번(C3). L4 의 「L2 가 서지 못함」은 루프가 싣는 사실(`LadderInput.UnstoodGapSeq`)로 받는다. `gap=no_row` 면 L4 를 WARN 으로 낮춘다 | 이탈 기록(M6 전 ENDLIST · 동결 부재) · 편차(kty 결정 ④ A — 방송 종료 구간 L4 등급) |
| 10 | 설계 4.6.3절 L3 · 4.5.3절 phase 2 만료 | 연속 GAP 셋 이상 → ending · 정산 창 만료 = 포기 등재 | M6(종결 축) | 이연 |
| 11 | M3 계획 · `upload.go` 주석 | `Result` 에 축 필드 = 영값 함정으로 기각 | 축 필드는 넣지 않고 ③ · init 통지는 `upload.Dirty` 가 맡는다. 주석의 「영값 = AxisArchive」 근거 문장은 사실과 달라(영값 = unknown) 한 줄 고쳤다 | 주석 사실 정정 |
| 12 | 설계 5.3절 인용 | init 규격을 bis-16 으로 인용 | bis-22 로 갱신할 후보다(인용 네 문장은 그대로 남아 있다) | 인용 갱신 후보 |
| 13 | 설계 5.4.1절 종료 판정 입력 | 「항목 부재」 중심 | `!Publishing`(항목 부재 또는 항목이 있되 online 거짓) — 슬레이트 ON 형상을 포함한다 | 구체화 · 설계 갱신 후보 |
| 14 | 설계 5.3절 ⓑ init 동일성 | 산출 init 바이트 동등성(`ftyp+moov` sha256) | 원문 그대로다. 판정 · 영속은 워커 트랜잭션(`MarkPlaybackFailed(init_mismatch)`)이고 대가는 불일치 조각 하나의 GAP(4초)이다 | 편차 없음(기록) |
| 17 | 설계 5.4.1절 · 계약9 | 종료 판정의 시간 항 미정 | 유입 정지 = 마지막 조각 실제 길이의 세 배 · 관측 = `!Publishing` · `OBS_FRESH`. 전이 앞에 파일 점검을 더한다 — 기다림 시작 + `SEGMENT_IDLE_TIMEOUT` 뒤에 시작한 완주 수집 · 결손 없음 · FS 래치 정상 | 값 확정(kty) · 설계 갱신 후보 |
| 18 | 설계 3.3절 결과 통지 | 결과 채널 | ③ · init 은 이벤트 집합(`upload.Dirty` — Peek/Ack)이다 | 형상 대체(유실 · 워커 정지 · 조회 0 을 함께 만족) |
| 19 | ADR-044 | 계승 창 기산점 「세션 종료 후」 · 호환 게이트 = `stsd` 지문 | 기산점 = 직전 회차 마지막 조각 끝(kty 확정) · 게이트는 `stsd` 지문 원문 그대로 · 판정 자리는 새 회차 첫 init CAS 의 CTE(원자 해제) | 기산점만 ADR-044 문언 정정 후보 |
| 20 | 설계 5.3절 ⓒ | 미등재 → ending + phase 1 → OpenFresh | 워커 트랜잭션 하나로 ③ `failed` 와 `ending(init_mismatch)` 를 영속하고, 새 회차는 다음 유입이 연다(비계승) | 실행 형상 구체화 |
| 21 | 제0원칙 4 · `index → session` 임포트 금지 | 세션 상태 전이 SQL 은 session 소유 | 종료 문장 셋을 index 가 소유한다(init 불일치 둘 · `EndLive`). 같은 PG 시험이 세 문장의 술어(`state='live'`)와 사유 어휘를 대조한다 | 중복 기록 |
| 22 | 설계 3.2절 패키지 경계 | session = `stream_sessions` 정책 | 재접속 계승 판정을 위해 session 이 `stream_segments` 를 한 곳 읽는다(직전 회차 마지막 조각의 끝 시각과 첫 조각의 경로 — 코덱 지문용) — 쓰기 0 | 경계 접촉 기록 |
| 23 | 제0원칙 2 | — | `MarkPlaybackFailed(reason=init_mismatch)` 가 조각 상태와 세션 전이를 겸한다(이름 부채) — 개명은 M5 | 이름 부채 기록 |
| 24 | 설계 5.3절 ⓓ | 직전 회차 init 미업로드 → 계승 취소(렌더마다 평가) | 계승 취소는 첫 PUT 전 · live 에서만 하고 장부에 영속하며 되살리지 않는다(P2′). 첫 PUT 뒤 S4 위반은 발행 중단이다. 해제 자리는 init CAS CTE(`stsd` 비호환)와 P2′(S4 위반) 둘이다 | 편차 있음 — 되살리면 이미 나간 목록 앞에 접두를 넣어 MSN 이 역행한다(S2 위반). 첫 PUT 뒤 취소는 남은 조각의 표시를 지운다(RFC 8216bis-22 6.2.1 밖) |
| 26 | 제0원칙 3 | — | `inherits_session` 쓰기자 셋(개시 · init CAS CTE 의 `stsd` 해제 · P2′) · `discontinuity_base` 쓰기자 다섯(같은 셋 · P4 · 화해 — 파생값). CTE · P2′ 는 첫 PUT 전에만 지우는 방향으로 쓰고, P4 · 화해는 fence 를 쥔 발행자만 쓴다 | 경계 기록 |
| 33 | 설계 4.9.1절 개시 TD | 새 회차 TD = max(6, round(첫 조각)) | 재접속 계승 개시는 max(6, 직전 회차 TD, round(첫 조각)) | 편차 있음(kty K1 2026-09-25) |
| 34 | 설계 4.5.1절 · 4.5.3절 phase 1 | 종료 개시 = 같은 문서에 ENDLIST(동결) | ending 회차는 발행을 멈춘다(P0 의 `state='live'`). ENDLIST · 동결은 M6 | 이연 — M4 에 시청 경로가 없다는 전제의 한시 편차 |
| 35 | 설계 4.4.2절 메타 · 4.4.3절 P0 · P3 · P4 · R3 · 4.4.5절 RC-19 | 메타 4키 · If-Match 로 낡은 PUT 차단 · P0 은 base 무접촉 · 남는 창은 T_pub 안 · 갱신 412 → 화해 뒤 한 번 재시도 | 메타 8키 · DISC-SEQ = 마지막 발행본 기준 절대식 · base 는 P4 · 화해가 쓰는 파생값 · 같은 본문 재PUT 금지 · If-Match 출처(P0 의 `manifest_etag`)를 루프의 P.ETag 와 대조 · P3 마감 = P0 송신 시각 + lease − T_pub · 갱신 412 는 화해 뒤 재시도 없이 틱 포기 · If-Match 409 는 Head 로 ETag 가 그대로일 때만 한 번 · R4 「최초 생성 경로」 = 장부 `manifest_etag := NULL` | 구체화 + 설계 결손 보완(ETag 가 메타를 반영하지 않아 같은 본문 판의 좀비 쓰기가 통과한다) + 편차(RC-19 의 한 번 재시도를 뺐다 — 대가는 한 틱) |
| 36 | 설계 4.5.5절 S4 처치 · 5.3절 ⓓ | S4 위반 = 계승 취소(렌더마다) | 계승 취소는 첫 PUT 전 · live 에서만(P2′) · 그 뒤 S4 위반 = 발행 중단 · S2 에 DISC-SEQ 비감소 · `stsd` 해제는 init 확정과 한 push · P0 에 init 하드 가드 · 잃은 init 결과는 30초 감시가 화해 | 편차 있음 — 첫 PUT 뒤 취소는 남은 조각의 표시를 지운다(RFC 8216bis-22 6.2.1 밖) |
| 37 | 설계 4.1절 부팅 재구성 | `seq ≥ cutoff` 전량 | 하한 = 행 상한 안의 되짚기(2시간 · 1만 행) · 읽기 전용 REPEATABLE READ 한 트랜잭션 · 회차 최소 seq 열 · 적재 토큰 · 부정 표식 · 잘라내기와 쉬는 뷰 버리기 · 적재 중 push 재생 · 적재 중 스트림은 드리프트 감시에서 뺀다 | 편차 있음(보안 L3 — 메모리 · 시간 상한) |
| 38 | 설계 3.1절 ② 교차 방송 오귀속 금지 · 5.4.1절 · 6.2절 세션 수명 · ADR-062 결정 3 | 오귀속 금지 · ending 중 도착 = 새 세션 | 종료 전이 앞에 파일 점검을 한 번 끝까지 돌린다. 방증 없는 스캔 조각이 ending 중에 오면 새 회차를 열지 못하고 비귀속이다. 재기동을 낀 오프라인은 다루지 않는다 | kty 철회 — 알려진 한계(아래 「ⓒ 한계 — 회차 경계와 재기동」) |
| 39 | 설계 9.1절 G8 | 낡은 writer 가 최신 계보를 덮지 못한다 | G8-P 판정 = 조건부 쓰기로 증명되는 넷 — ① 어떤 writer 의 PUT 도 저장된 판 본문이 자기 P 와 같을 때만 성공 ② 저장된 판은 늘 직전 판의 검사된 후속 ③ DB 세대 단조 ④ 메모리 P 가 낡으면 다음 P3 가 412 → 화해로 맞춘다. S3 메타 `pc-gen` 은 늦은 좀비 쓰기에서 DB 세대보다 뒤처질 수 있어 판정에 쓰지 않는다 | 편차 있음(기준 명확화 — kty 확정 2026-09-26) |
| 40 | 설계 3.2절 `session/` 행 | 「세션 수명과 fence」 | fence 코드는 `rewind/publish/fence.go` 에 둔다. 세션 수명은 `session` · `index` 그대로다 | 편차 있음(자리) |
| 41 | 설계 4.4.3절 P0 · 6.2절 fence lazy 행 · 0.3절 비용 표 · ADR-061 | P0 에 남은 lease 2초 조건 · 평시 lazy 발화 0 · DB 왕복 약 1,920회/시간/스트림 | P0 조건 = `fence_expires_at > now()`(남은 lease 조건 삭제) · lazy 3.5초 규칙은 그대로이고 평시 틱마다 최대 한 번 돈다 · 왕복 상한 약 2,820회/시간/스트림 | 편차 있음(kty 확정) — P0 이 같은 문장에서 lease 를 새로 쓰므로 남은 시간은 지키는 것이 없다. 늦은 PUT 창은 P3 마감이, 정확성은 조건부 쓰기가 진다 |
| 42 | 설계 4.4.3절 P0 RETURNING · P4 · 4.6.2절 stallAge | RETURNING 넷 · P4 가 쓰는 열 미기재 · stallAge = now − `published_at` | P0 RETURNING 은 `manifest_gen` · `manifest_etag` 둘이다. P4 는 `manifest_etag` · `discontinuity_base` · `published_seq` 를 쓰고 `published_at` 은 쓰지 않는다. stallAge 원천은 루프가 드는 두 시각(마지막 발행 성공 · 처음 소유 회차로 본 시각) 가운데 늦은 쪽이고, 발행 이력이 없으면 L4 · C3 가 서지 않는다 | 편차 있음(축소 · 구체화) — 동작 무영향 |
| 43 | 설계 4.1절 정합성 감시 (a) | DB 직조회 settled 접두 ↔ 캐시 `headSeq` | 캐시 머리 다음 행 하나만 DB 에서 settled 인지 보고, 같은 문장이 DB 컷오프를 실어 캐시 컷오프와 견준다. 두 감시 연속 불일치면 요구 적재이고 WARN 은 그 적재가 성공한 뒤다 | 편차 있음(좁힘) — 뷰는 적재 하한 앞 행을 몰라 접두 전체 대조는 감시마다 오보한다 |

개발 중 계획 문언과 다르게 한 세부 가운데 동작 · 관측에 닿는 것만 적는다. 시험
파일 자리 · 문서 정정 같은 세부는 커밋별 개발 보고에 있다.

*   종료 전이 실패 줄(`session_offline result=failed`)에 꼬리 seq 가 없다. 루프가
    꼬리 seq 를 읽을 공개 통로가 없어 뺐다.
*   `REWIND_PUBLIC_BASE_URL` 은 앞뒤 공백을 떼지 않는다(다른 env 와 다르다). 떼면
    끝의 CR · U+2028 을 거부하는 규칙이 무력해진다.
*   seq 가 int64 를 넘는 ③ 키는 워커 거부 사유가 `seq_mismatch` 에서 `bad_key` 로
    바뀐다. 받는 키 집합은 그대로다.
*   직전 발행 틱의 실패 표지는 fence 를 쥐었을 때만 화해 예약을 실패로 센다.
    fence 를 잃은 뒤 1초마다 재발행하지 않게 하려는 것이다.
*   빈 목록이면 루프가 틱을 내지 않는다. 병합 경로는 차선이 돌 때도 입력을 만들어
    보고 작업을 버린다.
*   fence 토큰을 버킷 확인보다 먼저 만든다. 버킷 확인 시한이 발행자 설정
    (`PublishTimeout`)에서 오기 때문이다.
*   로그 키가 계획 셈보다 다섯 늘었다 — `rewind_job_failed` 하나(커밋 7 의 둘째
    묶음)와 `rewind_publish_disabled` · `rewind_publish_started` ·
    `rewind_publish_degraded` · `rewind_fence_release_failed` 넷(셋째 묶음). 계획의
    「로그 키 5(ⓐ 2 · ⓒ 3)」는 10(ⓐ 2 · ⓒ 8)이 된다.
*   공개 표면이 계획보다 넓다(kty 2026-10-19 사후 승인). 커밋 5 의 감시 · 적재가
    캐시에 메서드 셋(`DemandLoad` · `DriftProbes` · `AuditDrift`), index 에 공개 타입
    일곱(`LedgerBounds` · `InitRepair` · 감시 타입 다섯 — 입력 1(`WatchProbe`) · 결과
    4), `Cache.Options` 칸 하나를 더했고 `CompleteLoad` 는 `(applied, drift)` 둘을,
    `ShouldTick` 은 `loading` 인자를 받는다. `cache.Session.MinSeq` 칸은 뺐다(같은
    값을 `index.RewindSession.MinSeq` 가 든다). 공개 필드 8 → 16 · 공개 메서드
    14 → 21 은 PR 전체 총계다 — 사전 승인 다섯(`Owner` · `Streams` · `IngestTail` ·
    `Expedite` · `AttachDirty`)을 포함한다. 전부 `media/internal/` 안이라 부르는 곳은
    사이드카 조립뿐이다.
*   30초 감시는 계획과 두 군데 다르다(kty 2026-10-19 승인). 드리프트 수리 요구는
    같은 스트림이 **연속 두 감시**에서 어긋날 때만 낸다 — 적재와 감시가 엇갈리는
    순간의 거짓 드리프트로 적재가 적재를 부르지 않게 하려는 것이다(수리는 최대
    30초 늦는다). 조회 셋은 재귀 CTE 한 문장이라 왕복은 1이지만 작업량이 스트림 ID
    가짓수에 비례한다(아래 「발행과 경보」 한계 표).
*   재접속으로 새 회차가 열릴 때 직전 회차를 캐시에서 `ending` 으로 내리지 않는다
    (계획 2.1 표는 「TD 분할 · 재접속 공통」 — kty 2026-10-19 코드 쪽 확정). 재접속
    개시는 직전 회차가 DB 에서 이미 `ending` · `ended` 일 때만 일어나고
    (`inheritable`), 캐시는 4.1 종료 전이 push 로 그 `ending` 을 이미 안다 — 그래서
    개시 때 강등 규칙을 더하지 않았다. push 를 놓친 창(결과 모름 뒤 적재 전 · init
    불일치 분리의 Dirty 반영 전)에서만 `live` 회차 둘이 보이고, 발행 소유자는 첫 조각
    번호가 큰 회차(`Owner`)라 뒤 회차를 고른다.

### M4 ⓒ 에서 M5/M6 로 넘긴 것

| 넘긴 것 | 어디로 | 까닭 · 자리 |
|---|---|---|
| 시청 경로 — 되감기 URL 서빙 · CDN · 제어면의 라이브 전환 | M5 | M4 는 S3 발행까지다(부기 34 의 전제) 〔추기 2026-10-26 — M5 ②a 가 제어면 서버 · 상태 스냅숏을 넣었다(위 「되감기 상태 제어면」). CDN · 웹 전환은 M5 의 다른 PR 몫이다.〕 |
| `ended` 전이 · ENDLIST 봉인 호출 · 동결 · VOD 전환 · 만료 | M6 | 종결 축이다(부기 34). `rewind.SealEndlist` 는 있지만 운영 코드에서 부르는 곳이 없다 〔추기 2026-10-27 — M6(POK-196)에서 닫혔다: 동결이 DVR 목록을 봉인하고(`publish` 의 `FreezeDVR` · `Endlist` — 여기서 `SealEndlist` 를 부른다) 꼬리 사슬 VOD 를 한 번 만든다(`FreezeVOD`). 회차가 닫히고 정산이 거두면 세션이 `ended` 가 된다. 제어면은 `vod` · `ended` 를 스트림 경로와 회차 경로로 낸다(위 「되감기 상태 제어면」). 만료는 위 환경 변수 절의 「60일 만료」 문단이다.〕 |
| L3(연속 GAP 셋 → ending) · 정산 창 만료 포기 · L2′ | M6 | 부기 10 〔추기 2026-10-27 — M6 에서 셋 다 닫혔다: L3(사유 `gap_run`)는 스위치 `REWIND_GAP_RUN_END` 로, L2′ 는 스위치 `REWIND_STALL_SEAL` 로 들어왔고 둘 다 기본은 끔이다(kty K5 — 위 환경 변수 표). L2′ 는 목록을 봉인하고 그 회차를 `ending`(사유 `stall_seal`)으로 끝낸다(kty 결정 2026-10-27). 정산 창 안에 거두지 못한 사슬은 포기 등재(`vod_abandoned`)로 닫는다.〕 |
| G8-F · G8-E | M6 | 부기 7 〔추기 2026-10-26 — M6 에서 닫혔다: 동결 · 봉인 경로의 두 writer 시험이다(`internal/rewind/publish` 의 `freeze_pg_test.go` G8-F · `audit_pg_test.go` G8-E).〕 |
| 의도적 종료와 끊김의 구분(계약4 4D 종료 신호 수신) | PR ⓒ 머지 뒤 별도 티켓 | kty 결정. `internal/session/registry.go` 의 `inheritable` 에 의도적 종료 갈래가 없다 |
| 검문 빈틈 셋(코덱 지문 · 게이트 공백 · 일시 오류) | M6 에서 다시 본다 | kty 결정(2026-09-30 — 코드 그대로). 아래 한계 표 〔추기 2026-10-26 — M6 에서 다시 봤고 코드 그대로다. M6 가 이어받기 조건에 더한 것은 닫힘 거부 둘(`inherit_declined reason=round_closed` · `chain_closed`)과 의도적 종료 제외뿐이다. 검문 입력 · 순서는 그대로고 플러그인 고정 설정 전제도 그대로다.〕 |
| init 잔량 영구 잔존의 보정 | M6 | kty K3 — 한계 유지(위 ⓐ 절 알려진 한계 여섯째 행) 〔추기 2026-10-27 — M6 에서 해소됐다: `ended` 전이가 생겨 init 조회 · 잔량 조건의 `state <> 'ended'` 가 실제로 거른다. 옛 ending 세션은 정산이 거둔다(빈 동결 → `ended`).〕 |
| ③ 잔량의 `ended` 동기 · M6 만료 뒤 컷오프 재주조 | M6 | ⓒ 에는 `ended` 가 없다 〔추기 2026-10-27 — M6 에서 닫혔다: ③ 잔량은 위 행과 같이 `ended` 로 거른다. 만료 뒤의 컷오프는 만료가 전진하거나 지운다(kty K2 — 위 「사이드카 DB 계정 media_indexer 권한」 의 `stream_cutoffs` UPDATE · DELETE).〕 |
| 첫 목록 지연 경보(`rewind_ready_delayed`) | M5 | 재기동 ② 뒤 비계승 회차의 무경보(아래 한계 표) 〔추기 2026-10-26 — M5 ②a 에서 닫혔다: 송출 중(4.1 종료 판정과 같은 술어)인데 60초 넘게 준비 안 되면 WARN 이다.〕 |
| 목록 객체의 Content-Type 값 | M5 회부 | 계획 7절 13 · 위 실측 기록 |
| 이름 부채 개명(`SeedResult` · `MarkPlaybackFailed`) | M5 | 부기 16 · 23 〔추기 2026-10-26 — M5 ②c 에서 닫혔다: `SeedResult` → `InsertCommit`(INSERT 트랜잭션의 커밋 사실), `MarkPlaybackFailed` → `SettlePlaybackFailure`(사유에 따라 조각만 또는 조각과 회차를 함께 정리). 동작은 그대로다. 이 문서의 이관 기록 · 부기 표는 그때 이름 그대로 둔다.〕 |
| 계승 사슬(두 단계 이상)과 1시간 상계 | M5/M6 | 위 ⓑ 절 알려진 한계 〔추기 2026-10-26 — M6 몫은 일부 닫혔다: VOD 는 사슬 전체를 덮는다. DVR 다단 접두의 1시간 상계는 한계 그대로다.〕 |
| 슬레이트 운영 프로비저닝 · 관측 등급 tier 상수 | 별도 마일스톤 | 계획 8절 kty 확정 (가) |
| 라이브 창 설정(ADR-056 ③ 대체 예정) 28초 적용 | PR ⓒ 뒤 설정 소PR | 되감기 URL 가동 뒤 · 적용 게이트 셋을 통과한 뒤. 라이브 창은 28초(4초 × 7)로 확정됐다(kty 2026-10-19 — ADR-056 의 14초는 대체 예정). 바뀌는 값은 `hlsSegmentCount` 900 → 7 하나다 |
| SDK 오류 본문 상한(S-1) · 운영 빌드 그래프 정적 단언(S-2) · HTTP 응답 헤더 상한 | PR ⓒ 밖 — kty 회부 | 계획 커밋 순서 7 — 선재 결함 · 범위 확장 |
| 업로더의 https 검사를 실효 엔드포인트로 | 후속 티켓 | 발행 몫만 고쳤다(계획 2.3 표 밖 파일) — 아래 한계 표 |

### M4 ⓒ 알려진 한계 (정직 서술)

코드는 지금 동작 그대로다. 「발현 조건」 칸 끝에 우리 플러그인 송출 · 운영
설정에서 생기는가를 적는다(kty 2026-09-30 규칙).

#### ⓒ 한계 — 회차 경계와 재기동

계획의 「README ⓒ 한계 절 행 요지」 표 여덟 행이다. 수리는 인프라로 한다 — 예컨대
사이드카를 따로 띄우는 구성(kty).

| 한계 | 발현 조건 | 영향 | 대응 · 다시 볼 조건 |
|---|---|---|---|
| 재기동 ① 이어 붙음 | 사이드카가 방송 B1 의 끝을 판정하기 전(재기동 창)에 다음 방송 B2 가 송출을 시작한다. 우리 송출: 배포 · 장애 재기동이 방송 끝 근처에 끼면 생긴다 | B2 가 B1 의 회차에 붙는다 — 되감기는 이어지고 두 방송이 한 회차가 된다 | 인프라. 운영 완화: 계획적 재기동(배포)은 스트림을 비운 뒤에 한다 |
| 재기동 ② 영구 정지 | B1 의 회차가 끝난 뒤 사이드카가 내려가 있는 동안 B2 의 조각이 쌓이고, 복귀한 사이드카가 그 조각을 방증 없이 본다(B2 종료 · 첫 관측보다 60초 넘게 이른 조각 · 지금 송출의 에폭 시작보다 이른 조각 · 관측 낡음 · 첫 관측 없음). 우리 송출: 사이드카가 내려간 동안 방송이 시작되면 생긴다 | 그 조각은 회차에 붙지 않는다(`session_id` NULL). 컷오프 뒤면 그 스트림의 목록이 그 행에서 멈추고 이후 방송도 모두 멈춘다. 계승 회차면 MSN 이 오르지 않고, 비계승 새 회차면 목록이 아예 나가지 않는다 | 인프라. 생기면 운영자가 수동 복구한다(절차는 운영 문서 몫 — 미확인). 판별: `stream_segments` 의 컷오프 이후 행에 `session_id IS NULL` 이 있다 |
| 재기동 ② 뒤의 경보 | 재기동 ② 로 생긴 NULL 행 뒤에 새 회차가 열린다. 우리 송출: 재기동 ② 가 난 뒤에만 | 뒤 회차가 계승 회차가 아니면 목록이 한 번도 나가지 않아 L4 가 없다(M4 무경보). 계승 회차면 접두로 첫 목록이 한 번 나간 뒤 멈추므로 7.5초 뒤 L4 가 뜬다 | 판별 · 복구는 재기동 ② 와 같다. 경보는 M5 `rewind_ready_delayed` |
| 관측 끔 | `MTX_API_URL` 이 비었다(compose 기본값은 `http://media:9997`). 관측 실패가 지속돼도 같다(`auth_rejected` 등 — 마지막 성공 관측이 `OBS_FRESH` 를 넘겨 낡는다). 우리 송출: 운영 설정에서는 생기지 않는다 — 롤백 손잡이를 쓸 때, 또는 자격 · 주소가 틀렸을 때만 | 종료 판정이 서지 않아 같은 스트림의 연속 방송이 한 회차로 이어진다(M3 한계의 연장) | 관측을 켠다. 실패 지속이면 그 원인(자격 · 주소)을 고친다 |
| 종료 판정은 파일 점검 뒤 | 방송이 끝난 뒤 ⓐ FS 래치가 트립돼 있거나 가장 최근에 적용된 수집이 완주가 아니다(절단 · 순회 오류 · 래치 중단 · 수집 오류 — 트리 전체 공용이라 모든 스트림이 멈춘다) ⓑ 그 스트림 디렉터리의 순회 오류나, 그 스트림 파일을 장부에 넣지 못한 일(확인 실패 · 인계 · 측정 실패 · 길이 0 · poison)이 있다 — 그 스트림만 멈춘다. 길이 0 의 현실 원천은 MediaMTX 비정상 종료다. 우리 송출: FS 장애 · MediaMTX 비정상 종료 때 | 회차가 끝나지 않고 그동안의 재송출은 같은 회차로 이어진다(kty 수용). 원인이 영구면 파일 원인은 다음 방송이 끝날 때와 그 파일이 지워질 때(`recordDeleteAfter` 기본 1일) 가운데 이른 쪽까지, 디렉터리 원인은 원인이 사라질 때까지 닫히지 않는다 | 일시 원인은 재점검이나 다음 주기 수집(5분) 뒤 저절로 닫힌다. 영구 원인은 그 파일을 치우면 다음 주기 수집 뒤 닫힌다(절차는 운영 문서 몫 — 미확인). 신호: `fs_op_stalled` · `scan_collect_stalled` · `scan_collect_truncated` · `scan_collect_failed` · `walk_entry_failed` · `latest_stat_failed` · `unsettled_giving_up` · `stat_failed` · `invalid_duration` · `insert_poisoned` · `session_offline result=awaiting_check` |
| init 불일치 분리 창 | 방송 중 트랙 파라미터가 바뀌어 워커가 회차를 끝낸 뒤, 다음 조각이 새 회차를 열기 전에 워처 · 훅이 놓친 조각이 방증 없이 스캔으로 들어온다. 우리 송출: **플러그인 고정 설정이면 사실상 생기지 않는다**(머리말은 트랙 파라미터만의 함수이고 OBS 는 방송 중 출력 설정을 못 바꾼다). 남는 여지는 플러그인 밖 송출기나 인코더 자체 재시작에 감시 누락이 겹칠 때다 | 재기동 ② 와 같다 | 생기면 수동 복구한다. 증상 · 판별은 재기동 ② 와 같다 |
| 래치 트립 창 새 방송 | 직전 회차가 끝난 스트림에서 새 방송이 FS 래치 트립 동안(최대 5분) 또는 워처 강등 ∧ 훅 리더 사망 중에 시작하고, 버려진 조각을 래치를 푼 수집이 방증 없이 넣는다. 같은 결과의 계기가 둘 더 있다 — 종료 처리가 미뤄지는 동안 관측 폴 사이에 짧은 새 방송이 시작하고 끝난 경우, 새 방송 첫 조각을 수집이 훅 · 워처보다 먼저 넣은 경우(워처 · 훅이 정상이어도 생긴다). 우리 송출: FS 장애 · 감시 열화 때. 마지막 계기는 평시에도 생길 수 있다 | 재기동 ② 와 같다. 트립 한 번이 그 창에 시작한 모든 스트림에 걸린다(뒤 두 계기는 그 스트림만) | 수동 복구. 신호: `fs_degraded value=1` · `fs_latch_early_return`(변형 `watcher_degraded value=1` · `hook_reader_stopped`). 마지막 계기는 전용 신호가 없다. 고치려면 ADR-062 백로그 하한이나 ADR-063 결정 4 를 바꿔야 한다 |
| GAP 원장 `recorded` 의 뜻 | GAP 을 실은 목록은 S3 에 올라갔는데 P4 가 실패하고, 그 뒤 P4 성공 없이 방송이 끝났다(또는 다음 P4 전에 그 행이 1시간 창에서 빠졌다). 우리 송출: DB 오류가 P4 에 겹칠 때 | 발행된 GAP 행이 `published_state='recorded'` 로 남는다 — `recorded` 는 「미발행」이 아니라 「발행됐을 수 있음」이다 | 읽는 쪽(M6)은 `recorded` 를 발행된 것으로 다룬다. 닫는 발행의 확정 CAS 가 같은 절을 실으면 거기서 확정된다 |

#### ⓒ 한계 — 계승(이어받기)

| 한계 | 발현 조건 | 영향 | 대응 · 다시 볼 조건 |
|---|---|---|---|
| 300초 안 재접속은 늘 이어받는다 | 방송을 껐다가 300초 안에 다시 켠다. 우리 송출: 평시에 생긴다 | 끊긴 것과 일부러 끈 것을 가르지 못해 둘 다 이어받고 검문(stsd 게이트)이 한 번 돈다 | M6 — 플러그인 종료 신호(계약4 4D)로 가른다. 수신부는 PR ⓒ 머지 뒤 별도 티켓이다(kty 결정) |
| 코덱 지문은 녹화 원본의 전 트랙을 본다 | 소리 트랙 번호만 달라도 이어받기가 풀린다 — AAC 의 stsd 가 트랙 ID 를 싣는다(과잉 해제). 트랙 번호를 싣지 않는 코덱(Opus 등)에서는 소리 트랙 순서 교환을 가리지 못한다 — 산출기는 첫 영상 · 첫 소리만 남긴다. 우리 송출: **플러그인 고정 설정이면 생기지 않는다**(트랙 수 · 코덱 · 순서를 잠근다) | 과잉 해제는 접두 없는 새 목록이 된다. 순서 교환은 다른 소리 사양을 이어받는다 | 코드 그대로(kty 2026-09-30). 다시 볼 조건: 플러그인 밖 송출기를 받을 때 · M6 에서 이어받기 조건을 고칠 때 |
| 이어받기 개시와 첫 init 확정 사이의 검문 공백 | 그 사이에 사이드카가 재기동하거나, 같은 프로세스가 개시 행의 COMMIT 응답을 잃는다(서버는 커밋). 그 회차의 실시간 init 요청이 게이트 입력 없이 나가 검문을 건너뛴다. 우리 송출: **플러그인 고정 설정이면 생기지 않는다**(stsd 가 같아 건너뛰어도 결과가 같다) | stsd 가 다른 계승이 풀리지 않는다 — 계승 목록에 코덱 사양이 다른 MAP 둘이 실린다 | 코드 그대로(kty 2026-09-30). 다시 볼 조건은 위 행과 같다 |
| 직전 회차 첫 조각을 일시 오류로 못 열어도 이어받기가 풀린다 | 직전 회차 첫 조각을 열 때 파일 기술자 고갈(EMFILE · ENFILE) 같은 일시 오류가 난다. 우리 송출: 송출 설정과 무관하다 — 사이드카의 자원 고갈 순간에만 생긴다(빈도 미확인) | 호환 계승도 되돌릴 수 없이 풀린다 — 접두 없는 새 목록 | 코드 그대로(kty 2026-09-30). 다시 볼 조건: 위 행과 같다 |
| 하루를 넘긴 방송 뒤 재접속은 늘 이어받기가 풀린다 | 직전 회차 첫 조각이 녹화 보존(`recordDeleteAfter` 기본 1일)을 넘겨 지워진 뒤 300초 안에 재접속한다. 우리 송출: 24시간 넘는 방송 끝의 재접속에서 생긴다 | 직전 첫 조각을 열 수 없어 계승을 푼다(fail-closed) — 접두 없는 새 목록 | 기록(보안 검수 참고) |

#### ⓒ 한계 — 발행과 경보

| 한계 | 발현 조건 | 영향 | 대응 · 다시 볼 조건 |
|---|---|---|---|
| PUT 이 실패해도 재발행하지 않는 틱 | PUT 이 표 안의 사유로 실패했는데 그 틱의 결과가 「발행 없음 · 오류 없음 · 화해 예약 없음」이다 — `put_unknown` · `create_412` · `update_412` · `update_404` 는 같은 틱의 화해가 성공했을 때, `put_conflict` 는 늘 그렇다. 우리 송출: S3 일시 장애 · 경합 때 | 다음 트리거(다음 조각 · L2 · 병합)까지 목록이 한 틱 늦다. 방송 끝에는 다음 트리거가 없어 마지막 조각이 ending 전이 때까지 실리지 않을 수 있다 — ending 뒤에는 발행이 멈춘다(M4 시청자 0) | 한계로 수용(kty 2026-10-01). 다시 볼 조건: 틱 결과에 「표 안 중단」 칸을 더할 때(왕복 상한을 다시 잰다) |
| 재기동 뒤 적재 뒤 틱은 발행하지 않는다 | 재기동 뒤 첫 적재가 끝나고 낸 틱이 fence 획득과 화해(P 복원)의 준비 단계에서 끝난다. 우리 송출: 사이드카 재기동마다 | 목록은 다음 ③ 확정까지 그대로다 | 기록 — 위 행과 같은 부류 |
| 적재 고리에 1초 쉼이 없는 설정 | P2′ 가 결정적으로 실패하는 설정(DB 역할 · 열 권한 · 트리거)이다. 우리 운영 설정: **현 운영 설정에는 없다**(보안 실측) | 「요구 적재 → 적재 → 적재 뒤 틱 → 요구 적재」 고리에 1초 쉼이 없어 적재가 쉬지 않고 돈다 | 코드 그대로(kty (가) 2026-10-17). 다시 볼 조건 셋: AWS DB 역할 · 3번 트리거 · M6 새 갈래 |
| 서지 않은 GAP 틱의 DB 비용 | 정체 스트림에서 L2 가 결정됐는데 GAP 틱이 서지 않는다. 우리 송출: 송출 규약 안에서는 사실상 닿지 않는다 | 보류 틱마다 트랜잭션 하나가 P0 을 커밋해 세대를 올린다 — 정체 스트림 하나에 시간당 3,600건이고 그동안 로그는 L4 한 줄이다 | 기록(운영 비용 공시) |
| 빈 접두 가장자리의 무음 정지 | 2시간 넘는 방송에서 적재 하한 행의 ③ 이 끝내 settled 되지 않는다. 우리 송출: ③ 업로드 실패가 적재 하한 행에 겹칠 때만 | L1 · L2 · L4 가 하나도 서지 않는다 — 목록이 멈췄는데 경보가 없다 | 기록. 다시 볼 조건: 적재 하한 규칙(되짚기 2시간)을 바꿀 때 |
| 꼬리 교정이 TD 를 넘긴 조각 | INSERT 때 4초였던 꼬리가 교정으로 6.5초 이상이 되면 TD 6 회차에 반올림 7초 조각이 남는다 — 교정은 TD 를 다시 재지 않는다. 우리 송출: 정상 경로에서 드물게 | 그 목록이 S5(TD 검사)로 그 조각이 창에서 빠질 때까지(최대 1시간) 멈춘다 — `rewind_publish_aborted reason=validate` ERROR | 코드 0 · 한계(kty K2). 회차를 끝내는 처방은 피해를 줄일 뿐이라 넣지 않았다 |
| 외부 메타 끝값이 장부에 남는 길 | 화해가 Head 의 메타(`pc-gen` · `pc-disc-seq`)를 장부에 옮긴다. 버킷 쓰기 권한을 가진 자 · 평문 엔드포인트의 중간자 · 비정상 호환 엔드포인트가 메타를 위조하면 그 끝값이 장부에 남는다. 우리 운영 설정: 실 S3 + https 에서는 버킷 쓰기 권한 갈래만 남는다 | 세대 끝값은 장부 한 열을 고쳐야 풀린다. DISC-SEQ 끝값은 재기동 · 화해로 풀리지만, 그 정지 창에 TD 분할이 오면 끝값 base 가 새 회차로 복사된다 | kty 회부 대기 — 근본 처방은 외부 값을 장부 값에 상대적으로 묶는 것이다 |
| 감시 문장의 비용 축은 스트림 ID 가짓수 | 30초 감시의 한 문장(재귀 CTE)이 장부에 있는 스트림 ID 전부를 훑는다. 실측: 250만 가짓수에서 10초 시한 실패 두 번(커밋 5 보안 검수) · 1만 가짓수 39–42ms · 약 200만은 그 사이의 어림이다. 우리 운영 설정: 스트림 키 고정 · 인증 관문(dev-media `authMethod: http`)이 있는 운영에서는 가짓수가 적다(수십 — 운영 스트림 키 수). 로컬 compose 기본은 익명 publish 라 해당한다 — 이름을 바꿔 가며 송출을 받으면 가짓수가 는다 | 감시 틱이 시한에 걸려 그 틱의 드리프트 · 컷오프 부재 대조가 빠진다(`rewind_job_failed` · `job=watch`). 그 틱의 init 화해 · 쉬는 뷰 버리기(Forget)도 빠진다. 발행은 계속된다 | 익명 publish 를 허용하는 설정을 쓰게 되면 다시 본다(가짓수 상한 또는 접두 분할) |
| Head 거짓 404 | 화해(R4)가 Head 404 만으로 장부 `manifest_etag` 를 비운다. 버킷 오설정 · 중간자 · 404 를 돌려주는 프록시가 있으면 이 일이 난다. 우리 운영 설정: 부팅 버킷 확인 · https 강제로 좁혔다 | 이미 발행한 S4 상태 회차에서 P2′ 계승 취소가 다시 열린다. 탐침에서는 거짓 404 를 거둔 뒤에도 발행 정지가 풀리지 않았다(8초 관측) | 처방 둘(PUT 404 로 부재가 확인됐을 때만 비운다 · P2′ 가 「한 번도 발행하지 않았음」을 따로 본다)은 kty 회부다. 없는 버킷 HEAD 응답의 단서는 실측하지 않았다 — 승인된 실버킷 밖 이름이 필요하다 |

#### ⓒ 한계 — 조립과 설정

| 한계 | 발현 조건 | 영향 | 대응 · 다시 볼 조건 |
|---|---|---|---|
| 부팅 때 S3 일시 장애면 그 프로세스 동안 발행이 꺼진다 | 부팅의 자격증명 · HeadBucket 단계가 503 · 시한으로 실패한다. 우리 운영 설정: S3 · 자격증명 일시 장애가 기동에 겹칠 때 | 발행 몫이 강등돼 재기동까지 목록이 갱신되지 않는다. 업로드 · 인덱싱은 돈다. 신호는 부팅 ERROR `rewind_publish_degraded` 한 줄뿐이다 | 재기동이 회복한다. 로그 수집을 붙일 때 이 줄에 경보를 건다 |
| `credential_process` 출력이 ERROR 에 실릴 수 있다 | 자격증명 provider 가 `credential_process` 이고 그 출력이 오류 문자열에 섞인다. 우리 운영 설정: 로컬은 env 3종이라 아니다 · 운영 자격증명 방식은 IaC 때 정한다 | 강등 줄의 `err` 에 그 문자열이 그대로 실린다 | 로그 수집을 정할 때 업로더의 같은 줄과 함께 본다 |
| HeadBucket 에는 `s3:ListBucket` 권한이 필요하다 | 운영 역할에 `s3:ListBucket` 이 없다. 우리 운영 설정: 데모 IAM 에는 있다(2026-10-17 실측) · 운영 역할은 IaC 때 | 부팅 버킷 확인이 403 으로 실패해 발행 몫이 강등된다 | IaC 때 접두 조건과 함께 정한다. 권한을 주면 LIST 금지의 실행 시점 방어선이 줄고, 금지는 정적 단언이 진다 |
| 강등 갈래의 유휴 연결 | 버킷 확인을 한 뒤 강등된다. 우리 운영 설정: 강등 때만 | S3 클라이언트에 닫는 API 가 없어 유휴 연결 하나가 SDK 유휴 시한(90초)까지 남는다 | 기록 |
| 엔드포인트 원문 로그 | 실효 엔드포인트에 query 가 들었다. 우리 운영 설정: 엔드포인트를 비워(AWS 기본) 두면 닿지 않는다 | 켜짐 · 강등 줄의 `endpoint` 에 query 가 그대로 실린다. `//` 없는 꼴(`https:user:pw@host`)과 빈 호스트는 ⓒ 종결 라운드에서 닫았다 — https 검사가 거부하고 `endpoint` 칸에는 원문 대신 표식 `(호스트 없는 URL)` 이 실린다 | 엔드포인트에 자격증명 · 토큰을 넣지 않는다 |
| 업로더의 https 검사는 설정값만 본다 | SDK 표준 설정(`AWS_ENDPOINT_URL_S3` 등)으로 평문 엔드포인트가 들어온다. 우리 운영 설정: 그 설정을 쓰지 않으면 닿지 않는다 | 업로더는 기동 때 거르지 못한다 — 발행 몫은 실효 엔드포인트를 본다 | 후속 티켓(계획 2.3 표 밖 파일) |

#### 위 ⓐ 절 한계 표에서 ⓒ 가 바꾼 것

*   일곱째 행(init CAS `Mismatch`)의 송출 설정 변경 갈래와 여덟째 행(ⓐ 단독
    국면의 24시간 휴지)은 ⓒ 의 종료 전이가 방송마다 회차를 끊어 닫는다. 관측 끔과
    재기동 ① 이어 붙음에서는 남고, 재포장 산출을 바꾸는 배포 갈래도 남는다.
*   다섯째 행(재기동 뒤 init 미복원)은 그대로다. 스위치가 켜져 컷오프가 생기므로
    그 회차의 ③ 은 스위퍼(30초)가 회차 기대값으로 올린다. Dirty 로 미복원을 감지해
    재요청하는 수리안은 넣지 않았다.
*   여섯째 행(init 잔량)은 kty K3 로 한계 유지 · M6 이월이 정해졌다.
*   아홉째 행(보류 재요청의 거울상 경합)은 ⓒ 가 주조 스위치를 기본으로 켜 컷오프가
    평시라 평시에 닿을 수 있는 경합이 됐다. 영향(재PUT 1회 · WARN 1줄 · 데이터 영향
    0)과 수리안은 그대로다.

### M4 ⓒ 테스트 지도

PR ⓒ 가 시험 함수 368개를 더했다(TestMain 제외 991 → 1,359 — develop 병합 뒤 셈 ·
커밋 1–8 이 365 · 종결 라운드가 3). 시험 파일은 80개가 바뀌었고 그 가운데 45개가 새
파일이다(수정 35). PG 가 필요한 시험은 아래 「테스트」 절대로 돌리고,
`internal/rewind/publish` 의 전용 DB 접미는 `_rewindpublish` 다.

| 자리 | 시험 파일 |
|---|---|
| `internal/rewind/publish` 단위 | `publisher_test.go` · `tick_test.go` · `ladder_test.go` · `watch_test.go` · `gate_test.go` · `s3store_test.go` · `fakestore_test.go` · `fakestore_contract_test.go` · `harness_test.go` · 정적 단언 `c2_static_test.go` · `s3list_static_test.go` |
| `internal/rewind/publish` PG | `tick_pg_test.go` · `dsn_pg_test.go` · `gap_pg_test.go` · `gapconfirm_pg_test.go` · `gapstand_pg_test.go` · `revoke_pg_test.go` · `rc19_pg_test.go` · `fence_pg_test.go` · `worker_pg_test.go` · `abort_log_pg_test.go` · `g8_pg_test.go` · `g8_race_pg_test.go` |
| `cmd/segment-indexer` | 루프 `publish_loop_test.go` · `publish_loop_done_test.go` · `publish_loop_ladder_test.go` · `publish_loop_watch_test.go` · `publish_loop_negative_test.go` · `publish_loop_fixture_test.go` · 종료 전이 `offline_loop_test.go` · `offline_loop_fixture_test.go` · 조립 `publish_assembly_test.go` · `publish_assembly_pg_test.go` · 통합 `publish_it_pg_test.go` · `publish_it_restart_pg_test.go` · `publish_it_fakes_pg_test.go` · `publish_it_fixture_pg_test.go` |
| `internal/indexer` | `offline_judgment_test.go` · `offline_pg_test.go` |
| `internal/index` | `end_live_pg_test.go` · `rewind_watch_pg_test.go` |
| `internal/rewind/cache` | `load_test.go` · `watch_test.go` |
| `internal/upload` | `init_gate_test.go` · `fakeputter_contract_test.go` |

위 표는 새 파일 45개다. 기존 시험 파일 35개에도 행이 더해졌다 — 예컨대
`internal/session/registry_test.go`(계승 개시 TD) · `internal/config/config_test.go`
(베이스 URL 수락 · 거부 표) · `internal/playback/mp4box_test.go`(stsd 지문).

#### ⓒ 커버리지 게이트

ⓒ 시점 CI(`media-ci`)의 80% 게이트는 열네 패키지였다(지금은 janitor 를 더해
열다섯 — 아래 PG 시험 절). ⓒ 가 `internal/rewind/publish` 를
더했고, develop 의 ingest-auth 응답기가 `streamkey` · `keyresolve` · `ingestauth` ·
`mtxctl` 넷을 더했다(병합으로 합쳐졌다). 아래 값은 커밋 8 의 CI 동일 실행(PG 켬 · `-race`) 프로파일에 CI 판정식을
다시 돌린 것이다. 표가 열 패키지뿐인 것은 그 프로파일이 병합 전 것이라 develop 이
더한 넷이 들어 있지 않아서다.

| 패키지 | 커버리지 |
|---|---|
| `internal/index` | 86.8% (347/400문) |
| `internal/upload` | 97.3% (940/966문) |
| `internal/indexer` | 94.2% (883/937문) |
| `internal/session` | 97.3% (108/111문) |
| `internal/mtxstate` | 95.3% (81/85문) |
| `internal/playback` | 95.4% (208/218문) |
| `internal/rewind/boundary` | 100.0% (21/21문) |
| `internal/rewind` | 100.0% (236/236문) |
| `internal/rewind/cache` | 100.0% (277/277문) |
| `internal/rewind/publish` | 99.4% (623/627문) |

#### ⓒ 뮤테이션 정식 실행

커밋 8 이 계획 6.3 표의 ⓒ 몫 · 커밋 4–7 스크래치 뮤턴트 · 커밋 8 판별 뮤턴트를 한
번에 다시 돌렸다. 로그 428개를 같은 원래 이름으로 묶으면 고유 뮤턴트 405개다.

| 묶음 | 고유 | KILLED | 생존 |
|---|---|---|---|
| 6.3 ⓒ 몫 · 커밋 6b 몫 | 87 | 87 | 0 |
| 커밋 7 첫째 묶음 | 44 | 41 | 3 |
| 커밋 7 둘째 묶음(6.3 겹침 포함) | 98 | 91 | 7 |
| 커밋 7 셋째 묶음 | 22 | 21 | 1 |
| 커밋 4 · 수정 넷 | 133 | 130 | 3 |
| 커밋 8 판별 · 대조군 | 21 | 17 | 4 |
| 합계 | 405 | 387 | 18 |

*   **생존 18 의 분류**: 대조군 3(무변이라 생존이 정상) · 등가 13(앞 보고가 등가로
    둔 5 · 이번에 등가로 둔 8) · 관측 불가 2(도달하지 않는 실패 갈래 하나 ·
    동시 시험에서만 사는 변이 하나). 등가로 둔 `m80_no_owner_check` 는 등가가
    아니었다(종결 라운드 1 확인) — 적재 중 보류 틱에 그 스트림의 Dirty 원소가 있으면
    Owner 거짓 갈래에 닿아 발행 상태(P · fence)를 영값으로 되돌린다.
    `TestLoadingDoesNotResetOwnerState` 에 그 원소를 더해 이 뮤턴트가 20/20 FAIL 한다.
*   **고유 수의 단서**: 생존의 `D14-m9-p0-nogencas` 는 6.3 `m9_p0_no_gencas`
    (패키지 전체에서 KILLED)를 동시 시험 하나로 좁힌 같은 변이다. 참고 행으로 빼면
    고유 404 · 생존 17 이다.
*   **빌드 실패의 수(정정)**: 최초 실행 요약 셋에는 빌드 실패가 13건이다(cmd 8 ·
    그 밖 2 · 발행 3). 정의를 고친 같은 이름의 재실행이 앞 로그를 덮어 cmd 의 8건은
    본문이 남지 않았다. 남은 로그의 빌드 실패는 5건이고 다섯 모두 고친 정의
    (`__v2`)가 KILLED 다. 이름 없는 자기 점검을 다시 세운 EX 뮤턴트는 7개다(커밋 8
    개발 보고의 「빌드 실패 여섯」 · 「EX 다섯」을 고친다).
*   **결함 처분**: 생존으로 판별 공백이 드러난 여섯에는 시험을 더했고, 해당
    뮤턴트에서 20/20 FAIL · 패키지 전체 재실행에서 KILLED 다.
*   **자료**: 작업 산출물 `c8_dev/mut/`(저장소 밖) — `summary.txt`(로그 단위) ·
    `final_table.txt`(고유 단위).

#### ⓒ G9 s4 leaf 다섯과 G8 동시 writer

| leaf | 시험 · 자리 |
|---|---|
| `c2_no_flag_read` | `TestC2NoFlagRead`(`internal/rewind/publish/c2_static_test.go`) — 신설 |
| `f4_all_lost` | `TestPhaseF4AllLostDoesNotSeed`(`internal/indexer/phase_pg_test.go`) · `TestRewindWatchCutoffAbsentWithoutLiveSession`(`internal/index/rewind_watch_pg_test.go`) |
| `s4_signal_present` | `TestDeclinedSeedIsSignalledWithItsReason`(`internal/indexer/signal_test.go`) · `TestSessionSignalsCarryWarnGradeAndActionableLabels`(`internal/session/registry_test.go`) |
| `s4_alarm_continuous` | `TestCutoffAbsentJudgment`(`internal/rewind/publish/watch_test.go`) |
| `s4_signal_grades` | `TestCutoffAbsentJudgment` · `TestSessionSignalsCarryWarnGradeAndActionableLabels` · `TestPollFailureSignalsAreWarnGrade`(`internal/mtxstate/state_test.go`) |

*   **G8 동시 writer**: `TestConcurrentWritersKeepOneLineage`
    (`internal/rewind/publish/g8_race_pg_test.go`)가 실 PG 에서 두 writer 를 동시에
    돌린다. `-race` 30/30 통과 · 경합 0 이고, 30회 가운데 29회에서 조건부 쓰기 패배,
    30회 모두에서 fence 상실이 실제로 났다. 결정적 교차 시험 10개는
    `g8_pg_test.go` 에 있다. 판정 기준은 부기 39 다. 판정 순서는 결함 신호(허용 밖
    중단 사유 · 시한 밖 틱 오류)와 적용된 판의 계보 검사가 먼저이고, 부하로 문턱
    (저마다 발행 5번 · lease 끝냄 20번 · 30초) 안에 경쟁 국면이 서지 않았는데 결함
    신호가 없으면 국면 문턱 · 정착 단계만 건너뛴다(원인 수치 로그 · 종결 라운드 1 · 2).
    결함 신호가 있으면 국면과 무관하게 실패다(예외 하나 — 국면이 서지 않은 실행에서
    연속한 두 판에 겹치는 조각이 0 인 것은 시험 장치의 인공물이라 미성립으로 센다 ·
    종결 라운드 2 확인 패스). 30/30 은 조용한 기계의 값이다 — 코드는
    부하를 재지 않고 발행 수 · lease 끝냄 수 · 시한으로만 판정하며, 부하 실행(평균 36–230
    관측)에서는 `-race` 가 건너뜀으로 끝났다(18/18). 그때도 계보는 위 사슬 검사와
    `g8_pg_test.go` 의 교차 열 개가 결정적으로 판정한다.
*   **G8 시험의 변이 교정(종결 라운드 1 재실행)**: fence 조건 제거는 동시 시험
    하나로 N/N 이 안 된다(세대 예약 제거는 현행 규칙에서 20/20 FAIL — 아래). `-count=20`(`-race` 없이 — 커밋 8 정식 실행과 같은 형)
    에서 fence 조건 제거(P4)는 1/20 FAIL(허용 밖 중단 사유 `etag_source_mismatch` ·
    종결 라운드 2 cc 재측정 6/20)이고, 세대 예약 제거는 종결 라운드 1 규칙(국면
    미성립이면 건너뜀)에서 20/20 건너뜀이었으나 라운드 2 규칙(결함 신호면 실패)에서는
    20/20 FAIL 이다(중단 사유 `meta_invalid` · `validate`). 두 변이
    모두 같은 패키지의 결정적 시험이 죽인다 — fence 조건 제거는
    `TestP4RefusesZombieAfterTakeover`, 세대 예약 제거는 `g8_pg_test.go` 교차 열 개를
    비롯한 시험 64개다(패키지 PG 켬 한 번).

### M4 ⓒ 출처

*   설계 · ADR: 볼트 설계 r17(4.1 · 4.4 · 4.5 · 4.6 · 4.9 · 6.2 · 9.1절) ·
    ADR-044 · ADR-061 · ADR-062 · ADR-063 · ADR-073.
*   계약: 팀 위키 `PokeClip-LLM-WIKI` — `contracts/계약-세그먼트인덱스.md` ·
    `contracts/계약3-LLHLS-DVR재생규약.md` 7-5(kty K4 — TD 문언 정정과 2번 통지
    POK-249) · 계약4 4D.
*   계획: POK-195 M4 작업 산출물 — 계획(개정 r53 까지)의 4.1 · 4.5 · 커밋 순서
    1–9 · 「README ⓒ 한계 절 행 요지」 표 · 부기 1–43 · 7절 13 · 8절.
*   결정: kty K1–K4(2026-09-25) · 검문 빈틈 셋 코드 그대로(2026-09-30) · L4 등급
    결정 ④ A · 작업 오류 로그 결정 ⑤ · PUT 실패 뒤 화해 (가)(2026-10-01) · G8
    기준(2026-09-26) · 적재 고리 (가)(2026-10-17).
*   실측: 위 「ⓒ 실측 기록」 넷과 커밋 7 셋째 묶음의 compose 실측(2026-10-06 —
    기본 compose 에서 컷오프 주조와 종료 전이 · 버킷만 준 override 에서 기동
    거부).
*   코드: 이 절이 인용한 파일 · 함수 · 시험(PR ⓒ 커밋 1–8 개발 보고 · 검수 통합과
    대조).

## 테스트

```bash
cd media && go test ./...
```

`internal/index`·`internal/session`·`internal/indexer`·`cmd/segment-indexer`·`internal/upload`·`internal/rewind/cache`·`internal/rewind/publish`·`internal/janitor`·`cmd/janitor`의 통합 테스트와
`internal/pgtest`의 자기 테스트는 **실제 PostgreSQL이 필요**하다. `PG_DSN`이 없으면 해당 케이스는 전부 `skip`되고 나머지는 그대로
돈다 — 즉 `PG_DSN` 없이 돌린 결과만으로는 DB 계층이 검증되지 않는다.

```bash
set -a; . ../.env; set +a
export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
go test ./internal/index/ ./internal/session/ ./internal/indexer/ ./cmd/segment-indexer/ ./internal/upload/ ./internal/rewind/cache/ ./internal/rewind/publish/ ./internal/janitor/ ./cmd/janitor/ ./internal/pgtest/ -v
```

이 통합 테스트들은 `PG_DSN`이 가리키는 DB에 **쓰지 않는다.** `PG_DSN`은 관리 접속으로만
쓰이고, 같은 서버에 전용 테스트 DB(`PG_TEST_DB`, 기본 `pokeclip_uploadtest`)를 만들어 그 안에서만
돌며 테스트 함수마다 비운다. **전용 DB는 패키지마다 하나씩이다**(`internal/pgtest`) —
`go test ./...`는 패키지별 테스트 바이너리를 병렬로 띄우므로 한 DB를 나눠 쓰면 서로의 표를
비운다. 실제 이름은 `PG_TEST_DB`에 패키지 접미를 붙인 것으로, `internal/index`만 무접미
(`pokeclip_uploadtest`)이고 `internal/session`은 `_session`, `internal/indexer`는 `_indexer`,
`cmd/segment-indexer`는 `_cmd`, `internal/upload`는 `_upload`, `internal/rewind/cache`는 `_rewindcache`, `internal/rewind/publish`는 `_rewindpublish`, `internal/janitor`는 `_janitor`, `internal/pgtest` 자기 테스트는 `_selftest`다.
`internal/index`는 로컬 정리 스키마 국면 시험용으로 `_lcempty`(빈 DB)·
`_lcpartial`(판정 표 일부만)·`_lcmigrate`(옛 스키마 이행)도 만든다.
`cmd/janitor`의 PG 시험은 전용 DB 없이 `PG_DSN`에 붙어 세션 설정·서버 버전을
읽기만 한다. 그래서 개발 DB의 `stream_segments`는 오염되지 않는다(대신 `PG_DSN`
롤에 `CREATEDB` 권한이 필요하고(`internal/index`의 janitor 최소 권한 시험은
실행마다 이름이 다른 시험 역할을 만들고 지우므로 `CREATEROLE`도) — **로컬
compose와 CI(`media-ci`)의 postgres 서비스 컨테이너 둘 다 superuser라 이미 갖고
있다**, `PG_TEST_DB`를 개발 DB 이름과 같게
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
하나만 지우면 `internal/index`는 새 스키마로 통과하고 `_session`·`_indexer`·`_cmd`·`_upload`·`_rewindcache`·`_rewindpublish`·`_janitor`는 옛
스키마 그대로라 그 일곱 패키지만 "does not exist" 계열로 실패한다.

**아래 루프는 소유 표식을 확인하지 않고 이름만 보고 지운다** — 밑이름이 기본 이름이거나 테스트가 새로 만든
고유 이름일 때만 쓰고, 표식이 없어 실패한 DB는 실패 메시지대로 전용 테스트 DB가 맞는지 직접 확인한 뒤 하나씩 지운다.

```bash
# PG_TEST_DB 를 바꿔 돌렸다면 그 이름이 밑이름이다 — 기본 이름만 지우면 실제 DB 는 남는다.
base="${PG_TEST_DB:-pokeclip_uploadtest}"
for suffix in "" _session _indexer _cmd _upload _rewindcache _rewindpublish _janitor \
  _lcempty _lcpartial _lcmigrate _selftest; do
  psql "$PG_DSN" -c "DROP DATABASE IF EXISTS ${base}${suffix}"
done
```

`internal/fmp4meta` 테스트는 `testdata/`의 커밋된 파일만 쓰므로 Docker가 꺼져 있어도 돈다.

`cmd/mtxhookwrite` 테스트는 **바이너리를 직접 빌드해 프로세스 8개를 동시에 띄운다**(줄 섞임 검증).
`go test`만 있으면 되고 Docker는 필요 없지만, 다른 테스트보다 몇 초 더 걸린다.

CI(`media-ci`)는 `go test`에 `-coverprofile`을 붙여 패키지별 커버리지를 함께 재고,
`internal/index`·`internal/upload`·`internal/indexer`·`internal/session`·
`internal/mtxstate`·`internal/playback`·`internal/rewind/boundary`·
`internal/rewind`·`internal/rewind/cache`·`internal/rewind/publish`·
`internal/streamkey`·`internal/keyresolve`·`internal/ingestauth`·
`internal/mtxctl`·`internal/janitor`
**열다섯 패키지 중 하나라도 80% 미만이면 잡을 실패시킨다**(`session`·`mtxstate` 는 POK-195 M3에서,
`playback` 은 M4 ⓐ 에서, `rewind/boundary`·`rewind`·`rewind/cache` 는 M4 ⓑ 에서, `rewind/publish` 는 M4 ⓒ 에서, `janitor` 는 POK-170 에서 추가 — 되감기 판정 로직과
③ 바이트를 만드는 층, 되감기 목록의 경계·본문·입력을 정하는 층이 그 안에 있다. 발행된 목록 줄은 고칠 수 없어
여기서 틀린 것은 그 목록이 사는 동안 남는다). 나머지 패키지는 수치만 로그에 남고 게이트 대상이 아니다.

## MediaMTX 버전업 체크리스트

**핀이 어긋나면 [`internal/mtxhook/version_contract_test.go`](internal/mtxhook/version_contract_test.go)가
빨간불이 되고, 그 실패 메시지가 이 절로 안내한다.** 상수는 셋이고 책임이 다르다 —
`pinnedMediaMTXTag`(FROM 태그와 대조)·`pinnedMediaMTXDigest`(FROM digest와 대조)·
**`upstreamBaseVersion`(아래 전제 9곳을 짊어지는 상수)**. **9곳 재확인의 서명은 마지막 하나이고**,
그 값이 바뀌는 순간만 진짜 버전업이다(포크 태그의 `.1`→`.2`는 보통 우리 수정만 바뀐 것이다 — 다만 상류 베이스 커밋이 함께 이동하면 준버전업이며 아래 "포크 태그와 상류 버전의 관계" 규약을 따른다).
실패 메시지에 9개 목록이 그대로 들어 있다.

제품 버전 고정의 기준은 [`media/Dockerfile.mtxhook`](Dockerfile.mtxhook)의 `FROM`이다
(compose의 `image:`가 `build:`로 바뀌면서 옮겨왔다). **지금 그 줄은 상류 공식 이미지가 아니라
우리 포크 빌드를 태그+digest로 가리킨다** — 아래 "이미지 출처에 묶인 전제" 절을 함께 읽는다. 훅 파라미터 이름은 버전 사이에 조용히
바뀌거나 사라질 수 있고, **훅이 실행되지 않아도 아무 오류가 나지 않는다**. 그래서 절차를 고정한다.
`FROM`과 함께 고칠 자리(테스트 상수 등)는 아래 체크리스트가 안내한다. dev-media(서버) 핀인
`infra/dev-media/compose.yml`의 `image` 줄은 서버가 필요로 할 때 서버 기준으로 올리고, 제품
`FROM`과 같을 필요는 없다(ADR-040 2026-10-12 추기).

### 버전에 묶인 전제 9곳 — 절차보다 먼저 확인한다

제품 버전 고정의 기준은 한 곳이지만, **"고정 버전이라서 참인 사실"에 기대는 자리는 아래 9곳**이다.
전제가 깨져도 예외도 로그도 나지 않는다 — 훅이 조용히 안 돌거나 길이가 조용히 틀릴 뿐이다.
"닻"은 그 자리를 `git grep`으로 바로 찾기 위한 문구다(줄 번호는 금방 낡아서 적지 않는다).

| # | 자리 (닻) | 무엇이 참이라고 전제하는가 | 어떻게 재확인하는가 |
|---|---|---|---|
| 1 | `infra/compose/mediamtx.yml` — `pathDefaults` 블록<br>(닻: `all_others 에도 상속된다`) | 설정 로딩이 `pathDefaults`(모든 경로의 기본값 묶음)를 먼저 복사하므로, 훅 3종을 여기에만 적어도 `all_others`(설정에 이름을 안 적은 모든 경로)에 그대로 붙는다 | 새 이미지를 띄우고 아무 이름(예: `demo`)으로 15초 송출한 뒤 스풀 `/hooks/events.jsonl`에 줄이 쌓이는지 본다. **안 쌓이면 상속이 사라진 것** — 훅 3종을 `paths: all_others:` 아래로 내려 적는다 |
| 2 | `infra/compose/mediamtx.yml` — 훅 명령 3줄<br>(닻: `이 세 줄에`) | 명령 문자열을 shell 규칙으로 **먼저 쪼갠 뒤** 조각별로 변수를 치환한다. 그래서 `$MTX_PATH`를 넣어도 인자 개수는 안 늘고, 대신 그 인자 하나의 내용이 송출자 제어가 된다 | 업스트림 `internal/externalcmd/cmd_os_other.go`(#6156에서 `cmd_os.go`를 개명)에서 분해와 치환의 **순서**를 확인한다. 치환이 먼저로 뒤집혔다면 송출자가 경로 이름만으로 인자를 늘릴 수 있다 |
| 3 | `media/README.md` — 훅 채널 절<br>(닻: `구명칭 runOnReady`) | 구명칭 `runOnReady`는 `runOnAvailable`("읽기 가능" 축)로 매핑되며 세션(Online) 축이 아니다. 그래서 쓰지 않는다 | 3·4는 같은 사실이다. `docker compose logs media \| head -50`에서 deprecated/unknown 파라미터 WARN을 본다. 훅 3종 이름(`runOnOnline`·`runOnOffline`·`runOnRecordSegmentComplete`)이 WARN 없이 살아 있는지가 핵심 |
| 4 | `media/internal/mtxhook/event.go` — `Kind` 주석<br>(닻: `runOnAvailable 로 매핑`) | 위와 같은 사실을 코드 쪽에 적어 둔 것 | 위 3번과 함께 한 번에 확인한다 |
| 5 | `media/internal/fmp4meta/probe.go`<br>(닻: `트랙 중 최대 길이`) | `moov/mvhd`(파일 전체 길이가 적힌 상자)의 duration이 "트랙 중 최대 길이"와 일치한다. 이게 어긋나면 인덱스의 `duration_ms`가 조용히 틀린다 | 새 버전이 떨어뜨린 세그먼트를 `ffprobe`(이 코드와 무관한 독립 구현)로 재고, `ProbeDurationMS` 결과와 100ms 안에서 맞는지 대조한다 |
| 6 | `media/internal/fmp4meta/probe_test.go` + `testdata/`<br>(닻: `채취: MediaMTX`) | 픽스처 3종이 **1.19.3이 `recordPath`로 직접 떨어뜨린 원본**이다(1.20.1 산출물과 박스 배치 동일 확인 — 2026-08-28, 1.21.1 산출물도 동일 — 2026-10-05, 재채취 불요). 검증 대상이 MediaMTX의 박스 배치라서 재인코딩본으로는 대체할 수 없다 | 5번 대조가 어긋났을 때만 손댄다 — 새 버전 산출물로 픽스처를 다시 채취하고 오라클(ffprobe 실측값)도 함께 갱신한다. 어긋나지 않으면 그대로 둔다 |
| 7 | `media/internal/recording/settle.go`<br>(닻: `업스트림 기본값 recordPartDuration`) | 업스트림 기본값 `recordPartDuration` = 1s. 쓰기와 쓰기 사이 공백을 "다 썼다"로 오해하지 않으려면 공백의 2배는 기다려야 하므로, 그 2배가 `SEGMENT_SETTLE_WAIT` 2s의 근거다 | 새 태그의 업스트림 기본 설정 파일(`mediamtx.yml`)에서 `recordPartDuration` 값을 확인한다. **1s보다 커졌으면 `SEGMENT_SETTLE_WAIT`를 그 2배로 올린다** — 안 올리면 절반짜리 파일을 완성으로 판정한다 |
| 8 | `media/Dockerfile.mtxhook`<br>(닻: `USER 10002:10002`) | **MediaMTX가 루트FS·CWD에 쓰지 않는다.** 비root(UID 10002)로 도니까 쓰려는 순간 실패한다. 우리 설정은 `moq: no`라 참이지만, `moq`/`webrtc`/`rtsps`를 켜며 `auto.key`류 자동 생성 경로를 쓰면 비root에서 기동 자체가 실패한다(POK-79 실험 E7) | 새 버전 **기본 설정**에서 CWD에 파일을 쓰는 지점이 늘었는지 본다. 실물 확인은 기동 로그에 `failed to save`·`permission denied`가 뜨는지 — `docker compose logs media \| grep -iE 'permission denied\|failed to save'`가 0건이어야 한다 |
| 9 | HLS 서빙 경계<br>(닻: `302 cookieCheck`) | **HLS 첫 요청은 302 `cookieCheck` 리다이렉트를 돈다 — 1.19.3에도 있던 동작이며 이번(1.20.1 전환)에 처음 체크리스트화했다**(우리 2026-08-17 결정 문서·v1.19.3 원문 대조). **버전별 델타**: 1.20.1은 plain HTTP에서 일반 쿠키를 중단하고 **Partitioned 쿠키(HTTPS 전용)로 통합**, HTTP에선 쿠키 미회신 시 **`?session=` 쿼리로 폴백**(만료 시 401 — 실측)·iOS UA 400 분기 제거. 1.21.1은 **세션 수명을 서버가 관리**한다 — 세션이 살아 있는데 muxer(한 경로의 HLS 재생목록과 세그먼트를 만드는 MediaMTX 안의 부품 — 송출이 끊기면 닫힌다)가 닫히면 30초 동안 404, 그 뒤와 세션이 없을 때는 401(본문 `session not found`), CORS(다른 출처(Origin)의 웹 페이지가 응답을 읽어도 되는지 서버가 헤더로 알려 주는 브라우저 규칙)는 `*` 대신 요청 Origin을 돌려주고 `Vary: Origin`을 붙인다(실측 — 아래 1.21.1 재검증 기록). CDN(Bearer) 경로는 302를 우회한다 | CDN·서명 쿠키·매니페스트 TTL 경계에서 실측 — 캐시가 302·Set-Cookie를 어떻게 다루는지, **HTTP 오리진에서 세션 쿼리가 캐시 키를 오염시키는지**. 이 행은 버전 특정이 아니라 **서빙 경계 상시 리스크**다 — 롤백해도 걷어내지 않고 델타 서술만 그 버전 값으로 갱신한다. ADR-050 선결 A |

**1.19.3 → 1.20.1 재검증 기록 (2026-08-28)**: 기존 8곳 전항 확인 + 9번 신설(체크리스트화) — ①`all_others` 송출로 훅 3종 실발화(스풀 기록) ②`shellquote.Split` 후 `expandEnv` 순서 불변(`cmd_os.go:16→22`) ③④`runOnReady` deprecated 별칭 생존(`conf/path.go:354`)·기동 WARN 0 ⑤1.20.1 실산출물 mvhd 4.117s = 최대 트랙 길이 일치 ⑥박스 배치 동일(ftyp·moov·(moof·mdat)×N — 픽스처 유지) ⑦`recordPartDuration` 기본 1s 불변(`conf/path.go:376`) ⑧UID 10002로 녹화 기록·권한 오류 0(+상류 read-only FS 복원 커밋 `c9f003f`). 부수: 상류 `a56c635`가 우리가 겪은 설정 API 데드락을 해소.

**준버전업 재확인 기록 (2026-09-03, `.1`→`.2`, 상류 베이스 e175003→f82bc23 13커밋)**: 이동 구간이 닿는 전제만 표적 재확인 — ②`shellquote.Split`(16행) 후 조각별 `expandEnv`(21~22행) 순서 불변, 파일은 #6156에서 `cmd_os.go`→`cmd_os_other.go`로 개명 ③④훅 이름 5종 존치·`runOnReady` 별칭 매핑 유지(`conf/path.go:351~364, 967~972`), 기동 WARN 0 ⑦`recordPartDuration` 기본값 1s(소스 `path.go:376` + 기동 후 `pathdefaults/get` 실측) ⑤⑥면제 — `internal/recorder` 이동 구간 diff 0(`record`·`formatprocessor` 경로는 존재하지 않음) ⑧`.2` compose 기동 로그에 권한 오류 0 · 추가로 `authInternalUsers` 기본 두 항목이 새 베이스 샘플과 동일(정규화 YAML 대조), playback 기본 비활성(`global/get` 실측). 스모크: 훅 3종 실발화(online 1·segcomplete 4·offline 1), #6155 신동작 확인(훅 비0 종료가 `runOnOnline command exited: command exited with code 1`로 보고), 익명 read 302 cookieCheck→200. 발행 이미지 격리 rig(GHCR digest 기준): 8축 전부 통과(유휴 무녹화·RTSP 송출 녹화 시작·동결·재개·정적 소스·오프라인 PATCH 동결·런타임 등록·SRT), 판정 대상 산출물 13개 전부 640x360(슬레이트 1920x1080 0건 — 일부러 슬레이트를 녹화하는 대조군 `pub1`은 지문 대상에서 제외), 기동 로그 `v1.20.1-pokeclip.2`. 포크 전량 테스트는 66패키지 중 63 통과, 3패키지(webrtc ICE 후보·mpegts/rtp 멀티캐스트 UDP)는 우리 커밋 없는 상류 원본 트리에서도 동일 실패 — 호스트 네트워크 환경 의존으로 제외.

**1.20.1 → 1.21.1 재검증 기록 (2026-10-05)**: 진짜 버전업이라 9곳을 전수 확인했다. 측정 이미지는
`ghcr.io/xodbs1021/mediamtx:v1.21.1-pokeclip.1@sha256:3078e9d7…8a32`(인덱스 digest
— amd64 `2f14a8b3…` · arm64 `96c37c0a…`, 런타임 실측은 arm64)이고, 트리는 상류
v1.21.1(`04825598`) + 수정 4건, 실행 파일에 박힌 판은 mediacommon v2.9.5 · go-mp4 v1.7.3이다
(`go version -m`). 소스 대조는 옛 베이스 `f82bc23` → 라인 tip `62a9683d`로 했다.
①`conf.go:1136`이 `pathDefaults`를 먼저 복사(`path.go:434-437`) — `all_others` 경로 송출
3회에 훅 3종 실발화(스풀 online · segcomplete · offline, 사이드카 `reason` 5 전량)
②`cmd_os_other.go:16` `shellquote.Split` → `:22` 조각별 `expandEnv` 순서 불변
(수정 6이 더한 줄은 31–32 · 46행) ③④`runOnReady` deprecated 별칭 · `RunOnAvailable` 매핑
유지(`conf/path.go:362 · 980`), 기동 로그 deprecated · unknown WARN 0
⑤`internal/recorder` 이동 구간 diff 0 — 실측 녹화 39개에서 ffprobe 트랙 최대 길이와 장부
`duration_ms`(go-mp4 v1.7.3) 차 최대 1.0ms ⑥같은 39개의 배치가 ftyp · moov · (moof ·
mdat)×N(픽스처 유지) ⑦`recordPartDuration` 기본 1s(`path.go:387` + `pathdefaults/get`
실측) ⑧UID 10002로 녹화(파일 소유 10002:10002) · 권한 오류 0 ⑨그대로인 것은 302 `cookieCheck` · 쿠키
속성(`HttpOnly; Secure; SameSite=None; Partitioned`) · 쿠키 없는 `?session=` 폴백 200이고,
바뀐 것은 델타 4가지다 — 세션 수명을 서버가 관리(bluenviron/mediamtx#6239), 살아 있는 세션에서 muxer가 닫히면
30초 동안 404(bluenviron/mediamtx#6240 — 실측: 송출이 끊기고 muxer가 닫힌 뒤 30초 404, 이어서 401),
세션 없음 401의 본문이 `session not found`(bluenviron/mediamtx#6241), CORS가 요청 Origin을
돌려주고 `Vary: Origin`을 붙임(bluenviron/mediamtx#6200 · bluenviron/mediamtx#6201).
무활동 만료 검사는 10초 주기라 만료는 마지막 요청 뒤 30–40초 사이다(실측: 35초 쉰 세션 200, 45초 쉰 세션 401). 부수:
`authInternalUsers` 기본 두 항목이 새 베이스 샘플과 주석까지 같다(정규화 YAML 대조).
Control API의 교차 출처 허용 기본값 `apiAllowOrigins`가 `['*']`에서 `[]`로
바뀌었다(bluenviron/mediamtx#6165). 제품 설정은 이 키를 적지 않아 다른 출처의 브라우저
페이지가 API 응답을 읽지 못한다 — 실측: Origin을 붙인 `/v3/paths/list`에 `.2`는
`Access-Control-Allow-Origin: *`를 붙이고 새 이미지는 붙이지 않는다. 사이드카처럼
서버에서 부르는 호출은 영향이 없다. LL-HLS
표지(`CAN-BLOCK-RELOAD` · `EXT-X-PART` · GAP 채움을 뺀 실제 세그먼트 3개 이상
`#EXTINF:4.00000`) 성립, `PART-HOLD-BACK=1.25000`(기록만). 사이드카 관측(`/v3/paths/list`)
형상 그대로 · `mtxstate_poll_failed` 0. 재접속 합성에서 `hook_break_consumed`의 seq가
`is_discontinuity=true` 행과 같다. 녹화기 WAR
`sample of track 2 received too late, discarding`이 송출 연결마다 1줄 나오는데, 같은 송출에서
`v1.20.1-pokeclip.2`도 같은 줄을 낸다(A/B 실측 — 소스도 같은 줄).

**표적 재확인 기록 (2026-10-12, `v1.21.1-pokeclip.1`→`.2`, 상류 베이스 `04825598` 그대로)**:
베이스가 그대로라 준버전업이 아니다. 라인에 더한 커밋은 셋이다 — 체리픽 `33bc311`(상류
`72e809b6`) · U(사용자별 SRT passphrase) `b9735847` · `POKECLIP.md` `ff1f75bb`. 그
diff가 전제 9곳에 닿는지 가르고, 닿는 파일이 있는 전제는 실측으로 다시 봤다. 측정 이미지는
`ghcr.io/xodbs1021/mediamtx:v1.21.1-pokeclip.2@sha256:8b5318ce…4dc8`(인덱스 digest
— amd64 `c6d01969…` · arm64 `8fb08d4f…`, 런타임 실측은 arm64)이다. 태그 대상 커밋
`ff1f75bb`와 digest는 2026-10-08 독립 확인(`imagetools inspect` · 익명 pull ·
`ls-remote`)과 같다. ②⑤⑥⑨는 diff가 닿지 않는다 — `internal/externalcmd` ·
`internal/recorder` · `internal/servers/hls` 변경 0파일. ①③④⑦은 닻 줄이 글자
그대로이고 줄 번호만 밀렸다 — ①`newPath(&conf.PathDefaults, …)` `conf.go:1136→1143`
③④`runOnReady` 매핑 `conf/path.go:980→981` ⑦기본값 `path.go:387→388`. 실측은 설정에
이름 없는 경로로 40초 송출했다(`all_others`로 붙음). ①훅 3종 스풀 online 1 ·
segcomplete 10 · offline 1 ③④기동 로그 deprecated · unknown WARN 0
⑦`pathdefaults/get`의 `recordPartDuration` 1s ⑧녹화 10개 모두 10002:10002 · 권한
오류 0 ⑨302 `cookieCheck` → 200. 기동 첫 줄은 `MediaMTX v1.21.1-pokeclip.2`다. LL-HLS
표지는 `index.m3u8`이 가리키는 미디어 재생목록에서 성립한다 — `CAN-BLOCK-RELOAD` ·
`EXT-X-PART` · GAP 채움을 뺀 실제 세그먼트 중 `#EXTINF:4.00000` 5개,
`PART-HOLD-BACK=1.25000`(기록만). 녹화기 WAR `sample of track 2 received too late,
discarding`이 송출 연결마다 1줄인 것은 그대로다 — 같은 송출에서 `.1`도 같은 줄을
낸다(A/B 실측). 부수: `.2`의 샘플 설정은 `authInternalUsers` 첫 항목에 빈
`srtPassphrase:`를 더했다. 제품 설정에는 이 키가 없고, 빈 값은 지정하지 않은 것과
같다(실측 `global/get` — 세 항목 모두 빈 값).
출처 확인(아래 「새 이미지를 만들 때」)과 ③ 재포장 핀 대조(「포크 태그를 올릴 때」)도 다시
했다 — 두 아치 `/mediamtx`의 `vcs.revision`과 발행 실행 `headSha`가 태그 대상 커밋
`ff1f75bb`이고 `vcs.modified=false`이며, 박힌 `mediacommon/v2` v2.9.5 ·
`go-mp4` v1.7.3이 `media/go.mod`와 같다. 픽스처 재채취는 면제다 — 두 태그 사이
`internal/recorder` · `internal/formatprocessor` · `go.mod` 변경 0파일.

전제는 아니지만 **버전 문자열을 그대로 적어 둔 곳**이 더 있다. 함께 고친다 —
[`docs/dev-environment.md`](../docs/dev-environment.md)의 서비스 표,
[`Dockerfile.mtxhook`](Dockerfile.mtxhook) 주석의 상류 베이스 서술.
[`infra/dev-media/compose.yml`](../infra/dev-media/compose.yml)의 `image` 줄
(dev-media 서버 핀)은 따로 판단한다 — 서버가 필요로 할 때 서버 기준으로 올리고, 제품
`FROM`과 같을 필요는 없다(ADR-040 2026-10-12 추기).

### 이미지 출처에 묶인 전제 — 우리 포크 라인(`pokeclip`)

위 9곳이 **MediaMTX 버전**에 묶인 전제라면, 이것은 **어느 이미지냐**에 묶인 전제다.

`FROM`이 가리키는 것은 상류 공식 이미지가 아니라 우리 포크 빌드
`ghcr.io/xodbs1021/mediamtx`다. 상류 v1.21.1(`04825598`) 위에 수정을 얹었다 —
**상류에서 심사 중이거나 아직 게시 전인 우리 수정**과, 상류가 다른 수정(bluenviron/mediamtx#6326 —
릴리스 전)으로 대체한 0b와, 상류에 머지됐지만 라인의 베이스(v1.21.1)에는
없는 체리픽이다. 결정 근거는 ADR-050 선결 B(ⓑ 자체 빌드 선행)다. 예전 포크가 싣던
슬레이트 무녹화 스위치 `alwaysAvailableRecorded`(bluenviron/mediamtx#6182)는 v1.21.0에
머지돼 라인에서 뺐다 — 이제 상류 공식 기능이다.

수정은 아래 표와 같다. 첫 열의 번호는 우리 상류 기여 트랙의 번호라 위 전제 9곳의 번호와 다르다 —
전제와 섞일 수 있는 자리에서는 "수정 9"처럼 적는다.

| 수정 | 상류 | 제품에서 하는 일 | 상류 공식 이미지로 되돌리면 |
|---|---|---|---|
| 0b(가드 v2) — 설정 리로드와 훅 로깅 사이의 데이터 경합 수정 | bluenviron/mediamtx#6206 (닫힘(2026-10-11) — 상류는 bluenviron/mediamtx#6326으로 다르게 고침(릴리스 전)) | 로거를 다시 만드는 리로드(로그 설정 변경) 때 돌던 훅이 끝나기를 기다린 뒤 로거를 다시 만든다. 그때 `waiting for running hooks` INF 한 줄이 남는다. 제품 훅 `mtxhookwrite`는 잠금 대기 상한이 200ms라 그 대기는 짧다 | 그 리로드에서 훅 로깅과의 데이터 경합이 돌아온다(상류 메인테이너가 버그로 인정 — 2026-09-20) |
| 9 — 모든 훅 명령의 실패를 로그에 남긴다 | bluenviron/mediamtx#6259 (심사 중) | `runOnOffline`·`runOnRecordSegmentComplete`가 실패하면 `… command exited: …` 한 줄이 남는다. `runOnOnline`은 상류 1.21.1도 남긴다 | 두 훅의 실패가 다시 **오류도 로그도 없이** 지나간다 |
| 10 — `CertLoader.Close()`가 `watch()`의 끝을 기다린다 | bluenviron/mediamtx#6261 (심사 중) | 없음 — 제품·dev-media는 TLS·MoQ를 켜지 않아 CertLoader를 만들지 않는다 | 차이 없음 |
| 6 — 훅 출력을 서버 로그로 돌리는 설정 | 상류 미게시 | 없음 — 기본 꺼짐이고 제품 설정에 키가 없다. 켜기는 이 이미지 교체와 섞지 않고 후속 PR에서 한다(아래 「절차」 1) | 키가 없는 동안은 차이 없음 |
| 체리픽 — 설정 PATCH가 `<redacted>`(설정 조회 API가 비밀번호 자리에 돌려주는 가림 문자열)를 비밀번호로 저장하지 않는다 | bluenviron/mediamtx#6292 (상류 머지 — v1.21.2에 들어갔다. 라인의 베이스가 v1.21.1이라 싣는다) | 없음 — 제품·dev-media 어디도 설정 PATCH(`/v3/config/global/patch`)를 부르지 않는다. 아래 U가 이 수정이 만든 `PatchGlobal` 블록을 고치므로 U와 함께 싣는다 | 차이 없음 |
| U(사용자별 SRT passphrase 트랙) — 인증 사용자의 `srtPassphrase`. 값이 있으면 경로 passphrase를 대신하고, HTTP 인증이면 2xx 응답 본문이 값을 준다 | bluenviron/mediamtx#6306 (심사 중) | 없음 — 제품·dev-media 설정의 인증 사용자에 이 키가 없어 지금처럼 경로 값을 쓴다(제품 설정 실측: 세 항목 모두 빈 값). 계정별 방송을 여는 dev-media 후속 PR이 쓴다 | 지금 설정에서는 차이 없음. 그 후속 PR 뒤에는 사용자 값이 사라져 모든 SRT 송출이 경로 잠금값(사용자 값 없이는 송출이 열리지 않게 거는 경로 passphrase)에 막힌다 — 안전한 실패지만 송출이 멈춘다 |

| 전제 | 깨지면 무슨 일이 나나 | 어떻게 재확인하는가 |
|---|---|---|
| `FROM`이 우리 포크 빌드를 가리킨다<br>(닻: `pokeclip`) | 공식 이미지로 되돌리면 위 표 넷째 열의 손실이 생긴다 — 수정 9의 손실은 **오류도 로그도 없이** 일어난다 | 기동 로그 첫 줄의 버전 문자열이 `v…-pokeclip.N`인지 본다(`docker compose logs media \| head -1`). 기계 방어는 `TestPinnedMediaMTXDigestMatchesDockerfile` |

**핀은 태그와 digest를 함께 적는다.** 태그(`v<상류>-pokeclip.<N>`)는 사람이 읽는 이름이고,
digest는 불변 좌표다 — 같은 태그를 다시 밀어도 가리키는 이미지가 바뀌지 않는다. 그래서
버전 대조 테스트도 둘 다 본다(`pinnedMediaMTXTag`·`pinnedMediaMTXDigest`).

**새 이미지를 만들 때**: `xodbs1021/mediamtx`의 `pokeclip` 라인에 커밋 → `*-pokeclip.*` 태그를
민다 → `pokeclip-image` 워크플로가 멀티아치 이미지를 GHCR에 올리고 **실행 요약에 `FROM …@sha256:`
한 줄을 찍는다** → 출처를 확인한다 — 그 실행의 `headSha`와 이미지에서 꺼낸 `/mediamtx`(플랫폼마다)의
`vcs.revision`(`go version -m`)이 검수한 라인 tip과 같고 `vcs.modified=false`여야 한다 →
그 값을 `Dockerfile.mtxhook`과 테스트 상수 2개에 옮긴다. dev-media(서버) 핀인
`infra/dev-media/compose.yml`의 `image` 줄은 서버가 필요로 할 때 서버 기준으로 따로 올린다
(ADR-040 2026-10-12 추기). 이미지 생성은
자동이고 제품 반영은 수동이다 — 핀 교체가 이 절의 재확인을 동반해야 하기 때문이다.

**되감기 ③ 재포장 핀도 같은 때 대조한다.** 재포장 라이브러리(`mediacommon/v2`)의 판이 새 포크
실행 파일에 박힌 판과 같아야 하고, 녹화기의 mtxi 형식이 그대로여야 한다. 절차는 「되감기 M4 ⓐ
이관 기록」 절의 「포크 태그를 올릴 때」에 있다.

**상류 main에만 일부 머지되어** 그 수정을 라인에서 빼려면, 먼저 수정이 포함된 상류 커밋으로
베이스를 옮기고 이동 구간이 닿는 전제를 표적 재확인한 뒤(아래 준버전업) 중복 수정을 빼서
`.N`을 올린다(베이스를 유지하면 수정도 유지한다). 그 수정을 포함한 새 상류 릴리스로 옮기면
`v<새 버전>-pokeclip.1`로 발행하고, `upstreamBaseVersion`을 갱신하며 전제 9곳을 전수
재확인한다.
**위 표의 모든 행이 상류 릴리스에 들어가면**(0b처럼 상류가 다른 수정으로 대체한 행은 그 대체
수정 bluenviron/mediamtx#6326이 들어가면) 포크가 필요 없어진다. 표에는 U도 있다 — U 없이 공식
이미지로 되돌리면 U를 쓰는 dev-media 계정별 방송(후속 PR) 뒤에는 모든 SRT 송출이 경로
잠금값에 막힌다(위 U 행). 그때 **포크 전용 장치를 전부
걷어낸다** — 빠뜨리면 공식 태그에서 포크 전용 단언이 남아 빨간불이 된다. 정리 목록은
[`version_contract_test.go`](internal/mtxhook/version_contract_test.go)의 `forkPinGuide`에 번호로
적혀 있다(FROM 복귀 — 공식 이미지도 태그 + digest, dev-media `image` 줄은 서버가 기대는
수정(특히 U)을 기준으로 따로 판단 · `mediaMTXImage` 복귀 · 태그 상수 정리 ·
digest 상수는 공식 이미지 digest로 · 포크 전용 테스트 2종 삭제 · 남는 `forkPinGuide` 참조를
`versionUpgradeGuide`로 바꾼 뒤 `forkPinGuide` 삭제 · 이 절 삭제).

**`v1.21.1-pokeclip.2` 핀을 되돌릴 때 (2026-10-12 기준)**: 이 핀을 올린 PR의 머지 커밋을
`git revert -m 1 <머지 커밋>`으로 되돌리면 제품 `FROM` · dev-media `image` 줄 · 테스트 상수가
함께 `v1.21.1-pokeclip.1`로 돌아간다. U를 쓰는 후속 PR(dev-media 계정별 방송)이 머지돼 있으면
**그 PR을 먼저 revert한다** — 머지의 역순(위 「되돌리기와 긴급 정지」의 원칙과 같다). `.1`에는
U가 없어 그 구성에서 모든 SRT 송출이 경로 잠금값에 막힌다(안전한 실패지만 송출이 멈춘다).
데모 박스(dev-media 서버)는 develop을 따라가지 않아 이 revert로 바뀌지 않는다 — 박스는 따로 되돌린다.

**이 핀을 되돌릴 때 (2026-10-06 · 3K-PokeClip/pokeclip-mono#217 기준)**:
이 이미지 교체 PR의 머지 커밋을 `git revert -m 1 <머지 커밋>`으로
되돌리면 제품 `FROM`이 `v1.20.1-pokeclip.2` digest로, 재포장 핀이 v2.9.3으로, 재생 픽스처가
옛 것으로, dev-media `image` 줄이 `bluenviron/mediamtx:1.21.1`로 함께 돌아간다. 팀은
`git pull && docker compose up -d --build`로 반영한다. 순서가 둘이다.

1.  수정 6 설정을 켜는 후속 PR이 머지돼 있으면 **그 PR을 먼저 revert한다** — 머지의 역순(위
    「되돌리기와 긴급 정지」의 원칙과 같다). 수정 6이 없는 이미지(스톡 또는 이전 포크 태그
    `v1.20.1-pokeclip.*`)는 그 키를 모르는 필드로 보고 기동을 거부한다
    (`ERR: json: unknown field …`로 종료 1 — 2026-10-05 `v1.20.1-pokeclip.2` 실측).
1.  팀 스택을 다시 띄우기 전에 옛 이미지로 설정 검사를 먼저 한다. `--validate-conf`를 아는
    이미지(스톡 1.21.1 등)는 아래가 `configuration file is valid`로 끝나야 한다.

    ```bash
    docker run --rm -v "$PWD/infra/compose/mediamtx.yml:/mediamtx.yml:ro" \
      <옛 이미지> --validate-conf=/mediamtx.yml
    ```

    `v1.20.1-pokeclip.*`는 이 플래그를 모른다(`unknown flag --validate-conf`로 종료 80 — 같은
    날 실측). 그 이미지는 같은 마운트로 단독 기동해 `configuration loaded`가 찍히고 죽지
    않는지 본다. 이 PR을 되돌리면 가는 `v1.20.1-pokeclip.2`는 아래처럼 본다(포트를 열지 않고
    띄웠다가 지운다).

    ```bash
    docker run -d --name mtx-revert-check \
      -v "$PWD/infra/compose/mediamtx.yml:/mediamtx.yml:ro" \
      ghcr.io/xodbs1021/mediamtx:v1.20.1-pokeclip.2@sha256:704e9e2c7c3248953294ae80e0b0e1382d168b7c26d7999d7560e2f77aac2556
    sleep 5
    docker logs mtx-revert-check 2>&1 | head -3  # configuration loaded from /mediamtx.yml
    docker inspect -f '{{.State.Status}}' mtx-revert-check  # running
    docker rm -f mtx-revert-check
    ```

되돌린 뒤의 상태에서 알아 둘 것이 둘 있다.

*   되돌린 `v1.20.1-pokeclip.2`에도 위 표의 수정이 없다 — 위 표 넷째 열의 손실이 그대로 온다(수정 9는
    오류도 로그도 없이 사라진다).
*   데모 박스(dev-media 서버)는 develop을 따라가지 않아 이 revert로 바뀌지 않는다 — 박스는
    따로 되돌린다.

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

## DB 연결 풀 관측 (POK-388)

사이드카의 인덱서 · 발행자 풀과 janitor 풀은 1분마다 풀 통계를 보고, 버린
연결이 늘면 WARN 으로 낸다. 지표의 뜻은 「버려졌거나 맺지 못한 연결」이다. 질의
도중 기한이 끝나 닫힌 연결, 트랜잭션이 열린 채 반납된 연결, 점검에 실패한
연결, 연결 시도 실패(DB 장애 · IAM 토큰 실패 포함)를 모두 센다. 수명 · 유휴
한도에 따른 교체는 세지 않는다.
관측은 풀 통계만 읽고 DB 에 아무것도 보내지 않는다.

- `pg_pool_conn_discarded`(WARN · `pool`=indexer · publisher · janitor):
  버린 연결이 늘어난 주기마다 value=1(`discarded` 구간 · `discarded_total`
  누적 · `new_conns` 구간), 켜진 뒤 처음 늘지 않은 주기에 value=0 을 한 번
  낸다. 기동하면 value=0 reason=startup 을 먼저 낸다. 셋 다 WARN 이라
  `LOG_LEVEL=warn` 에서도 회복이 보인다.
- `pg_pool_stats`(DEBUG): 매 주기 풀 통계 한 줄. `skipped=1` 은 그 주기를
  세지 않았다는 뜻이다.
- janitor 의 기동 줄은 게이지 7종 시작 덤프보다 먼저 나오며 7종 계약 밖이다.
- 한계: 연결을 세우는 중이면 그 주기를 건너뛴다. DB 장애로 연결 시도가
  이어지면 건너뜀이 계속되다가 장애가 끝난 뒤 한꺼번에 센다. 다만 연결
  거부형 장애는 시도가 바로 끝나 주기마다 그 자리에서 센다. 기동 때 세 번
  더 읽어도 연결을 세우는 중이면, 그 뒤 처음 건너뛰지 않은 주기가 기준점이
  되어 그 사이 폐기는 세지 않는다. 시한 정렬을 판단할 때는 DB 장애 구간을
  빼고 읽는다.
- 스위치는 없다. 끄려면 이 변경을 되돌린다(revert). `LOG_LEVEL` 로 거르면
  같은 등급의 다른 게이지도 함께 사라지므로 쓰지 않는다
  (`janitor_backlog_capped` 는 통째로, `rewind_cutoff_absent` 는 들어올 때
  줄과 해소 줄이).

## 로컬 ② 정리기(janitor) 운영 (POK-170)

janitor 는 업로드가 끝난 ② 로컬 녹화 파일을 지우고 장부 행의 `local_path` 를
NULL 로 바꾼다. 행은 남는다. 삭제 조건은 인덱스 불변식 2(그레이스 8분 경과 ·
② uploaded · ③ 컷오프 인지 술어)이고 조각마다 따로 판정한다. 회차의 첫
조각과 init 재수거 원천은 보호 규칙으로 남긴다.

- 실행 형태: 사이드카와 **같은 이미지**의 다른 명령(`/usr/local/bin/janitor`),
  compose 서비스 `janitor`. 계정 `10002:10002`(MediaMTX 와 같은 소유자) ·
  recordings 에 쓰기로 붙는 것은 media(MediaMTX)와 janitor · 사이드카는 `:ro` 그대로.
- janitor 는 업로드가 끝난 조각을 MediaMTX 의 보존 기한(`recordDeleteAfter` —
  설정에 없으면 기본 24시간)보다 먼저 지우는 쪽이다. 보존 기한 삭제는 MediaMTX
  몫이라 janitor 를 꺼도 계속된다.
- 삭제 순서: 행 잠금(`FOR UPDATE NOWAIT`) → 파일 삭제 → `local_path=NULL` →
  COMMIT. 잠긴 행은 건너뛰고 다음 주기에 다시 본다.
- 삭제마다 `segment_local_deleted` INFO 한 줄(stream_id · seq · path ·
  axis=archive · outcome)이 남는다. 부채를 갚은 뒤 행이 아직 있는지 확인하지
  못했으면(잠김 · 오류) `row_probe=unknown` 이 붙는다 — 그 행은 이미 지워졌을
  수도 있다. 주기 요약은 `janitor_cycle_summary`.

### janitor 끄고 켜는 법

정본 끄기는 스위치다. 저장소 루트 `.env` 에 빈 값을 적고 서비스만 다시
올린다.

```sh
# .env
JANITOR_ENABLED=
```

```sh
docker compose up -d --no-deps --no-build janitor
```

- 꺼지면 `janitor_disabled` WARN 한 줄을 내고 컨테이너는 떠 있는 채 대기한다.
  DB · 파일은 건드리지 않는다. 다음 `docker compose up -d` 뒤에도 꺼져 있다.
- 다시 켜려면 그 줄을 지우거나 주석으로 돌리고 같은 명령을 다시 쓴다
  (미설정이면 compose 가 `true` 로 채운다). `false` · `0` 도 끔이다.
- `docker compose stop janitor` 는 **일시 정지일 뿐**이다. 다음 `up -d` 에서
  다시 켜진다.
- 확인한 실행 기록: 전용 compose 프로젝트에서 janitor 만 띄워
  빈 값 → `janitor_disabled` · 미설정 → `janitor_started` · 다시 빈 값 →
  `janitor_disabled` 를 봤다(2026-10-20, POK-371 실험 기록
  `readme-switch.txt`).

### 스키마 이행 배포 때 janitor 끄기

사이드카 기동마다 도는 `EnsureSchema` 가 컬럼 · 제약을 실제로 더하는
배포(스키마 이행)에서는, 그 잠금과 janitor 의 행 잠금이 드물게 교착할 수
있다. 교착에서 사이드카 쪽이 희생되면 사이드카가 재기동해 다시 시도한다.
피하려면 이렇게 배포한다(한계 L26).

1. `.env` 에 `JANITOR_ENABLED=` 를 적고
   `docker compose up -d --no-deps --no-build janitor`.
   로그에 `janitor_disabled` 가 나온 것을 본다.
1. 사이드카를 새 판으로 올린다. 두 서비스는 같은 Dockerfile 이지만 이미지가
   따로라, janitor 이미지도 이 단계에서 함께 빌드해 둔다(마지막 단계의
   `--no-build` 는 이미 빌드한 이미지를 쓴다).

    ```sh
    docker compose build segment-indexer janitor
    STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    docker compose up -d --no-deps --no-build segment-indexer
    ```

   빌드만으로는 떠 있는 사이드카가 바뀌지 않는다 — `up -d` 가 새 이미지로
   사이드카를 다시 만들어야 기동 때 `EnsureSchema` 가 돈다. `--no-deps` 는
   postgres · mediamtx 를 다시 만들지 않게 한다.
1. 이행 성공을 확인한다 — **이번 기동 뒤** 사이드카 로그에 `schema_ensured`
   INFO 가 있어야 한다(`ENSURE_SCHEMA=true` 인 로컬 compose 기준). 옛 기동의
   줄에 속지 않도록 위에서 적어 둔 기동 시각 이후만 본다.

    ```sh
    docker compose logs --since "$STARTED" segment-indexer | grep schema_ensured
    ```

1. 확인한 뒤에만 `.env` 의 그 줄을 지우고
   `docker compose up -d --no-deps --no-build janitor`.
   로그에 `janitor_started` 가 나온다.

이행이 없는 평소 배포에는 끌 필요가 없다.

### janitor 게이지 7종

게이지는 켜질 때(value=1)와 꺼질 때(value=0) 같은 등급으로 한 줄씩 낸다.
기동하면 7종 모두 `value=0 reason=startup` 을 먼저 낸다(이 run_id 이전 값을
모두 0 으로 본다는 뜻). `LOG_LEVEL=warn` 으로 모아도 켜짐 · 꺼짐이 둘 다
보인다.

| 게이지 | 등급 | 켜짐 | 먼저 볼 것 |
|---|---|---|---|
| `janitor_delete_failing` | ERROR | 삭제 실패(권한 · 읽기 전용 · 경로 거부)로 건너뛴 행이 있음 — items · sample | sample 행의 파일 소유 · 마운트 rw · `janitor_path_rejected` |
| `janitor_cycle_failing` | ERROR | 주기가 DB 오류로 중단됨 — reason · SQLSTATE · stage. 종료 신호로 주기가 끊긴 것은 켜지 않고 `janitor_cycle_canceled` INFO 한 줄만 남는다 | DB 연결 · `statement_timeout` · 잠금 대기 |
| `janitor_cutoff_invalid` | ERROR | 가리키는 행이 없는 컷오프 — 그 스트림 삭제 정지(stream_id 별) | `stream_cutoffs` 와 `stream_segments` 대조 |
| `janitor_backlog_capped` | WARN | 한 주기 상한(`JANITOR_MAX_PER_CYCLE`)에 닿고도 남은 후보가 있음 | 일시적이면 정상. 계속되면 상한 · 주기 조정 |
| `janitor_volume_suspect` | ERROR | 지웠어야 할 파일이 이미 없는 젊은 행이 3개 이상 — 다른 볼륨이 붙었을 수 있음 | 마운트된 볼륨이 MediaMTX 와 같은지 · sample 경로 |
| `janitor_fs_suspended` | ERROR | 파일 삭제가 멈춰(stall) 파일 작업을 보류 중 — hold · stall_streak | 녹화 디스크 · 마운트 상태(`fs_op_stalled`) |
| `janitor_reclaim_blocked` | ERROR | 삭제 뒤 장부 갱신을 못 한 행(부채)을 계속 못 갚음 — 새 삭제가 멈춘 상태 | 아래 절 |

PG 가 비정상 종료된 뒤 `janitor_volume_suspect` 가 켜질 수 있다. 비동기
커밋으로 확정된 삭제 기록 몇 건이 사라진 것이다(한계 L31 — 실측 KILL 1회에
5건). 파일은 이미 지워졌고 장부만 경로를 들고 있다. 그 행들이 나이
`JANITOR_RECORD_DELETE_AFTER` − 1h(기본 23h)를 넘기면 janitor 가 스스로
정리하고 게이지가 꺼진다(최대 약 23h).

다만 같은 게이지는 볼륨이 정말 잘못 붙었을 때도 켜진다. 기다리기 전에 둘을
가른다. 둘 다 맞을 때만 L31 로 보고 자동 정리를 기다린다.

1. 세 서비스의 `/recordings` 가 같은 볼륨인지 본다 — 출력된 볼륨 이름과
   원본 경로(`Source`)가 셋 다 같아야 한다(파일 수 비교로는 가려지지 않는다).
   바인드 마운트는 이름이 비므로 이름만 보면 둘 다 바인드일 때 같아 보인다 —
   원본 경로까지 대조한다.

    ```sh
    for s in media segment-indexer janitor; do
      printf '%s: ' "$s"
      docker inspect -f '{{range .Mounts}}{{if eq .Destination "/recordings"}}{{.Name}} {{.Source}}{{end}}{{end}}' \
        "$(docker compose ps -q "$s")"
    done
    ```

    떠 있지 않은 서비스는 `docker compose ps -q` 가 비어 그 줄에서
    `invalid container name or ID: value is empty` 오류가 난다 — 그 서비스를
    먼저 띄우고 다시 본다.

1. 게이지 줄의 표본(`sample_stream_id` · `sample_seq`)이 PG 종료 직전
   `segment_local_deleted` 줄(같은 `stream_id` · `seq`)에 있는지 본다 — 있으면
   janitor 가 지운 뒤 기록만 사라진 것(L31)이다.

    ```sh
    docker compose logs janitor | grep -E 'janitor_volume_suspect|segment_local_deleted'
    ```

볼륨 이름이 다르거나 표본이 삭제 로그에 없으면 오연결로 보고 janitor 를 끄고
(위 「끄고 켜는 법」) 마운트를 고친다 — 그대로 두면 나이 기준을 넘긴 행의
경로가 지워질 수 있다(한계 L17).

### janitor_reclaim_blocked 대응

janitor 는 파일을 지운 뒤 장부 갱신에 실패한 행을 「부채」로 들고, 갚기
전에는 새 파일을 지우지 않는다(장부와 파일이 2개 이상 어긋나지 않게).
`janitor_reclaim_blocked` 는 그 부채를 계속 못 갚는다는 뜻이다. 줄에
`kind` · `stream_id` · `seq` · `path` · `failures` 가 실린다.

- `kind=deleted`(10회 연속 실패) — 대개 다른 세션이 그 행을 오래 쥐고 있다.
  그 표를 잠근 세션을 찾는다.

    ```sql
    SELECT a.pid, a.application_name, a.state,
           date_trunc('second', now() - a.xact_start) AS xact_age,
           l.mode, left(a.query, 60) AS query
      FROM pg_locks l JOIN pg_stat_activity a USING (pid)
     WHERE l.relation = 'stream_segments'::regclass
       AND a.pid <> pg_backend_pid()
     ORDER BY a.xact_start;
    ```

    오래된 트랜잭션(`xact_age` 가 큰 세션)이 원인이면 그 작업을 끝낸다.
    풀리면 다음 주기에 부채를 갚고 게이지가 꺼지며 삭제가 다시 돈다.
- `kind=maybe`(3회 연속 stall) — 파일 삭제 자체가 멈춘다. `janitor_fs_suspended`
  · `fs_op_stalled` 와 함께 녹화 디스크를 본다.
- 그동안 디스크는 MediaMTX 의 24시간 정리(`recordDeleteAfter`)가 상한을 지킨다.

### DB 요구 — PostgreSQL 17 이상 · 계정 · TLS

- **PostgreSQL 17 이상**이어야 한다. janitor 는 행 잠금 트랜잭션에
  `transaction_timeout`(17 에서 생김) 8초를 걸어 잠금 보유 시간을 묶는다.
  17 미만이면 `janitor_pg_version_unsupported` ERROR(server_version_num ·
  required=170000)를 내고 종료 코드 1 로 끝난다 — compose 는
  `restart: unless-stopped` 라 기동 거부를 되풀이한다.
- **운영 계정 `media_janitor` 권한**은 이것만 준다. janitor 의 장부 질의가 이
  권한 안에서 도는지는 실 PG 시험이 잰다
  (`internal/index/local_cleanup_role_pg_test.go`).

    ```sql
    GRANT SELECT ON stream_segments, stream_sessions, stream_cutoffs TO media_janitor;
    GRANT SELECT ON stream_published_gaps TO media_janitor;
    GRANT UPDATE (local_path) ON stream_segments TO media_janitor;
    ```

    `stream_published_gaps` SELECT 는 M6 의 ③ 포기 조각 갈래(GAP 원장의
    `vod_abandoned` 행을 읽는다)를 위해 미리 준다.

    계정(`CREATE ROLE media_janitor LOGIN …`)은 DB 관리자가 만들고 비밀번호는
    비밀 관리자(Secrets Manager)에만 둔다(IAM 인증이면 비밀번호 없음) — 문서 ·
    저장소 · compose 파일에 적지 않는다.

    권한은 janitor 를 켜기 **전에** 준다. `UPDATE (local_path)` 가 없으면 행
    잠금(`SELECT … FOR UPDATE NOWAIT`) 단계에서 42501 로 막혀 파일을 지우지 않고
    매 주기 실패한다(`janitor_cycle_failing`). 지운 뒤 장부를 못 고쳐 부채가 되는
    것은 잠금은 되는데 갱신 문장이 그 밖의 열을 건드릴 때다(시험 변이 VT3-3).
- **로컬 compose 의 한계**: 로컬 janitor 는 `POSTGRES_USER`(DB 소유자 계정)로
  붙는다. 최소 권한 경계는 로컬 실행으로는 확인되지 않는다 — 위 시험이 그
  몫이다.
- **운영 DSN 은 `POSTGRES_SSLMODE=verify-full`** 로 두고 CA 번들 경로를
  `POSTGRES_SSLROOTCERT=/etc/pokeclip/rds-ca/global-bundle.pem` 으로 준다.
  이 경로는 이미지에 든 RDS 통합 번들이다(아래 「RDS CA 번들」 절). 서버
  인증서와 호스트 이름까지 검증해야 중간자를 막는다. 경로는 기동 때 확인한다
  — 절대 경로 · 일반 파일 · PEM 인증서 1장 이상이 아니면 기동 오류다.
  `PGSSLROOTCERT` 는 쓰지 않는다. 이것만 있고 `POSTGRES_SSLROOTCERT` 가 비면
  기동 오류다(옛 방식 기록: pgx 가 `PGSSLROOTCERT` 를 읽는다는 2026-10-20
  확인 — 실험 기록 `c21-pgsslrootcert.txt`). CA 를 주면 sslmode 는
  `verify-ca` · `verify-full` 만 받는다(그 밖 값은 기동 오류).
  **`verify-ca` 는 체인만 검증하고 호스트 이름은 보지 않는다 — 같은 CA 의
  다른 서버를 막지 못하므로 운영은 `verify-full`.** RDS 통합 번들은 모든 AWS
  고객의 RDS 가 쓰는 공용 CA 라, `verify-ca` 면 다른 고객의 RDS 인증서도
  통과한다. `verify-ca` 는 운영자가
  체인 검증만을 명시적으로 고른 경우라 허용한다. CA 를 준 `require` 는 pgx
  가 암묵적으로 `verify-ca` 처럼 체인을 검증하지만 값이 검증을 밝히지 않고,
  CA 없는 `require` 는 암호화만 하고 서버 인증서를 검증하지 않는다 — 그래서
  CA 와 함께 주면 거부한다. 기본 `prefer` 는 TLS 를 못 쓰면
  평문으로 내려간다. `require` 이상이면 TLS 를 받지 않는 서버에는 붙지 않고
  기동 오류(`janitor_failed`)로 끝난다(2026-10-20 실행 확인 — 실험 기록
  `c9-sslmode.txt`).
- **`POSTGRES_AUTH=iam`**(RDS IAM 인증)이면 `POSTGRES_PASSWORD` 를 비운다 —
  연결마다 새 IAM 토큰으로 로그인한다. sslmode 는 `verify-full` 만 받고
  `POSTGRES_SSLROOTCERT` 는 필수다.
- **password 로 되돌리기**: 코드 되돌림과 운영 인증 전환은 다르다.
  `POSTGRES_AUTH` 를 비우는 것만으로는 운영 계정이 비밀번호로 붙지 않는다 —
  `rds_iam` 을 가진 계정은 IAM 인증이 비밀번호보다 우선한다. 운영 전환에는
  계정 비밀번호 설정 · `rds_iam` 회수 · 비밀 그릇(Secrets Manager) · 그 읽기
  정책이 함께 필요하다(엣지 설계 r7.5.8 3-6절 「password 대비 갈래」 —
  2026-10-22 원문 대조).

### RDS CA 번들

`POSTGRES_SSLROOTCERT` 가 가리킬 CA 번들을 이미지에 싣는다. 사이드카와
janitor 가 같은 이미지라 둘 다 쓴다.

| 항목 | 값 |
|---|---|
| 저장소 파일 | `media/certs/rds-global-bundle.pem` |
| 이미지 안 경로 | `/etc/pokeclip/rds-ca/global-bundle.pem`(root 소유 0644 · 상위 디렉터리 755 — Dockerfile 이 디렉터리를 먼저 만들고 `COPY --chmod=0644` 로 파일 권한을 고정한다, 실행 계정 10001 이 읽는다) |
| 출처 | https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem (AWS 문서 「Using SSL/TLS」의 전 상용 리전 통합 번들) |
| 취득 | 2026-10-22 20:19 KST(서버 `last-modified` 2026-10-14 22:38:21 GMT) |
| sha256 | `90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b`(174560 바이트) |
| 인증서 | 114장 — 전부 자체 서명 루트 CA(중간 CA 0장) |
| 가장 이른 만료 | 2061-05-18(Amazon RDS Preview us-east-2 Root CA RSA2048 G1) |

- **sha256 정본은 `media/Dockerfile` 의 `sha256sum -c` 줄 하나다.** 위 표의
  값은 옮겨 적은 것이다. 파일이 그 값과 다르면 이미지 빌드가 멈추고, 계약
  시험(`internal/pgconf/rdsca_bundle_test.go`)도 실패한다.
- **갱신**: 같은 URL 에서 다시 받아 파일 교체 · Dockerfile sha256 교체 · 위
  표 갱신을 한 커밋에 한다. 이미지를 다시 배포해야 새 번들이 쓰인다(번들은
  기동 때 읽는다). 받은 파일은 커밋 전에 따로 검증한다 — sha256 은 받은
  파일을 고정할 뿐 그 파일이 맞는지는 말하지 않는다.
    1.  이전 번들과 인증서 단위로 diff 한다(주체 · 만료 · 지문 목록 비교).
    1.  새로 생긴 인증서는 하나씩 본다 — 주체가 Amazon RDS 루트 CA 이고
        자체 서명인지, AWS 문서의 리전 · CA 목록과 맞는지.
    1.  `PRIVATE KEY` 블록이 없는지 확인한다(`grep -c 'PRIVATE KEY'` = 0).
- Dockerfile 에 `POSTGRES_SSLROOTCERT` 기본값(`ENV`)은 두지 않는다 — 로컬
  compose 의 password · `prefer` 경로에 끼어들지 않게 배포 정의가 준다.

### janitor 환경 변수

| 변수 | 기본 | 범위 · 뜻 |
|---|---|---|
| `JANITOR_ENABLED` | compose 가 `true` 로 채움 | 참 값(`true` · `1` · `t` 등) = 실행. 빈 값 · 거짓 값(`false` · `0` · `f` 등) = 대기. 해석할 수 없는 값(예: `yes`)만 기동 거부 |
| `JANITOR_INTERVAL` | `30s` | 주기. 1초 이상 |
| `JANITOR_GRACE` | `8m` | 업로드 확정 뒤 기다리는 시간. 8분 미만은 기동 거부 |
| `JANITOR_RECONNECT_WINDOW` | `300s` | 첫 조각 보호의 재접속 창. 300초 미만 거부 — 사이드카 재접속 창과 같아야 한다 |
| `JANITOR_RECORD_DELETE_AFTER` | `24h` | MediaMTX `recordDeleteAfter` 와 맞춘다. 2시간 미만 거부 |
| `JANITOR_BATCH` | `200` | 한 번에 읽는 행. 10000 이하 · `JANITOR_MAX_PER_CYCLE` 이하 |
| `JANITOR_MAX_PER_CYCLE` | `2000` | 한 주기에 살펴보는 행 상한. 100000 이하 |
| `FS_OP_TIMEOUT` | `5s` | 파일 작업 한 번의 상한. 100ms 이상 6초 이하 |
| `POSTGRES_*` · `SEGMENT_ROOT` · `LOG_LEVEL` | 사이드카와 같음 | 위 사이드카 표 참조. `POSTGRES_*` 에는 `POSTGRES_AUTH` · `POSTGRES_SSLROOTCERT` 가 들고, iam 이면 `AWS_REGION`(기본 `ap-northeast-2`)도 읽는다 — 규칙은 사이드카와 같은 코드(`internal/pgconf`)다. 기동 때 Ping 성공 뒤 `db_connected` 한 줄(`pool=janitor`)을 남긴다 |

로컬 compose 가 janitor 에 넘기는 것은 `JANITOR_ENABLED` 와 `POSTGRES_USER` ·
`POSTGRES_PASSWORD` · `POSTGRES_DB`(호스트 · `SEGMENT_ROOT` · `TZ` 는 고정값)뿐이다
— 나머지는 `.env` 에 적어도 전달되지 않아 기본값으로 돈다. 운영은 배포 정의에
직접 지정한다.

알려진 한계(L1~L33) 전문은 PR 본문 「알려진 한계」에 있다.
