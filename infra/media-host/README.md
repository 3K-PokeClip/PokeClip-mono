# media-host — prod Media 상자 기동 체계

prod Media 상자[^box]를 띄우고 지키는 파일 모음이다. 상자 위에서 MediaMTX ·
nginx · 세그먼트 인덱서 사이드카[^sidecar](켜면 녹화 정리 janitor 도)를 docker
compose 로 한 묶음으로 돌리고, systemd 유닛 하나가 그 묶음 전체를 띄우고 다시
띄운다. 계획 POK-299 의 M2 가 이 디렉터리를 만들었고, M9 의 user_data(인스턴스가
처음 뜰 때 도는 설치 스크립트)가 상자에 설치한다.

*   **무엇을 지키나:** 비밀은 환경변수로 넘기지 않고 메모리 파일 시스템
    (tmpfs — 재부팅하면 사라지는 `/run`)에만 둔다. MediaMTX 의 내부 포트
    (8888 · 9996 · 9997)는 밖에서 닿지 않는다. 재기동은 compose 전체 단위다.
*   **누가 읽나:** 상자를 운영하는 사람(SSM 으로 명령을 보내는 사람)과 M9 ·
    M15 · I1-1 처럼 이 파일을 입력으로 쓰는 작업.
*   **송출 인증은 두 모드다:** 템플릿 변수 `ingest_auth_image` 하나가 옛
    인증(익명 송출 · 팀 IP)과 응답기 인증(MediaMTX 가 송출마다 응답기에 묻는
    방식)을 고른다. 아래 「송출 인증 두 모드」 절이 정본이다.
*   **팀 로컬 환경과 다르다:** 루트 `docker-compose.yml` · `infra/compose/` 는
    로컬 개발용이고, `infra/dev-media/` 는 dev 상시 상자다. 이 디렉터리는 prod
    상자 전용이다.

## 파일 · 서비스 지도

저장소 파일과 상자 위 자리, 그리고 그 파일을 부르는 쪽이다.

| 저장소 파일 | 상자 위 자리 | 부르는 쪽 |
|---|---|---|
| `systemd/pokeclip-media.service` | `/etc/systemd/system/pokeclip-media.service` | systemd(부팅 · `systemctl`) |
| `bin/start-media.sh` | `/opt/pokeclip-media/bin/start-media.sh` | 유닛 `ExecStart` |
| `bin/stop-media.sh` | `/opt/pokeclip-media/bin/stop-media.sh` | 유닛 `ExecStop` · `ExecStopPost`(둘 다 인자 없음 — compose down + 렌더 삭제) |
| `bin/loopback-health.sh` · `paths-count.sh` · `paths-list.sh` · `secret-count.sh` | `/opt/pokeclip-media/bin/` | 운영자(SSM) — secret-count 의 `ssm` 모드만 kty 로컬 |
| `bin/ingest-auth-deny-sync.sh` · `bin/ingest-auth-ip-limit.sh` | `/opt/pokeclip-media/bin/` | 운영자(SSM · 응답기 모드만 — 아래 「응답기 런북」) |
| `bin/lib/` 다섯 — `ops.sh` · `mtx_paths.sh` · `deny_tokens.sh` · `docker_config.sh` · `render_files.sh` | `/opt/pokeclip-media/bin/lib/` | 위 스크립트가 source — 시작 스크립트는 뒤 셋, 운영 스크립트는 `ops.sh`(paths 둘은 `mtx_paths.sh` 도, 거부 목록 동기화는 `deny_tokens.sh` 도) |
| `compose.yml.tmpl` | `/etc/pokeclip-media/compose.yml`(렌더본) | `start-media.sh` · `stop-media.sh` |
| `ingest-auth.env.tmpl` | `/etc/pokeclip-media/ingest-auth.env`(렌더본 · `root:root 0644` · 두 모드 공통) | 응답기 서비스 `env_file` · `ingest-auth-ip-limit.sh`(바꿔 씀) · 시작 스크립트(응답기 모드에서 꼴 점검) |
| `nginx/nginx.conf.tmpl` | `/etc/pokeclip-media/nginx.conf`(렌더본) | compose 가 nginx 에 읽기 전용 마운트(원본이 없으면 만들지 않음: `create_host_path: false`, nginx 기동만 실패) |
| `mediamtx.yml.tmpl` | `/etc/pokeclip-media/mediamtx.yml.tmpl`(토큰이 남은 렌더본) | `start-media.sh` 가 기동마다 비밀을 채움 |
| (M9 이 씀) | `/etc/pokeclip-media/media.env` | 유닛 `EnvironmentFile` · 운영 확인 스크립트. 응답기 모드 키 `INTERNAL_API_TOKEN_SECRET_ARN` · `INGEST_AUTH_DENYLIST_PARAM` 은 응답기 모드에서만 읽는다 |
| (M9 이 씀) | `/root/.docker/config.json`(ECR 자격 도우미 설정) | docker · 시작 스크립트 선행 점검 |
| (시작 스크립트가 씀) | `/run/pokeclip-media/mediamtx.yml` · `mtx_api_pass` | MediaMTX · 사이드카(읽기 전용 bind — 원본이 없으면 만들지 않음: `create_host_path: false`, 그 컨테이너 기동만 실패) |
| (시작 스크립트가 씀 · 응답기 모드) | `/run/pokeclip-media/ingest-auth/`(`root:root 0711`) 아래 `ingest_basic_pass` · `mtx_api_pass` · `stream_key_resolve_token`(`10003:10003 0400`) · `state/`(`10003:10003 0700`) | 응답기(비밀 셋은 읽기 전용 bind · `state/` 는 쓰기 bind) |
| (시작 스크립트 · 거부 목록 동기화가 씀 · 응답기 모드) | `/var/lib/pokeclip-media/ingest-auth/deny/deny-tokens`(디렉터리 `root:10003 0750` · 파일 `root:10003 0640`) | 응답기(**디렉터리**를 읽기 전용 bind — rename 한 새 파일이 보이게) |
| `bundle/Dockerfile` | 설치하지 않음 — 설치 묶음 이미지(`pokeclip-prod/media-host-bundle`)를 만든다. 위 `bin/` · `bin/lib/` · `mediamtx.yml.tmpl` 은 이 이미지에 담겨 상자에 온다 | O7c 빌드 · M9 부트스트랩(`docker create` · `docker cp` 로 꺼내 설치) |
| `ops/force-diff.sh` | 설치하지 않음 | kty 로컬(force 복구 대조) |
| `images.json` | 설치하지 않음 | 시험 · O7c 미러링 |
| `test/` | 설치하지 않음 | 로컬 · CI 시험 |

네 템플릿(`compose.yml.tmpl` · `nginx/nginx.conf.tmpl` · `mediamtx.yml.tmpl` ·
`ingest-auth.env.tmpl`)은 Terraform `templatefile()` 문법이다. M9 user_data 가
렌더하고, 시험(`test/lib/render.sh`)도 같은 함수를 부른다.

### 서비스

compose 프로젝트 이름은 `pokeclip-media` 다(유닛의 `MEDIA_COMPOSE_PROJECT`).

| 서비스 | 하는 일 | 엔진 재시작 | 로그가 가는 곳 |
|---|---|---|---|
| `mediamtx` | SRT 수신 · LL-HLS · 재생 서버 · 녹화. 망 칸[^netns] 주인이고 공개 포트를 연다 | `"no"` | 유닛 저널[^journal] + json-file |
| `nginx` | 앞단 프록시(live · playback · 제어면 · 헬스 포트) | `"no"` | 유닛 저널 + json-file |
| `sidecar` | 세그먼트 인덱서 · 업로더 · 관리 API 관측 | `on-failure` | journald 태그 `pokeclip-media-sidecar` |
| `ingest-auth` | 송출 인증 응답기 — 응답기 모드에서만 렌더한다(`ingest_auth_image` 가 비면 서비스가 없다). MediaMTX 의 송출 · 읽기 · 관리 API 인증 요청에 망 칸 루프백 `127.0.0.1:8080` 으로 답하고(헬스 `127.0.0.1:8081`), 4C(스트림 키 판정 — auth 서비스에 묻는 호출)를 부른다 | `on-failure` | 태그 `pokeclip-media-ingest-auth` |
| `janitor` | 녹화 정리 — 업로드가 끝난 녹화 조각을 지운다. 사이드카 이미지의 janitor 명령이고 `janitor_enabled` 가 참일 때만 렌더한다 | `on-failure` | 태그 `pokeclip-media-janitor` |

*   **재기동 단위:** 시작 스크립트는 `mediamtx` · `nginx` 의 출력에만
    붙는다[^attach]. 둘 중 하나가 끝나면 compose 전체가 내려가고, 유닛이 20초 뒤
    전체를 다시 띄운다. 나머지 서비스는 엔진(dockerd)이 그 컨테이너만 다시 띄운다 — DB
    장애로 사이드카가 죽어도 송출은 끊기지 않는다.
*   **`unless-stopped` · `always` 를 쓰지 않는 이유:** 데몬 재시작 · 부팅 때
    엔진이 컨테이너를 스스로 띄워, systemd 와 기동자가 둘이 된다.
*   **볼륨:** `recordings`(녹화) · `hooks`(훅 스풀) · `dvr`(LL-HLS 조각). 사이드카는
    `recordings` · `hooks` 를 읽기 전용으로 붙인다. 녹화는 MediaMTX 가 보존
    기한(`recordDeleteAfter` — `mediamtx.yml.tmpl` 에 적지 않아 기본 24시간)이
    지나면 스스로 지우고, janitor 를 켜면 업로드가 끝난 조각을 그 전에 지운다.

### 설정 파일

*   **`control_upstream`**(nginx 템플릿 변수): 제어면 상류 `<호스트>:<포트>`.
    **비어 있으면 제어면 경로(`/api/ingest/` · `/api/streams/`)가 모두 404** 이고
    상류 호출은 0이다. 제어면 서버(I1-6)가 배포되기 전의 기본값이다. 값을 넣으면
    그 경로를 그대로 넘긴다. 제어면 포트의 그 밖 경로는 언제나 404 다.
*   **nginx 는 9997(관리 API)로 넘기는 설정을 두지 않는다.** 렌더 결과에 그 번호가
    한 번도 나오지 않는지 `render_test.sh` 가 본다.
*   **`rewind_public_base_url`**(compose 템플릿 변수): 사이드카
    `REWIND_PUBLIC_BASE_URL` — 되감기 목록 URI 앞머리다. 목록 줄은 이 값 +
    `/` + S3 키(`dvr/…`)이고, prod 는 영상 CDN 주소 `https://media.pokeclip.com`
    (CDN 의 `/dvr/*` 가 rewind 버킷 — 설계 4-5)다. 사이드카는 `S3_BUCKET` 이
    있으면 이 값 없이 기동하지 않으므로(재시작 반복) 늘 넘긴다. https 절대
    URL · 끝 `/` 없음은 M9 모듈이 plan 에서 검증한다.
*   **`detached_log_driver`**(compose 템플릿 변수): prod 는 `"journald"`.
    `"json-file"` 은 journald 가 없는 로컬 엔진에서 띄울 때만 쓴다. 그 밖의 값은
    모두 journald 로 떨어진다.
*   **`janitor_enabled`**(compose 템플릿 변수, bool): 참이면 janitor 를
    렌더하고, 거짓이면 서비스가 없다. bool 로 바꿀 수 없는 값(예: `"yes"` · 빈
    문자열 · `null` · `1`)은 렌더 실패다. 일부 문자열(`"true"` · `"false"` 등)은
    bool 로 바뀌어 통과한다.
    *   켜면 사이드카 이미지를 녹화 디렉터리 소유자 계정 `10002:10002` · 읽기
        전용 루트로 띄우고 `JANITOR_ENABLED: "true"` 를 넣는다. janitor 는
        업로드가 끝난 조각을 MediaMTX 의 보존 기한보다 먼저 지운다. 보존
        기한 삭제는 MediaMTX 몫이라 janitor 를 꺼도 계속된다. recordings 에
        쓰기로 붙는 것은 mediamtx 와 janitor 이고 사이드카는 `:ro` 다.
    *   켜고 끄는 길은 설정 반영 교체다(아래 「되돌리기」). 끄면 다음 시작의
        `--remove-orphans` 가 옛 janitor 컨테이너를 지운다.
    *   로컬 compose 는 서비스를 남긴 채 `JANITOR_ENABLED` 를 비워 대기시킨다
        (`.env` 한 줄과 `up -d` 로 바로 바꿀 수 있어서다). 이 상자는 env 를
        바꾸는 길도 상자 교체라 대기 컨테이너를 둘 이유가 없어, 끈 상태에서는
        렌더하지 않는다.
    *   켠 뒤의 고정 줄(`db_connected`(pool=janitor) · `janitor_started` · 표
        대기 `janitor_schema_waiting`)은 태그 저널 `-t pokeclip-media-janitor`
        에 있다. 켜기 전 전제는 아래 「후속」 의 janitor 행이다.
*   **MediaMTX 설정은 기본값 전체를 대체한다.** 템플릿에 없는 키는 MediaMTX
    기본값이고, `paths: all_others:` 가 빠지면 모든 송출이 거부된다.
*   **송출 인증 변수 셋**(`ingest_auth_image` · `stream_key_resolve_url` ·
    `ingest_auth_ip_limit`)은 아래 「송출 인증 두 모드」 절이 정본이다.

## 송출 인증 두 모드

`ingest_auth_image` 하나로 모드를 고른다. 빈 문자열이면 **옛 모드**(M2 내부
인증 — 익명 송출, 송출 통제는 NLB 의 팀 IP)이고, 이미지 참조면 **응답기
모드**(MediaMTX 가 송출 연결마다 같은 망 칸의 응답기에 묻고, 응답기가 4C 로
스트림 키를 판정한다)다. O7 은 옛 모드로 뜨고, O8a 2 의 설정 반영 교체가
다이제스트를 넣어 응답기 모드로 바꾼다. 응답기 계약 전문은
[media/cmd/ingest-auth/CONTRACT.md](../../media/cmd/ingest-auth/CONTRACT.md)
다.

### 고르는 변수와 요구 입력

| 이름 | 꼴 · 기본 | 모드 |
|---|---|---|
| `ingest_auth_image`(compose · mediamtx 템플릿 변수) | `^[^@]+@sha256:[0-9a-f]{64}$` 또는 빈 문자열 | 빈 값 = 옛 모드 · 찬 값 = 응답기 모드. **두 템플릿에 같은 값**을 넘긴다 |
| `stream_key_resolve_url`(compose 템플릿 변수) | `http://auth.pokeclip.internal:<auth 포트>` | 응답기 모드만 필수(옛 모드는 비어도 된다 — `templatefile()` 이 안 쓰는 갈래의 변수도 요구해 늘 넘긴다) |
| `ingest_auth_ip_limit`(`ingest-auth.env.tmpl` 변수) | `"on"` · `"off"`, 기본 `"on"` | 두 모드 모두 렌더 · 응답기 모드에서만 읽힌다 |
| `media.env` 의 `INTERNAL_API_TOKEN_SECRET_ARN` | 4C 토큰(공용 창구 `INTERNAL_API_TOKEN`) 비밀 ARN | 응답기 모드만 필수 |
| `media.env` 의 `INGEST_AUTH_DENYLIST_PARAM` | 거부 목록 SSM 파라미터 이름(`/pokeclip/prod/media/ingest-auth-deny-tokens`) | 응답기 모드만 필수 |

`media.env` 키로는 모드를 정하지 않는다. 응답기 모드 키가 옛 모드 상자에 미리
있어도 읽지 않는다.

### 모드별 동작

