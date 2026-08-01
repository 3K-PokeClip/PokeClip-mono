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

잘못된 값(숫자 자리에 문자, 음수, 파싱 불가한 기간)은 조용히 기본값으로 넘어가지 않고 기동에 실패한다.
