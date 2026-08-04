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
| 훅이 이긴 조각은 승격·학습에서 빠진다 | 같은 파일을 훅과 파일 감시가 모두 올리려 하고 먼저 온 쪽이 이긴다. 훅이 이긴 파일은 `ReasonHook`이라 크기 재확인(승격)과 길이 학습 대상에서 제외된다 — 훅 시점의 파일이 이미 최종 크기라는 실측(29/29)에 기댄 결정이다. 남은 방어선인 `correctTail`은 **그 조각이 꼬리인 약 4초 동안만** 유효한 유한 창이다 | 관측만(YAGNI) — `segment_indexed`의 `duration_ms`·`reason`으로 본다 |
| 훅 이벤트에 레이트리밋·상태 맵 TTL이 없다 | 스트림별 세션 상태(`pendingOffline`·`lastOnlineAt`·`breaks`)는 경계 큐 상한(64)만 있고 시간 기반 만료가 없다. 스트림 수가 매우 많고 각각 짧게 붙었다 떨어지면 맵 항목이 남는다. 스풀을 폭주시키는 송출자에 대한 방어도 없다 | 이월 — 로컬·소규모에서는 발생하지 않는다. 멀티테넌트 규모에서 재평가 |
| 스풀·녹화 경로의 심링크 검증 없음 | 훅이 준 경로는 루트 밖(`..`)이면 거부하지만 심링크를 따라간 결과까지 확인하지는 않는다. 지금은 두 경로 모두 우리가 만든 볼륨이라 노출면이 없다 | S3 업로더 PR에서 재평가 — 파일을 밖으로 내보내는 순간 위험도가 달라진다 |

### 훅 채널 (POK-74) — 무엇이 켜져 있고 로그를 어떻게 읽나

MediaMTX가 이벤트마다 컨테이너 안의 작은 명령을 실행하고(`media/Dockerfile.mtxhook`이 얹은
`/hooks-bin/mtxhookwrite`), 그 명령이 공유 볼륨의 스풀 파일에 JSON 한 줄을 덧붙인다.
사이드카는 그 파일을 따라 읽는다. 켜 둔 훅은 3종이다 —
`runOnOnline`·`runOnOffline`(세션 붙음/끊김) + `runOnRecordSegmentComplete`(조각 닫힘).

- 구명칭 `runOnReady`는 **쓰지 않는다.** v1.19.3에서 그것은 `runOnAvailable`로 매핑되며
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

위 표가 `internal/config/config.go`가 읽는 전부다(`TZ` 제외 32개). 잘못된 값(숫자 자리에 문자,
음수, 파싱 불가한 기간)은 조용히 기본값으로 넘어가지 않고 기동에 실패한다.
`SEGMENT_SUSPECT_BELOW_MS`가 `SEGMENT_EXPECTED_DURATION_MS`보다 크면 기동 단계에서 거부한다.

## 테스트

```bash
cd media && go test ./...
```

`internal/index`의 통합 테스트는 **실제 PostgreSQL이 필요**하다. `PG_DSN`이 없으면 해당 케이스는
전부 `skip`되고 나머지는 그대로 돈다 — 즉 `PG_DSN` 없이 돌린 결과만으로는 DB 계층이 검증되지 않는다.

```bash
set -a; . ../.env; set +a
export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
go test ./internal/index/ -v
```

`internal/fmp4meta` 테스트는 `testdata/`의 커밋된 파일만 쓰므로 Docker가 꺼져 있어도 돈다.

`cmd/mtxhookwrite` 테스트는 **바이너리를 직접 빌드해 프로세스 8개를 동시에 띄운다**(줄 섞임 검증).
`go test`만 있으면 되고 Docker는 필요 없지만, 다른 테스트보다 몇 초 더 걸린다.

## MediaMTX 버전업 체크리스트

버전 고정의 유일한 자리는 [`media/Dockerfile.mtxhook`](Dockerfile.mtxhook)의 `FROM`이다
(compose의 `image:`가 `build:`로 바뀌면서 옮겨왔다). 훅 파라미터 이름은 버전 사이에 조용히
바뀌거나 사라질 수 있고, **훅이 실행되지 않아도 아무 오류가 나지 않는다**. 그래서 절차를 고정한다.

1. **`FROM` 변경은 별도 PR로 낸다.** 다른 변경과 섞으면 회귀 원인을 가를 수 없다.
2. 기동 로그에서 **deprecated/unknown 파라미터 WARN**을 확인한다 — `docker compose logs media | head -50`.
   훅 3종의 이름이 그대로 살아 있는지가 핵심이다.
3. **스모크**: 15초 송출 후 스풀에 `online`·`segcomplete`·`offline` 3종이 찍히는지 본다
   (`docs/dev-environment.md`의 "훅 채널 확인").
4. **재접속 합성**을 다시 돌려 `is_discontinuity=true`가 재접속 지점에 붙는지 확인한다.
5. `segment_indexed`의 `reason` 분포에 `5`(훅)가 남아 있는지 본다 — 사라졌으면 훅이 안 도는 것이다.
6. 이상이 있으면 `HOOK_SPOOL_PATH`를 빈 값으로 두고 사이드카만 재기동해 즉시 롤백한다.
   그 상태로도 인덱싱은 정상이고, 재접속 검출만 현행 수준으로 돌아간다.