| 축 | 옛 모드 | 응답기 모드 |
|---|---|---|
| 송출(publish) | 내부 인증의 익명 항목 — 누구나. 통제는 NLB 팀 IP | `authMethod: http` → 응답기(`127.0.0.1:8080`) → 4C. 허용하면 응답기가 그 계정의 SRT passphrase 를 돌려준다 |
| HLS 읽기 | CDN Bearer 세션만(read 사용자 없음) | CDN Bearer 세션만 — 그 세션은 MediaMTX 가 인증을 건너뛰고, 그 밖의 read 는 응답기가 401 로 거부한다 |
| SRT 읽기 | read 사용자 없음(막힘) | 응답기는 `protocol=srt` read 에 200 을 주고, `srtReadPassphrase` 잠금이 막는다 |
| 응답기에 묻지 않는 것 | — | `authHTTPExclude: [{action: playback}]` 하나 |
| 관리 API(9997) | 내부 사용자 `pokeclip-sidecar` | 응답기가 판정한다(본문 출발지 = 127.0.0.1 ∧ 사이드카 자격 일치) |
| SRT 잠금 · `overridePublisher` | 없음 · 기본(허용) | `srtPublishPassphrase` · `srtReadPassphrase`(기동마다 새로 만든 서로 다른 값) · `overridePublisher: no` |
| 기동 때 만드는 비밀 | API 비밀번호 | API 비밀번호 · 호출자 Basic 비밀번호 · 잠금 둘 |
| 기동 때 읽는 외부 값 | CDN 비밀 | CDN 비밀 · 4C 토큰(Secrets Manager) · 거부 목록(SSM Parameter) · `ingest-auth.env` |
| compose 서비스 | `ingest-auth` 없음 | `ingest-auth` 있음 |

*   **판정 원천은 렌더본 하나다.** 시작 스크립트는 1단계 렌더본
    `mediamtx.yml.tmpl` 의 토큰 집합으로 모드를 안다 — CDN 비밀 · API
    비밀번호 각 1 이고 응답기 토큰(Basic 비밀번호 · 잠금 둘) 0 이면 옛 모드,
    CDN 비밀 · Basic 비밀번호 · 잠금 둘 각 1 이고 API 비밀번호 0 이면 응답기
    모드, 그 밖이면 `MEDIA START FAIL render
    template-token` 이다. 모드 판정 줄은 따로 내지 않는다.
*   **어긋남은 막는다.** compose 렌더본의 `  ingest-auth:` 줄 수가 응답기
    모드에서 1, 옛 모드에서 0 이 아니면 `MEDIA START BLOCKED: ingest-auth mode
    mismatch` 다. CDN 비밀을 읽은 뒤, 응답기 모드의 외부 입력(IP 한도 env ·
    4C 토큰 · 거부 목록)을 받기 전에 본다.
*   **잠금을 옛 모드에 넣지 않는 이유:** 송출 잠금은 내부 인증 성공에
    passphrase 가 없으면 그 값으로 판정해, 익명 송출(O7f 9 포함)이 깨진다.
*   **`overridePublisher` 는 옛 모드에서 기본(허용)이다** — 아래 「알려진
    한계」.

### O7 → O8a 2 전환

옛 모드 상자에서 설정 반영 교체로 `ingest_auth_image` 에 다이제스트를 넣는다.
교체 전에 아래 「O8a 2 전 필수 조건」 을 모두 채운다.

*   **시작 스크립트가 보는 입력**(`ingest-auth.env` · 4C 토큰 · 거부 목록)이
    빠지거나 꼴 밖이면 새 상자 B 의 시작 스크립트가 BLOCKED 줄을 내고 compose
    up 을 부르지 않는다(아래 「판정 줄」 표 · 유닛은 20초마다 다시 시도한다).
*   **시작 스크립트가 보지 않는 입력**은 BLOCKED 줄 없이 지나간다.
    `stream_key_resolve_url` 이 비면 응답기가 기동을 거부한 채 `on-failure` 로
    되풀이되고(CONTRACT 1-5 의 7 — 필수 값), 주소가 틀리거나 Media → auth SG
    짝이 빠지면 응답기는 뜨지만 4C 가 실패해 모든 송출이 거부된다. 앞은 M9
    모듈 validation(아래 「후속」 M9 행)이, 둘 다 아래 점검표 3(전환 직후
    `loopback-health.sh --passphrase`)이 잡는다.
*   교체 절차가 B 를 받아들일지 판정하는 방법은 M9 런북의 「설정 반영 교체」
    가 정한다.

### O8a 2 전 필수 조건

점검표다. 운영 실행(SSM · 승인)은 O8a 몫이고 이 디렉터리는 절차만 둔다.

1.  **응답기 이미지 판:** 다이제스트는 **M15 머지 뒤 develop 커밋**에서 빌드한
    것이어야 한다. 그 전 판은 read 에 늘 200 을 줘 Bearer 없는 LL-HLS 읽기가
    다시 열린다. 빌드 커밋 해시를 POK-310 · POK-301 코멘트에 적는다.
1.  **Bearer 없는 HLS 몰아치기 판단(M15 계획 결함표 P47):** 응답기 모드부터 ALB 에 닿는
    누구나 비CDN 세션을 열 수 있고, 세션마다 MediaMTX 경로 관리자가 응답기
    왕복만큼 붙잡힌다(새 송출 지연 · `budget_exhausted` — 응답기 예산이 바닥나
    새 송출을 판정 없이 거부하는 것). 판단 시점은 O8a 5 가
    아니라 **O8a 2 전**이다.
    *   S-47(이 몰아치기의 로컬 실측) 수치를 kty 에 보고한다(아래 「시험」 의
        기록 자리).
    *   M8 공개 ALB 의 원본 검증(CloudFront 전용 헤더 등) 상태를 확인한다.
    *   nginx live 경화 회부의 결론을 받는다.
1.  **전환 직후 상자 위 음성 확인**(SSM · 실행 직전 kty 승인):
    *   **시험 송출이 활성인 상태에서** 상자 루프백의 Bearer 없는
        `…/<시험 ID>/index.m3u8?cookieCheck=1` 이 401 이 **아니면 실패**다.
        시험 송출이 없으면 옛 판 응답기도 404 라 가르지 못한다. 같은 요청에
        `Bearer <CDN 비밀>` 이면 200 이다.
    *   익명 SRT 송출이 거부되고, `loopback-health.sh --passphrase` 가
        통과한다.
1.  **입력 준비:**
    *   SSM 파라미터(머리 줄 `# pokeclip deny-tokens v1`) · INTERNAL_API_TOKEN
        비밀 · 인스턴스 역할 권한 둘(아래 「후속」 M9 행) ·
        `stream_key_resolve_url` · `ingest-auth.env` 렌더.
    *   INTERNAL_API_TOKEN 비밀이 **평문 단일 값**인지 확인한다(미확인 —
        3번에 확인한다). JSON 이 한 줄이면 시작 스크립트의 꼴 검사를 지나
        **기동은 되고**, 4C 가 실패해 모든 송출이 거부된다(태그 저널
        `ingestauth_auth_degraded`). 여러 줄 JSON 은 제어 문자(줄바꿈) 때문에
        `MEDIA START BLOCKED: ingest-auth resolve token unavailable` 로 막힌다.
    *   상자 root 의 aws CLI 기록(`cli_history`)이 꺼져 있는지 확인한다
        (미확인).
    *   거부 목록 부모 디렉터리(`/var/lib/pokeclip-media/ingest-auth`)의 소유를
        M9 설치 단언이 본다.
    *   prod `media.env` 에 시험 전용 이음매 `INGEST_AUTH_DENY_DIR` 를 두지
        않는다(아래 「시험 이음매」).

### 되돌리기 순서

**O8a 5 전**(SRT 인바운드 = 팀 IP): `ingest_auth_image` 를 비운 설정 반영
교체가 곧 옛 모드 복귀다.

**O8a 5 뒤**(SRT 인바운드 = `0.0.0.0/0`) — 이 순서만 쓴다. 옛 모드는 익명
송출이라, 인바운드를 먼저 좁히지 않으면 인터넷의 누구나 송출 · 진행 중 송출
탈취 · DVR 리셋을 할 수 있다(M15 계획 결함표 P53).

1.  platform tfvars 의 `ingest_allowed_cidrs` 를 팀 IP 로 되돌려 plan(「NLB SG
    인그레스 규칙 교체만 · 그 밖 0」) → apply 하고, 팀 IP 밖에서 SRT 연결이
    안 되는 것을 확인한다.
1.  `ingest_auth_image` 를 비운 설정 반영 교체(옛 모드).
1.  팀 IP 밖 출발지에서 익명 송출이 거부(연결 불가)되는지 확인한다.

1 을 건너뛰는 롤백은 금지다. **M15 PR revert 도 같다** — revert 한 템플릿은
옛 모드뿐이라, O8a 5 뒤에는 위 1 을 먼저 하고 나서 revert 판으로 교체하고,
교체 뒤 위 3 을 확인한다.
응답기 코드만(read 판정) 되돌리는 것은 응답기 모드 상자가 없을 때만 한다.

## 시작 · 정지

재기동 · 정지는 이 유닛으로만 한다(아래 운영 규칙 ①). SSM 으로 보내는 명령은
이 꼴이다(실행 직전 kty 승인).

```shell
systemctl restart pokeclip-media
systemctl stop pokeclip-media
systemctl start pokeclip-media
```

**저널은 SSM 으로 원문을 내지 않는다(운영 규칙 ③).** SSM 명령으로는 판정 줄의
**건수만** 낸다. 아래는 이번 기동(유닛이 active 가 된 시각) 이후의 CDN 비밀 차단
줄 수를 내는 꼴이다.

```shell
fail() { echo "BLOCKED COUNT FAIL $1"; exit 2; }
since="$(systemctl show -P ActiveEnterTimestamp pokeclip-media)" || fail since
[ -n "${since}" ] || fail since
log="$(journalctl -q -u pokeclip-media --since "${since}" -o cat --no-pager)" \
  || fail journal
n="$(printf '%s\n' "${log}" | awk -v a='MEDIA START BLOCKED: CDN secret unavailable' \
  'index($0, a) { n++ } END { print n + 0 }')" || fail count
echo "BLOCKED COUNT ${n}"
```

*   **종료 코드:** 0 = 셌다(0건 포함 — 판정은 출력 숫자로 한다) · 2 = 조회
    실패(`BLOCKED COUNT FAIL since|journal|count`). SSM 은 스크립트의 마지막
    명령 종료 코드를 실행 상태로 보고하므로, 조회 실패는 `exit 2` 로 직접
    끝낸다.
*   `grep -c` 를 쓰지 않는다 — 맞는 줄이 0이면 종료 1 이라, 0건이 SSM 에서
    실패로 보인다. `awk` 는 0건에도 0 을 내고 종료 0 이다.
*   `since` 가 비면(유닛이 한 번도 active 가 된 적 없음) 실패다. 빈 값으로
    `--since` 를 부르면 저널 전체를 세게 된다.

저널 원문은 상자 셸에서 직접 보고, SSM 명령 출력 · 기록으로 남기지 않는다.
nginx 요청 줄 · 서비스 진단이 섞여 있고, 비밀이 잘못 찍혔다면 그것을 SSM 기록에
다시 옮기게 된다.

**시작 스크립트(`start-media.sh`)가 하는 일 — 순서대로:**

1.  선행 점검 세 항목을 매번 같은 순서로 모두 본다: Compose 판(`docker compose
    version --short` = `MEDIA_EXPECTED_COMPOSE_VERSION`) · ECR 자격 도우미
    (`docker-credential-ecr-login`) · `${DOCKER_CONFIG}/config.json` 의
    `credHelpers` 에 ECR 호스트. 하나라도 실패하면 `MEDIA PREREQ FAIL <항목>`
    으로 끝낸다.
1.  CDN 비밀(MediaMTX `hlsCDNSecret`)을 Secrets Manager 에서 읽는다. argv 에는
    ARN 만 오른다. 실패 · 빈 값 · 허용 글자(`[A-Za-z0-9._+=-]`) 밖이면
    `MEDIA START BLOCKED: CDN secret unavailable`.
1.  사이드카용 관리 API 비밀번호를 새로 만든다(`/dev/urandom` 32바이트 → 16진
    64자). 저장하지 않는다.
1.  템플릿의 토큰 집합으로 모드를 알고(위 「송출 인증 두 모드」), compose
    렌더본의 응답기 서비스 수가 그 모드와 맞는지 본다. 어긋나면 `MEDIA START
    BLOCKED: ingest-auth mode mismatch`.
1.  응답기 모드만 — 외부 입력을 이 순서로 받는다. 하나라도 실패하면 그
    BLOCKED 줄을 내고 compose up 을 한 번도 부르지 않는다.
    1.  compose 파일 옆 `ingest-auth.env` 가 링크 아닌 일반 파일이고 정확히
        `INGEST_AUTH_IP_LIMIT=on` 또는 `=off` 한 줄인지 본다(`… ingest-auth
        ip-limit setting unavailable`). 이 파일이 없으면 compose 의 `required`
        env_file 때문에 판정 줄 없이 재시작만 되풀이되기 때문이다.
    1.  4C 토큰을 Secrets Manager 에서 읽는다(argv 에는 ARN 만). ARN 빔 ·
        실패 · 빈 값 · `None` · 제어 문자 · 앞뒤 공백 · 값 + 줄바꿈이
        4096바이트 초과(값이 4096바이트면 이미 거부)면
        `… ingest-auth resolve token unavailable`.
    1.  거부 목록을 SSM 파라미터에서 받아(argv 에는 이름만) 거부 목록
        디렉터리에 rename 으로 둔다. 이름 빔 · 받기 실패면 `… ingest-auth
        denylist unavailable`, 공백뿐 · 첫 줄이 머리 줄
        `# pokeclip deny-tokens v1` 이 아님 · NUL · 64 KiB 초과면 `…
        ingest-auth denylist invalid`. **머리 줄만 있는 목록(토큰 0건)은
        정상이다**(kty 정의 — 「빔」 은 파라미터가 없거나 공백뿐인 것).
    1.  Basic 비밀번호 · 송출 잠금 · 읽기 잠금을 API 비밀번호와 같은 꼴로 새로
        만든다. 실패면 `… generated credential unavailable`.
1.  템플릿의 고정 토큰(각각 정확히 한 번)을 채운다. 옛 모드는 두 곳
    (`@@HLS_CDN_SECRET@@` · `@@MTX_API_PASS@@`), 응답기 모드는 네 곳
    (`@@HLS_CDN_SECRET@@` · `@@INGEST_AUTH_BASIC_PASS@@` ·
    `@@SRT_PUBLISH_LOCK@@` · `@@SRT_READ_LOCK@@`)이다. 결과를
    `/run/pokeclip-media/mediamtx.yml`(10002 소유 0400)로, API 비밀번호를
    `mtx_api_pass`(10001 소유 0400)로 둔다.
    응답기 모드는 `ingest-auth/` 아래에 응답기 파일 셋(10003 소유 0400)과
    `state/`(10003 소유 0700)를 더 둔다 — 응답기 모드 `mediamtx.yml` 에는 API
    비밀번호가 없다. 모든 파일을 임시 파일에 다 쓰고 대상을 모두 점검한
    뒤에야 차례로 이름을 바꾼다 — 실행 중인 스택이 보는 옛 파일을 제자리에서
    고쳐 쓰지 않는다. 중간에 실패하면 새 임시 파일은 남지 않는다. 이름을 바꾸기
    전에 실패하면 이미 있던 파일은 그대로이고, 이름 바꾸기 하나가 실패하면
    이번에 옮긴 파일을 지워 짝이 어긋난 설정을 남기지 않는다.
1.  `docker compose --ansi never -p pokeclip-media -f
    /etc/pokeclip-media/compose.yml up --force-recreate --remove-orphans
    --abort-on-container-exit --attach mediamtx --attach nginx` 로
    바뀐다(exec). 컨테이너는 늘 새로 만든다(`--force-recreate`) — 남은
    컨테이너가 지운 옛 렌더(옛 CDN 비밀 · 옛 API 비밀번호)를 붙든 채 다시
    쓰이지 않게 한다. 렌더에서 빠진 서비스(비운 이미지 변수 · 끈 janitor)의 옛
    컨테이너도 지운다(`--remove-orphans`) — 남으면 옛 비밀을 든 채 같은 망 칸에
    붙어 있다.

인자는 받지 않는다. 점검을 건너뛰는 플래그 · 변수는 없다(아래 「시험
이음매」).

**정지:** `systemctl stop` 은 두 단계로 멈춘다. 먼저 주 프로세스(compose up)에
TERM 을 보내 스스로 끝나기를 기다리고(상한 30초), 그다음 `stop-media.sh` 가
`docker compose … down` 과 렌더 파일 삭제를 한다. `ExecStopPost` 가 끝난 방식과
상관없이 한 번 더 compose 를 내리고 렌더를 지운다(정상 정지에서는 빈 down).
`down` 은 볼륨을 지우지 않는다.

