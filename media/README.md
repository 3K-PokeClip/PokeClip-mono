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
| S3 | **미접촉**. `s3_key`는 문자열로 예약만 하고 `upload_state`는 항상 `pending` (업로드는 POK-30) |
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
| `ENSURE_SCHEMA` | `false` | true면 기동 시 임시 DDL로 `stream_segments`를 만든다. 로컬 개발 전용 |
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

위 표가 `internal/config/config.go`가 읽는 전부다(`TZ` 제외 21개). 잘못된 값(숫자 자리에 문자,
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
