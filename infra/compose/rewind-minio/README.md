# rewind-minio — 로컬 되감기 바이트 원천

팀 compose 에 덧붙여 켜는 로컬 S3(MinIO · https)와 로컬 엣지다. 켜면
`segment-indexer` 가 되감기 목록 · 조각을 이 MinIO 로 발행 · 업로드하고, 브라우저는
로컬 엣지(`http://127.0.0.1:28480/dvr/…`)로 받는다. 실버킷은 쓰지 않는다
(POK-200 · 설계 M5 9.4.1 · 9.4.2).

기본 `docker compose up` 은 바뀌지 않는다 — 발행은 꺼져 있고 제어면은 모든 스트림에
404 를 낸다. 바이트까지 보려면 이 폴더를 덧붙인다.

## 무엇이 뜨나

| 서비스 | 하는 일 | 망 · 포트 |
|---|---|---|
| `minio-certs` | 로컬 CA · `minio` SAN 서버 인증서를 만든다(있으면 건너뜀) | 없음 · 한 번 돌고 끝 |
| `minio` | S3 API(https) · 데이터 볼륨 | 망 안 `https://minio:9000` 만 |
| `minio-init` | 버킷 `pokeclip-rewind-local` · 익명 정책(`dvr/*` GetObject 만) | 없음 · 한 번 돌고 끝 |
| `dvr-edge` | `/dvr/` 만 MinIO 로 넘기는 nginx · CORS · 목록 거부 | 호스트 `127.0.0.1:28480` |
| `minio-check` | 공통 수용 시험(아래) — 평소 up 에는 안 뜬다 | 프로필 `rewind-minio-check` |

사이드카에는 `S3_*` · `REWIND_PUBLIC_BASE_URL` · `SSL_CERT_DIR` 를 덧붙인다. 사이드카와
엣지는 CA 인증서만 받고 서버 개인 키는 받지 않는다.

## 켜기

1.  `.env` 에 로컬 전용 자격을 적는다(`.env.example` 의 `REWIND_MINIO_USER` ·
    `REWIND_MINIO_PASSWORD` — 사용자 3자 · 비밀번호 8자 이상). 이 값은 MinIO root
    자격이고 사이드카 · 수용 시험 · 헬스체크가 함께 쓴다. 헬스체크 명령 줄에 실려
    `docker inspect` 와 컨테이너 안 `ps` 에 보이므로 다른 곳의 비밀번호를 재사용하지
    않는다.
1.  저장소 루트에서 덧붙여 띄운다.

    ```sh
    docker compose -f docker-compose.yml -f infra/compose/rewind-minio/compose.yml \
      --profile rewind-minio up -d
    ```

1.  사이드카 로그에서 `rewind_publish_started endpoint=https://minio:9000` 과
    `control_server_started` 를 본다. `rewind_publish_degraded` 면 인증서 · 버킷을
    본다(아래 「막히면」).

`--profile rewind-minio` 는 `logs` · `run` · `down` 에도 붙인다 — 사이드카가 이
프로필의 `minio-init` 에 기대므로 빠지면 compose 가 프로젝트를 거부한다.

## 수용 시험 — 이미지 · 판을 고르거나 올릴 때마다

발행 층은 조건부 PUT(`If-None-Match: *` · `If-Match`)으로 쓰기 울타리를 지킨다.
대체 저장소가 이를 무시하면 로컬 실측이 prod 와 다른 동작을 잰다. 하나라도 어긋나면
그 판은 쓰지 않는다.

```sh
docker compose -f docker-compose.yml -f infra/compose/rewind-minio/compose.yml \
  --profile rewind-minio --profile rewind-minio-check run --rm minio-check
```

기대: 조건부 PUT 200 · 412 · 412 · 200, 익명 목록 · `streams/` · `dvr/` 가
403 · 403 · 200. 결과 원문(명령 · 응답 코드 · 이미지 다이제스트)은 실험 기록에 남긴다.

## 끄기 · 정리

팀 프로젝트에 올렸으면 덧붙임 없이 다시 띄워 되돌린다 — 덧붙인 채로 두면 팀 사이드카가
계속 MinIO 로 발행 · 업로드한다.

```sh
docker compose -f docker-compose.yml -f infra/compose/rewind-minio/compose.yml \
  --profile rewind-minio rm -sf minio-certs minio minio-init dvr-edge
docker compose up -d segment-indexer
```

실험은 전용 프로젝트(`pcbench-<ID>`)로 띄운다. 이름만 바꾸면 호스트 포트는 팀 값
그대로라 부딪히고 라벨도 없으므로, 덧붙임 예
[`bench.example.yml`](bench.example.yml)의 `<ID>` 를 바꾼 사본을 한 겹 더 붙인다.
포트를 28xxx 대역으로 옮기고 실험 컨테이너에 라벨 `com.pokeclip.bench=<ID>` 를 단다.

```sh
ID=c7  # 실험 이름
B="${TMPDIR:-/tmp}/bench-${ID}.yml"  # 저장소 밖 — 작업 트리에 남지 않게
sed "s/<ID>/${ID}/g" infra/compose/rewind-minio/bench.example.yml > "$B"
docker compose -p "pcbench-${ID}" -f docker-compose.yml \
  -f infra/compose/rewind-minio/compose.yml -f "$B" \
  --profile rewind-minio up -d
```