*   **정지 뒤 유닛 상태는 `inactive` 가 정상이다.** 기동 중(선행 점검 · 비밀
    조회 · 렌더)에 멈춰도 같다 — 시작 스크립트가 TERM 에 compose up 과 같은
    130 으로 끝나고, 유닛은 130 을 성공으로 본다.
*   `failed` 면 정지 단계 하나가 실패한 것이다. `down` 실패(판정 줄
    `MEDIA STOP down exit <n>`)나 정지 중 주 프로세스가 0 · 130 이 아닌 코드로
    끝난 경우다. 저널을 본다.
*   `down` 이 실패하면(compose 파일이 깨진 경우 포함) 정지 스크립트가 프로젝트
    라벨로 남은 컨테이너를 찾아 지운다(`MEDIA STOP fallback removed <n>`).
    그 docker 호출은 하나에 10초 상한이고, 넘기면 `MEDIA STOP fallback FAIL` 이다.
    `down` · 폴백이 끝나지 않아 systemd 가 정지 명령에 TERM 을 보내도 렌더는
    지운다(그때 남은 컨테이너는 ExecStopPost 의 정지 스크립트가 다시 거둔다).
*   정지 뒤에는 프로젝트 컨테이너가 0 인지 건수로 확인한다. `containers 0`
    줄이 나와야 회수가 끝난 것이다. 그 줄 없이 오류만 나오면 조회가 실패한
    것이다(데몬 정지 · 권한) — 0 으로 읽지 않는다.

    ```shell
    ids=$(docker ps -a -q --filter label=com.docker.compose.project=pokeclip-media) \
      && printf 'containers %s\n' "$(printf '%s' "$ids" | grep -c .)"
    ```

**크래시:** 주 프로세스가 실패로 끝나면(compose 플러그인 · 유닛 cgroup
SIGKILL 등) systemd 252 는 `ExecStop` 을 건너뛰고 `ExecStopPost` 로 간다. 그래서
`ExecStopPost` 가 컨테이너를 내린다 — 렌더만 지우면 옛 비밀을 붙든 컨테이너가
남는다.

**재시도 한도가 없다.** 선행 점검이 실패하거나 CDN 비밀 조회(Secrets Manager
· NAT)가 몇 분 끊겨도 유닛은 `failed` 로 굳지 않고 20초마다 다시 시도한다.
20초는 시도 사이의 대기일 뿐 복구 시간의 상한이 아니다 — 시작 스크립트는 CDN
비밀 조회(aws CLI)에 시간 상한을 걸지 않아, 조회가 걸려 있으면 aws CLI 가
스스로 끝날 때까지 다음 시도가 늦어진다.
그동안 저널에 같은 판정 줄이 되풀이된다. RDS 장애는 유닛이 아니라 사이드카만
다시 띄운다(위 「재기동 단위」).

## 판정 줄

스크립트는 고정 문자열 줄만 낸다. SSM 기록 · 저널에서 이 줄로 판정한다.

| 스크립트 | 줄 | 뜻 |
|---|---|---|
| `start-media.sh` | `MEDIA PREREQ OK\|FAIL compose-version` | Compose 판 일치 여부 |
| | `MEDIA PREREQ OK\|FAIL ecr-credential-helper` | 자격 도우미가 PATH 에 있는지 |
| | `MEDIA PREREQ OK\|FAIL docker-config <경로>` | 그 `config.json` 의 `credHelpers` 에 ECR 호스트 |
| | `MEDIA START BLOCKED: CDN secret unavailable` | CDN 비밀을 못 읽음 · 빈 값 · 허용 글자 밖 |
| | `MEDIA START BLOCKED: MTX API credential unavailable` | API 비밀번호 생성 실패 |
| | `MEDIA START BLOCKED: ingest-auth mode mismatch` | compose 렌더본의 응답기 서비스 수가 템플릿 토큰 집합의 모드와 다름(두 템플릿에 다른 `ingest_auth_image`) |
| | `MEDIA START BLOCKED: ingest-auth ip-limit setting unavailable` | 응답기 모드 — `ingest-auth.env` 없음 · 링크 · 일반 파일 아님 · `INGEST_AUTH_IP_LIMIT=on\|off` 한 줄이 아님 |
| | `MEDIA START BLOCKED: ingest-auth resolve token unavailable` | 응답기 모드 — 4C 토큰 ARN 빔 · 못 읽음 · 꼴 밖 |
| | `MEDIA START BLOCKED: ingest-auth denylist unavailable` | 응답기 모드 — 거부 목록 파라미터 이름 빔 · 받기 실패 |
| | `MEDIA START BLOCKED: ingest-auth denylist invalid` | 응답기 모드 — 받은 거부 목록이 꼴 밖(공백뿐 · 머리 줄 없음 · NUL · 64 KiB 초과) |
| | `MEDIA START BLOCKED: generated credential unavailable` | 응답기 모드 — Basic 비밀번호 · 잠금 둘 생성 실패 |
| | `MEDIA START FAIL render <단계>` | 렌더 실패 — `runtime-dir` · `template` · `template-token` · `owner` · 파일마다 `<앞머리>tmpfile` · `write` · `chown` · `target` · `rename`. 앞머리는 `mediamtx.yml` 이 없음, 사이드카 비밀번호가 `api-pass-`, 응답기 모드 파일이 `ingest-auth-basic-pass-` · `ingest-auth-api-pass-` · `ingest-auth-resolve-token-` 다. 응답기 모드 디렉터리 단계는 `ingest-auth-dir` · `ingest-auth-state-dir` · `deny-dir`(거부 목록 디렉터리 · 남은 임시 파일 정리 · 임시 파일), 거부 목록 파일 설치 단계는 `deny-install` |
| | `MEDIA START FAIL usage` | 인자를 줬음 |
| `stop-media.sh` | `MEDIA STOP FAIL usage\|runtime-dir` | 인자 오류 · `RUNTIME_DIR` 이 절대 경로가 아님 |
| | `MEDIA STOP down exit <n>` | `compose down` 이 0 아닌 코드 |
| | `MEDIA STOP fallback removed <n>` · `MEDIA STOP fallback FAIL` | down 실패 뒤 프로젝트 라벨로 지운 컨테이너 수 · 그 조회 · 삭제 실패 |
| | `MEDIA STOP interrupted` | down 중 TERM · INT — down 을 멈추고 렌더를 지운 뒤 143 · 130 |
| | `MEDIA STOP CLEANUP FAIL <디렉터리>` | 렌더 파일이 지운 뒤에도 남음(`RUNTIME_DIR` 과 응답기 모드의 `RUNTIME_DIR/ingest-auth` — 비밀 셋 · 임시 파일. `state/` 는 남긴다) |
| `paths-count.sh` | `PATHS <n>` | 경로 항목 수(송출 중 아닌 것 포함) |
| | `PATHS FAIL incomplete` | 응답이 여러 페이지 — 원자 스냅샷 아님 |
| | `PATHS FAIL <단계>` | `usage` · `scratch`(스크래치 디렉터리를 못 만듦) · `config` · `sidecar` · `credentials` · `request` · `timeout` · `auth`(401) · `http` · `response` |
| `paths-list.sh` | (표준 출력) 경로 이름 한 줄에 하나 | 송출 중인 ingestStreamId 목록 |
| | `PATHS LIST <n>`(표준 오류) | 그 목록의 개수 — force 대조에 함께 넘긴다 |
| | `PATHS LIST INCOMPLETE`(표준 오류) | 여러 페이지 — force 를 하지 않는다 |
| | `PATHS FAIL <단계>`(표준 오류) | paths-count 와 같은 단계 이름 |
| `loopback-health.sh` | `LOOPBACK OK` | 송출 → 프록시 경유 재생목록 200 |
| | `LOOPBACK FAIL <단계>` | `usage` · `config` · `cdn-secret` · `test-key` · `mediamtx` · `scratch` · `pull` · `sender` · `playlist` · `sender-cleanup` |
| `secret-count.sh` | `SECRET COUNT box\|ssm <n>` | 비밀 값이 든 줄 수 |
| | `SECRET COUNT FAIL <단계>` | `usage` · `config` · `cdn-secret` · `test-key` · `api-pass`(관리 API 비밀번호 파일 없음 · 못 읽음 · 꼴 밖 — 정지 뒤에는 정상) · `ingest-auth`(box — 응답기 모드 표지 넷 가운데 일부만 있음 · 꼴 밖 · 못 읽음) · `scratch` · `journal` · `mediamtx` · `nginx` · `docker-logs` · `ssm-command` · `ssm-list` · `ssm-status` · `ssm-output` · `ssm-truncated` · `grep` |
| `ingest-auth-deny-sync.sh` | `INGEST AUTH DENY SYNC OK [release] tokens=<N>` | 거부 목록을 받아 두고 응답기 healthcheck 통과(`release` 면 응답기 재생성 · healthy 까지). N 은 설치한 목록의 유효 토큰 수(주석 · 빈 줄 · 중복 제외) — 기대한 수와 대조한다 |
| | `INGEST AUTH DENY SYNC FAIL <단계>` | `usage`(종료 2) · `config`(옛 모드에서 파라미터 이름이 비면 여기 — 이름을 먼저 본다) · `not-running`(옛 모드 · 정지 — 아무것도 하지 않음) · `ssm` · `invalid` · `install` · `health` · `recreate` · `unhealthy` |
| `ingest-auth-ip-limit.sh` | `INGEST AUTH IP LIMIT OK on\|off` | env 파일을 바꾸고 응답기를 재생성해, 새 컨테이너의 `ingestauth_started` 줄 `ip_limit` 가 요청한 값과 같음 |
| | `INGEST AUTH IP LIMIT FAIL <단계>` | `usage`(종료 2) · `config` · `not-running` · `write` · `recreate` · `unhealthy` · `journal` · `verify` |
| `ops/force-diff.sh` | `FORCE REMAINING <n>` + 남은 ID | n 이 0 이면 복구 완료 |
| | `FORCE FAIL input A\|B\|ended` · `FORCE FAIL usage` | 입력 거부 · 인자 오류 |
| | `FORCE FAIL count A\|B` · `FORCE FAIL empty A` | 잘린 목록(ID 줄 수가 개수 인자와 다름 · 마지막 줄에 끝 줄바꿈 없음) · A 에 ID 가 없음 |

`FAIL` 줄은 「0 건」 이 아니다. 조회가 실패하면 실패로 끝낸다.

## 운영 확인 스크립트

모두 상자에서 SSM 으로 돌린다(실행 직전 kty 승인). 스크립트는
`/etc/pokeclip-media/media.env` 를 스스로 읽지 않으므로, 그 값이 필요한 것은
먼저 셸에 읽어 들인다. 아래는 그 꼴이고, 정본 명령문은 M9 의 런북이 정한다.

```shell
set -a; . /etc/pokeclip-media/media.env; set +a
/opt/pokeclip-media/bin/paths-count.sh
```

`media.env` 에는 비밀 값이 없다 — 판 · ARN · 호스트 · 포트 · 이미지 참조만 있다.

| 스크립트 | 쓰는 자리 | 읽는 `media.env` 키 |
|---|---|---|
| `paths-count.sh` | 교체 4(ingest 전환 직전 게이트) · 운영 MediaMTX 설정의 로컬 compose 실측 | 없음 |
| `paths-list.sh` | force 1 · 6, 리스 만료 중단 통지 | 없음 |
| `loopback-health.sh [--passphrase] <ingestStreamId>` | 교체 3 · O7f 9 | `CDN_SECRET_ARN` · `LOOPBACK_SENDER_IMAGE` · `MEDIA_LIVE_PORT` · (`--passphrase` 면) `TEST_STREAM_KEY_SECRET_ARN` |
| `secret-count.sh [--passphrase] box` | O7f 9 · O8a 3 | `CDN_SECRET_ARN` · (`--passphrase` 면) `TEST_STREAM_KEY_SECRET_ARN`. 응답기 모드 대조 넷은 상자 파일에서 읽는다(키 없음) |
| `ingest-auth-deny-sync.sh [--release]` | 거부 목록 덧붙이기 · 해제(아래 「응답기 런북」) | `INGEST_AUTH_DENYLIST_PARAM` |
| `ingest-auth-ip-limit.sh on\|off` | IP 한도 끄기 · 켜기(아래 「응답기 런북」) | 없음 |
| `secret-count.sh [--passphrase] ssm <command-id>` | kty 로컬 — 그 SSM 명령 기록 대조 | 같은 이름을 kty 셸에 넣는다 |

시간 상한은 명령마다 다르다. `MEDIA_OPS_TIMEOUT` 이 모든 외부 명령에 걸리는 것은
아니다.

| 상한 | 값 | 걸리는 곳 |
|---|---|---|
| `MEDIA_OPS_TIMEOUT` | 초, 1–600, 기본 30 | paths 두 스크립트의 컨테이너 찾기 · `docker exec` · secret-count 의 `journalctl` · `docker logs` · `aws` · 루프백의 비밀 읽기 · 컨테이너 찾기 · 송출기 정리(`docker rm` · `ps`) · 응답기 운영 스크립트 둘의 `aws ssm` · `docker` · `docker compose` · `journalctl` 한 번씩 |
| `MEDIA_OPS_HEALTH_WAIT` | 초, 1–600, 기본 120 | 응답기 재생성 뒤 healthy 를 기다리는 상한(두 응답기 운영 스크립트) |
| 고정 300초 | `PULL_TIMEOUT` | 루프백 송출기 이미지 받기(`docker pull`) |
| 고정 5초 · 바깥 10초 | `CURL_MAX_TIME` · `CURL_BOUND` | 루프백 재생목록 요청 한 번(curl `max-time` · 그 바깥 상한) |
| `LOOPBACK_WAIT_SECONDS` | 초, 1–300, 기본 30 | 루프백이 200 을 기다리는 대기 루프의 상한. 진행 중인 요청 한 번(최대 10초)과 재시도 간격(1초)만큼 넘을 수 있다. 송출기는 이보다 15초 길게 송출한다 |
| 고정 5초 | `nc -w 5` | paths 조회의 컨테이너 안 연결 유휴 상한 |

`MEDIA_OPS_TIMEOUT` · `LOOPBACK_WAIT_SECONDS` 가 틀린 값이면 기본값으로 바꾸지
않고 `… FAIL config` 로 끝난다.

**paths 두 스크립트의 조회 방식:** 9997 은 MediaMTX 망 칸의 127.0.0.1 에만 열려
있고 MediaMTX 이미지에는 셸이 없다. 그래서 사이드카 컨테이너 안에서 고정 `sh
-c` 스크립트(`bin/lib/mtx_paths.sh` 의 `MTX_PATHS_EXEC_SCRIPT`)를 돌린다. 자격은
사이드카 자신의 `MTX_API_USER` · `MTX_API_PASS_FILE` 에서 읽고, 비밀번호는 셸 내장
`read` · `printf` → `base64` · BusyBox `nc` 의 표준 입력으로만 지나간다. 자격이
비면 요청하지 않고 `PATHS FAIL credentials` 다.

**force 대조(`ops/force-diff.sh`, kty 로컬):**

1.  force 1 에서 A 상자의 `paths-list.sh` 표준 출력을
    `force-<시각>-A.txt` 로 저장하고, 표준 오류의 `PATHS LIST <n>` 의 n 을
    함께 적어 둔다. **종료 0 일 때만 저장한다** — 실패하면 표준 출력이 비어
    있어, 저장하면 「송출 중 0」 처럼 읽힌다. 같은 자리에
    `force-<시각>-ended.txt` 가 **없을 때만** 빈 파일로 만든다(`[ -e
    force-<시각>-ended.txt ] || : > force-<시각>-ended.txt`). `force-diff.sh` 는
    세 파일이 모두 있어야 대조하고(없으면 `FORCE FAIL input ended`), 다시 이어
    할 때 이미 적은 종료 확인을 지우면 안 된다.
