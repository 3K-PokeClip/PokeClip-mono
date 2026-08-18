# 로컬 개발 환경 (M1 1단계)

팀 전원 공통 바닥. 요구사항: **Docker만** (스텁 세그먼트 생성 시에만 ffmpeg 추가).

## 시작

```bash
cp .env.example .env        # 최초 1회
docker compose up -d --build
```

⚠️ `media`는 이제 공식 이미지를 그대로 쓰지 않고 **훅 바이너리를 한 층 얹어 빌드한다**
(`media/Dockerfile.mtxhook`). `git pull` 후 첫 기동에는 `--build`를 붙여야 하며,
그때 한 번만 Go 빌드 때문에 수십 초 더 걸린다.

### ⚠️ 1회만 필요한 볼륨 초기화 (POK-79, media 비특권 전환)

`media`가 root 대신 **UID 10002**로 돈다. 볼륨의 소유권은 **볼륨이 새로 만들어질 때 이미지에서
복사**되므로, 이 변경 이전에 만들어진 볼륨(root 소유)은 자동으로 고쳐지지 않는다.
안 하면 **녹화가 조용히 멈춘다.** 아래를 **한 덩어리로** 1회 실행한다 — 중간에 `up -d`만 먼저 하면
옛 이미지가 볼륨을 root로 굳혀 같은 문제가 재발한다.

```bash
docker compose down
docker volume rm pokeclip_recordings pokeclip_dvr pokeclip_hooks
docker compose up -d --build
```

- **`docker compose down -v`는 쓰지 않는다.** `pokeclip_pgdata`까지 지워져 로컬 DB가 날아간다.
- 지워지는 것: 로컬 녹화 파일·DVR 세그먼트·훅 스풀(전부 테스트 산출물). 유지: DB·Redis.
- **S3 업로더를 켜 둔 경우(개인 override)** 초기화 전에 미업로드 잔여를 확인한다 — 파일을 지우면
  그 행들은 영영 업로드되지 않는다:
  ```bash
  docker compose exec -T -e Q="SELECT upload_state, count(*) FROM stream_segments GROUP BY 1;" \
    postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$Q"'
  ```
- 초기화가 필요한지 확인 — **세 볼륨을 전부** 본다(아무것도 출력되지 않으면 이미 정상):
  ```bash
  for v in recordings dvr hooks; do
    docker run --rm -v "pokeclip_$v:/x" alpine stat -c '%u' /x | grep -qx 10002 \
      || echo "초기화 필요: pokeclip_$v"
  done
  ```
  ⚠️ **하나만 확인하면 안 된다.** 볼륨은 각각 독립적으로 소유권을 받으므로 일부만 옛 상태일 수
  있다. 예컨대 `recordings`만 정상이고 `hooks`가 root면 **훅 3종이 전부 `permission denied`로
  죽어 스풀이 0줄**이 되는데, 녹화는 멀쩡해서 겉으로는 정상으로 보인다.
  (이 진단이 없는 볼륨을 미리 만들어도 무해하다 — 빈 볼륨은 media 기동 시 정상적으로 채워진다.)
- 이후에는 `git pull && docker compose up -d --build`로 평소대로 돌아간다.

| 서비스 | 포트 | 용도 |
|---|---|---|
| postgres:17 | 5432 | 3번: 스키마 v0 마이그레이션은 여기에 (M1 2단계) |
| redis:7.4 | 6379 | 3번: 키·TTL·pub/sub 설계 자리 |
| media (MediaMTX 1.19.3 + 훅 바이너리) | UDP 8890 (SRT) · 1935 (RTMP) · 8888 (LL-HLS) | 1번: Media Origin 자리. 버전 고정은 `media/Dockerfile.mtxhook`의 `FROM`. **비특권 UID 10002로 실행**(POK-79) — 실행 계정 정본도 같은 Dockerfile의 `USER` |
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

이렇게 붙여도 **개발 DB(`PG_DSN`이 가리키는 곳)에는 쓰지 않는다.** `PG_DSN`은 관리 접속으로만 쓰이고,
같은 서버에 전용 테스트 DB(`PG_TEST_DB`, 기본 `pokeclip_uploadtest`)를 만들어 그 안에서만 돌며
테스트 함수마다 비운다. 그래서 개발 DB의 `stream_segments`는 오염되지 않는다. 대신 여섯 가지를 기억한다.

