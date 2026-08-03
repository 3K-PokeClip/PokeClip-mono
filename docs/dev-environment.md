# 로컬 개발 환경 (M1 1단계)

팀 전원 공통 바닥. 요구사항: **Docker만** (스텁 세그먼트 생성 시에만 ffmpeg 추가).

## 시작

```bash
cp .env.example .env        # 최초 1회
docker compose up -d
```

| 서비스 | 포트 | 용도 |
|---|---|---|
| postgres:17 | 5432 | 3번: 스키마 v0 마이그레이션은 여기에 (M1 2단계) |
| redis:7.4 | 6379 | 3번: 키·TTL·pub/sub 설계 자리 |
| media (MediaMTX 1.19.3) | UDP 8890 (SRT) · 1935 (RTMP) · 8888 (LL-HLS) | 1번: Media Origin 자리 |
| media-stub (nginx) | 8080 | 2번: 플레이어 개발용 정적 세그먼트 (`infra/compose/stub/README.md`) |
| segment-indexer | (포트 없음) | 1번: 녹화 세그먼트를 감지해 `stream_segments`에 기록하는 사이드카 (`media/README.md`) |

## 송출 테스트 (media)

**OBS (SRT):** 설정 → 방송 → 서버 `srt://localhost:8890?streamid=publish:test`
**ffmpeg (RTMP — homebrew ffmpeg은 SRT 미포함):**

```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 \
  -c:v libx264 -preset veryfast -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
  -c:a aac -f flv rtmp://localhost:1935/test
```

⚠️ **인코더 GOP는 반드시 2초**(30fps `-g 60` / 60fps `-g 120`, ADR-020) — 세그먼트 4s가 GOP 정수배여야 드리프트가 없다.

재생 확인:

```bash
curl -s http://localhost:8888/test/index.m3u8   # EXT-X-PART · CAN-BLOCK-RELOAD 보이면 LL-HLS 정상
```

## 세그먼트 인덱싱 확인 (segment-indexer)

MediaMTX가 4초마다 떨어뜨리는 녹화 파일을 사이드카가 `stream_segments` 표에 한 줄씩 기록한다.
아래 절차를 그대로 돌리면 "제대로 기록됐는가"를 숫자로 확인할 수 있다.

**1) 이번 실행 전용 방송 이름을 만든다.** 이전 테스트가 남긴 행과 섞이지 않게 하기 위해서다.

```bash
set -a; . ./.env; set +a
export STREAM="test-$(uuidgen | tr 'A-Z' 'a-z' | cut -c1-8)"
echo "$STREAM"
```

**2) 60초 송출한다.** 위 "송출 테스트" 명령에 `-t 60`과 이 이름을 붙인 것이다.

```bash
ffmpeg -re -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 \
  -c:v libx264 -preset veryfast -g 60 -keyint_min 60 -sc_threshold 0 -pix_fmt yuv420p \
  -c:a aac -t 60 -f flv "rtmp://localhost:1935/$STREAM"
sleep 20   # 마지막 조각은 유휴 타임아웃(10s) + 안정 대기(2s)로 확정된다
```

**3) 파일 수와 행 수가 같은지 본다.** 하나라도 다르면 누락이나 중복이 있다는 뜻이다.

```bash
docker run --rm -e S="$STREAM" -v pokeclip_recordings:/r alpine sh -c 'ls /r/$S | wc -l'
docker compose exec -T -e Q="SELECT count(*) FROM stream_segments WHERE stream_id = '$STREAM';" \
  postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

**4) 조각 길이가 4초 언저리인지 본다.** `duration_ms`가 파일에서 실측한 값이라는 증거다.
`seq = 0`은 아래 "알아 둘 것" 때문에 제외하고, 마지막 조각은 방송이 4초 경계에서 끝나지 않으면
정당하게 짧으므로 함께 제외한다. **min·max 모두 3900–4100 안에 들어와야 한다.**

```bash
docker compose exec -T -e Q="SELECT min(duration_ms), max(duration_ms), count(*) FROM stream_segments WHERE stream_id = '$STREAM' AND seq > 0 AND seq < (SELECT max(seq) FROM stream_segments WHERE stream_id = '$STREAM');" \
  postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