1.  **SSM 출력 한도(표준 출력 24,000자)에 닿은 출력은 저장하지 않는다** — 뒤가
    잘린 목록이다. 놓쳐도 다음 단계가 거부한다 — 줄 수가 개수와 다르거나,
    마지막 ID 가 가운데에서 잘려 끝 줄바꿈이 없으면 대조하지 않는다. 저장할 때
    출력을 손으로 고치지 않는다(끝 줄바꿈을 더하면 이 검사가 무력해진다).
1.  force 4 뒤 B 상자에서 같은 스크립트로 `force-<시각>-B.txt` 와 개수를
    받는다(같은 규칙).
1.  종료를 확인한 방송은 `force-<시각>-ended.txt` 에 탭 구분 3열(`ingestStreamId`
    · 확인 시각 · 확인 경로)로 덧붙인다.
1.  `force-diff.sh A B ended <A 개수> <B 개수>` → `FORCE REMAINING <n>`. 남은
    ID 가 연락 대상이고, 0 이면 복구 완료다. A · B 파일의 ID 줄 수가 개수와
    다르거나 마지막 줄에 끝 줄바꿈이 없으면(잘린 목록), A 가 비면 대조하지
    않는다(`FORCE FAIL count|empty`).

**루프백 헬스(`loopback-health.sh`):** 상자 안에서 시험 신호를 MediaMTX
(`srt://127.0.0.1:8890`)로 보내고, `127.0.0.1:<live 포트>/<ID>/index.m3u8` 이
200 인지 본다. CDN 비밀(Bearer)은 `curl --config -` 의 표준 입력으로만 간다.

*   **기본은 passphrase 를 싣지 않는다.** O7f 9 시점의 상자는 옛 모드라 익명
    publish 이고, 그때 passphrase 를 실으면 MediaMTX 가 끊는다. O7f 9 런북은
    `--passphrase` 없이 부른다.
*   `--passphrase` 는 응답기 모드에서만 쓴다. 그 모드에서는 거꾸로 기본 갈래가
    거부되는 것이 정상이다.
*   `--passphrase` 일 때 passphrase 는 송출기 argv 에 실린다. 송출이 도는 수십
    초 동안 상자 프로세스 목록 · `docker inspect` 에 보인다(설계에서 받아들인 위험 —
    상자 셸에 들어온 사람은 이미 인스턴스 역할로 비밀을 읽을 수 있다).
*   송출기는 `linuxserver/ffmpeg` 다이제스트 고정 미러를 `docker run --rm
    --network container:<mediamtx>` 로 띄운다. 모든 cap 을 버리고, 읽기 전용
    루트 · 비root(65534)로 돈다.

**비밀 건수(`secret-count.sh`):** 대조 값은 CDN 비밀이고, 시험 키 passphrase
는 `--passphrase` 를 줄 때만 센다(기본은 세지 않는다). 값은 Secrets Manager 에서
직접 읽고, `grep -F -f -` 의 표준 입력으로만 넘긴다. `box` 모드 입력은 유닛
저널 · 태그 저널 셋 · `mediamtx` · `nginx` 의 `docker logs`(스택이 도는 동안
재기동으로 멈춘 컨테이너 포함)다. `ssm` 모드는 SSM API 가 주는 한도(표준 출력
24,000자 · 표준 오류 8,000자)에 닿으면 `ssm-truncated` 로 실패한다.

`box` 모드는 이번 기동의 관리 API 비밀번호(`/run/pokeclip-media/mtx_api_pass` 를
root 로 읽음)와 그 Basic 값(`pokeclip-sidecar:<비밀번호>` 의 base64)도 대조한다.

*   **`box` 는 스택이 떠 있을 때만 쓴다.** 정상 정지 뒤에는 정지 스크립트가
    비밀번호 파일을 지우므로 `SECRET COUNT FAIL api-pass` 로 끝난다(로그를
    모으기 전에 비밀번호 파일부터 읽는다). compose down 이 컨테이너도 지워
    어차피 셀 로그가 없다.
*   **비밀번호 파일은 시작 스크립트가 쓰는 꼴 하나만 받는다** — 파일 전체가
    16진 64자 한 줄(끝 줄바꿈 하나, 65바이트)이다. 파일이 없거나 못 읽거나, 그
    꼴이 아니면(줄바꿈 없음 · 둘째 줄 · 뒤에 남는 바이트 포함) 0 건이 아니라
    `SECRET COUNT FAIL api-pass`(종료 1)다. 정지 뒤의 이 실패는 정상이다.
    스택이 떠 있는데 이 실패가 나면 비밀번호 파일 이상이니 조사한다.
*   옛 기동의 비밀번호와 `ssm` 모드의 비밀번호 대조는 아래 「알려진 한계」.
*   **응답기 모드면 넷을 더 센다** — 호출자 Basic 비밀번호와 그 Basic 머리 값
    (`pokeclip-mediamtx:<비밀번호>` 의 base64) · 4C 토큰 · SRT 잠금 둘. 표지
    (응답기 파일 둘 · `mediamtx.yml` 의 잠금 두 줄)가 모두 있으면 응답기 모드,
    하나도 없으면 옛 모드다. 일부만 있거나 꼴 밖이면 `SECRET COUNT FAIL
    ingest-auth` 다.

## 운영 규칙

1.  **재기동 · 정지는 `systemctl restart|stop pokeclip-media`(compose 전체)만
    쓴다.** `docker compose restart <서비스>` 단독, 컨테이너 단독 `docker stop` ·
    `docker restart` 는 금지한다.
    *   서비스 단독 재시작은 시작 스크립트(비밀 조회 · 렌더 · 선행 점검)를
        건너뛴다. 망 칸을 함께 쓰는 서비스는 주인(mediamtx)과 함께 뜨고 져야
        한다.
    *   M2 실측(계약 시험 사례 14): `docker compose restart mediamtx` 단독 뒤 nginx
        가 다시 뜨고, 시작 스크립트의 `compose up` 은 「Aborting on container
        exit」 뒤 3분 넘게 끝나지 않았다. mediamtx · 사이드카는 멈추고 nginx 만
        남았다. prod 라면 유닛은 active 인데 서비스가 없는 상태가 이어진다.
    *   사이드카만 `docker stop` 하면 `on-failure` 정책은 다시 띄우지 않는다.
        다음 유닛 재기동까지 인덱싱이 멈춘다.
    *   **예외 — 응답기만 다시 만들기:** 응답기 모드에서 응답기 컨테이너만
        재생성하는 것은 두 스크립트(`ingest-auth-deny-sync.sh --release` ·
        `ingest-auth-ip-limit.sh`)로만 한다. 실행 직전 승인 · 한 번에 하나씩
        (동시에 돌리면 한쪽이 `FAIL recreate` 로 끝날 수 있다). 스크립트는
        `docker compose up -d --no-deps --force-recreate ingest-auth` 를
        부른다. 응답기는 시작 스크립트가 붙지 않은(비부착) 서비스라, 이 재생성은
        compose 전체와 mediamtx · nginx 를 건드리지 않는다(S-3 실측 5/5 —
        아래 「시험」). `docker restart` 는 쓰지 않는다 — env_file 을 다시 읽지
        않아 IP 한도 전환이 반영되지 않는다. 하나뿐인 손 명령 예외는 재생성이
        끊겨 응답기 컨테이너가 없을 때의 복구다(아래 「응답기 런북」 의 IP 한도
        「실패 뒤 상태」).
        상설 시험은 `unit_restart_test.sh` 사례 22(응답기만 재생성 → 판정:
        「Aborting on container exit」 0 · MainPID · NRestarts 불변 · 부착
        서비스 ID 불변 · 응답기 ID 교체)와 양성 대조 사례 23(부착 nginx 재생성
        → Aborting · ID 바뀜)이고, CI 잡 media-host-systemd 가 3회 돌린다.
1.  **SSM 에서 `docker inspect` · `docker exec … env` ·
    `/run/pokeclip-media` 내용 출력을 금지한다.** `docker exec` 는
    `paths-count.sh` · `paths-list.sh` 가 사이드카 컨테이너에 쓰는 고정 `sh -c`
    한 꼴(`MTX_PATHS_EXEC_SCRIPT`)만 쓴다. 대조는 kty 로컬의 `force-diff.sh` 로
    한다.
    *   **예외 — 응답기 exec 두 꼴:** `docker compose exec -T ingest-auth
        /ingest-auth healthcheck`(거부 목록 동기화가 부름)와 `docker compose
        exec -T ingest-auth /ingest-auth cut <토큰>`(운영자 — 아래 「응답기
        런북」)만 쓴다. 응답기 이미지에는 셸이 없어 이 둘 말고는 exec 할 것이
        없다.
1.  **SSM 명령 출력에는 판정 줄(`LOOPBACK …` · `PATHS …` · `SECRET COUNT …` ·
    `INGEST AUTH …` · cut 출력 한 줄)과 force 때의 `paths-list.sh` 방송
    목록(공개 식별자)만 남긴다.** 결과를 파일로 저장하는 것은 종료 0 일
    때만이다.

## 응답기 런북

응답기 모드 상자에서만 쓴다. 옛 모드 상자에서는 `ingest-auth-ip-limit.sh` 가
`FAIL not-running` 으로 끝난다. `ingest-auth-deny-sync.sh` 는 파라미터 이름을
먼저 보므로 `INGEST_AUTH_DENYLIST_PARAM` 이 비면 `FAIL config`, 차 있으면 `FAIL
not-running` 이다. 어느 쪽이든 아무것도 하지 않는다. 모든 명령은 SSM 으로 보내고
실행 직전 kty 가 승인하며, 한 번에 하나씩 돌린다. 명령 앞에 위 「운영 확인
스크립트」 처럼 `media.env` 를 읽어 들인다.

*   **두 스크립트 · 시작 스크립트 사이에 잠금이 없다 — 순차가 전제다.** 기동
    (유닛 재기동 · 재부팅)과 거부 목록 동기화가 겹치면, 기동이 남은 임시 파일을
    정리하다 동기화의 진행 중 임시 파일을 지울 수 있다. 그때 동기화는 `FAIL
    invalid` 나 `FAIL install` 로 끝나고 옛 목록은 그대로다 — 원인이 경합일 수
    있으니 기동이 끝난 뒤 다시 돌린다. 잠금(`flock` 등)을 둘지는 PR 단계 kty
    판단 대기다.
*   **응답기를 다시 만드는 것은 모두 거부 해제다.** 응답기는 한 번 읽은 거부
    토큰을 프로세스가 끝날 때까지 쥐고, 다시 뜨면 그때의 파일부터 다시 쌓는다.
    그래서 `--release` 만이 아니라 IP 한도 전환 · 유닛 재기동도 SSM 에서 이미
    뺀 토큰을 푼다. 둘을 돌리기 전에 SSM 값과 동기화 상태를 맞춘다.
*   **mediamtx · nginx 는 손으로 재생성하지 않는다.** 시작 스크립트가 붙은
    (부착) 서비스를 밖에서 다시 만들면, 엔진 25.0.16 실측(M15 c7)에서 주 `up`
    이 걸린 채 유닛이 active 로 남았다. MainPID 도 그대로라 systemd 가 회복시키지
    않는다. 응답기 밖의 서비스는 위 운영 규칙 1 대로 유닛으로만 다시 띄운다.

### 거부 목록 덧붙이기 · 긴급 절단

어떤 계정의 송출을 막고 이미 붙은 연결까지 끊는 절차다. 응답기는 요청마다
거부 목록 파일을 다시 읽어, 덧붙인 토큰은 재시작 없이 새 연결부터 막힌다.

1.  kty 가 SSM 파라미터 값 끝에 토큰 한 줄을 덧붙인다(`aws ssm put-parameter
    --overwrite` — kty 로컬). **26자 토큰만 넣는다.** streamid 를 통째로
    붙여 넣으면(`#!::r=…`) `#` 뒤가 주석이 되어 그 줄은 아무것도 막지 않는다.
    머리 줄 `# pokeclip deny-tokens v1` 은 첫 줄에 그대로 둔다.
1.  상자에서 `/opt/pokeclip-media/bin/ingest-auth-deny-sync.sh` 를 돌린다.
    `INGEST AUTH DENY SYNC OK tokens=<N>` 의 N 이 기대한 수(이전 수 + 덧붙인
    수)와 같은지 본다. 다르면 붙여 넣은 줄을 의심한다.
1.  이미 붙은 연결을 끊는다. 출력은 JSON 한 줄이다. 종료 0 과
    `"msg":"ingestauth_cut_completed"` 이면 끝난 것이고, `"kicked":<n>` 이 끊은
    수다. `ingestauth_cut_incomplete`(종료 1 — `remaining` · `reason` 이 붙음)면
    연결이 남았을 수 있다. `verify_timeout` · `api_unreachable` 처럼 일시적인
    reason 이면 다시 돌린다. `denylist_unreadable` · `api_auth` ·
    `connlog_unreadable` 처럼 원인이 남는 reason 은 원인(거부 목록 읽기 ·
    응답기 자격 · 연결 기록)을 고친 뒤 다시 돌린다.

    ```shell
    docker compose -p pokeclip-media -f /etc/pokeclip-media/compose.yml \
      exec -T ingest-auth /ingest-auth cut '<토큰>'
    ```

    cut 은 토큰이 거부 목록에 없으면 종료 2(`not_denied`)로 끝난다 — 위 단계
    2 의 동기화를 먼저 한다.

**동기화가 `FAIL health` 로 끝났을 때:** 파일은 이미 바뀌었다. **원인은 표준
오류에 실린 healthcheck 의 `/healthz` 본문 줄로 가른다**(`ingest-auth:
/healthz 의 답이 200 이 아니다 — HTTP 503` 다음 줄들 — 까닭 한 줄에 하나. 경로 ·
시스템 호출 이유 · 틀린 줄 수뿐이라 비밀은 없다). `/healthz` 는 수신 중 ·
거부 목록 · 연결 기록 셋을 늘 모두 본다.

*   **틀린 줄(토큰 꼴 아님)이 있다:** 그 줄의 값은 막히지 않고, 같은 파일의
    유효 토큰은 막힌다. SSM 값을 고쳐 다시 동기화한다. 값을 되돌려도 응답기가
    다시 뜰 때까지는 이번에 더한 유효 토큰이 막힌 채이고, 다음 재생성 때
    풀린다.
*   **응답기가 파일을 못 읽는다:** 모든 새 송출이 거부된다. 값이 아니라 소유 ·
    권한 문제일 때가 많아 같은 값으로 다시 돌려도 또 실패한다. 디렉터리
    `root:10003 0750` · 파일 `root:10003 0640` 인지, `INGEST_AUTH_GID` 가 응답기
    이미지 USER 의 그룹(10003)과 같은지부터 본다.
*   **연결 기록(본문 「허용 연결 기록 …」 · 「기록 디렉터리에 다음 세대를 만들 수
    없다」 · 「현재 세대 경로가 연 파일과 다르다」):** 거부 목록과 무관하다.
    상태 디렉터리 `/run/pokeclip-media/ingest-auth/state` 가 `10003:10003
    0700` 인지, `/run` tmpfs 에 여유가 있는지, 정지 정리가 일부만 되어 기록
    파일이 지워지거나 바뀌지 않았는지 본다. 이 상태면 다음 단계의 cut 이
    `connlog_unreadable` · `connlog_window_short` 로 미완일 수 있다.
*   **인증 리스너(본문 「인증 리스너가 받고 있지 않다」):** 응답기가 아직 듣기
    전이거나 듣기를 멈췄다. 태그 저널에서 그 컨테이너의 기동 · 종료 줄을 본다.
*   **본문 줄이 없다:** exec 자체가 실패했거나(응답기 컨테이너가 그 사이 멈춤)
    `MEDIA_OPS_TIMEOUT` 시한을 넘겼다. 응답기 상태를 본 뒤 다시 돌린다.

### 거부 해제

응답기는 한 번 읽은 거부 토큰을 프로세스가 끝날 때까지 쥔다. 그래서 해제는
값에서 줄을 지운 뒤 응답기만 다시 만들어야 선다.

1.  kty 가 SSM 파라미터 값에서 그 줄을 지운다(머리 줄은 남긴다).
1.  `ingest-auth-deny-sync.sh --release` →
    `INGEST AUTH DENY SYNC OK release tokens=<N>`. N 이 기대한 수(이전 수 −
    지운 수)인지 사람이 본다 — 스크립트가 기대 수를 받아 대조할지는 PR 단계
    kty 판단 대기다.