- **`PG_DSN`에는 위 로컬 compose의 개발 DB만 준다.** 공유 개발 서버·원격·프로덕션 DSN을 넣지 않는다 —
  이 테스트는 같은 서버에 DB를 만들고 그 안을 비운다(지우는 것은 아래처럼 사람이 한다).
- `PG_DSN` 롤에 `CREATEDB` 권한이 있어야 한다(없으면 skip이 아니라 실패). 로컬 compose의
  `POSTGRES_USER`도, `media-ci`의 postgres 서비스 컨테이너(superuser)도 이미 갖고 있으므로 **권한을 새로 올려 줄 일은 없다.**
- `PG_TEST_DB`에 개발 DB 이름을 그대로 주면 테스트가 기동 즉시 실패한다 — 그 조합은 개발 DB를
  비우기 때문이다. `PG_DSN`에 DB 이름을 아예 안 적었을 때도 마찬가지인데, 여기엔 단서가 붙는다:
  `PGDATABASE` 환경변수가 설정돼 있으면 그 값이 DB 이름으로 채워지므로 "이름이 비었다" 가드는
  걸리지 않고, 그 이름이 개발 DB와 같은지를 위의 동일 이름 가드가 대신 판정한다.
- **이미 있는 DB를 `PG_TEST_DB`로 주면, 그 DB에 이 테스트가 심어 둔 소유 표식
  (빈 표 `pokeclip_testdb_marker`)이 없는 한 채택하지 않고 실패한다** — 남의 DB를 비우지 않기
  위해서다. 표식은 테스트가 DB를 새로 만들 때만 심는다. 그래서 **표식이 생기기 전에 만들어 둔
  기존 전용 DB(`pokeclip_uploadtest` 등)는 한 번 `DROP DATABASE` 후 다시 돌려야 한다**
  (표식 도입 전에 만든 전용 DB라면 한 번만 겪는다. 단 실패 메시지의 원인은 이것 말고도 있을 수 있으니 — 남의 DB, 중단된 부트스트랩 잔재 — 지우기 전에 그 DB가 전용 테스트 DB가 맞는지 직접 확인한다).
- **같은 `PG_TEST_DB`로 두 실행을 동시에 돌리면 서로의 데이터를 지운다.** CI나 병렬 실행에서는
  실행마다 고유한 `PG_TEST_DB`를 주고, **끝나면 `DROP DATABASE`로 그 DB를 정리한다** — 정리 없이
  고유 이름만 늘리면 DB가 무한히 쌓인다. 이 규약은 **PG를 공유하는 실행이 있을 때** 적용된다 —
  `media-ci`의 postgres 서비스 컨테이너는 잡마다 뜨고 잡과 함께 사라져 공유가 없으므로 고정 이름을 쓴다.
  **규약을 완화한 것이 아니라 적용 조건(PG를 공유하는 실행의 존재)을 드러낸 것이다.**
- `ddl.go`를 바꾼 뒤에는 전용 DB가 옛 스키마를 유지한다(`CREATE TABLE IF NOT EXISTS`).
  `DROP DATABASE pokeclip_uploadtest` 후 다시 돌린다.

### 스트림 이름 제약

MediaMTX는 아무 경로로나 송출을 받아 주지만, 사이드카는 **`[A-Za-z0-9_-]` 1–64자**인 이름만
인덱싱한다. 규칙에 어긋난 이름(공백, 슬래시, 한글 등)으로 송출하면 **녹화 파일은 정상으로 생기는데
`stream_segments`에는 한 줄도 안 들어가고** 사이드카 로그에 `stream_id_rejected` WARN만 남는다.

```bash
docker compose logs segment-indexer | grep stream_id_rejected
```

## 훅 채널 확인 (MediaMTX → 사이드카)

MediaMTX가 방송이 붙고 끊길 때, 그리고 녹화 조각이 닫힐 때마다 컨테이너 안의 작은 명령을
실행한다. 그 명령이 공유 볼륨의 스풀 파일 `/hooks/events.jsonl`에 JSON 한 줄씩 덧붙이고,
사이드카가 그 파일을 따라 읽는다. **이 채널이 있어야 0.9초짜리 재접속이 끊김으로 잡힌다**
(벽시계만으로는 그 정도 공백이 안 잡힌다 — POK-36 실측).