**5) 번호가 빠짐없이 이어지는지 본다.** 어긋난 행만 출력하므로 **0 rows가 정상**이다.

```bash
docker compose exec -T -e Q="SELECT seq FROM (SELECT seq, lag(seq) OVER (ORDER BY seq) AS prev FROM stream_segments WHERE stream_id = '$STREAM') t WHERE prev IS NOT NULL AND seq <> prev + 1;" \
  postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

**6) 끊김 표시가 붙은 조각이 있는지 본다.** `seq = 1` 한 건 외에 `is_discontinuity = true`가
있으면 원인을 조사한다(아래 "알아 둘 것" 참고).

```bash
docker compose exec -T -e Q="SELECT seq, start_pts_ms, duration_ms, is_discontinuity FROM stream_segments WHERE stream_id = '$STREAM' AND is_discontinuity ORDER BY seq;" \
  postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

**7) 재생 타임라인이 빈틈없이 이어지는지 본다.** 각 행의 `start_pts_ms + duration_ms`가
다음 행의 `start_pts_ms`와 같아야 한다. 아래 쿼리는 어긋난 행만 출력하므로 **0 rows가 정상**이다.

```bash
docker compose exec -T -e Q="SELECT seq, start_pts_ms, prev_end FROM (SELECT seq, start_pts_ms, lag(start_pts_ms + duration_ms) OVER (ORDER BY seq) AS prev_end FROM stream_segments WHERE stream_id = '$STREAM') t WHERE prev_end IS NOT NULL AND prev_end <> start_pts_ms;" \
  postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

**8) 조용히 버려진 것이 없는지 로그로 본다.** 아래 세 가지는 **0건이어야 한다.**

```bash
docker compose logs segment-indexer | grep -c -E 'late_segment_skipped|tail_update_rejected|unsettled_giving_up'
```

- `late_segment_skipped` — 순서가 어긋나 도착한 파일을 버렸다. **1건이라도 뜨면 원인을 조사한다**
  (감시가 뭔가를 놓쳤다는 1차 경보이며, 자동 복구는 없다).
- `tail_update_rejected` — 마지막 행의 길이 정정이 DB에서 거부됐다.
- `unsettled_giving_up` — 파일이 끝내 안정되지 않아 기록을 보류했다.

### 알아 둘 것 — 첫 세그먼트는 4초보다 길다

`ffmpeg -re`는 송출 시작 직후 앞부분을 몰아서 보낸다. MediaMTX는 벽시계 기준 4초마다 자르므로,
첫 세그먼트에는 4초 wall 동안 약 6초 분량의 영상이 담긴다. 그 결과:

- `seq = 0`의 `duration_ms`가 **5933 ms**로 나온다(60초 실측값. ffprobe로 따로 재도
  비디오 5.933s / 오디오 5.921s로 같은 값이 나온다 — 파일에 정말 그만큼 들어 있다).
  첫 조각의 비디오 프레임 수는 180장(= 6.0초 분량)이고 두 번째부터는 120장(= 4.0초)이다.
- 그 다음 조각(`seq = 1`)에서 기대 시각과 실제 시각이 **-1945 ms** 어긋나 허용치(1500 ms)를
  넘으므로 `is_discontinuity = true`가 붙고 `negative_drift` ERROR 로그가 1건 남는다.

**둘 다 송출 쪽 특성이며 인덱싱 오류가 아니다.** 타임라인 연속성(7번 쿼리)은 그대로 성립한다.
OBS처럼 처음부터 실시간으로 보내는 인코더에서는 나타나지 않는다.
그래서 4번(길이 확인) 쿼리에서 `seq = 0`을 제외한다.

참고로 조각 사이의 어긋남은 정상 상태에서도 늘 조금씩 음수다(실측: 60초 방송의 전이 14건 중
13건이 -1 ~ -22 ms). `duration_ms`는 영상 파일 안의 시간에서, 시작 시각은 파일 이름에서 오기
때문이며, 허용치 안의 이 값들은 DEBUG 로그로만 남는다.

### 사이드카 테스트를 돌릴 때

```bash
cd media && go test ./...
```

`internal/index`의 통합 테스트는 **실제 PostgreSQL이 필요**하다. `PG_DSN`이 없으면 그 케이스들은
조용히 `skip`되므로, DB 계층까지 확인하려면 아래처럼 붙여서 돌린다.

```bash
set -a; . ./.env; set +a
export PG_DSN="postgres://$POSTGRES_USER:$POSTGRES_PASSWORD@localhost:5432/$POSTGRES_DB"
cd media && go test ./internal/index/ -v
```

### 스트림 이름 제약

MediaMTX는 아무 경로로나 송출을 받아 주지만, 사이드카는 **`[A-Za-z0-9_-]` 1–64자**인 이름만
인덱싱한다. 규칙에 어긋난 이름(공백, 슬래시, 한글 등)으로 송출하면 **녹화 파일은 정상으로 생기는데
`stream_segments`에는 한 줄도 안 들어가고** 사이드카 로그에 `stream_id_rejected` WARN만 남는다.

```bash
docker compose logs segment-indexer | grep stream_id_rejected
```

## S3 즉시 업로드 확인 (segment-indexer, 1번 전용)

**기본 상태는 업로더 꺼짐이다.** `S3_BUCKET`이 비어 있으면 사이드카는 인덱싱만 하고
(`uploader_disabled` INFO 1줄), S3·AWS 자격증명이 전혀 필요 없다 — 2·3번은 이 절을 건너뛰면 된다.

1번이 실버킷 업로드까지 확인하려면:

```bash
# 1) 개인 override 를 만든다 (커밋 금지 — .gitignore 등재됨)
cp docker-compose.override.yml.example docker-compose.override.yml