1.  재생성이 끝날 때까지(healthy · 상한 `MEDIA_OPS_HEALTH_WAIT`) 모든 계정의
    **새 연결 · 재접속이 거부된다.** 진행 중 송출은 끊기지 않는다(인증은 연결
    때만 묻는다).

### IP 한도 끄기 · 켜기

응답기의 송출자 IP별 시도 한도를 켜고 끈다. 이 한도는 송출자 IP(IPv4 /32 ·
IPv6 /64)마다 4C 를 부르는 송출 시도를 세어, 기본 30번까지 몰아 쓰게 하고 그
뒤에는 3초에 하나만 받는다(CONTRACT 1-1 의 15).

1.  `/opt/pokeclip-media/bin/ingest-auth-ip-limit.sh off`(또는 `on`) →
    `INGEST AUTH IP LIMIT OK off`.
1.  스크립트는 `ingest-auth.env` 를 바꾸고 응답기를 재생성한 뒤, 새 컨테이너의
    `ingestauth_started` 줄로 반영을 확인한다. 재생성 동안은 위 해제와 같이 새
    연결이 거부되고, SSM 에서 뺀 거부 토큰도 풀린다(위 「거부 해제」 와 같다).
1.  **실패 뒤 상태:**
    *   `FAIL write` 뒤의 단계(`recreate` · `unhealthy` · `journal` ·
        `verify`)에서 끝났으면 env 파일은 이미 새 값이다. 응답기가 그 값으로
        아직 안 떴어도 다음 재생성 · 유닛 재기동 때 반영된다.
    *   `FAIL journal` · `verify` 는 healthy 확인 뒤의 단계라, 그때 env 교체와
        재생성은 **이미 끝났다.** 저널 대조가 journald 로그 드라이버와 응답기
        INFO 줄을 전제해서 실패했을 수 있다. 반영 값은 상자 셸에서 태그 저널의
        새 `ingestauth_started` 줄(`"ip_limit"`)을 직접 보고 확인한다(원문은 SSM
        출력으로 내지 않는다).
    *   재생성이 끊겨 응답기 컨테이너가 없으면 두 스크립트 모두 `FAIL
        not-running` 이라 스크립트로는 되살릴 수 없다. 손으로 응답기만 다시
        띄운다(승인 · 응답기 한정 — 유닛 재기동은 mediamtx 까지 다시 띄워 진행
        중 송출을 끊는다).

        ```shell
        docker compose -p pokeclip-media -f /etc/pokeclip-media/compose.yml \
          up -d --no-deps ingest-auth
        ```
1.  값은 재부팅에 남고, **상자 교체 때 템플릿 기본값(`"on"`)으로 돌아간다.**
    계속 끄려면 M9 변수 `ingest_auth_ip_limit` 을 바꾼다.

### 루프백 passphrase 확인

응답기 모드에서는 `loopback-health.sh --passphrase <ingestStreamId>` 를 쓴다.
passphrase 없는 기본 갈래는 거부되는 것이 정상이다(음성 확인 — 위 「O8a 2 전
필수 조건」 의 3).

## 로그

*   **유닛 저널(`-u pokeclip-media`)에는 사이드카 줄이 없다.** 사이드카 ·
    응답기 · janitor 의 줄은 태그 저널(`-t pokeclip-media-sidecar` · `-t
    pokeclip-media-ingest-auth` · `-t pokeclip-media-janitor`)에 있다.
    컨테이너가 다시 만들어져도 앞 컨테이너의 줄이 남는 곳이 여기다.
*   어느 저널이든 SSM 으로는 건수만 내고, 원문은 상자 셸에서 직접 본다(위
    「시작 · 정지」).
*   유닛 저널에는 시작 · 정지 스크립트의 판정 줄과 `mediamtx` · `nginx` 의 출력이
    남는다. 이 둘의 엔진 쪽 로그는 json-file 10 MB × 3 개로 돈다.
*   로깅 옵션에 `env` · `labels` 를 넣지 않는다 — 컨테이너 환경이 저널 필드로
    남는다.
*   nginx 접근 로그에는 `Authorization` 과 쿼리 문자열을 싣지 않는다.
*   nginx 오류 로그(표준 오류 → 유닛 저널)에는 요청 줄(쿼리 포함) · Referer 가
    실린다. 원문을 SSM 출력으로 내지 않는다(건수만 — 위 규칙).
*   응답기는 info 수준으로 뜬다(`LOG_LEVEL` 을 적지 않는다). Bearer 없는 HLS
    read 거부(`ingestauth_read_denied`)와 16 KiB 넘는 인증 본문
    (`ingestauth_request_invalid` 의 `too_large`)은 **DEBUG** 라 이 상자의
    태그 저널에 남지 않는다 — 공격 입력에서만 생기고, 로그 쓰기 시간도 응답기
    예산(새 송출 판정에 쓰는 시간 몫)에서 빠지기 때문이다. 탐지 자리는 아래
    「보안 경계」 의 몰아치기 항목이다.

### 기동 확인 신호

O7e 기동 확인에서 사이드카 관리 API 자격이 붙었는지는 사이드카 태그 저널로
본다(상자 안에서 9997 을 따로 두드리지 않는다 — 규칙 ②).

사이드카 로그는 **JSON 한 줄**(Go `slog` JSON 핸들러)이다. 그래서 `auth=basic`
같은 문자열로 찾으면 정상 · 실패 모두 0건이 되어, 실패를 정상으로 읽는다. 판정은
메시지(`"msg"`)와 필드를 함께 본다.

| 신호 | JSON 조건(한 줄 안에 모두) | 판정 |
|---|---|---|
| 정상 기동 | `"msg":"mtxstate_started"` · `"auth":"basic"` · `"first_observation":true` | 이번 기동 이후 1건 이상 |
| 자격 거부 | `"msg":"mtxstate_poll_failed"` · `"reason":"auth_rejected"`(ERROR, 폴마다) | 이번 기동 이후 0건 |

이번 기동 이후의 두 건수로 판정 줄 하나와 종료 코드를 내는 꼴이다.

```shell
fail() { echo "SIDECAR AUTH FAIL $1"; exit 2; }
since="$(systemctl show -P ActiveEnterTimestamp pokeclip-media)" || fail since
[ -n "${since}" ] || fail since
log="$(journalctl -q -t pokeclip-media-sidecar --since "${since}" -o cat \
  --no-pager)" || fail journal
started="$(printf '%s\n' "${log}" | awk -v a='"msg":"mtxstate_started"' \
  -v b='"auth":"basic"' -v c='"first_observation":true' \
  'index($0, a) && index($0, b) && index($0, c) { n++ } END { print n + 0 }')" \
  || fail count
rejected="$(printf '%s\n' "${log}" | awk -v a='"msg":"mtxstate_poll_failed"' \
  -v b='"reason":"auth_rejected"' \
  'index($0, a) && index($0, b) { n++ } END { print n + 0 }')" || fail count
if [ "${started}" -ge 1 ] && [ "${rejected}" -eq 0 ]; then
  echo "SIDECAR AUTH OK started=${started} rejected=${rejected}"
  exit 0
fi
echo "SIDECAR AUTH NOT OK started=${started} rejected=${rejected}"
exit 1
```

| 갈래 | 출력 | 종료 코드(SSM 상태) |
|---|---|---|
| 정상 — started ≥ 1 · rejected = 0 | `SIDECAR AUTH OK started=<n> rejected=0` | 0(Success) |
| 기동 줄 없음 · 자격 거부 | `SIDECAR AUTH NOT OK started=<n> rejected=<n>` | 1(Failed) |
| 조회 실패(`systemctl` · `journalctl` · `awk`) · 기동 기록 없음 | `SIDECAR AUTH FAIL since\|journal\|count` | 2(Failed) |

*   1 과 2 는 SSM 에서 둘 다 Failed 다. 판정 줄로 「거부」 와 「조회 실패」 를
    가른다. 저널 원문은 내지 않는다.

*   유닛이 active 가 된 시각은 compose 전체 재시작 때만 바뀐다. 사이드카만 엔진이
    다시 띄운 경우는 같은 창 안에 든다.
*   **이 신호는 인증이 강제된다는 증거가 아니다.** 무인증 `api` 항목이 남아
    있으면 틀린 자격도 붙어 같은 신호가 난다(`media/README.md` 「관측 자격
    파일 규칙」). 이 상자는 그 항목을 지운 설정(`mediamtx.yml.tmpl`)이라 이
    신호를 자격 확인으로 쓴다.
*   무자격 · 「같은 이름 + 틀린 비밀번호」 가 모두 401 인지(음성 확인)는 M2
    로컬 실측이 맡았다.
*   **응답기 모드는 판정 기준이 다르다 — 「auth basic 기동 줄 1건 이상 +
    그 기동 줄 뒤 거부 0건」 이면 OK 다**(`first_observation` 값은 보지
    않는다). 이 모드의 관리 API 인증은 응답기가 판정하므로, 응답기가 듣기 전에
    나간 사이드카 첫 폴이 거부될 수 있다(M15 계약 시험 C16 기록). 사이드카는
    프로세스마다 기동 줄을 한 번만 찍고(첫 폴 뒤 최대 3초 기다림), 첫 폴이
    거부되면 `"first_observation":false` 로 찍는다. 다음 폴(10초 뒤)이 성공해도
    따로 줄이 없어, 같은 프로세스에서 `true` 줄은 끝내 나오지 않는다. 그래서 위
    꼴로 세면 정상 교체도 NOT OK 다. 응답기 모드에서는 위 꼴의 `started=` ·
    `rejected=` 부터 끝까지를 아래로 바꾼다.

    ```shell
    counts="$(printf '%s\n' "${log}" | awk -v s='"msg":"mtxstate_started"' \
      -v u='"auth":"basic"' -v a='"msg":"mtxstate_poll_failed"' \
      -v b='"reason":"auth_rejected"' '
      index($0, s) && index($0, u) { started++; seen = 1; next }
      seen && index($0, a) { failed++; if (index($0, b)) rejected++ }
      END { printf "%d %d %d\n", started, rejected, failed }')" || fail count
    set -- ${counts}
    started="$1" rejected="$2" failed="$3"
    if [ "${started}" -ge 1 ] && [ "${rejected}" -eq 0 ]; then
      echo "SIDECAR AUTH OK started=${started} rejected=0 failed=${failed}"
      exit 0
    fi
    echo "SIDECAR AUTH NOT OK started=${started} rejected=${rejected} failed=${failed}"
    exit 1
    ```

    *   `failed` 는 기동 줄 뒤의 모든 폴 실패(거부 포함) 수다. 판정에는 쓰지
        않고 참고로 낸다.
    *   **한계 — `false` 기동의 뜻:** `first_observation:false` 줄 뒤 10초 폴이
        성공했는지는 저널로 알 수 없다(성공 줄이 없다). 그래서 이 판정의 OK 는
        「기동 뒤 거부가 이어지지 않았다」 일 뿐이다. 확인은 관찰 창을 10초보다
        길게 두고 `failed=0`(기동 줄 뒤 `mtxstate_poll_failed` 가 이어지지
        않음)을 함께 보는 것이다. 폴러가 BootWait 안에서 다시 묻거나 회복 줄을
        남기게 할지는 아래 「알려진 한계」 의 kty 판단 대기 항목이다.

*   응답기 자신의 기동은 태그 저널(`-t pokeclip-media-ingest-auth`)의
    JSON 줄 `"msg":"ingestauth_started"` 에 `"caller_auth":"basic"` ·
    `"auth_channel":"internal-token"` 이 함께 있는지로 본다(건수만 — 위와 같이
    문자열 `caller_auth=basic` 으로 찾으면 늘 0건이다). `caller_basic_off` 가
    보이면 설정 누락이다.

## 보안 경계

*   **SG 가 유일한 경계다.** 공개 포트(live · playback · 제어면 · 헬스 TCP,
    8890/udp)는 `0.0.0.0` 에 게시한다(ALB · NLB 대상). docker 의 포트 게시는
    DNAT 규칙으로 호스트 방화벽을 우회하므로, 상자 방화벽은 경계가 아니다.
    인그레스 규칙은 M9 의 Media SG 가 정하고 단언한다.
*   **관리 API(9997):** 망 칸의 127.0.0.1 에만 묶인다. 무인증 `api` 항목은
    없다(기본 localhost 관리자 항목도 지웠다). 같은 망 칸의 컨테이너는 모두
    127.0.0.1 로 붙어 IP 로는 가를 수 없기 때문이다. 쓸 수 있는 사용자는
    비밀번호가 있는 사이드카 사용자(`pokeclip-sidecar`) 하나다. **운영자용 API
    사용자는 두지 않는다** — 운영 조회는 paths 두 스크립트가 사이드카 자격으로
    한다. 응답기 모드에서는 **판정자가 응답기다** — MediaMTX 가 `action=api`
    요청마다 응답기에 묻고, 응답기는 본문 출발지가 127.0.0.1 이고 사이드카
    자격(응답기도 같은 사용자 · 같은 비밀번호 파일 내용을 받는다)이 맞을 때만
    200 을 준다. 응답기가 멈춰 있으면 관리 API 도 거부된다.
*   **재생 서버(9996) 무인증 항목:** `ips` 가 루프백이어도 같은 망 칸의
    컨테이너는 모두 127.0.0.1 로 붙어, 칸 안 누구나(사이드카 · 응답기 ·
    janitor) `/list` · `/get` 을 부를 수 있다(받아들인 위험). 실제 관문은
    nginx 의 경로 번호 대조와 CloudFront 서명 쿠키다. 응답기 모드에서도
    같다 — `playback` 은 응답기에 묻지 않는 유일한 action 이고, 9996 의 통제는
    `127.0.0.1` 바인딩뿐이다(포크의 제외 항목에는 IP 조건이 없다).
*   **HLS 원본 보호(응답기 모드):** 정상 시청은 CloudFront 가 싣는 CDN
    Bearer 로만 열린다(MediaMTX 가 그 세션의 인증을 건너뜀). Bearer 없는 LL-HLS
    읽기는 응답기가 read 거부(`protocol` 이 정확히 `srt` 가 아니면 401)로
    막는다. SRT 읽기는 응답기가 200 을 주고 `srtReadPassphrase` 잠금이 막는다.
*   **Bearer 없는 HLS 몰아치기(응답기 모드 · 남은 위험):** ALB 에 닿는 누구나
    비CDN 세션을 열 수 있고, 세션마다 MediaMTX 경로 관리자가 응답기 왕복만큼
    붙잡히며 응답기 예산도 깎인다(새 송출 지연 · `budget_exhausted`). 응답기
    쪽 비용은 DEBUG · 4C 0 으로 줄였지만 없앨 수는 없다. 판단은 위 「O8a 2 전
    필수 조건」 의 2 다.
    *   **탐지 자리:** 응답기 태그 저널에는 보이지 않는다(위 「로그」 — DEBUG).
        nginx 접근 로그(유닛 저널)의 **live 포트 401 급증**으로 본다. 접근
        로그는 쿼리 · User-Agent 를 싣지 않으므로 무엇으로 채운 요청인지는
        가를 수 없다 — 건수 신호다.
    *   **debug 로 띄우면 비용이 커진다.** 응답기를 `LOG_LEVEL=debug` 로 띄운
        동안에는 두 DEBUG 줄을 실제로 쓰고, 그 쓰기 시간이 예산에서 빠진다.
        몰아치기 중에는 debug 기동을 하지 않는다.