**1) 스풀에 3종이 다 찍히는지 본다.** 위 "송출 테스트"를 15초쯤 돌린 뒤:

```bash
docker run --rm -v pokeclip_hooks:/hooks:ro alpine grep "$STREAM" /hooks/events.jsonl
```

`media` 컨테이너 안에서 `cat`을 쓰지 않는 이유는 그 이미지가 **scratch 기반이라 셸이 없기**
때문이다. 그래서 진단용으로 alpine 컨테이너를 하나 띄워 같은 볼륨을 읽기 전용으로 붙인다.

합격 기준: `$STREAM`으로 걸러진 줄에 `"kind":"online"` 1건, `"kind":"segcomplete"` 여러 건,
`"kind":"offline"` 1건이 있고, 각 줄이 개행으로 끝나며 `at_unix_nano`가 정수다.

**2) 사이드카가 그 줄들을 정상으로 읽었는지 본다.** 아래는 **0건이 정상**이다.

```bash
docker compose logs segment-indexer \
  | grep -cE 'hook_line_invalid|hook_line_overflow|hook_spool_truncated|hook_segment_path_rejected'
```

`hook_spool_missing`은 예외로 **기동 후 0~1회까지 정상**이다 — 새 볼륨에는 첫 송출 전까지
스풀 파일이 아예 없다. 스풀이 생긴 뒤에 또 나오면 그건 이상이다.

한 가지 예외가 더 있다. 스트림 이름이 사이드카의 화이트리스트(`[A-Za-z0-9_-]{1,64}`, 위
"인제스트와 인덱싱의 허용 범위가 다르다" 참고) 밖이면 `hook_line_invalid`·
`hook_segment_path_rejected`가 나오는데, 이건 **경계 검증이 제대로 작동한 결과**이지 고장이
아니다. 그러니 위 명령이 0이 아니면 먼저 `stream_id_rejected`부터 확인한다 —
그게 함께 나온다면 원인은 훅이 아니라 스트림 이름이다.

```bash
docker compose logs segment-indexer | grep stream_id_rejected
```

**3) 훅 채널이 실제로 일을 하는지 본다.** `reason` 값 `5`가 "훅이 먼저 알려 준 조각"이다.

```bash
docker compose logs segment-indexer | grep segment_indexed | grep "$STREAM" \
  | grep -o '"reason":[0-9]*' | sort | uniq -c
```

`5`가 한 건도 없으면 훅 채널이 조용히 죽은 것이다(인덱싱 자체는 파일 감시가 계속하므로
겉으로는 멀쩡해 보인다). `docker compose logs media | grep -i hook`으로 훅 실행 실패를 본다.

**4) 재접속 판정 로그를 읽는 규칙.**

```bash
docker compose logs segment-indexer | grep "$STREAM" | grep hook_break_
```

- `hook_break_armed` → `hook_break_consumed` 한 쌍이 정상이다. `consumed` 줄의 `seq`가
  `is_discontinuity = true`인 행의 `seq`와 같아야 한다.
- **`hook_break_discarded`와 `hook_break_dropped`가 같은 시각에 함께 나오면 한 건의 정리다.**
  `discarded`는 "판정 대상이던 경계 1건이 왜 버려졌는가"이고, `dropped`는 "그와 함께 정리된,
  조각이 한 건도 없던 더 오래된 경계들"이다. **놓친 건수는 `discarded`만 센다.**
- `armed`만 있고 아무 반응이 없으면 스트림 이름 대조부터 한다(훅의 `MTX_PATH`와 DB의
  `stream_id`가 **바이트 단위로 같아야** 무장이 소비된다).

**끄는 법(즉시 롤백).** `docker-compose.yml`의 `HOOK_SPOOL_PATH`를 빈 값으로 두고

```bash
docker compose up -d --no-deps segment-indexer   # 사이드카만 재기동. MediaMTX는 건드리지 않는다
```

그러면 훅 어댑터가 아예 뜨지 않고 판정이 현행(벽시계)으로 돌아간다. DB 변경은 없다.

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