# 2) 같은 셸에 임시 자격증명을 내보내고 재기동한다
eval "$(aws configure export-credentials --format env)"
docker compose up -d --force-recreate segment-indexer
docker compose logs segment-indexer | grep -E 'uploader_started|credentials_ok'

# 3) 위 "세그먼트 인덱싱 확인" 절차로 송출한 뒤, 장부가 uploaded 로 차는지 본다
docker compose exec -T -e Q="SELECT upload_state, count(*) FROM stream_segments WHERE stream_id='$STREAM' GROUP BY 1;" \
  postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
```

끌 때는 override를 **제외**하고 재기동한다 — `unset S3_BUCKET`은 무효다
(override의 `${S3_BUCKET:-…}`가 미설정·빈값을 둘 다 실버킷으로 치환한다):

```bash
docker compose -f docker-compose.yml up -d --force-recreate segment-indexer
docker compose -f docker-compose.yml logs segment-indexer | grep -c uploader_disabled   # 1
```

주의: `AWS_PROFILE`을 override에 넣는 것은 `~/.aws` 마운트 폴백을 켤 때만 —
마운트 없이 넣으면 SDK가 존재하지 않는 프로필을 찾다 기동이 죽는다(.example 주석 참조).
환경변수 전체 목록과 의미는 [`media/README.md`](../media/README.md)의 환경변수 절에 있다.

## 2번(플레이어) 로컬 URL 매핑

| 용도 | URL |
|---|---|
| 정적 스텁 (뼈대·시킹 UI) | `http://localhost:8080/live/stub/index.m3u8` |
| 진짜 LL-HLS (송출 필요) | `http://localhost:8888/{streamId}/index.m3u8` |

- MediaMTX는 `index.m3u8` 요청에 **302 리다이렉트**(세션 파라미터 부여)를 줄 수 있음 — hls.js 기본 동작이 따라가므로 커스텀 fetch를 끼울 때만 주의.
- 로컬은 인증·서명 쿠키 없음. 프로덕션 규약은 **PokeClip-architecture `contracts/계약3-LLHLS-DVR재생규약.md`**(정본)을 따를 것 — 특히 §4 catch-up 끄기.

## 설계 근거 포인터

- 파라미터(4s/0.5s/900개): ADR-020 · 재생 규약: 계약3 (플레이어는 **catch-up 끄기** 필수)
- 이 환경은 로컬 전용 — AWS 배포(프라이빗+NLB, ADR-021)는 별도 IaC로 진행