*   **비밀 처리:**
    *   CDN 비밀은 Secrets Manager → 시작 스크립트 셸 변수 → tmpfs 의
        `mediamtx.yml`(10002 소유 0400)로만 간다.
    *   API 비밀번호는 기동마다 새로 만들고 저장하지 않는다. 같은 실행에서
        MediaMTX 설정과 사이드카 파일에 함께 쓰이므로 둘이 어긋나지 않는다.
    *   정지 때 두 파일을 지운다.
    *   응답기 모드의 Basic 비밀번호 · 잠금 둘도 기동마다 새로 만들고 저장하지
        않는다. 4C 토큰은 Secrets Manager → 셸 변수 → 응답기 파일(10003 소유
        0400)로만 간다. 응답기는 비밀을 파일 경로 env(`*_FILE`)로만 받고,
        컨테이너 env 에 값이 없다. 정지 때 `ingest-auth/` 의 비밀 셋을 지운다.
    *   core 파일을 남기지 않는다. 시작 스크립트와 compose CLI 에는 유닛의
        `LimitCORE=0` · 스크립트의 `ulimit -c 0` 이, 컨테이너에는 compose 의
        `ulimits: core: 0`(모든 서비스)이 건다 — 컨테이너는 dockerd 가 만들어
        유닛의 제한을 물려받지 않는다.
    *   옛 스택이 살아 있는 채 시작 스크립트가 다시 돌아도, `--force-recreate`
        로 컨테이너를 모두 새로 만들어 MediaMTX 와 사이드카가 같은 새 렌더를
        읽는다. 렌더에서 빠진 서비스의 옛 컨테이너는 `--remove-orphans` 가
        지운다.
*   **받아들인 위험 — 사이드카 자격 = 관리 API 전권:** MediaMTX 의 `api` 권한은
    조회 전용이 아니다. 이 자격으로 할 수 있는 것은 다음과 같다.
    *   설정 조회 — 응답에 `hlsCDNSecret` 이 평문으로 들어 있다(포크의 가림
        처리가 이 값을 가리지 않는다 — M2 실측). 응답기 모드에서는
        `authHTTPAddress` 의 **호출자 Basic 비밀번호**와 `pathDefaults` 의
        **SRT 잠금 둘**(`srtPublishPassphrase` · `srtReadPassphrase`)도 같은
        응답에 평문으로 들어 있다. 그래서 사이드카 자격이 새면 Basic 으로
        응답기에 직접 물어 모든 계정의 passphrase 를 얻을 수 있다(CONTRACT D2
        ③ — 받아들인 위험).
    *   설정 변경 — 훅 명령을 바꿔 명령 실행까지 갈 수 있다.
    *   kick · 녹화 삭제.

    루프백 접근과 유효한 비밀번호가 둘 다 있어야 하고, 무자격 접근이나 정상
    로그로 새는 길은 확인되지 않았다. 같은 망 칸 누구나 무인증 전권이던 이전
    구성보다 좁다.
*   **침해가 의심되면** 서비스를 멈추고(`systemctl stop pokeclip-media` —
    SSM · 실행 직전 승인) kty 에게 알린다. 상세 절차(격리 · 증거 보존 · 역할
    세션 취소 · 비밀 교체 · 상자 교체)는 별도 보안 런북이 맡는다(아래
    「후속」).

## 되돌리기

상자 설정은 user_data 가 쓴 파일이라, 설정을 바꾸는 길은 M9 런북의 「설정
반영 교체」(`config_version` 을 올린 새 상자 B 로 교체)다. 되돌릴 때도 같은
길이다.

*   **이전 설정:** 이전 커밋의 템플릿으로 설정 반영 교체를 한다.
*   **응답기 모드 → 옛 모드:** 위 「송출 인증 두 모드」 의 「되돌리기 순서」
    만 쓴다. O8a 5 뒤에는 SRT 인바운드를 팀 IP 로 먼저 좁힌다 — PR revert 판으로
    교체할 때도 같다.
*   **관측만 끄기:** 사이드카 `MTX_API_URL` 을 비우면 관측이 꺼지고 비밀번호
    파일은 읽지 않는다. 사이드카 관리 API 자격 PR(아래 「출처」)은 이것을
    「즉시 롤백」 이라 부르지만, 이 상자에서 값을 바꾸는 길은 위의 설정 반영
    교체다 — 바로 되돌아가지 않는다.
*   **어느 쪽이든 반영은 compose 전체 재시작이다.** MediaMTX 단독 재시작은 하지
    않는다(규칙 ①).
*   관리 API 로 하는 수동 조작(kick · 설정 PATCH)은 되돌리기 수단이 아니다 —
    운영자 API 사용자가 없다.

## 이미지

compose 는 이미지를 모두 `<ECR 저장소 URL>@<다이제스트>` 로 가리킨다. 태그가
아니라 다이제스트[^digest]라서, 같은 참조는 늘 같은 내용이다.

| compose 변수 | prod ECR 저장소 | 출처 |
|---|---|---|
| `nginx_image` | `pokeclip-prod/media-nginx` | nginx 공식 이미지 미러[^mirror] — 빌드하지 않음. 핀[^pin] = `images.json` 의 `nginx` |
| `mediamtx_image` | `pokeclip-prod/media-mediamtx` | 1번이 `media/Dockerfile.mtxhook` 으로 빌드(훅 바이너리 · USER 10002). FROM 은 현행 포크 태그 — 정본은 볼트 런북 「mediamtx-커스텀이미지-반영」 |
| `sidecar_image` | `pokeclip-prod/media-sidecar` | 1번이 `media/Dockerfile` 로 빌드(USER 10001). I1-1(IAM 인증) 반영본. janitor 도 이 이미지다(entrypoint 로 명령을 고름 — 별도 저장소 없음) |
| `ingest_auth_image` | `pokeclip-prod/media-ingest-auth` | 1번이 빌드(`media/Dockerfile.ingest-auth`). 비면 옛 모드(서비스를 렌더하지 않음). 응답기 모드에 넣는 다이제스트는 M15 머지 뒤 develop 커밋에서 빌드한 것(위 「O8a 2 전 필수 조건」 의 1) |
| (`media.env` `LOOPBACK_SENDER_IMAGE`) | M7 이 정함(후속) | 루프백 송출기 — `linuxserver/ffmpeg` 미러. 핀 = `images.json` 의 `loopback_sender` |

*   **포크 원본 MediaMTX 이미지를 그대로 미러링하지 않는다.** 훅 바이너리가 없어
    녹화 이벤트가 기록되지 않는다.
*   올리는 절차(O7c)는 [prod 산출물 올리기 런북](../terraform/runbooks/prod-artifacts.md)
    이다. 푸시한 다이제스트를 tfvars 에 넣는다 — `images.json` 의 핀과 다를 수
    있다(한 플랫폼만 올리면 매니페스트가 달라진다).
*   **arm64:** prod 상자는 `linux/arm64` 하나다(결정 146 — Graviton ·
    AL2023 arm64). 이미지는 arm64 한 변형만 올린다. kty 로컬(Apple Silicon)
    에서는 네이티브로 빌드되지만, 미러는 받을 때 `--platform linux/arm64` 를
    지정하고 런북의 아키텍처 확인을 거친다.

### images.json 갱신

`images.json` 은 공식 이미지를 받아 미러링하는 둘(nginx · 루프백 송출기)의 핀
정본이다. 빌드하는 이미지(MediaMTX · 사이드카 · 응답기)는 여기에 없다.

1.  새 태그를 정하고, 그 태그의 **멀티 아키텍처 인덱스 다이제스트**를 읽는다.
    받지 않고 읽기만 한다.

    ```shell
    docker buildx imagetools inspect docker.io/library/nginx:<태그>
    ```

1.  출력의 `Digest:` 가 인덱스 다이제스트다. 그 아래 `Manifests:` 에
    `linux/arm64` 가 있는지 본다. 없으면 쓰지 않는다(prod 는 arm64 하나 —
    결정 146).
1.  `images.json` 의 `source`(태그까지 적은 참조)와 `digest` 를 함께 고친다.
1.  `images.json` 을 읽는 시험을 돌린다 — `proxy_contract.sh` · `loopback_test.sh`
    (사례 10 핀 꼴) · `unit_restart_test.sh`(CI).
1.  머지 뒤 O7c 런북대로 새 핀을 미러링하고, 푸시 다이제스트를 tfvars 에
    넣는다. 상자 반영은 설정 반영 교체다.

## 판 고정

prod 상자에 깔 도구의 판이다. M9 user_data 가 같은 값으로 설치하고, 시작
스크립트의 선행 점검이 Compose 판을 다시 본다.

| 도구 | 판 | 출처 · 확인 |
|---|---|---|
| docker compose 플러그인 | **5.5.0** | 공식 릴리스 `docker/compose` v5.5.0(2026-10-20 확인). `sha256` — `docker-compose-linux-x86_64`: `c57ab918abd5b05ca7e7d0f275875dd1330a695074f309dc9eab1b49efafcd4b` · `docker-compose-linux-aarch64`: `ff42489f5a9b879d5d117c5ffea6defc27390b3286da8ad52cbc9c6ab5df590e` |
| `amazon-ecr-credential-helper` | **0.12.0-3.amzn2023** | Amazon Linux 2023 저장소(release 2023.12.20260930 기준 최신 · 2026-10-20 `dnf` 조회) |
| docker | **25.0.16**(`modules/media_host` · `prod/media` tfvars 예시) | M2 탐침 상자(AL2023 · systemd 252)는 25.0.16 으로 재기동 시험을 통과했다. AL2023 **arm64** 저장소에 이 판이 있는지는 O7e 에서 확인한다 |

*   Compose 의 `--abort-on-container-exit` · `--attach` · `--force-recreate`
    동작과 bind 의 `create_host_path: false`(원본이 없으면 디렉터리를 만들지
    않고 그 컨테이너 기동만 실패)는 이 판(5.5.0)과 docker 25.0.16 으로
    실측했다.
*   AL2023 저장소의 판은 AMI 날짜에 묶인다. M9 은 prod AMI 에서 같은 판이 있는지
    확인한다.
*   판을 바꾸면 `media.env` 의 `MEDIA_EXPECTED_COMPOSE_VERSION` 과 tfvars 의
    체크섬도 함께 바꾼다. 하나만 바꾸면 `MEDIA PREREQ FAIL compose-version` 으로
    기동이 막힌다.

## 시험

| 시험 | 보는 것 | 도는 곳 |
|---|---|---|
| `test/render_test.sh` | 네 템플릿 렌더 단언(두 모드 — 옛 갈래는 골든 대조) · `docker compose config -q` · 시험 compose 와의 드리프트(bind 원본 자동 생성 여부 포함) · 렌더를 세는 사례가 읽기 실패를 0 건으로 읽지 않는지(사례 29 — 가짜 grep · awk 로 정적) | terraform · docker compose(컨테이너 안 띄움) |
| `test/start_test.sh` | 시작 · 정지 스크립트(가짜 docker · chown · aws). 사례 41 = 우회 문자열 검사가 grep 읽기 실패를 0 건으로 읽지 않는지 | 어디서나 |
| `test/proxy_contract.sh` | 앞단 프록시 계약 사례 1–17 · 1b(14 는 판정 아닌 기록) | terraform · docker(에코 상류 `test/echo` 빌드) |
| `test/unit_restart_test.sh` | 유닛 재기동 사례 1–23(15–17 = 크래시 경로 · 19 = 기동 중 정지 · 20 · 21 = 저널 · 렌더 자리 건수 도구가 조회 실패를 0 건으로 바꾸지 않는지 — 가짜 sudo 로 정적 · 22 · 23 = 응답기 모드 D8 과 그 양성 대조) | 1–3 · 20 · 21 은 어디서나, 4–19 · 22 · 23 은 리눅스 · systemd · sudo(CI 잡 media-host-systemd 3회). **13–17 은 호스트를 흔든다**(docker 엔진 재시작 · dockerd kill -9 · SIGKILL) — CI(`GITHUB_ACTIONS=true`) · 일회용 상자(`PCBENCH_ALLOW_HOST_DISRUPTION=1`)가 아니면 거부한다. 개발 머신에서 돌리지 않는다 |
| `test/loopback_test.sh` · `paths_count_test.sh` · `paths_list_test.sh` · `secret_count_test.sh` · `force_diff_test.sh` | 운영 확인 스크립트(가짜 명령) | 어디서나 |
| `test/ingest_auth_ops_test.sh` | 응답기 운영 스크립트 둘(가짜 aws · docker · journalctl — argv · 판정 줄 · 원자 교체) | 어디서나 |
| `test/ingest_auth_contract.sh` | 송출 인증 계약 — C0(실물 MediaMTX 의 `authInternalUsers: []` 수용) · 층 L(옛 모드) · 층 A(가짜 응답기) · 층 B(실물 응답기 + 가짜 4C, 실물 cut 포함) | 로컬만(CI 상설 아님 — 무겁고, infra-ci 경로 필터가 응답기 변경에 돌지 않는다). 프로젝트 `pcbench-m15-ingest-auth` · 라벨 `com.pokeclip.bench=m15-ingest-auth` · 호스트 게시 0 |
| `test/harness_selftest.sh` · `query_guard_selftest.sh` | 시험 부품 자체 | 어디서나 |

*   시험 · 실측의 compose 프로젝트 이름은 `pcbench-edge-proxy`, 라벨은
    `com.pokeclip.bench=edge-proxy` 다.
*   제품 파일이 없으면 시험은 FAIL 이다. 건너뛰는 갈래는 없다.

### 골든 · 실측 기록

*   **옛 모드 골든**(`test/golden/mediamtx.legacy.json` ·
    `compose.legacy.a.json` · `compose.legacy.c.json`)은 develop 0d057a32 의
    템플릿을 `test/render_vars.json` 으로 렌더한 구성 JSON 이다. 옛 모드
    출력이 develop 과 같다는(회귀 0) 단언의 기준이다. CI checkout 은 이력이
    얕아 시험이 git 으로 옛 판을 읽지 않는다.
*   **골든은 옛 모드 출력을 일부러 바꿀 때만 갱신한다.** 그 커밋의 템플릿을 같은
    렌더 함수(`test/lib/render.sh`)와 `render_vars.json` 으로 렌더해 JSON 으로
    바꾸고, 기준 커밋 해시를 위 문장과 사례 주석에 함께 고친다. develop 을 병합해
    기준 템플릿이 바뀔 때도 같다. 만든 명령 원문은 M15 c2a 실험 기록(병합 때
    다시 만든 명령은 merge-develop 기록)에 있다. 빈 줄 차이는 YAML 의미가 같아 골든이 잡지
    않는다(값 비교) — 기록만 한다.
*   **compose 골든 두 벌**(옛 갈래 a · c)은 같은 기준 커밋의 compose 템플릿
    렌더를 `docker compose config --no-normalize` 의 JSON 으로 받아 `jq -S` 로
    정렬한 것이다(사례 43). 이 출력은 compose 판에 기대므로 판 고정(5.5.0)을
    올리면 골든도 다시 만든다. 생성 스크립트는 M15 c4 실험 기록의
    `make-golden.sh` 다.
*   **S-3**(응답기만 재생성할 때 compose 전체가 무사한지 — 버리는 실험):
    통과 5/5 · 양성 대조 2/2. 비부착 서비스 재생성은 주 `up` · 부착 서비스를
    건드리지 않고, `docker restart` 는 env_file 을 다시 읽지 않는다. 원문은
    저장소 밖 실험 루트 `runs/2026-10-23-m15-ingest-auth/s3/`.
*   **S-47**(Bearer 없는 HLS 몰아치기 실측): 결과 수치 · 실험 루트를 여기에
    적는다. <!-- TODO(S-47 실측 뒤): 기준선 대비 송출 수락 시간 ·
    budget_exhausted · 첫 CDN 재생 지연 · TIME_WAIT 수치. -->

### 시험 이음매

시험은 스크립트가 읽는 변수의 **값만** 바꾼다[^seam]. 선행 점검 세 항목과 CDN
비밀 조회는 모든 실행에서 똑같이 돈다. **우회 플래그는 두지 않는다** —
`SKIP` · `BYPASS` 같은 문자열이 스크립트에 0건인지 `start_test.sh` 가 본다.