## 권한 문제 트러블슈팅 (media 비특권 전환 이후, POK-79)

`media`는 UID 10002로 돈다. 아래 세 가지가 이 변경으로 새로 생길 수 있는 증상이다.
**공통 증상은 "오류처럼 안 보인다"는 것** — 빌드도 기동도 성공한 뒤에 조용히 안 써진다.

**1) 녹화 파일이 하나도 안 생긴다 / 스풀에 줄이 안 쌓인다 → 볼륨이 아직 root 소유다**

가장 흔한 원인은 위 "1회만 필요한 볼륨 초기화"를 안 했거나, 초기화 중간에 `up -d`만 먼저 한 경우다.

**세 볼륨을 전부** 본다 — 볼륨마다 독립이라 일부만 옛 상태일 수 있고, 그 경우 증상이 갈린다
(`recordings`만 root면 녹화가 멈추고, `hooks`만 root면 **녹화는 멀쩡한데 훅만 조용히 죽는다**).

```bash
for v in recordings dvr hooks; do
  printf '%s: ' "$v"; docker run --rm -v "pokeclip_$v:/x" alpine stat -c '%u %g %a' /x
done                                                                     # 셋 다 기대: 10002 10002 755
docker compose logs media | grep -iE 'permission denied|failed to save'  # 0건이어야 정상
```

하나라도 `0 0 755`로 나오면 초기화 절차를 **한 덩어리로** 다시 실행한다. 볼륨은 **비어 있을 때만**
이미지에서 소유권을 받으므로, 파일이 한 개라도 들어간 뒤에는 아무리 재기동해도 안 고쳐진다.

**2) 초기화를 했는데도 root 소유다 → 복사 금지 옵션(`nocopy`)이 붙어 있다**

`docker-compose.yml`은 계약 테스트(`media/internal/mtxhook/runtime_identity_contract_test.go`)가
막지만, **개인 `docker-compose.override.yml`은 gitignore라 검사할 수 없다.**

```bash
grep -n nocopy docker-compose.override.yml 2>/dev/null   # 있으면 그 줄을 지운다
```

**3) media가 아예 안 뜬다 / 설정을 못 읽는다 → 리눅스의 설정 파일 모드 (Mac에서는 재현 안 됨)**

`infra/compose/mediamtx.yml`은 bind mount라 **호스트의 파일 모드가 그대로 보인다.** 체크아웃 umask가
0077이면 0600이 되어 비root가 읽지 못하고 기동에 실패한다. Docker Desktop(Mac)은 소유권을
재매핑하므로 이 증상이 나지 않는다 — 리눅스/EC2에서만 발생한다.

```bash
ls -l infra/compose/mediamtx.yml | cut -c8    # 'r' 이어야 한다 (o+r 비트)
chmod a+r infra/compose/mediamtx.yml          # 처방
```

⚠️ **이 처방은 그 설정 파일에 자격증명이 하나도 없기 때문에 성립한다**(전수 확인함). 계약4의 내부
토큰(`X-Internal-Token`)이나 SRT passphrase가 이 파일에 들어오면 `o+r`은 ADR-018(평문 금지)과
충돌한다 — 그때는 `o+r` 대신 **소유권·그룹으로** 읽기 권한을 주고, 비밀은 Secrets Manager로 뺀다.

### 되돌리기 (롤백)

머지 방식이 merge commit이라 **첫 부모를 지정해야 한다** — `-m 1`이 없으면 revert가
`is a merge but no -m option was given`으로 아무것도 하지 않고 끝난다.

```sh
git revert -m 1 <merge-sha>     # -m 1 = 첫 부모(main) 기준
docker compose up -d --build
```

되돌리면 media가 다시 root로 돌고, **root는 10002 소유 디렉토리에도 쓴다** →
**롤백에는 볼륨 재초기화가 필요 없다.**

훅 채널만 끄는 부분 롤백은 위 "훅 채널 확인"의 "끄는 법(즉시 롤백)"을 쓴다.

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
- 멘토 데모용 AWS 임시 서버는 `infra/dev-media/`(기한부 — 2026-08-24 만료 후 디렉토리째 삭제, 팀 공용 아님)