끝나면 인증서 · 데이터 볼륨까지 지운 뒤 잔여 0 을 확인한다(아래 `<ID>` 는 같은 이름). 잔여 확인은 compose 가
모든 컨테이너 · 볼륨 · 망에 붙이는 프로젝트 라벨로 한다 — 덧붙임을 빠뜨린 실행도 잡는다.

> **주의:** `down -v` 는 그 프로젝트의 이름 붙은 볼륨을 **전부** 지운다 — 팀
> 프로젝트(`pokeclip`)면 `pgdata` · `recordings` · `dvr` · `hooks` · MinIO 데이터까지
> 사라진다. 폐기해도 되는 전용 실험 프로젝트(`-p pcbench-<ID>`)에서만 쓴다. 팀
> 스택에서 인증서만 바꾸려면 아래 「인증서 다시 만들기」를 따른다.

```sh
docker compose -p pcbench-<ID> -f docker-compose.yml \
  -f infra/compose/rewind-minio/compose.yml -f "$B" \
  --profile rewind-minio down -v --rmi local
docker ps -a --filter label=com.docker.compose.project=pcbench-<ID>
docker volume ls --filter label=com.docker.compose.project=pcbench-<ID>
docker network ls --filter label=com.docker.compose.project=pcbench-<ID>
```

## 인증서 다시 만들기

인증서 볼륨 둘(`rewind-minio-certs` · `rewind-minio-ca`)만 지우고 다시 띄운다.
MinIO 데이터와 팀 볼륨은 그대로 둔다. 실제 볼륨 이름에는 프로젝트 이름이 앞에
붙는다 — 팀 프로젝트면 `pokeclip_rewind-minio-certs` · `pokeclip_rewind-minio-ca` 다.

이 볼륨을 붙든 컨테이너가 하나라도 남아 있으면(멈춘 것 포함) 지우기가 거부된다
(`volume is in use`). 사이드카도 CA 를 받으므로 함께 내렸다가 올린다 — 다시 뜰 때 새
CA 를 읽는다.

```sh
docker compose -f docker-compose.yml -f infra/compose/rewind-minio/compose.yml \
  --profile rewind-minio rm -sf minio-certs minio minio-init \
  dvr-edge minio-check segment-indexer
docker volume rm pokeclip_rewind-minio-certs pokeclip_rewind-minio-ca
docker compose -f docker-compose.yml -f infra/compose/rewind-minio/compose.yml \
  --profile rewind-minio up -d
```

## 이미지 판

MinIO 커뮤니티판은 배포가 멈췄다 — Docker Hub `minio/*` 는 없고 `quay.io/minio/*` 는
인증을 요구한다(2026-10-26 실물 확인). 그래서 관리되는 재배포판
`cgr.dev/chainguard/minio` 를 다이제스트로 고정했다(`RELEASE.2026-10-23T20-06-51Z` ·
linux/arm64 · 같은 이미지에 `mc` 가 들어 `minio-init` 도 이것을 쓴다). 알려진 취약점
판정은 아직 하지 않았다(로컬에 스캐너가 없다 — 판단 대기). 판을 올리면 위 수용 시험을
다시 돌린다.

## prod 엣지와 다른 점

*   엣지 캐시(TTL 합치기 · 오류 캐시)는 흉내 내지 않는다.
*   `x-amz-*` 응답 머리와 MinIO 의 CORS 머리를 지운다. MinIO 도 CORS 머리를 스스로
    실어 그대로 두면 `Access-Control-Allow-Origin` 이 두 번 나간다(브라우저가 거부한다).
*   제어면은 이 엣지를 거치지 않는다 — 브라우저가 `http://localhost:8086` 을 직접
    부르고 CORS 는 `CONTROL_CORS_ORIGIN` 이 낸다.

## 막히면

*   `rewind_publish_degraded` · `HeadBucket` 실패 · `x509` — 사이드카의
    `SSL_CERT_DIR` 에 CA 디렉터리(`/run/rewind-minio-ca`)가 빠졌거나 `minio-init` 이
    끝나기 전에 떴다.
*   `minio` 가 healthy 가 안 된다 — `.env` 의 자격이 MinIO 길이 조건을 못 채운다.
    또는 자격에 URL 특수문자(`@` · `:` · `/` · `#` · `%` · `?`)가 있다 — 헬스체크와
    `minio-init` 은 자격을 `MC_HOST_*` URL 에 넣으므로 그 문자에서 깨진다. 영숫자로
    바꾼다.
*   인증서를 새로 만들려면 위 「인증서 다시 만들기」를 따른다(볼륨 둘만 지운다).

## See also

*   [`media/README.md`](../../../media/README.md) 제어면 절 · 환경변수 표
*   [`check-s3-conditions.sh`](check-s3-conditions.sh) — 수용 시험 본체
*   [`dvr-edge.conf`](dvr-edge.conf) — 로컬 엣지 설정
*   [`bench.example.yml`](bench.example.yml) — 전용 실험 프로젝트 덧붙임 예