| 변수 | prod(유닛 · `media.env`) | 시험에서 바꾸는 값 |
|---|---|---|
| `MEDIA_EXPECTED_COMPOSE_VERSION` | 고정 판(위 표) | 그 환경의 `docker compose version --short` |
| `DOCKER_CONFIG` | `/root/.docker` | 스크래치 디렉터리(가짜 `config.json`) |
| `PATH` | `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin` | `test/fakebin` 을 앞에(가짜 자격 도우미 · aws) |
| `MEDIA_COMPOSE_FILE` | `/etc/pokeclip-media/compose.yml` | `test/compose.contract.yml` · `test/compose.unit.yml` |
| `MEDIA_MTX_TEMPLATE` | `/etc/pokeclip-media/mediamtx.yml.tmpl` | `test/mediamtx.test.yml.tmpl` |
| `CDN_SECRET_ARN` · `ECR_REGISTRY_HOST` | ARN · 호스트(값 아님) | 가짜 문자열 |
| `RUNTIME_DIR` · `MEDIA_COMPOSE_PROJECT` | `/run/pokeclip-media` · `pokeclip-media` | 스크래치 · `pcbench-edge-proxy` |
| `MTX_UID`/`GID` · `SIDECAR_UID`/`GID` | 10002 · 10001 | 로컬 실물 compose 는 자기 uid/gid |
| `INGEST_AUTH_UID`/`GID` | 10003(응답기 모드만) | 로컬 실물 compose 는 자기 uid/gid |
| `INTERNAL_API_TOKEN_SECRET_ARN` · `INGEST_AUTH_DENYLIST_PARAM` | ARN · 파라미터 이름(응답기 모드만) | 가짜 문자열(가짜 aws 가 받는다) |
| `INGEST_AUTH_DENY_DIR` | **두지 않는다**(기본 `/var/lib/pokeclip-media/ingest-auth/deny`) | 스크래치 디렉터리 |

*   **`INGEST_AUTH_DENY_DIR` 는 시험 전용 이음매다 — prod `media.env` 에 두지
    않는다.** 응답기 서비스의 거부 목록 bind 원천은 compose 템플릿에 고정된
    기본 경로라, 이 값을 바꾸면 시작 스크립트는 다른 곳에 쓰고 응답기는 낡은
    목록을 본 채로 뜬다(조용한 해제). `render_test.sh` 사례 42 는 bind 원천이
    시작 스크립트의 **기본값**과 같은지만 본다 — 값을 덮어쓴 상자는 막지
    못한다. 덮어쓰기를 구조로 막을지, 그리고 root 로 도는 운영 스크립트가
    `INGEST_AUTH_DENY_DIR` · `MEDIA_COMPOSE_FILE` 경로를 env 에서 받는 것(env
    경로 주입)을 막을지는 PR 단계 kty 판단 대기다.

유닛 시험은 prod 유닛 파일을 바이트 그대로 설치하고, drop-in 에는
`Environment=` · `EnvironmentFile=` 만 둔다.

## 알려진 한계

고치지 않고 받아들인 것이다. 우리 상자에서 생기지 않는 입력이거나, 대조 수단이
없는 경우다.

*   **옛 기동의 API 비밀번호:** 비밀번호는 기동마다 새로 만들어지므로
    `secret-count.sh box` 는 이전 기동 로그에 남은 옛 비밀번호를 대조하지
    못한다. `ssm` 모드는 tmpfs 의 비밀번호 파일을 읽을 수 없어 비밀번호를
    대조하지 않는다. 판정 줄(`SECRET COUNT box 0`)에는 이 한계가 적히지
    않는다 — 0 은 「CDN 비밀 · 이번 기동의 비밀번호(`--passphrase` 면 시험 키
    passphrase 도) 0 건」 이다.
*   **비밀번호 파일의 크기 · 내용 사이:** `secret-count.sh` 는 크기를 잰 뒤
    내용을 다시 연다. 그 사이에 파일이 바뀔 수 있지만 그 자리는 root 만 쓰는
    tmpfs 이고 바꾸는 길은 시작 스크립트의 rename(재기동)뿐이다. 바뀌어도 새
    내용을 그대로 꼴 검사하므로 통과로 새지 않는다(실패 또는 새 비밀번호 대조).
*   **paths 응답 크기 상한의 시점:** 8 MiB 상한은 응답을 다 받은 뒤 잰다 — 그
    전까지는 파일에 쓴다. 응답원은 같은 망 칸의 MediaMTX 이고 조회에는 상한
    시간이 있어 받는 양이 끝없이 늘지 않는다.
*   **정지 뒤의 로그:** 정상 정지(compose down) 뒤에는 컨테이너와 그 로그가
    지워지고 비밀번호 파일도 정지 스크립트가 지운다. `secret-count.sh box` 는
    그때 셀 수 없어 실패한다(`FAIL api-pass`) — 정지 뒤에 남는 유닛 · 태그
    저널만 따로 볼 수단은 없다.
*   **SSM 에이전트의 상자 안 실행 기록:** SSM 에이전트가 상자에 남기는 명령 ·
    출력 기록 파일은 `secret-count.sh` 의 대조 밖이다.
*   **운영 확인 스크립트의 스크래치:** `$TMPDIR`(없으면 `/tmp`)에 0600 으로 두고
    끝나면 지운다. 그 자리가 tmpfs 인지는 AL2023 의 `/tmp` 마운트에 달렸다 —
    M9 이 상자에서 확인한다.
*   **paths 조회의 자격 정규화:** 사이드카는 자격 파일의 앞뒤 공백 · 끝 CRLF 를
    떼지만 paths 두 스크립트는 그러지 않는다. 자격 파일은 시작 스크립트가 16진
    64자 + LF 로만 쓰고 사용자 이름은 고정이라 갈리는 입력이 생기지 않는다.
*   **상시 서비스의 실행 상한:** compose 의 상시 서비스(mediamtx · nginx ·
    사이드카 · 응답기)에 읽기 전용 루트(`read_only`)를 두지 않았고, janitor
    를 포함한 모든 서비스에 프로세스 수 상한(`pids_limit`) · 메모리 상한을
    두지 않았다. 서비스마다 쓰는 자리와 기동 메모리를 재야 정할 수 있어 아래
    「후속」 으로 넘긴다. janitor 는 recordings 밖에 쓰지 않아 읽기 전용 루트를
    이미 둔다.
*   **paths 조회의 `pageCount` 산술:** 2^63 급 값이면 셸 산술이 넘쳐 여러 페이지
    판정을 비켜 갈 수 있다. 그 값은 같은 망 칸 MediaMTX API 가 주는 것이라 그
    값을 위조할 수 있으면 응답 전체가 이미 신뢰 밖이고, 실제 경로 수로는 생기지
    않는다.
*   **SSM 거부 목록 크기:** Standard 파라미터 값 한도는 4 KB(토큰 약 150개)다.
    넘으면 kty 의 덧붙이기(`put-parameter`)가 실패한다 — 응답기 파서 상한
    64 KiB 보다 먼저 걸린다. 넘을 때가 오면 등급 · 저장소를 다시 정한다.
*   **머리 줄만으로 되돌아간 목록:** SSM 값이 실수로 머리 줄만 남으면 꼴은
    정상이라, 다음 응답기 재생성(유닛 재기동 · 재부팅 · IP 한도 전환 ·
    `--release`)에서 **모든 차단이 판정 줄 없이 풀린다.** 일반 동기화는
    `tokens=0` 을 내지만, 실행 중인 응답기는 이미 읽은 토큰을 쥐고 있어
    그때까지는 막힌 채다. 재생성 전에 SSM 값을 되돌려 다시 동기화하면 차단을
    잃지 않는다. 덧붙이기 · 해제 때 `tokens=<N>` 을 기대한 수와 대조하고(위
    「응답기 런북」), 줄 수 감소를 따로 잡는 판정 줄은 아직 없다(PR 단계 kty
    판단 대기).
*   **`secret-count.sh ssm` · 옛 모드의 응답기 비밀:** `box` 응답기 모드만
    넷을 센다. Basic 비밀번호 · 잠금 둘은 기동마다 만든 tmpfs 값이라 kty
    로컬(`ssm` 모드)에서 읽을 수 없다. 4C 토큰은 Secrets Manager 에서 읽을 수
    있지만, `ssm` 모드와 옛 모드에서의 대조는 범위 밖으로 두었다(M15 kty 결정
    3 의 범위 — 판단 대기).
*   **옛 모드의 `overridePublisher`:** 옛 모드는 기본(허용)이라 같은 경로에
    둘째 송출자가 붙으면 진행 중 송출을 빼앗는다. 팀 IP 한정 동안 받아들인
    위험이고, 공개 뒤에는 옛 모드를 쓰지 않는다(위 「되돌리기 순서」).
*   **index 페이지(HTML) 401:** 응답기 모드에서 `…/<ID>/` 의 HTML index
    페이지는 CDN 세션이어도 인증을 타 401 이다. 옛 모드도 401 이었다(회귀
    아님).
*   **모드 전환 뒤 남는 응답기 비밀:** 정지 정리가 실패한 상자를 옛 모드로
    띄우면, 옛 모드 기동은 `ingest-auth/` 에 남은 응답기 모드 비밀을 지우지
    않는다(PR 단계 kty 판단 대기). 그 상자에서는 `secret-count.sh box` 가
    매번 `SECRET COUNT FAIL ingest-auth` 로 끝난다(응답기 표지 일부만 남음 —
    안전 쪽 실패). 처치는 유닛을 정지해(정지 스크립트가 `ingest-auth/` 를
    지운다 — `MEDIA STOP CLEANUP FAIL` 이 없어야 한다) 다시 시작하거나
    상자를 재부팅하는 것이다(`/run` 은 tmpfs 라 사라진다).
*   **응답기 모드의 사이드카 기동 확인:** 사이드카 폴러는 첫 폴이 거부되면
    `first_observation:false` 로 한 번 찍고, 뒤의 성공은 로그로 남기지 않는다
    (위 「기동 확인 신호」). 폴러가 BootWait 안에서 다시 묻거나 회복 줄을
    남기게 바꿀지는 PR 단계 kty 판단 대기다(코드 변경 — M15 범위 밖).
*   **응답기 상태 디렉터리의 크기:** `state/` 의 원천은 `/run`(tmpfs — 메모리)
    이고 compose 에 크기 상한이 없다. 정상 쓰기는 연결 기록 두 세대(1 MiB 에서
    회전)로 묶이지만, 응답기가 침해되면 메모리를 채울 수 있다. 상한은 아래
    「후속」 의 하드닝 행에서 정한다.

## 후속

이 디렉터리를 입력으로 쓰는 작업에 넘기는 것이다.

| 받는 쪽 | 할 일 |
|---|---|
| M9 | 템플릿 변수 키 집합에 `detached_log_driver` · `janitor_enabled` 를 넣고, 모듈이 문자 그대로 `"journald"` 를 넘기는지 · `janitor_enabled` 를 bool 로 넘기는지 단언한다(문자열 `"true"` · `"false"` 도 렌더는 되므로 꼴은 모듈 쪽에서 막는다). `daemon.json` 로그 옵션에 `env` · `labels` 계열 0 — **처리(M9):** 모듈이 `"journald"` 를 문자 그대로, `janitor_enabled` 를 bool 변수로 넘기고 user_data 시험이 키 집합(`render_vars.json`)과 함께 단언한다. `daemon.json` 은 쓰지 않는다(로그 드라이버는 compose 서비스마다) |
| M9 | 이미지 변수 꼴 `^[^@]+@sha256:[0-9a-f]{64}$`. 참조가 ECR 레지스트리 호스트(`media.env` 의 `ECR_REGISTRY_HOST` 와 같은 값) + `/` 로 시작하는지도 단언한다. 다이제스트가 비면 `…@` 로 조립하지 않고 빈 문자열을 넘긴다(응답기 — 빈 문자열 = 옛 모드, 아래 송출 인증 행) — **처리(M9):** 모듈 변수 검증(꼴) · 인스턴스 사전 조건(이 계정 레지스트리 호스트로 시작) · `prod/media` 가 빈 응답기 다이제스트를 빈 문자열로 넘긴다(루트 시험) |
| M9 | Media SG 인그레스 단언(위 「SG 가 유일한 경계」). 인그레스 포트에 8888 · 9996 · 9997 0 — **처리(M9):** `modules/media_host` 시험 `media_sg_ingress_matches_design` |
| M9 | prod 프록시 포트 넷(nginx 템플릿 변수)을 모듈이 넘기고 모듈 시험에서 단언한다. user_data 렌더본은 렌더 파일 · nginx 설정 bind 의 긴 문법(`create_host_path: false`)을 그대로 쓴다 — 원본이 없으면 그 컨테이너만 기동에 실패한다. 이 PR 의 유닛 · 스크립트 · compose 템플릿을 함께 설치한다 — **처리(M9):** 모듈이 `proxy_ports` 를 넘기고 검증한다. 렌더본은 저장소 템플릿을 같은 변수로 다시 렌더한 값과 같다(시험). 유닛 · compose · nginx 는 user_data, 스크립트 · MediaMTX 템플릿은 설치 묶음 이미지로 설치한다(kty 결정 2026-10-23 — user_data 16 KiB) |
| M9 | 설치 단언: `bin/lib/` 다섯 파일(`ops.sh` · `mtx_paths.sh` · `deny_tokens.sh` · `docker_config.sh` · `render_files.sh`)이 스크립트와 같은 상대 위치에 있고 내용이 저장소와 같다. `bin/` · `bin/lib/` 소유 `root:root`, 스크립트 0755 · lib 0644. `media.env`(유닛 `EnvironmentFile`)의 소유 · 모드(root 만 쓰기). `/etc/pokeclip-media/`(디렉터리 · `mediamtx.yml.tmpl` · `compose.yml` · `nginx.conf`)와 유닛 파일도 소유 `root:root` · 그룹 · 기타 쓰기 불가. 압축 user_data 16 KiB 게이트를 다시 잰다 — **처리(M9):** user_data 파일 소유 · 모드 시험(`root:root` · 0644 · `config.json` 0600 · 부트스트랩 0700), 부트스트랩이 `bin/` 0755 · `bin/lib/` 0644 로 설치(시험이 그 줄을 본다) · `/etc/pokeclip-media/` 는 0755(부트스트랩 — 시험 밖), 묶음 내용 = 저장소(Dockerfile 이 그대로 COPY · 2026-10-23 로컬 빌드 9파일 sha256 일치), 압축 user_data 12,381 바이트(최대 렌더 12,453 — 시험이 매번 잰다) |
| M9 | prod 엔진의 비특권 포트 하한(`net.ipv4.ip_unprivileged_port_start`)을 상자에서 잰다. nginx 는 cap CHOWN · SETGID · SETUID 만 받고 `NET_BIND_SERVICE` 가 없다 — 프록시 포트가 하한보다 낮으면 nginx 가 기동하지 못한다 — **넘김(M9):** 값을 넣지 않고 [교체 런북](../terraform/runbooks/media-swap.md) 「O7e 뒤 측정 · 기록」 으로 |
| M9 · O7f · edge-secrets 런북 | CDN 비밀 생성 형식을 고정한다: 16진 또는 패딩 없는 base64url. 허용 글자는 `[A-Za-z0-9._+=-]` 이고 표준 base64 의 `/` 는 MediaMTX 가 거부해 기동이 막힌다. 입력 도구 · 런북 문구에 반영 |
| M9 | 유닛 하드닝 지시어(지금은 `LimitCORE=0` 뿐)를 상자에서 정한다 — docker CLI · `/run` 쓰기와 부딪히지 않는 지시어만 실측으로 고른다 — **넘김(M9):** 교체 런북 「O7e 뒤 측정 · 기록」 |
| M9 | `media.env` 키에 `LOOPBACK_SENDER_IMAGE` · `MEDIA_LIVE_PORT` · `TEST_STREAM_KEY_SECRET_ARN` 을 더한다. ECR 읽기 Resource 에 송출기 저장소 하나 — **처리(M9):** `media.env` 에 세 키(시험) · ECR 읽기 Resource 에 `media-loopback-sender`(시험 · 저장소는 `prod/registry` 에 더함) |
| M9 | `bin/lib/` 다섯 파일을 스크립트와 같은 상대 위치에 설치. nginx 렌더본을 `/etc/pokeclip-media/nginx.conf` 에. journald 보존 설정. 위 「판 고정」 값으로 설치 — **처리(M9):** `bin/lib/` 는 설치 묶음으로 같은 상대 위치에 · nginx 렌더본은 user_data 로 · journald 는 `Storage=persistent`(크기 · 보존 상한은 교체 런북 측정으로 넘김) · docker 25.0.16 · 자격 도우미 0.12.0-3 · compose 5.5.0(aarch64 sha256)로 설치. AL2023 arm64 저장소에 그 판이 있는지는 O7e 에서 확인 |
| M9 | 사이드카 `S3_BUCKET` 은 버킷 **이름**(`rewind_bucket_name`)이다. 런타임 출력은 `rewind_bucket_arn` 뿐이라 이름을 꺼내거나 출력을 더한다 — **처리(M9):** ARN 에서 이름을 꺼낸다(시험) |
| M9 | prod 인스턴스 아키텍처를 정한다(O7c 플랫폼 · 위 arm64 주의) — **처리:** `linux/arm64` 하나(결정 146 · 2026-10-23). `c8gn.large` · AL2023 arm64 AMI · 이미지 arm64 한 변형 |
| M9 런북 · O7e 5 | 사이드카 기동 판정은 태그 저널(`-t pokeclip-media-sidecar`)로. DB 장애 때는 compose 전체가 아니라 사이드카만 다시 뜬다(송출 유지) — 「5분 MainPID 무변화」 는 mediamtx · nginx 감시로만 읽는다. 판정 조건에 「관찰 창 5분 동안 사이드카 태그 저널의 종료 줄 0 건」 을 넣고, 먼저 `docker info` 로 로그 드라이버(journald)를 확인한다 — **처리(M9):** [교체 런북](../terraform/runbooks/media-swap.md) 의 기동 판정 · 관찰 판정 |
| M7 · O7c | 루프백 송출기 ECR 저장소 · 미러 하나를 더한다 — **처리(M9):** `prod/registry` 키 `media-loopback-sender` · 산출물 런북 「설치 묶음 · 루프백 송출기」 |
| M9 | 하드닝 — 상시 서비스에 `read_only`(쓰는 자리는 tmpfs · 볼륨으로 — janitor 는 이미 있음) · `pids_limit` · 메모리 상한을 둔다. 값은 상자에서 서비스별 쓰기 자리 · 기동 메모리를 재서 정하고 `render_test.sh` 에 단언을 더한다 — **넘김(M9):** 교체 런북 「O7e 뒤 측정 · 기록」 |
| M9 · 런타임 | janitor 를 켜기(`janitor_enabled`) 전 전제: RDS PostgreSQL 17 이상(미만이면 janitor 가 기동을 거부해 엔진이 계속 다시 띄운다) · DB 사용자 `media_janitor` 와 `GRANT rds_iam` · 스키마 배치 · 인스턴스 역할 `rds-db:connect`(`media_janitor`) · 권한은 세 표 `SELECT` 와 `stream_segments` 의 `UPDATE (local_path)` 뿐이다(사이드카처럼 표 단위 UPDATE 를 주지 않는다 — 정본은 [media/README.md](../../media/README.md) 「DB 요구 — PostgreSQL 17 이상 · 계정 · TLS」 의 「운영 계정 `media_janitor` 권한」). 켠 뒤 상자에서 태그 저널의 `db_connected`(pool=janitor) · `janitor_started` 를 보고, IAM 경로에서 읽기 전용 루트로 기동되는지 확인한다(로컬은 비밀번호 경로로만 확인됨) — **처리(M9 몫):** 인스턴스 역할 `rds-db:connect`(`media_janitor`) · 교체 런북 기동 판정의 `JANITOR_*` 줄 · O7e 선행 확인 ③. 런타임 몫(PostgreSQL 17 · 사용자 · GRANT)은 런타임 트랙 |
| I1-1 | 반영됨(POK-311). `compose.yml.tmpl` 사이드카 · janitor env 에 RDS CA 번들 경로(`POSTGRES_SSLROOTCERT` — 이미지 안 `/etc/pokeclip/rds-ca/global-bundle.pem`)와 `AWS_EC2_METADATA_V1_DISABLED: "true"` 를 넣고 `render_test.sh` 사례 10 이 단언한다. DB 연결 성공 고정 줄은 `db_connected`(media/README 「DB 연결 성공 줄」) |
| 범위 밖(제안) | `media/cmd/janitor` 의 compose 계약 시험을 이 템플릿까지 넓힌다 — `media/Dockerfile.mtxhook` 의 USER 가 바뀌어도 infra-ci(경로 필터 `infra/media-host/**`)는 돌지 않아, `render_test.sh` 사례 9 의 대조가 다음 infra 변경 때에야 돈다. `infra/terraform/prod/registry` 의 「janitor 저장소는 janitor 코드가 생길 때 더한다」 문장은 사이드카 이미지를 쓰는 지금 결정과 어긋나 정리한다 — **처리(M9 — 문장만):** `prod/registry` README · 변수 주석을 고쳤다. compose 계약 시험 확대는 남음 |
| I1-6 | 제어면 서버가 생기면 `control_upstream` 을 채운다. control 헬스는 nginx 경로라, 사이드카가 다시 뜨는 동안에도 healthy 로 보이고 제어면은 502 를 낸다 |
| kty · 보안 | 보안 런북: 침해 대응 — 증거 보존 방식 · 저널 영속 여부(M9 의 journald 저장 방식 — 휘발 저널은 격리 뒤 꺼낼 길이 없다) · **정지 전 컨테이너 증거 보존 여부**(서비스를 멈추면 compose down 이 컨테이너 파일 시스템과 json-file 로그를 지운다 · 정지 승인을 기다리는 동안 서비스는 계속 돈다) · 격리 수단(IMDS 차단 필수 · 기존 연결 차단) · 역할 세션 취소 뒤 인라인 정책 정리 시점 · 비밀 교체와 상자 교체 순서 |
| 포크 mediamtx 트랙 | 다음 포크 태그에서 설정 조회 응답의 `hlsCDNSecret` 을 가리고(redact), 응답에 비밀이 새지 않는지 회귀 시험을 둔다(위 「받아들인 위험」). ADR 로 남길지는 kty |
| M15 | 반영됨(POK-301). 응답기 자리를 응답기 모드로 채웠다(위 「송출 인증 두 모드」). `--passphrase` 루프백은 「응답기 런북」 에 있다. 응답기의 kick 자격은 사이드카와 같은 사용자 · 같은 비밀번호 파일 내용(응답기 쪽 파일 따로)으로 정했다 — 전용 사용자를 두지 않는다. `api` 는 `authHTTPExclude` 에 넣지 않는다. Basic 비밀번호 · 잠금 둘은 시작 스크립트의 토큰 계약을 모드별로 넓혀 렌더한다. 응답기가 다시 뜨는 동안의 거부 창은 계약 시험 C13 에 기록한다 |
| M15 | 반영됨(POK-301). `render_test.sh` 사례 11 은 비밀 계열 env 이름 금지를 유지하고, `_FILE` 로 끝나며 값이 읽기 전용 bind 대상과 같은 env 만 예외로 둔다. `authHTTPExclude` 는 `playback` 하나다(read 를 빼면 Bearer 없는 LL-HLS 가 열려, read 는 응답기가 판정한다). SRT 경로 잠금 · `overridePublisher: no` 는 응답기 모드에만 있다. 포트 127.0.0.1:8080 · 헬스 8081. dev-media 응답기와 다른 점(전용 제어망 대신 같은 망 칸 · 비밀 env 대신 `_FILE`)은 CONTRACT 2절 D1 · D4 |
| M9 | 송출 인증 모드는 템플릿 변수 `ingest_auth_image` 하나로 고른다 — 빈 문자열이면 옛 인증(익명 publish · 응답기 없음), 이미지 참조면 응답기 인증이다. **compose · MediaMTX 두 템플릿에 같은 값을 넘긴다**(어긋나면 `MEDIA START BLOCKED: ingest-auth mode mismatch`). O7 은 빈 값, O8a 2 의 설정 반영 교체에서 다이제스트를 넣는다 — 그 다이제스트는 M15 머지 뒤 develop 커밋에서 빌드한 것이어야 한다(빌드 커밋 기록) |
| M9 | **공개 인바운드와 옛 모드를 함께 쓰지 않는다.** `ingest_allowed_cidrs` 에 `0.0.0.0/0` 이 있는 동안 `ingest_auth_image` 는 비울 수 없다 — 두 값이 같은 루트면 precondition 으로 막고, 다른 루트면 롤백 런북(인바운드를 팀 IP 로 먼저 되돌림 → 옛 모드 교체 → 팀 IP 밖 익명 송출 거부 확인)과 plan 점검 항목으로 지킨다. M15 PR revert 판으로 교체할 때도 같다 |
| M9 | 응답기 모드에서만 값이 필수: `stream_key_resolve_url`(`http://auth.pokeclip.internal:<auth 포트>` — compose 에 따옴표 없는 스칼라로 들어가므로 모듈 validation 은 문자 집합 `^http://[A-Za-z0-9.-]+:[0-9]+$` 로 묶는다. `$` · 공백 · `#` · 사용자 정보 · 질의 · 조각 금지. **키는 두 모드 모두 넘기고** 옛 모드는 빈 값) · `media.env` 의 `INTERNAL_API_TOKEN_SECRET_ARN` · `INGEST_AUTH_DENYLIST_PARAM`. 옛 모드에서는 비어도 된다. `ingest_auth_ip_limit`(`"on"` · `"off"`, 기본 `"on"`)은 `ingest-auth.env.tmpl` 로 `/etc/pokeclip-media/ingest-auth.env`(root:root 0644)에 늘 렌더한다 — 응답기 모드에서 없으면 기동이 막힌다. `INGEST_AUTH_DENY_DIR` 는 `media.env` 에 넣지 않는다(시험 전용 이음매) |
| M9 | O8a 2 전에: 거부 목록 SSM Parameter(`/pokeclip/prod/media/ingest-auth-deny-tokens` · String · Standard · 초기값 `# pokeclip deny-tokens v1`) — **`lifecycle { ignore_changes = [value] }` 필수**. 인스턴스 역할에 그 파라미터 하나의 `ssm:GetParameter` · INTERNAL_API_TOKEN 비밀 하나의 `secretsmanager:GetSecretValue`(CMK 면 `kms:Decrypt`). Standard 값 한도 4 KB(토큰 약 150개). Media → auth(4C) SG 짝은 출발지 = Media SG(POK-307 코멘트 10640 — 3번 · M8 와) |
| M9 | 망 칸 예약 포트(응답기 127.0.0.1:8080 · 8081, MediaMTX 127.0.0.1:8888 · 9996 · 9997 · :8890/udp)와 프록시 네 포트가 겹치지 않음을 모듈에서 단언한다 |
| M9 | 설치 목록에 `bin/ingest-auth-deny-sync.sh` · `bin/ingest-auth-ip-limit.sh`(0755) · `bin/lib/` 의 새 셋 `deny_tokens.sh` · `docker_config.sh` · `render_files.sh`(0644 — 위 설치 단언 행의 다섯) · `ingest-auth.env.tmpl` 렌더본을 더하고(**두 모드 공통** — 시작 스크립트가 새 lib 셋을 source 하므로 빠지면 옛 모드(O7) 기동도 실패한다) 압축 user_data 16 KiB 게이트를 다시 잰다. 거부 목록 부모 디렉터리 `/var/lib/pokeclip-media/ingest-auth` 의 소유(`root:root` · 그룹 · 기타 쓰기 불가)도 단언한다 |
| O7f · M15 · O8a · edge-secrets 런북 | 루프백 순서: O7f 9 의 기본 루프백은 passphrase 없이 돌고 시험 경로의 공개 토큰(`<ingestStreamId>` 인자)만 있으면 된다 — O7f 8 의 시험 키(passphrase) **입력**은 M15 · O8a 로 넘어간다. 기본 `secret-count.sh box` 는 CDN 비밀(과 이번 기동 API 비밀번호)만 대조한다. 옛 모드 동안 상자는 NLB 송출 대상 밖(팀 IP 한정)이라는 전제다. 응답기 모드에서는 기본 갈래가 거부되는 것이 정상(음성 확인)이고, `--passphrase` 갈래는 인스턴스 역할에 시험 키 ARN 읽기 권한이 있어야 한다. edge-secrets 런북의 「루프백 직전 입력」 문구도 이 순서로 고친다 |

## 출처

*   계획 POK-299 의 M2 절과 착수 때 계획에서 바꾼 점(재기동 정책 `on-failure` ·
    로그 설계 · CA 번들 env 이월(POK-311 에서 반영) · 0.0.0.0 게시와 SG 경계 ·
    루프백 순서 · API 비밀번호 · 무인증 관리 항목 정리 · 단독 restart 관찰 · 두
    단계 정지 · 크래시 경로 정리), 결함 예측표, 로그 설계 절 — 저장소 밖 작업
    기록.
*   설계 결정: 루프백 passphrase 의 argv 일시 노출 수용 · 무인증 관리 항목 삭제 ·
    paths 조회 꼴(사이드카 안 고정 `sh -c`) · 자격 필수 · 루프백 순서(M15 전에는
    passphrase 없이) · API 비밀번호 기동마다 생성 · 운영자 API 사용자 없음 · 침해
    대응 상세 절차는 보안 런북으로.
*   사이드카 관리 API 자격 PR(3K-PokeClip/PokeClip-mono#291) 본문 「인프라 쪽
    전달 사항」 — 비밀번호 파일 규칙 · 배포 신호 · 즉시 롤백.
*   실측 기록(저장소 밖, 2026-10-20): 로컬 compose 의 MediaMTX 단독 restart(사례
    14) · 설정 조회 응답의 CDN 비밀 평문 · 탐침 상자(정지 두 단계 · AL2023
    docker 25.0.16 · compose 5.5.0) · compose 판 조회.
*   보안 리뷰(0.0.0.0 게시 · 사이드카 자격 전권).
*   M15(POK-301) 확정 계획 — 두 모드 · HLS read 거부(kty 결정 1) · 롤백 순서 ·
    O8a 2 전 필수 조건 · M9 경계 표, 계획 게이트 처분표와 커밋별 검수 장부 —
    저장소 밖 작업 기록. 4C 공용 창구 · `auth.pokeclip.internal` · 출발지 = SG
    는 POK-307 코멘트 10640.
*   [Docker 문서 「Start containers automatically」](https://docs.docker.com/engine/containers/start-containers-automatically/)
    — 재시작 정책과 호스트 프로세스 관리자를 섞지 말라는 경고.

## See also

*   [prod 산출물 올리기 런북](../terraform/runbooks/prod-artifacts.md)(O7c)
*   [엣지 비밀 런북](../terraform/runbooks/edge-secrets.md)(CDN 비밀 입력 ·
    교체 · 기동 차단 표)
*   [dev-media](../dev-media/README.md) — dev 상시 상자(구성이 다르다)
*   [media/README.md](../../media/README.md) — 사이드카 설정 · 관측 자격

[^box]: **상자** — prod 미디어 서버를 돌리는 EC2 인스턴스 한 대. 교체할 때는 새
    상자(B)를 띄워 옮긴다.
[^sidecar]: **사이드카** — MediaMTX 옆에 붙어 녹화 조각을 인덱싱하고 S3 에 올리는
    1번 서비스(`media/`). 오토바이 옆 칸처럼 본체와 함께 뜨고 진다.
[^netns]: **망 칸** — 리눅스 네트워크 네임스페이스. 컨테이너 여럿이 루프백
    (127.0.0.1)과 포트를 함께 쓰는 한 칸이다. `network_mode: service:mediamtx` 로
    MediaMTX 의 칸에 들어간다.
[^journal]: **저널** — systemd 의 로그 저장소(journald). `journalctl -u <유닛>` 은
    유닛 출력, `-t <태그>` 는 그 태그로 들어온 줄을 읽는다.
[^attach]: **붙는다(attach)** — `compose up --attach <서비스>` 가 그 서비스의
    출력을 받고, `--abort-on-container-exit` 로 그 서비스가 끝나면 compose 를
    끝낸다. 붙지 않은 서비스가 끝나도 compose 는 계속 돈다.
[^digest]: **다이제스트** — 이미지 내용의 해시(`sha256:…`). 태그와 달리 내용이
    바뀌면 값도 바뀐다.
[^mirror]: **미러** — 공식 이미지를 받아 내용을 바꾸지 않고 우리 ECR 에 다시 올린
    사본. 상자는 인터넷 레지스트리가 아니라 ECR 에서만 받는다.
[^pin]: **핀** — 쓸 이미지를 태그가 아니라 다이제스트로 못 박아 둔 값.
[^seam]: **이음매** — 시험이 제품 코드를 고치지 않고 값을 갈아 끼우는 자리. 여기서는
    환경변수 값뿐이다.
