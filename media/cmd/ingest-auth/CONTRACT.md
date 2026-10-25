# ingest-auth 응답기 prod 계약 — dev 가동판 실물 · prod 차이 · 결정 K1–K8

- 상태: **2026-10-20 확정 · 2026-10-24 M15 반영** — 결정 K1–K8(kty, K4 는
  2026-10-23 개정) · 설계 듀얼 리뷰 종결 · 선행 조건(prod 4C 호출 방식 3번
  합의)은 **해소**(POK-307 코멘트 10640 · 창구는 POK-429 로 전용 — 3절) · 남은 확인 항목은 3절
- 갱신: 2026-10-20 — 송출자 IP별 시도 한도(S1 게이트 · POK-310 트랙 B, 결정 119차) 반영: 1-1 의 7 · 11 · 15 · 1-2 의 7 · 1-5 의 16–18 · 1-9 의 2 · 3 · 4. 이 행들의 근거는 함수 이름으로 적는다(줄 번호는 위 기준 판의 것이라 한도 코드가 없다)
- 갱신: 2026-10-24 — M15(POK-301) 반영: c0(read 판정 · `too_large` 수준 —
  1-1 의 9 · 1-2 의 2 · 8 · 1-9 의 2 · D6 · K4)과 c8(머리 · D3 · D6 · D8 ·
  D9 · D11 · D12 · 후보값 표 · 3절). 이 행들의 근거도 함수 · 파일 이름으로 적는다
- 갱신: 2026-10-24 c11 — 4C 창구 전용(POK-429): 1-5 의 15 · 1-9 의 평문 http
  잔여 위험 · D9 · 3절(머리 · K6 · 확인 항목)
- 관련 티켓: POK-310 · POK-307 · POK-301(M15 · O8a) · POK-299(엣지 계획)
- 목적: 엣지 트랙 M15(prod 송출 인증을 응답기로)의 착수 조건 「응답기 계약 확정(포트 · 인증 요청 경로 · 비밀 파일 경로)」을 채운다.
- 기준 판: 응답기 코드 · dev-media 구성 = `origin/develop`(64633374), M2 compose 자리 =
  `origin/feat/infra-media-host`(14a57f37), 계획 = 엣지 계획 r6.7(4절 출처). 줄 번호는 이 판
  기준이다. 단 POK-310 트랙 A(비밀 `_FILE` 입력 · 호출자 Basic · 4C 창구 선택 ·
  Basic 비밀번호 최소 24자)가 바꾼 행은 1 · 2 · 3절 모두 그 코드 PR 판이다. 그 행의
  저장소 파일 인용은 줄 번호 대신 파일 · 함수 이름이나 문서의 절 이름으로 적는다(계획의
  줄 번호는 위 판 그대로).
- 읽는 법: 1절은 **dev-media 가동판 코드와 설정에서 읽어낸 사실**이다(코드가 그렇게 동작한다). 2절은 **prod 에서 정해야 할 선택**이다(계획이 가정한 것과 코드가 다른 자리). 3절은 kty 가 정한 것(K1–K8)과 착수 때 할 확인 항목이다.
- 표기: 제어망 `10.250.0.0/29` 안의 두 주소는 `<A_mtx>`(MediaMTX) · `<A_auth>`(응답기)로 쓴다. 비밀 · 계정 ID · 사설 IP 실값은 이 문서에 없다(설계 제어망 대역 `10.250.0.0/29` 표기는 예외).
- 이 조사에서 **직접 실행해 확인하지 않은 것**은 「미확인」으로 표시했다. 코드를 읽고 추론한 것은 「추론」이다.

## 용어 각주

- **응답기**: MediaMTX 가 송출 연결마다 보내는 HTTP 인증 요청에 답하는 작은 서버(`ingest-auth`). 허용하면 그 계정의 SRT passphrase 를 답 본문에 실어 돌려준다.
- **4C**: 계약4 의 한 갈래(`POST /internal/stream-keys/resolve`). 응답기가 auth 서비스에 「이 스트림 키가 유효한가, passphrase 는 무엇인가」를 묻는 호출이다. 승인권은 3번(ADR-033).
- **netns**(네트워크 네임스페이스): 컨테이너가 보는 네트워크 칸. `network_mode: "service:mediamtx"` 로 두면 여러 컨테이너가 한 칸(같은 `127.0.0.1`, 같은 포트 공간)을 쓴다.
- **SelfIP**: 응답기가 자기 주소로 아는 IP(= `INGEST_AUTH_LISTEN` 의 IP). Control API 요청(긴급 절단)이 이 주소에서 온 것만 허용하는 데 쓴다.
- **합집합**: 응답기가 떠 있는 동안 한 번이라도 읽은 거부 토큰 전체. 파일에서 줄을 지워도 응답기를 다시 시작하기 전에는 풀리지 않는다.
- **fail-closed**: 판정할 수 없으면 허용하지 않고 거부하는 방식.
- **tmpfs**: 메모리에 있는 파일 시스템(`/run` 등). 재부팅하면 사라지고 디스크에 남지 않는다.
- **긴급 절단(cut)**: 토큰을 거부 목록에 적은 뒤 `ingest-auth cut <토큰>` 으로 이미 붙어 있는 그 토큰의 SRT 연결을 Control API 로 끊는 운영 절차.
- **Control API**: MediaMTX 의 관리용 HTTP API(9997).

---

## 1. 실물 계약 — dev-media 가동판에서 확정된 것

### 1-1. MediaMTX → 응답기 (인증 요청)

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 주소 · 메서드 · 경로 | `POST /mediamtx/auth` 하나뿐이다. 다른 경로는 404, 다른 메서드는 405 이며 판정 · 예산에 닿지 않는다. dev 는 `http://<A_auth>:8080/mediamtx/auth` | `media/cmd/ingest-auth/serve.go:25-30,181-186` · `infra/dev-media/mediamtx.demo.yml.tmpl:49-50` |
| 2 | 연결 방식 | MediaMTX 는 요청마다 새 연결로 머리 · 본문을 한 번에 보내고 답을 받으면 닫는다. 응답기는 keep-alive 를 끄고 답 뒤 먼저 닫는다(TIME_WAIT 이 MediaMTX 쪽 임시 포트에 쌓이는 것을 줄이려는 것) | `serve.go:32-34,188-213` |
| 3 | 서버 한도 | 머리 1초 · 머리+본문 2초 · 쓰기 3초 · 머리 크기 8 KiB. 종료 신호 뒤 처리 중 요청을 3초 기다린다 | `serve.go:35-52` |
| 4 | 호출자 판정(첫 행) | **TCP 피어 주소**(헤더는 보지 않는다)가 `INGEST_AUTH_CALLER_IP` 와 다르면 403(본문 없음). 예산 · 시한 가드 밖이다 | `media/internal/ingestauth/handler.go:164-180` |
| 5 | 본문 크기 | 16 KiB 를 넘으면 400 | `ingestauth/request.go:10-11,68-74` |
| 6 | 읽는 본문 필드 | `ip` · `action` · `path` · `protocol` · `id`. `user` · `password` 는 `action=api` 일 때만 해석한다. `token` · `query` · `userAgent` 등 나머지는 버린다 | `request.go:34-61,98-103` |
| 7 | `ip` 필드 | IP 로 해석되지 않으면 400(`bad_ip`). **쓰임은 둘**이다 — `action=api` 행에서 SelfIP 와 견주고, publish 행에서 IP별 시도 한도의 키로 쓴다(아래 15). publish 로그에는 한도 사건에서만 싣는다(1-9 의 4) | `request.go` `readRequest` · `invalidIP` · `handler.go` `api` · `publish.go` `ipAdmit` · `ipTake` |
| 8 | `id` 필드(연결 id) | null 이면 빈 값. 아니면 소문자 UUID 꼴이어야 하고 아니면 400(`bad_conn_id`) | `request.go:30-32,84-90` |
| 9 | 행 가르기 | `action=api` → api 행 · `action=read` → read 행(`protocol` 이 정확히 `srt` 면 200, 그 밖은 401 — 1-2 의 2 · 8) · **그 밖의 모든 action(playback · metrics · pprof 포함)은 publish 행으로 간다**. read 행은 4C · 실패 토큰 차례 · IP 한도(15)를 타지 않는다 | `handler.go` `ServeHTTP` · `read` |
| 10 | publish 형식 조건 | `action=publish` ∧ `protocol=srt` ∧ `path` 가 토큰 꼴(26자 Crockford 대문자) ∧ `id` 있음. 하나라도 아니면 401 | `publish.go:99-113` |
| 11 | publish 판정 순서 | 형식 → **IP 한도 검사** → 거부 목록 → 예산 → 요청 ctx → 실패 토큰 차례 → **IP 한도 차감**(그 사건 로그를 쓰다 resolve 시한을 넘겼으면 resolve 없이 401 — 요청 ctx 행과 같은 갈래) → 4C resolve → 기억 갱신 → 연결 기록 추가 → 거부 목록 마지막 확인 → 200. 위에서 처음 맞는 행이 답이다. 이 순서는 4(출발지 403) · 14(호출자 Basic 401)를 지나 본문을 읽은 뒤다 | `publish.go` `publish` · `allowPublish` |
| 12 | 예산 | auth 를 부르느라 MediaMTX 경로 관리자를 붙잡는 시간의 토큰 버킷: 한도 1.375초 · 초당 0.5초 보충. 출발지 · 호출자 Basic 검사(4 · 14)를 지난 요청마다 걸린 시간을 뺀다. api 행은 잔액 검사(`allow`)에 걸리지 않지만, 걸린 시간은 다른 요청처럼 차감된다 | `ingestauth/holdbudget.go` `holdBurst` · `holdRefill` · `handler.go` `layers` · `api` |
| 13 | 시한 | 요청 도착부터 resolve 시한 1.125초, 바깥 가드는 +50ms(1.175초). 상수이며 설정으로 바꿀 수 없다 | `handler.go:17-21,130-136` · `publish.go:16-18,88-97` |
| 14 | 호출자 Basic(둘째 행) | 4 를 지난 요청의 `Authorization: Basic` 이 `INGEST_AUTH_BASIC_USER` · `INGEST_AUTH_BASIC_PASS` 와 다르거나 없거나 깨졌으면 401(본문 없음) + WARN `ingestauth_caller_unauthenticated`. **본문을 읽기 전**이고 4 처럼 예산 · 시한 가드 밖이다. 비교는 api 행과 같은 꼴(SHA-256 다이제스트끼리 상수 시간 · 사용자와 비번을 늘 둘 다). 모든 action(api · read · publish)에 걸린다. `INGEST_AUTH_CALLER_BASIC=off`(명시 끔)면 보지 않는다 — 자격도 끔도 없으면 기동 거부. 비밀번호는 24자 이상이어야 기동한다(1-5 의 12) | `ingestauth/callerauth.go` `denyUnauthenticatedCaller` · `callerBasicOn` · `handler.go` `layers` |
| 15 | 송출자 IP별 시도 한도 | 본문 `ip` 의 키(IPv4 /32 · IPv6 /64 — IPv4-mapped 는 IPv4, zone 은 뗌)마다 **resolve 를 부르는 시도**를 GCRA 로 센다: `BURST` 번(기본 30)은 몰아 쓰고 그 뒤 `INTERVAL`(기본 3초)에 하나. 성공(200)도 센다(공개 토큰 + 틀린 passphrase 시도도 응답기에는 200 이다). 예산 · 차례 거절 · 형식 거절은 세지 않는다. 벌칙 없음 — 통이 비면 3초마다 하나씩 다시 허용. 키 상한 16,384 — 차면 새 키는 기록 없이 예산만 탄다(fail-open). 메모리 상태라 다시 뜨면 비어서 시작한다. publish 행만 걸고 api · read 는 걸지 않는다. 본문을 읽은 뒤라 14 의 401 보다 뒤다. 끄기 = `INGEST_AUTH_IP_LIMIT=off`(1-5 의 16) | `ingestauth/iplimit.go` `ipLimiter` · `publish.go` `ipAdmit` · `ipTake` |

### 1-2. 응답기 → MediaMTX (응답)

| # | 상황 | 응답 | 근거 |
|---|---|---|---|
| 1 | publish 허용 | `200` · `Content-Type: application/json` · `Cache-Control: no-store` · 본문 `{"srtPassphrase":"<원문>"}`. passphrase 원문이 나가는 **유일한 자리** | `publish.go:225-241` · `ingestauth/doc.go:8-10` |
| 2 | api 허용(출발지 = SelfIP ∧ 자격 일치) · read(`protocol=srt` — 소문자 정확히) | `200` 빈 본문 · 이벤트 없음(read) | `handler.go` `api` · `read` |
| 3 | api 거부 · publish 의 모든 거부 · 호출자 Basic 실패(1-1 의 14) | `401` 본문 없음(MediaMTX 가 비-2xx 본문을 오류 문구에 싣기 때문) | `handler.go` `ServeHTTP` · `api` · `publish.go` `denyPublish` · `callerauth.go` `denyUnauthenticatedCaller` |
| 4 | 본문 불량(크기 · JSON · ip · id) | `400` 본문 없음 | `handler.go:186-190` |
| 5 | 호출자 불일치 | `403` 본문 없음 | `handler.go:177-179` |
| 6 | 바깥 가드가 먼저 끝냄 | `503` 본문 `timeout`. 보통은 resolve 시한(가드보다 50ms 앞)이 먼저라 401 이다 | `handler.go:131` · `publish.go:88-97` |
| 7 | publish 401 의 reason(닫힌 집합) | `not_publish` · `not_srt` · `not_token` · `no_conn_id` · `ip_limited` · `denylist_unreadable` · `denylisted` · `budget_exhausted` · `recheck_wait` · `undecided` · `malformed` · `not_found` · `revoked` · `rejected` · `connlog_unwritable` · `internal_error`. `ip_limited` 만 DEBUG 로 내고 auth 게이지(`denied_during`)에 세지 않는다 | `publish.go` 의 reason 상수 · `ipAdmit` |
| 8 | read 거부(`protocol` 이 `srt` 가 아님 — `hls` · `webrtc` · `rtsp` · `rtmp` · 빈 값 · 대문자 `SRT` 포함) | `401` 본문 없음 + DEBUG `ingestauth_read_denied`(1-9 의 2). 정상 CDN 시청은 MediaMTX 가 인증을 건너뛰어(Bearer 일치 세션) 여기 오지 않는다 — Bearer 없는 LL-HLS 읽기를 막는 행이다(M15 계획 0-1 · kty 결정 1, 2026-10-23) | `handler.go` `read` |

### 1-3. 응답기 → auth (4C resolve)

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 호출 | `POST {STREAM_KEY_RESOLVE_URL}/internal/stream-keys/resolve`. URL 은 http · https 절대 URL, **호스트 이름 허용**, 사용자 정보 · 질의 · 조각 불가 | `media/internal/keyresolve/client.go:29,51-71` |
| 2 | 헤더 | 출입증 헤더는 **창구 설정**(`STREAM_KEY_RESOLVE_CHANNEL`)이 고른다 — 비면 · `resolve-token` 이면 전용 `X-Stream-Key-Resolve-Token: <값>`, `internal-token` 이면 공용 `X-Internal-Token: <값>`. **늘 하나만** 싣는다(둘 다 실리면 auth 의 전용 체인이 가져간다). `Content-Type: application/json` | `keyresolve/client.go` `Channel` · `NewClient`(`authorize`) · `cmd/ingest-auth/config.go` `resolveChannel` |
| 3 | 본문 | `{"streamid":"#!::r=<토큰>,m=publish"}` — MediaMTX 는 SRT streamid 원문을 주지 않고 경로(토큰)만 주므로 정규형을 다시 만든다 | `client.go:141-143,169` · 위키 계약4 2절 |
| 4 | 응답 해석 | 200 + `valid:true` → `userId` · `passphrase` 필수. `valid:false` → reason `MALFORMED` · `NOT_FOUND` · `REVOKED`(그 밖의 값은 `rejected`). 허락인데 passphrase 가 형식 밖이면 401 + ERROR `ingestauth_passphrase_invalid` | `client.go:146-153,207-226` · `publish.go:79-82,338-350` |
| 5 | 판단 불가 | 비-200 · 시간 초과 · 연결 실패 · 해석 실패 → 그 새 연결을 401(`undecided`)로 거부. 진행 중인 송출은 건드리지 않는다. 판단 불가였던 토큰은 기억해 차례로 다시 묻는다(기억된 토큰 전체에서 4초에 하나) | `client.go:106-130,185-187` · `publish.go:380-389` · `ingestauth/doc.go:3-6` |
| 6 | 시간 제한 | 전체 1.125초(위 1-1 의 13). 연결 준비(TCP · TLS) 상한 2초. 응답 본문 4 KiB. **재시도 없음** | `client.go:133-139,155-157` |
| 7 | 전송 성질 | 환경 변수 프록시를 타지 않는다(출입증이 프록시를 거치지 않게). 리다이렉트를 따르지 않는다(3xx 는 비-200 = 판단 불가). 연결은 다시 쓴다 | `client.go:74-87` |
| 8 | auth 쪽 조건(계약4 2절) | 전용 토큰이 설정돼 있으면 요청 출발지가 **설정된 IPv4 대역**(운영 = Media 프라이빗 서브넷 · dev = media-dev)일 때만 받는다. 출발지 판정이 먼저: 다르면 403, 맞고 토큰이 틀리면 401. 전용 토큰이 설정되지 않으면 창구가 닫혀 401. **운영 Media 도 전용 헤더로 부른다**(2026-10-24 개정 — 그 전 판은 「미정」) | 위키 `contracts/계약4-OBS-Control-API.md` 2절 · 0절(개정 커밋 62eaf27 · 3d6b66d) |

### 1-4. 응답기 → MediaMTX Control API (긴급 절단 `cut` 에서만)

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 쓰는 때 | `ingest-auth cut <토큰>` 하위 명령뿐이다. `run`(상주)은 클라이언트를 만들어 **값만 검사**한다(틀린 값이 긴급 절단 때 처음 드러나지 않게) | `cmd/ingest-auth/main.go:3-5` · `cut.go:37-51` · `serve.go:100-117` |
| 2 | 엔드포인트 | `GET /v3/srtconns/list?itemsPerPage=2147483647` · `GET /v3/srtconns/get/{id}` · `POST /v3/srtconns/kick/{id}` | `media/internal/mtxctl/client.go:26-33,206-207,264-268,287-291` |
| 3 | 자격 | HTTP Basic `MTX_API_USER` : `MTX_API_PASS`. 사용자 이름에 `:` 불가 | `mtxctl/client.go:58,70-79,88` |
| 4 | 주소 조건 | `MTX_API_URL` 은 **http** · **IP 리터럴 호스트** · 경로 / 사용자 정보 / 질의 없음 | `cmd/ingest-auth/config.go:94-110` · `mtxctl/client.go:109-127` |
| 5 | MediaMTX 가 다시 응답기에 묻는다 | 이 Control API 호출마다 MediaMTX 가 `action=api` 인증을 응답기에 묻는다. 응답기는 **본문 `ip` = SelfIP ∧ 자격(상수 시간 비교) 일치**일 때만 200. 잔액 검사에 걸리지 않아 잔액이 바닥나도 cut 은 돈다(걸린 시간은 차감된다) | `handler.go:204-222` |
| 6 | 시한 | cut 한 번 전체 2분. kick 한 id 는 최대 2초(100ms 간격) 확인. 연결 준비 2초 | `main.go:36-43` · `ingestauth/cut.go:18-23` · `mtxctl/client.go:44` |
| 7 | dev MediaMTX 쪽 설정 | `api: yes` · `apiAddress` = 제어망 주소 · `apiTrustedProxies: []`(프록시 헤더를 믿지 않음) · `authHTTPExclude` 는 `read` 한 줄뿐(api 를 넣으면 Control API 가 무인증이 된다). render.sh 가 이 셋을 단언한다 | `mediamtx.demo.yml.tmpl:20-30,49-55` · `infra/dev-media/render.sh:159-182` |

### 1-5. 설정 입력 — 환경 변수 전체

환경 변수 이름은 아래 18개가 전부다. 비밀 셋(5 · 8 · 12)은 값 변수 대신 이름에 `_FILE` 을 붙인 변수(6 · 9 ·
13)로 **그 값이 든 파일의 절대 경로**를 줘도 된다. 둘 다 주면 기동 거부, 둘 다 없으면 기동 거부다.
단 Basic 비밀번호(12 · 13)에는 예외가 있다 — 14 가 `off` 면 거꾸로 둘 다 없어야 하고, 11 · 12 · 13
가운데 하나라도 있으면 기동 거부다(`config.go` `loadCallerBasic`, 1-1 의 14). 파일 규칙은 사이드카
`MTX_API_PASS_FILE` 과 같다(일반 파일 · other 권한 · group 쓰기 거부 · 4 KiB · 끝 줄바꿈 하나만 걷음 ·
실패 = 기동 실패 — `internal/secretfile`). 에러에는 **어느 갈래에서도 경로를 싣지 않고** 변수 이름과 원인만
싣는다(`cmd/ingest-auth/secrets.go` `resolveSecret`). cut 은 Control API 셋(3 · 4 · 5
또는 6)만 읽는다.

| # | 이름 | 필수 | 비밀 | 내용 · 검사 | 근거 |
|---|---|---|---|---|---|
| 1 | `INGEST_AUTH_LISTEN` | 필수 | 아니오 | `<IP>:<포트>`. 호스트 이름 · 미지정 주소(0.0.0.0 · ::) · zone 불가. 이 IP 가 SelfIP. 포트 0 허용. dev `<A_auth>:8080` | `config.go:41-54,66-78` · `serve.go:103` · `compose.yml:59` |
| 2 | `INGEST_AUTH_CALLER_IP` | 필수 | 아니오 | MediaMTX 의 주소(IP 리터럴, 미지정 불가). 1-1 의 4 가 TCP 피어와 견준다. dev `<A_mtx>` | `config.go:56-64` · `compose.yml:60` |
| 3 | `MTX_API_URL` | 필수 | 아니오 | 1-4 의 4. `run` 도 검사한다. dev `http://<A_mtx>:9997` | `config.go:94-110` · `compose.yml:61` |
| 4 | `MTX_API_USER` | 필수 | 아니오 | Control API Basic 사용자 이름. 응답기의 api 행이 같은 값을 기대한다 | `config.go:19` · `handler.go:82-83,93` |
| 5 | `MTX_API_PASS` | 필수(또는 6) | **예** | Control API Basic 비밀번호. 응답기는 **SHA-256 다이제스트만** 쥐고(원문 보관 없음) api 행에서 견준다. dev 는 `.env` 에서 compose 보간 | `config.go` `envMTXAPIPass` · `handler.go` `newHandler` · `compose.yml` `ingest-auth` 의 `environment` |
| 6 | `MTX_API_PASS_FILE` | 5 대신 | 경로(값은 아님) | 5 의 값이 든 파일의 절대 경로 | `secrets.go` `resolveSecret` |
| 7 | `STREAM_KEY_RESOLVE_URL` | 필수 | 아니오(그러나 dev 는 사설 주소라 문서 · 로그에 싣지 않는다) | 1-3 의 1. 기동 로그에는 호스트의 **부류**(private · public · name · other)와 포트만 싣는다 | `config.go` `newResolver` · `authTarget` · `.env.example` 의 `STREAM_KEY_RESOLVE_URL` 설명 줄 |
| 8 | `STREAM_KEY_RESOLVE_TOKEN` | 필수(또는 9) | **예** | 4C 출입증(창구는 15). 제어 문자 · 앞뒤 공백이면 기동 거부. auth 쪽 설정 값과 같아야 한다 | `keyresolve/client.go` `NewClient` · `headerSafe` · `.env.example` 의 `STREAM_KEY_RESOLVE_TOKEN` 설명 줄 |
| 9 | `STREAM_KEY_RESOLVE_TOKEN_FILE` | 8 대신 | 경로(값은 아님) | 8 의 값이 든 파일의 절대 경로 | `secrets.go` `resolveSecret` |
| 10 | `LOG_LEVEL` | 선택(기본 info) | 아니오 | debug · info · warn · error. 틀리면 기동 실패 | `config.go` `logLevel` |
| 11 | `INGEST_AUTH_BASIC_USER` | 12 와 함께 필수(14 가 off 가 아니면) | 아니오 | 호출자(MediaMTX) Basic 사용자 이름(1-1 의 14). `authHTTPAddress` URL 에 렌더되므로 **URL unreserved 문자(`A-Z a-z 0-9 - . _ ~`)만** 받는다 | `config.go` `loadCallerBasic` · `unreserved` |
| 12 | `INGEST_AUTH_BASIC_PASS` | 11 과 함께 필수(또는 13 · 14 가 off 가 아니면) | **예** | 호출자 Basic 비밀번호. unreserved 문자만, **24자 이상**(짧으면 기동 거부 — 에러엔 변수 이름 · 최소 길이만, kty 120차). 길이 · 문자 집합만 보고 무작위성은 보지 않는다 — 같은 글자 반복 같은 약한 값도 기동은 통과하니 값은 무작위로 만든다(2절 「후보값」 표의 4b). 응답기는 SHA-256 다이제스트만 쥔다 | `config.go` `loadCallerBasic` · `minCallerBasicPassLen` · `ingestauth/handler.go` `newHandler` |
| 13 | `INGEST_AUTH_BASIC_PASS_FILE` | 12 대신 | 경로(값은 아님) | 12 의 값이 든 파일의 절대 경로 | `secrets.go` `resolveSecret` |
| 14 | `INGEST_AUTH_CALLER_BASIC` | 선택 | 아니오 | `off` 만 받는다(명시 끔 — 11 · 12 · 13 가운데 하나라도 함께 주면 기동 거부). 그 밖의 값은 기동 거부. dev-media 는 `off`(제어망이 따로 있어 1-1 의 4 가 MediaMTX 를 가른다) | `config.go` `loadCallerBasic` · `compose.yml` |
| 15 | `STREAM_KEY_RESOLVE_CHANNEL` | 선택(기본 `resolve-token`) | 아니오 | 4C 창구(1-3 의 2): `resolve-token`(전용) · `internal-token`(공용). 그 밖의 값은 기동 거부. prod 는 `resolve-token`(kty 결정 2026-10-24 · POK-429 — D9). 기본과 같아도 compose 에 명시한다(빠짐은 렌더 시험 사례 35 의 정확 집합이 잡는다). `internal-token` 으로 두면 전용 토큰이 공용 헤더에 실려 auth 가 거부하고 started `auth_channel=internal-token` · `ingestauth_auth_degraded` 로 드러난다 | `config.go` `resolveChannel` |
| 16 | `INGEST_AUTH_IP_LIMIT` | 선택(기본 `on`) | 아니오 | `on` · `off`(1-1 의 15). 그 밖의 값(`On` · `false` 등)은 기동 실패. dev compose 가 `.env` 에서 넘긴다(`${INGEST_AUTH_IP_LIMIT:-on}`) — 바꾼 값은 `restart` 가 아니라 응답기 재생성(`up -d --force-recreate ingest-auth`)으로 반영된다 | `cmd/ingest-auth/iplimit_config.go` `loadIPLimit` · `compose.yml` |
| 17 | `INGEST_AUTH_IP_LIMIT_BURST` | 선택(기본 30) | 아니오 | 정수 1–1000. 꺼짐이어도 검사한다. dev compose 는 넘기지 않는다(기본값) | `iplimit_config.go` |
| 18 | `INGEST_AUTH_IP_LIMIT_INTERVAL` | 선택(기본 `3s`) | 아니오 | Go duration 100ms–10m. 꺼짐이어도 검사한다. dev compose 는 넘기지 않는다(기본값) | `iplimit_config.go` |

환경 변수가 아닌 **이미지 안 고정값**(설정으로 못 바꾼다): 거부 목록 `/etc/ingest-auth/deny-tokens` · 기록 디렉터리 `/var/lib/ingest-auth` · healthz 리스너 `127.0.0.1:8081`(`main.go:32-34`), 시한들(1-1 의 3 · 13).
비밀을 값 변수(5 · 8 · 12)로 주면 `docker inspect` 에 보인다 — prod 는 `_FILE`(6 · 9 · 13)로
준다(사이드카 README 가 같은 까닭으로 파일 입력을 택했다 — `media/README.md` 의 사이드카 환경변수
표 `MTX_API_PASS_FILE` 행).

### 1-6. 파일 · 디렉터리

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 거부 목록 `/etc/ingest-auth/deny-tokens` | 한 줄에 토큰 하나 · `#` 뒤 주석 · 빈 줄 허용. **일반 파일 · 64 KiB 이하**(링크는 끝이 일반 파일이면 따름). 넘거나 못 읽으면 새 연결이 모두 거부되고 **cut 도 아무것도 끊지 않는다**. 파일이 없으면 빈 목록(위험은 2절의 D7) | `ingestauth/denylist.go:22-35,117-142,185-205` · `RUNBOOK.md:1674-1697` |
| 2 | 거부 목록 읽기 시점 | publish 하나에 두 번(판정 · 마지막 확인) 캐시 없이 다시 읽는다. 덧붙이면 곧바로 효과가 난다 | `publish.go:49,176` · `RUNBOOK.md:1722-1723` |
| 3 | 거부 목록 권한(dev) | 응답기(UID 10003)가 읽을 수 있어야 한다: `root:10003 0640`. 디렉터리째 읽기 전용 bind(이름을 바꿔 끼우는 편집이 닿게). 호스트 디렉터리가 없으면 기동 실패(`create_host_path: false`) | `RUNBOOK.md:1676-1677` · `compose.yml:69-76` |
| 4 | 상태 디렉터리 `/var/lib/ingest-auth` | `connlog.jsonl`(현재 세대)과 `.1`(직전 세대), 두 세대만. 줄 내용은 `at` · `path` · `id`(비밀 없음). **코드가 검사하는 것**: 현재 세대를 `O_NOFOLLOW` 로 열고 `0600` 밖의 권한이 있으면 기동 거부, 쓸 수 없으면 기동 실패. 1 MiB 는 쓰기 **전** 회전 기준이라 마지막 줄만큼 넘을 수 있다. **코드가 검사하지 않는 것**: cut 의 두 세대 읽기는 링크 · 파일 종류 · 크기를 보지 않는다 — 세대 자리에 링크 · FIFO · 큰 파일을 두지 않는 것은 운영자가 지킬 조건이다 | `serve.go:22-23` · `ingestauth/connlog.go:20-35,50-52,74-96,120-159,345-353` · `RUNBOOK.md:1704-1707` |
| 5 | 상태 디렉터리 권한(dev) | `10003:10003 0700`, 호스트 쪽 첫 반영이 만든다(없으면 docker 가 root 소유로 만들어 응답기가 unhealthy) | `RUNBOOK.md:1701-1703` |
| 6 | 상태의 쓰임 | cut 이 읽어 「허용한 연결」 id 를 끊는다. 쓰기는 fsync 하지 않는다(응답기가 죽어도 페이지 캐시에 남아 cut 이 읽는다) | `connlog.go:114-119` · `ingestauth/cut.go:63-72` |
| 7 | CA 번들 | `/etc/ssl/certs/ca-certificates.crt`(root 소유) — 4C 를 https 로 부를 때를 대비 | `media/Dockerfile.ingest-auth:20-22` |

### 1-7. 실행 조건

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 이미지 | 멀티스테이지, 최종은 **scratch**(셸 · wget · nc 없음). 바이너리 `/ingest-auth` 는 root 소유. ENTRYPOINT `/ingest-auth` | `Dockerfile.ingest-auth:15-36` |
| 2 | 계정 | `USER 10003:10003`(이미지) + dev compose 가 같은 값을 `user:` 로 한 번 더 못박음 | `Dockerfile.ingest-auth:31-34` · `compose.yml:47-49` |
| 3 | 컨테이너 보안 | `read_only: true` · `no-new-privileges` · `cap_drop: [ALL]`. 로컬에서 이 꼴로 기동 · healthcheck 통과(2026-10-17), 상자 기동은 반영 때 확인 | `compose.yml:50-57` |
| 4 | 하위 명령 · 종료 코드 | `run`(기본) · `healthcheck` · `cut <토큰>`. 0 완료 · 1 실패 · 2 시작 못 함(설정 · 사용법 오류, cut 의 토큰이 거부 목록에 없음) | `main.go:1-18,46-54` |
| 5 | 운영 명령 | cut 은 `docker compose exec ingest-auth /ingest-auth cut <토큰>`. 셸이 없어 exec 꼴만 된다 | `Dockerfile.ingest-auth:24-28` |
| 6 | 재시작 동작 | 다시 뜨면 합집합이 비어 **파일부터 다시 쌓는다**. 줄을 지운 **해제**는 응답기만 다시 시작해야 풀린다. **틀린 줄 걷기는 재시작이 필요 없다**(틀린 줄은 판정에서 건너뛰고 healthz 만 실패시킨다). dev 상자 실측 재시작 창 31초(healthy 대기 포함, 2026-10-19) | `denylist.go:53-66,82-114,185-204` · `README.md:174-183` · `RUNBOOK.md:1719-1721,1979-1989` |

### 1-8. healthcheck

| # | 항목 | 내용 | 근거 |
|---|---|---|---|
| 1 | 명령 · 주기 | `["CMD","/ingest-auth","healthcheck"]` · 30초 · 3회 · 시작 유예 10초. 명령은 `http://127.0.0.1:8081/healthz` 를 5초 안에 묻는다 | `compose.yml:82-86` · `cmd/ingest-auth/health.go:15-17,53-73` |
| 2 | 200 조건(셋 모두) | 인증 리스너가 받는 중 ∧ 거부 목록을 **지금 다시 읽어** 온전 ∧ 기록을 **지금 다시 확인해** 쓸 수 있음. 실패하면 503 + 까닭(경로 · 이유 · 틀린 줄 수) | `health.go:22-49` |
| 3 | 보지 않는 것 | auth 상태 · 예산 · 실패 토큰 기억(스스로 다시 차거나 풀리므로). auth 장애는 healthz 가 아니라 아래 1-9 의 `auth_degraded` 로그로만 드러난다 | `health.go:22-26` |
| 4 | 종료 코드 | 실패는 늘 1(docker 가 2 를 예약) | `main.go:10-12` |

### 1-9. 로그 — 남는 것 / 남지 않는 것

JSON 한 줄씩 stdout(`slog`). 이벤트 이름은 `ingestauth_*`.

| # | 구분 | 내용 | 근거 |
|---|---|---|---|
| 1 | 남는 것(허용) | `publish_allowed`: `path`(토큰) · `conn_id` · `user_id` · `resolve_ms` | `publish.go:201-208` |
| 2 | 남는 것(거부) | `publish_denied`: `path`(**토큰 꼴일 때만**) · `conn_id` · `reason` · `resolve_status` · `resolve_ms` · `wait`(`reason=ip_limited` 은 DEBUG 이고 `path` · `conn_id` · `reason` 만). `caller_denied`(`remote_ip` = TCP 피어) · `caller_unauthenticated`(WARN — `remote_ip` = TCP 피어 · `reason` = `missing` · `malformed` · `mismatch`, 자격 값 없음) · `api_denied`(`ip` = 본문 ip) · `request_invalid`(reason 만 — `too_large` 는 **DEBUG**, 그 밖 reason 은 WARN) · `read_denied`(**DEBUG** — `protocol` 만, IP 없음). 두 DEBUG 줄은 공격 입력에서만 생기고 로그 쓰기 시간도 예산에서 빠지므로(`layers` 의 차감) `ip_limited` 전례를 따른다. **IP 한도 사건**(WARN — 에피소드마다 한 줄): `ip_limited`(`ip` = 키 문자열, IPv6 는 /64 접두 · `ip_kind` = public · private · loopback · other · `burst` · `interval_ms`) · `ip_limit_released`(`ip` · `denied` · `limited_for_ms` — 통이 다시 다 찬 뒤 처음 보거나 정리할 때) · `ip_limit_released_summary`(`count` · `denied_total` — 정리 한 번에 32개를 넘은 몫) · `ip_limit_full` / `ip_limit_recovered`(`max_entries` · `state_seq` — 상한 전이의 순번. 로그 줄의 차례는 전이의 차례와 다를 수 있어 가장 큰 순번이 지금 상태다) · `ip_limit_unkeyed`(`ip_kind`). 토큰 · 연결 id · passphrase 는 싣지 않는다 | `publish.go` `denyPublish` · `identityAttrs` · `handler.go` `denyForeignCaller` · `api` · `callerauth.go` `denyUnauthenticatedCaller` · `handler.go` `logInvalid` · `read` · `request.go` 의 `invalid*` 상수 · `iplimit.go` `logEvents` |
| 3 | 남는 것(상태) | `started`(`listen` · `caller_ip` · `caller_auth`(`basic` · `off`) · `auth_host_kind` · `auth_port` · `auth_channel`(`resolve-token` · `internal-token`) · `deadline_ms` · `ip_limit` · `ip_limit_burst` · `ip_limit_interval_ms` · `health`) · `caller_basic_off`(WARN — 명시 끔으로 기동할 때마다 한 줄. prod 에서 보이면 설정 누락) · `denylist_changed`(개수만) · `denylist_error` / `recovered` · `connlog_rotated`(bytes) · `failed_cleared` · cut 출력 한 줄(`path` · 출처별 개수 · `kicked` …) | `serve.go` `serve`(`ingestauth_started` · `caller_basic_off`) · `denylist.go` `merge` · `setFailing` · `clearFailing` · `connlog.go` `rotateIfFull` · `cmd/ingest-auth/cut.go` `writeCutLine` |
| 3b | 남는 것(장애 · 복구) | `auth_degraded`(ERROR — `reason` · `status`: 판정 불가가 처음 선 때) · `auth_recovered`(ERROR — `down_for` · `denied_during` · `budget_denied` · `recheck_denied`) · `connlog_error`(ERROR — `error`: 기록 쓰기 실패) · `connlog_recovered`(ERROR). healthcheck 는 auth 상태를 보지 않으므로 auth 장애의 유일한 신호다 | `ingestauth/gauge.go:43-74,90-101` |
| 4 | 남지 않는 것 | passphrase 원문(200 본문 한 자리만) · 4C 출입증 · API 비밀번호 · 호출자 Basic 자격(`Authorization` 원문 포함) · 비밀 파일의 경로 · 거부 목록 줄 내용 · 토큰 꼴이 아닌 경로 · publish 요청의 `user` / `password` · **publish 한 송출자의 IP(IP 한도 사건만 예외 — 위 2, 결정 119차 Q2)** · 4C URL 의 주소(부류 · 포트만) · 에러 문구의 환경 변수 값(변수 이름만) | `ingestauth/doc.go` 패키지 주석 · `request.go` `mtxRequest` · `config.go` `authTarget` · `main.go` 패키지 주석 |
| 5 | 비밀 보호 장치 | `Secret` 타입은 지원하는 문자열 포맷(`String`) · `GoString` · `slog` 에서 `[REDACTED]` 를 내고, 그 밖의 포맷도 원문을 노출하지 않는다(원문은 포인터 뒤 비공개 필드 — `secret.go:12-14`; `encoding/json` 직접 직렬화는 `{}`, JSON 마샬러는 없다). 조립 시험이 확인한다 | `ingestauth/secret.go:1-44` · `serve.go:90-98` |

잔여 위험: `caller_unauthenticated` 는 `caller_denied` 처럼 요청마다 한 줄이라, 같은 netns 에서 틀린
Basic 을 몰아 보내면 로그가 불어난다. 줄 수 한도 · 표본화는 없고 남는 방어는 로그 회전(배치 몫)뿐이다.
S1(송출자 IP별 시도 한도 — 1-1 의 15)은 본문을 읽은 뒤에 돌아 이 401 보다 뒤라 막아 주지
않는다.

잔여 위험(비밀 파일 소유자): `_FILE` 검사(1-5 표 위 문단)는 파일 종류 · 권한 비트 · 크기 · 내용만 보고
소유자 uid 는 보지 않는다. 소유자는 모드와 상관없이 파일을 바꿔 쓸 수 있다. 그래서 비밀 파일을 root 나
10003 소유로 두는 것은 배치 몫이다(2절 후보값 표 6).

잔여 위험(평문 http): MediaMTX → 응답기 인증 요청과 응답기 → Control API 호출은 http 평문이다. 호출자
Basic · Control API 자격과 200 본문의 passphrase 가 암호화 없이 오간다. 응답기에는 TLS 리스너가 없고
`MTX_API_URL` 은 http 만 받는다(1-4 의 4). 이 구간을 바깥에서 엿볼 수 없게 가두는 것은 배치 몫이다 —
dev 는 `internal` 제어망, prod 후보는 같은 netns 루프백(D1 · 2절 후보값 표 1).
prod 의 응답기 → auth 4C 호출도 호스트 간 VPC 구간을 http 평문으로 지난다 — 요청에
`X-Stream-Key-Resolve-Token` 출입증이 실리고 응답 본문에 passphrase 가 온다. 출발지는
보안 그룹과 auth 의 서브넷 대역(K6)으로 잠기고(D9), 사설 DNS 이름을 http 로 부르는 것은
플랫폼 전체 관례다(`infra/terraform/prod/platform/README.md` 의
`http://auth.pokeclip.internal`). 이 구간의 TLS 는 플랫폼 결정 때 함께 정한다.

**1절 표 행 수**: 1-1 15행 · 1-2 8행 · 1-3 8행 · 1-4 7행 · 1-5 18행 · 1-6 7행 · 1-7 6행 ·
1-8 4행 · 1-9 6행(3b 포함) = **79행**.

---

## 2. prod 기대와의 차이

prod 기대 = 계획 M2(Media 상자 compose) · M15(송출 인증 반영) · O8a, 그리고 M2 브랜치의 compose 자리. 요약표:

| # | 차이 | 응답기 코드 변경 | 한 줄 |
|---|---|---|---|
| D1 | 연결 꼴: 같은 netns 루프백 vs 제어망 고정 주소 | 불필요((가) 루프백은 M15 c1 계약 시험 층 B 로 확인됨 — 3절) | 계획은 루프백 · 같은 netns, dev 는 별도 제어망 |
| D2 | 호출자 IP 검사가 같은 netns 안에서는 구별력을 잃는다 | 현재 TCP 피어 IP 검사로는 구별 불가. Basic 자격(K1 (라), 포크 실측으로 확인됨)으로 구별 — 응답기에 Basic 검사를 더했다(트랙 A, 1-1 의 14) | nginx · 사이드카도 같은 `127.0.0.1` 로 보인다 |
| D3 | Control API 인증: 계획은 `api` 를 응답기에서 제외, 실물은 응답기 경유 | 불필요 | 제외하면 Control API 무인증 |
| D4 | 비밀 전달: 계획은 tmpfs 파일 경로, 기존 dev 가동판은 환경 변수만 | **필요** — 더했다(트랙 A, 1-5 의 6 · 9 · 13) | 기존 가동판엔 `_FILE` 입력이 없었고 이미지에 셸이 없다 |
| D5 | 송출자 `ip`: 계획이 응답기 요청에서 검증하려는 것을 응답기는 안 남긴다 | 선택 | publish 판정 · 로그에 ip 를 쓰지 않는다 |
| D6 | action 처리: 계획의 제외 목록에 `playback` 이 없다 · `read` 를 빼면 Bearer 없는 LL-HLS 가 열린다 | read 판정 변경(M15 c0 — SRT 만 200) | 그 밖의 action 은 전부 401 · 제외는 `playback` 하나 |
| D7 | 거부 목록 · 상태의 저장 자리 | 불필요 | 상자 교체 시 거부 목록 소실 = 신호 없이 풀림 |
| D8 | 재시작 단위: 계획 문구 vs M2 브랜치 vs dev 운영 절차 | 불필요 | 응답기만 다시 만들 수 있어야 한다 — S-3 로 확인(재생성 · restart 는 env_file 미반영) |
| D9 | 4C 호출 경로 · 인증 방식 · 출발지 잠금 | 창구 헤더를 설정으로 고르게 더했다(트랙 A, 1-3 의 2 · 1-5 의 15). auth 쪽은 3번 | 닫힘 — auth 는 POK-316 으로 `native` 위에서도 전용 창구를 연다(전달 머리글이 있으면 403). 운영 경로 = 전용 창구 · 출발지 = Media 프라이빗 서브넷(POK-429 · 계약4 2절 개정) |
| D10 | 시한 · 예산 상수가 환경 변수로 안 열린다 | 측정 뒤 판단 | prod 의 auth 왕복이 dev 와 다를 수 있다 |
| D11 | compose 자리가 비어 있다(포트 · 볼륨 · healthcheck · 기동 순서) | 불필요 | M15 c4 가 채웠다 |
| D12 | prod MediaMTX 에 SRT 송출 · 읽기 경로 잠금과 `overridePublisher: no` 가 없다 | 불필요(MediaMTX 설정) | SRT `read` 는 응답기가 200 이라 SRT 읽기 방어는 이 잠금뿐 |

### D1. 연결 꼴 — 루프백(같은 netns) vs 제어망

- **계획이 가정한 것**: nginx · 사이드카 · 응답기 · janitor 가 MediaMTX 의 netns 를 함께 쓴다. `authHTTPAddress: http://127.0.0.1:<응답기 포트>/<경로>`. 「MediaMTX ↔ 응답기는 같은 네임스페이스 안의 루프백이라 SG 가 필요 없다」(계획 718 · 747 · 1552~1553행, M2 브랜치 compose 27-30 · 159행).
- **실물**: dev 는 별도 `internal` 제어망(`10.250.0.0/29`)에 고정 주소 둘(`<A_mtx>` · `<A_auth>`)을 둔다. 응답기는 그 주소에만 묶고(미지정 주소 거부), `INGEST_AUTH_CALLER_IP` 로 MediaMTX 주소를 못박는다(`compose.yml:9-16,59-61`).
- **코드가 루프백을 받는가(추론)**: `INGEST_AUTH_LISTEN` 은 IP 리터럴이면 되고 거부하는 것은 미지정 주소뿐이다.
  `127.0.0.1` 은 통과한다(`config.go:44-54,66-78`). `MTX_API_URL` 도
  `http://127.0.0.1:9997` 이 IP 리터럴 http 라 통과한다(`mtxctl/client.go:109-127`).
  사이드카가 이미 prod 에서 같은 주소를 쓴다(`compose.yml.tmpl:139`). **실제 MediaMTX 가 루프백
  `authHTTPAddress` 로 인증을 묻는 동작은 이 조사에서 돌려 보지 않았다(미확인)** — M15 c1 의 시험은 가짜
  응답기(계획 1552행)라 실물 응답기 + 실물 MediaMTX 의 루프백 조합이 시험에 없다. — **확인됨(M15)**: c1 계약 시험
  층 B(C9 · C12 · C18 — 실물 MediaMTX + 실물 응답기 루프백)로 닫았다(3절).
- **그 밖에 따라오는 것**:
  - 같은 netns 는 포트 공간도 같다. 응답기의 인증 포트(dev 8080)와 **고정 healthz
    `127.0.0.1:8081`**(바꿀 수 없다 — `main.go:34`)이 MediaMTX(8888 · 9996 · 9997 ·
    8890/udp) · nginx 프록시 포트 넷과 겹치지 않아야 한다. M2 시험값(28181–28184)과는 겹치지 않으나 **prod
    포트 값은 이 조사에서 못 봤다(미확인)**. — **처분(M15)**: 렌더 시험 사례 40(망 칸 포트 서로 다름)이 시험 값으로
    보고, prod 값은 M9 모듈 단언으로 이관했다(3절).
  - 기동 순서가 dev 와 반대다. dev 는 응답기가 먼저(MediaMTX 가 그 뒤), prod 는 응답기가 MediaMTX 의 netns 에 붙으므로 MediaMTX 가 먼저 떠야 한다. 그 사이 MediaMTX 의 인증 요청은 연결 거부로 실패해 새 연결이 모두 거부된다(fail-closed — 안전한 쪽).
- **선택지**:
  - (가) 계획대로: `LISTEN=127.0.0.1:<포트>` · `CALLER_IP=127.0.0.1` · `MTX_API_URL=http://127.0.0.1:9997` · `authHTTPAddress: http://127.0.0.1:<포트>/mediamtx/auth`. 장: M2 구조 그대로, SG · 고정 IP 불필요. 단: D2.
  - (나) dev 식 별도 `internal` 망 + 고정 주소. **같은 netns 와 양립하지 않는다** — `network_mode: service:mediamtx` 는 MediaMTX 의 인터페이스를 전부 공유하므로 nginx · 사이드카도 제어망 인터페이스를 가진다. M2 구조를 바꿔야 한다.
- **응답기 코드 변경**: 둘 다 불필요(추론).

### D2. 호출자 IP 검사가 같은 netns 안에서는 구별력을 잃는다 (보안)

- **계획이 가정한 것**: 언급 없음. 「SG 가 필요 없다」(계획 747행)만 있다.
- **실물**: 응답기의 첫 방어선은 TCP 피어 주소 = `CALLER_IP`(1-1 의 4)이다. dev 에서는 nginx 가 제어망에 없어 응답기에 닿지도 못한다(`compose.yml:116-118`). 같은 netns 에서는 **nginx · 사이드카 · janitor 도 피어가 `127.0.0.1`** 이라 **현재의 TCP 피어 IP 검사로는** MediaMTX 와 구별되지 않는다. 그 컨테이너 안에서 `POST /mediamtx/auth` 를 칠 수 있으면 공개 식별자인 토큰(HLS 경로 이름이기도 하다 — `mediamtx.demo.yml.tmpl:64-65`)으로 **passphrase 를 200 본문으로 받을 수 있다**(`publish.go:225-241`). 거부 목록 · 예산 · 4C 검증은 그대로 거치지만 호출자 구별만 사라진다.
- **트랙 A 뒤**: 위 passphrase 노출은 호출자 Basic 검사(1-1 의 14)가 없던 기존 가동판의 모습이다.
  지금은 TCP 피어 검사(1-1 의 4) 다음에 Basic 검사가 서서, 자격 없는 요청은 본문을 읽기 전에
  401 이다. 자격을 읽을 수 있는 주체에게 남는 위험은 바로 아래 잔여 위험이다.
- **잔여 위험(숨기지 않고 적는다)** — K1 (라) 채택 뒤의 모습: **nginx 침해 → 막힌다**(Control API 자격이 없어 설정 조회로 Basic 자격을 읽지 못하고, 응답기를 직접 쳐도 Basic 이 없어 401). **사이드카 침해 → 못 막는다**: 사이드카는 Control API 자격을 쥐고 있으므로 설정 조회로 Basic 자격을 읽고(위 ③), 그 자격으로 응답기에 물어 공개 토큰마다 **모든 계정의 passphrase** 를 받을 수 있다. `overridePublisher: no` 는 진행 중인 송출의 탈취만 막고, 송출 중이 아닌 계정 경로에 먼저 송출하는 것은 막지 않는다(D12). 사이드카도 침해 대상이 되는 것은 (라) 이전에는 구별되지 않던 것이 nginx 와 사이드카를 가른 만큼 줄어든 위험이다.
- **완화 후보(확인 항목)**: 응답기가 `action=api` 요청 가운데 **설정 조회를 사이드카 자격에 대해 거부**할 수 있는가. 인증 요청 본문에 API 경로가 실리는지가 **미확인**이다(응답기가 읽는 본문 필드는 1-1 의 6 뿐 — `path` 가 API 경로인지는 코드에서 확정되지 않았다).
- **선택지**:
  - (가) 수용하고 기록한다. 근거: M2 의 nginx 는 고정 location 만 프록시하고 8080 · 9997 로 가는 location 이 없다(계획 c2). 위 잔여 위험을 M15 PR 의 보안 검토(ecc:security-reviewer 병렬)에 명시 항목으로 올린다. 비용 0.
  - (나) nginx 를 MediaMTX netns 에서 분리한다. 장: 응답기 · Control API 에 닿는 컨테이너가 mediamtx · 사이드카 · janitor 로 줄어든다. 단: M2 의 「nginx 가 `127.0.0.1:8888` 에 닿는다」(c1 사례 11)가 깨져 M2 구조 · 시험 · 헬스 경로를 다시 짠다.
  - (라) **별도 자격 검증 — 확인됨(kty 결정 2026-10-20 채택)**: `authHTTPAddress` 를 `http://사용자:비번@127.0.0.1:포트/…` 꼴로 두면 응답기가 호출자를 Basic 으로 검증할 수 있다. 포크 `v1.21.1-pokeclip.2` 이미지 로컬 실측(2026-10-20)으로 **확인된 것은 셋**이다: ① MediaMTX 가 응답기로 보내는 요청에 `Authorization: Basic` 이 실린다 ② 인증 실패 로그는 `http://user:***@…` 로 비밀번호를 가린다 ③ **Control API 의 설정 조회(`GET /v3/config/global/get`)는 `authHTTPAddress` 를 자격 평문 그대로 돌려준다**(가림 없음 · 로그 노출은 0). 소스 근거: `internal/auth/manager.go` 의 `httpClient.Post(m.HTTPAddress, …)`, `internal/conf/conf.go` 는 `http(s)` 접두만 검사. ③ 때문에 **Control API 에 인증된 주체(사이드카 · 응답기 자신)는 Basic 자격을 읽을 수 있고, Control API 자격이 없는 nginx 는 읽지 못한다.** 응답기 코드 변경 = Basic 검사 추가(「응답기 코드 변경 한 묶음」). 비용: 비밀이 하나 늘고 MediaMTX 설정 파일에 비밀이 든다(tmpfs 렌더 · `0400` 10002 — M2 c4 의 `mediamtx.yml` 렌더와 같은 자리).
- **표현 한정**: 「구별 불가」는 **현재의 TCP 피어 IP 검사로는**이라는 한정이 붙는다. 다른 수단이 불가능하다는 뜻이 아니다.
- **다른 결정에 주는 영향**: (라)를 채택하면 K1 의 잔여 위험이 nginx 에 대해 줄고(사이드카에 대해서는 위), K2(Control API 비밀)와 K3(비밀 파일 수)에 인증 비밀이 하나 더 생긴다(응답기 ← 이 URL 자격, MediaMTX 설정에도 렌더). K1 · K2 는 한 묶음으로 정한다.
- **응답기 코드 변경**: (가) · (나) 없음, (라) 필요(채택됨 — 트랙 A 가 더했다, 1-1 의 14).

### D3. Control API 인증 — `api` 를 제외할 것인가, 응답기가 판정할 것인가

- **계획이 가정한 것**: M15 c2 `authHTTPExclude` 에 `api` · `metrics` · `pprof`(계획 1553행). 제외된 action 은 인증 없이 통과한다.
- **실물**: dev 는 `read` 만 제외하고 `api` 는 응답기로 보낸다 — 템플릿이 「api 를 여기 넣으면 Control API 가 무인증이 된다」고 못박고 render.sh 가 단언한다(`mediamtx.demo.yml.tmpl:51-55` · `render.sh:171-182`). 응답기의 api 행은 SelfIP + 자격 일치를 요구한다(1-4 의 5).
- **같이 봐야 할 것**: 사이드카는 이미 `MTX_API_USER` + `MTX_API_PASS_FILE`(파일) 로 Basic 자격을 싣는다. 그리고 「M2 상자에서 인증을 강제하려면 무인증 `api` 항목(기본값의 localhost 관리자 항목 포함)을 지우는 일이 따라야 한다」고 적혀 있다(`media/README.md:198-199,352-361`). 즉 사이드카 쪽은 (나)를 이미 준비해 두었다. 같은 netns 에서 사이드카의 출발지는 `127.0.0.1` = 응답기 SelfIP(응답기가 `127.0.0.1` 에 묶일 때)이므로 자격만 맞으면 통과한다.
- **선택지**:
  - (가) 계획대로 `api` 제외. 장: 설정 한 줄. 단: netns 안의 모든 컨테이너(D2)가 Control API 전권(설정 변경 · 경로 추가 · 연결 끊기 · 녹화 삭제 — `media/README.md:350`)을 무인증으로 가진다. 긴급 절단(`cut`)은 응답기를 거치지 않아 자격이 의미가 없는데도 `MTX_API_USER` / `MTX_API_PASS` 는 기동 필수라 값은 어차피 넣어야 한다(`serve.go:103`).
  - (나) dev 식: `api` 를 응답기로 보낸다(`authHTTPExclude` 는 D6 의 항목뿐 — dev 는 `read`,
    prod 응답기 모드는 `playback` 하나(K4 개정, 2026-10-23)). 장: Control API 가 자격으로 잠기고
    사이드카 · cut 이 같은 비밀을 쓴다. 단: 비밀 하나(`MTX_API_PASS`)를 응답기(10003)와 사이드카(10001)가
    각자 읽는 파일 둘로 나눠 렌더해야 한다. 응답기가 죽으면 api 인증도 죽는다(사이드카 관측이 `auth_rejected` / 실패로
    보인다 — `media/README.md:199`).
- **K1 과의 관계**: (나)는 Control API 만 자격으로 잠근다. D2 의 passphrase 오라클(인증 경로)은 막지 않는다 — 그래서 K1 · K2 를 한 묶음으로 정한다.
- **응답기 코드 변경**: 없음(D4 의 비밀 입력 방식은 별건).

### D4. 비밀 전달 — tmpfs 파일 경로 vs 환경 변수

- **계획이 가정한 것**: 「비밀은 환경변수로 넘기지 않는다 … 응답기도 비밀 파일 경로만 받는다(M15)」(계획 738~740 · 1025 · 1724행). M15 c1 시험 「응답기 컨테이너 환경에 비밀 0건」(1552행). 시작 스크립트가 `media_secret_arns` 값을 tmpfs 파일로 렌더, 소유 = 응답기 실행 계정.
- **실물(기존 dev 가동판)**: 비밀 둘(`MTX_API_PASS` · `STREAM_KEY_RESOLVE_TOKEN`)을
  **환경 변수로만** 읽었다. `_FILE` 입력이 코드에 없었다 — POK-310 트랙 A 가 더했다(1-5 의 6 · 9 ·
  13). 최종 이미지가 scratch(셸 없음)라 「셸 래퍼가 파일을 읽어 export 한 뒤 exec」하는 우회도
  불가능하다(`Dockerfile.ingest-auth` 의 최종 스테이지 `FROM scratch`).
- **선택지**:
  - (가) 응답기 코드에 `STREAM_KEY_RESOLVE_TOKEN_FILE` · `MTX_API_PASS_FILE` 를 더한다(파일이 있으면 파일, 환경 변수와 동시 지정은 기동 거부). 사이드카의 `MTX_API_PASS_FILE` 규칙(한 줄 · 끝 줄바꿈 하나만 걷음 · other 권한 · group 쓰기 거부 · 4 KiB 이하 · 실패 = 기동 실패 — `media/README.md:206-230`)을 그대로 따를 수 있다. 장: 계획 · 시험(c1)이 그대로 성립, 사이드카와 규칙 통일. 단: 응답기 코드 · 시험 변경(1번 몫, 이 문서 밖).
  - (나) 환경 변수 유지, 계획 · 시험을 고친다(「환경에 비밀 0건」을 「compose 파일에 값 0건」으로 완화 — 값은 시작 스크립트가 만든 `env_file` 로). 장: 코드 변경 0. 단: `docker inspect` 에 비밀이 남는다(사이드카 README 가 파일을 택한 바로 그 이유). M2 의 원칙 「비밀은 환경변수로 넘기지 않는다」와 어긋난다.
- **값의 출처(같이 정할 것)**: `STREAM_KEY_RESOLVE_TOKEN` 은(전용 창구를 유지할 때) auth 쪽 설정과 같은 값이어야 하므로 Secrets Manager(`media_secret_arns`)에서 읽는다. `MTX_API_PASS` 는 **응답기와 사이드카 둘만 아는 값**이라 Secrets Manager 에 둘 필요 없이 시작 스크립트가 **부팅마다 무작위로 만들어** 두 파일로 렌더해도 된다(둘 다 같은 시작에서 읽는다; 교체 = compose 전체 재시작 — `media/README.md:199`). 단 3번(auth)과 값을 맞출 일이 없는지는 확인 대상이다(추론: 이 값은 MediaMTX ↔ 응답기 ↔ 사이드카 안에서만 쓰인다).
- **M2 시험과의 충돌(추가 항목)**: M2 `infra/media-host/test/render_test.sh:387` 이 compose 의 환경 변수 **이름**에 `CDN|TOKEN|PASSWORD|SECRET` 계열을 금지한다(계획 743행의 문구). `STREAM_KEY_RESOLVE_TOKEN_FILE` 은 `TOKEN` 에 걸리고 `MTX_API_PASS_FILE` 은 걸리지 않는다. (가)를 택하면 **파일 경로를 담는 변수(`*_FILE`)는 허용하되 비밀 값을 직접 주입하는 변수의 금지는 유지**하도록 그 검사와 계획 743행 문구를 바꿔야 한다(M2 브랜치·계획 수정 몫 — 이 문서는 고치지 않는다).
- **응답기 코드 변경**: (가)면 **필요** — K3 로 채택, 트랙 A 가 더했다(1-5 의 6 · 9 · 13).

### D5. 송출자 `ip` 필드

- **계획이 가정한 것**: M15 c1 시험 「응답기 요청 본문에 송출자 `ip`」, O8a 4 「T8e 때 MediaMTX 인증 요청의 `ip` = 송출자 IP」(계획 1510 · 1552행).
- **실물**: MediaMTX 가 본문에 `ip` 를 싣는 것은 맞고 응답기가 파싱도 한다(안 되면 400). 그러나 응답기는 **publish 에서 `ip` 를 판정에도 로그에도 쓰지 않는다**(1-1 의 7 · 1-9 의 4). 실물 응답기의 로그만으로는 「`ip` = 송출자 IP」를 확인할 수 없다.
- **갱신(2026-10-20 · POK-310 트랙 B)**: 이제 응답기는 publish 의 `ip` 를 IP별 시도 한도의 키로 쓰고, **한도 사건에서만** 그 키와 부류(`ip_kind`)를 로그에 남긴다(1-1 의 15 · 1-9 의 2). 그래서 한도를 일부러 일으키면(자극 도달 식 — 트랙 B 계획 5-1) 응답기 로그로 「`ip` = 송출자 IP」를 확인할 수 있다(트랙 B 계획 5-3 D1 · O8a). 요청마다 남기는 (나)는 택하지 않았다. **전제**: `ip` 가 송출자 공인 주소여야 한다 — docker 포트 게시 · NLB 가 출발지를 바꾸면 모든 송출자가 한 키로 묶여 30번 뒤 전원 거절된다. prod SRT 수신은 IPv4 전용이 공개의 선행 조건이다(결정 119차 Q7 — dualstack NLB 는 반대 계열 송출자를 LB 주소 하나로 보인다).
- **선택지**:
  - (가) 코드 불변. c1(가짜 응답기)이 본문을 기록해 `ip` 를 확인하고, O8a 4 의 실물 확인은 MediaMTX 쪽 연결 로그(연결 시 송출자 주소가 찍히는지는 이 조사에서 확인 안 함 — 미확인)로 한다. 장: 송출자 IP 를 응답기 로그에 새로 남기지 않는다(현 설계가 의도적으로 안 남기는 것). 단: O8a 4 의 수단을 계획에서 다시 적어야 한다.
  - (나) 응답기에 `ip` 를 DEBUG 로 남긴다. 장: 한 곳에서 확인. 단: 개인정보(송출자 IP)가 응답기 로그에 생긴다. 로그 정책 · 누설 시험(`leak_test`) 변경.
- **응답기 코드 변경**: (가) 없음, (나) 필요.

### D6. action 처리 — 계획의 G17 에 대한 코드 쪽 답

- **계획이 가정한 것**: `authHTTPExclude` 에 `api` · `metrics` · `pprof`. read · playback 은 「c2 착수 때 포크 소스로 확인」(G17, 계획 1553 · 1924행).
- **실물(코드로 확정)**: 응답기는 `api` 와 `read` 를 가르고 **나머지 action 은 전부 publish 행으로 보내 `not_publish` 401 로 거부**한다(`handler.go:192-201` · `publish.go:103-104`). 따라서:
  - `playback` 을 제외하지 않으면 9996 재생 서버가 401 이다(계획의 제외 목록에 없음). 계획 c6 은 루프백 한정 playback 내부 사용자 항목을 따로 두지만 `authMethod: http` 에서는 내부 사용자 목록이 쓰이지 않는다(계획 1553행 · `mediamtx.demo.yml.tmpl:46-48`).
  - `read` 는 응답기가 **`protocol=srt` 만 200, 그 밖(HLS 등)은 401** 이다(M15 c0 — kty 결정
    1, 2026-10-23 · `handler.go` `read`). 그 전에는 늘 200 이었다. 그래서
    `authMethod: http` 에서 `read` 를 제외하거나 늘 200 을 주면 Bearer 없는 LL-HLS 읽기가 열린다 —
    CDN Bearer 와 같은 세션만 MediaMTX 가 인증을 건너뛰고(SkipAuth), 그 밖의 HLS 요청은 이 인증을 탄다(M15
    계획 0-1 — 포크 `hls/http_server.go` · `session.go` · `auth/manager.go` 판독).
    제외하지 않은 `read` 는 요청마다 응답기를 지나 그 처리 시간이 예산에서 빠진다(`handler.go` `layers` 의 차감 —
    거부 줄이 DEBUG 인 까닭). dev 는 응답기 판정과 무관하게 `read` 를 제외한다(동작 불변). **SRT 읽기는 응답기가
    200 이므로 막지 않는다.**
  - **필수 전제 — 경로 잠금(D12)**: SRT `read` 를 응답기가 200 으로 답해도 안전한 것은 MediaMTX 의
    `srtReadPassphrase` 가 SRT 읽기를 막기 때문이다. prod 설정에 이 잠금이 없으면 공개 토큰을 아는 누구나 SRT
    read 로 원 스트림을 무암호로 읽는다. 그러므로 SRT read 의 200 은 **D12 의 설정 이행이 선행**일 때만 성립한다.
    이것은 응답기 코드가 아니라 MediaMTX 설정 변경이다.
  - `metrics` · `pprof` 도 제외하지 않으면 401 이다. 계획 c6 은 둘 다 `no` 로 명시하므로 prod 에서는 켜져 있을 때만 문제다.
  - `protocol != srt` 와 토큰 꼴이 아닌 `path` 의 publish 는 전부 401 이다. prod 계획 c6 은 `rtmp: no` 로 바꾸고 이름을 박은 시험 경로(`pokeclip-m4-test`)를 지우며 경로는 `all_others` 만 남긴다(계획 860~877행). 그래서 비-SRT 송출은 애초에 열려 있지 않고, 토큰 꼴이 아닌 경로의 송출은 응답기가 `not_token` 으로 거부한다. 루프백 시험 송출의 경로도 4C 로 풀리는 등록 키의 토큰 꼴이어야 한다 — 계획 c5 시험 송출 경로를 이에 맞춘다.
  - 포크의 action 이름 체계는 코드 주석(`request.go:13`)이 가리킨다. **확인됨(M15, 2026-10-23 포크
    소스)**: `internal/conf/auth_action.go` 의 action 이름은 publish · read · playback
    · api · metrics · pprof 이고 `playback` 은 유효 이름이다. `authHTTPExclude` 항목은
    action · path 뿐이라 IP 조건이 없다(`internal/conf/conf.go`) — 제외한 playback(9996)의
    통제는 `127.0.0.1` 바인딩뿐이다.
- **선택지**:
  - (가) `authHTTPExclude` = `read` · `playback`(+ `metrics` · `pprof` 가 켜져 있으면
    그것들, `api` 는 D3). **단 D12 의 잠금 이행을 같은 PR 의 전제로 한다.** — **K4 가 2026-10-23 에
    대체했다**: prod 응답기 모드의 제외는 `playback` 하나이고, `read` 는 응답기가 판정한다(위 실물 둘째 항목 ·
    M15 c0). 장: 코드 불변, dev 와 같은 원리. 단: playback 이 무인증이 되어, 재생 접근 통제는 nginx 의
    `path` 대조(`map`)와 설계 3-3 의 루프백 한정 수단에 의존한다.
  - (나) 응답기 코드가 playback 을 판정하도록 확장. 장: 한 곳에서 통제. 단: 응답기 판정표(설계 2-4)에 없는 새 행, 시험 · 설계 변경. 권하지 않는다.
- **응답기 코드 변경**: 원안 (가)는 코드 변경이 없었다. 채택안(K4 개정 — (가)를 대체)은 `read` 를 제외 목록에서 빼므로
  read 판정을 SRT 만 200 으로 바꿨다(M15 c0 — `handler.go` `read`).

### D7. 거부 목록 · 상태 디렉터리의 저장 자리

- **계획이 가정한 것**: 이 항목에 대한 기술이 없다(M2 의 응답기 자리는 이미지 · netns · 보안 옵션만 — M2 브랜치 `compose.yml.tmpl:157-171`). Media 상자는 user_data 로 렌더하는 교체형이다(`config_version` 을 올려 새 키로 apply 하는 정상 교체 — 계획 1256행).
- **실물**: dev 는 호스트의 영속 디렉터리(`${DEMO_HOME}/ingest-auth/{deny,state}`)다. **거부 목록 파일이 사라진 채 응답기가 다시 뜨면 거부했던 토큰이 신호 없이 다시 붙는다**(합집합이 비어서 시작 — 흔적은 `publish_allowed` 한 줄뿐). 그래서 dev 의 사람이 응답기를 띄우는 모든 블록은 파일부터 확인한다(`RUNBOOK.md:1686-1697` · `README.md:194-199`). 디렉터리째 없으면 `create_host_path: false` 로 기동이 실패해 드러난다.
- **prod 에서의 의미**: 상자를 교체하면 로컬 디스크의 거부 목록이 사라진다. 상태 디렉터리는 사라져도 무해하다(연결도 상자와 함께 사라진다 — `connlog.go:116-119`) — 단 디렉터리는 **존재하고 10003 이 쓸 수 있어야** 기동한다.
- **선택지**:
  - (가) 상자 디스크(EBS)의 영속 디렉터리. 장: dev 와 같은 구조. 단: 교체 때마다 거부 목록을 옮기는 절차가 없으면 조용히 풀린다.
  - (나) 거부 목록의 **정본을 상자 밖**(예: Secrets Manager 아닌 비밀 아님 저장소 — SSM Parameter · S3 객체)에 두고, 시작 스크립트가 시작 때 파일로 렌더한다. 못 받거나 비면 기동을 막는다(CDN 비밀 실패 갈래와 같은 fail-closed 패턴 — 계획 c4). 긴급 절단 덧붙이기는 정본에 쓴 뒤 상자 파일에도 쓴다. 장: 교체 · 재부팅에 안전. 단: 덧붙이기 · 해제 절차(dev RUNBOOK 8 · 9절)를 prod 용으로 다시 써야 한다.
  - (다) tmpfs: 재부팅 때마다 사라지므로 **안 된다**(fail-open).
  - 상태 디렉터리는 `/run` 아래 tmpfs 도 가능하다(컨테이너만 다시 시작할 때는 남고, 상자 재부팅 때는 연결도 없다). 어느 쪽이든 `10003:10003 0700` 으로 만들어야 한다.
- **응답기 코드 변경**: 없음(시작 스크립트 · 절차의 몫).

### D8. 재시작 단위

- **계획이 가정한 것(문구)**: 「모든 서비스는 `restart: "no"`, 재기동은 systemd 유닛이 compose 전체 단위로만」(계획 731행).
- **M2 브랜치의 실제**: `mediamtx` · `nginx` 만 `restart: "no"`(시작 스크립트가 이 둘의 출력에만 붙어, 하나가 끝나면 전체가 내려간다). 나머지(응답기 자리 포함)는 `restart: on-failure`로 **그 컨테이너만** 다시 뜬다(`compose.yml.tmpl:27-37,123,159`). 계획 문구와 다르다 — 이 문서가 맞출 쪽은 브랜치(응답기에는 이쪽이 필요하다).
- **실물이 요구하는 것**: 거부 **해제**는 **응답기만 다시 시작**하는 절차다(틀린 줄 걷기는 재시작이 필요 없다)(MediaMTX 는 건드리지 않아 다른 송출이 이어진다 — `README.md:164,174` · `RUNBOOK.md:1979-1989`). 응답기가 멈췄다 다시 듣기까지 모든 계정의 새 연결 · 재접속이 거부된다(dev 상자 재시작 창 31초).
- **확인됨(M15 S-3 스파이크, 2026-10-23 — compose 5.5.0 · 5/5회)**: 시작 스크립트와 같은 argv 의
  부착 `up` 아래에서 `up -d --no-deps --force-recreate ingest-auth`(비부착 재생성)를 해도 주
  `up` 은 살아 있고 「Aborting on container exit」 은 0, 부착 서비스 ID 도 그대로였다. 양성 대조(부착 서비스
  재생성)는 2/2 로 잡혔다. `docker restart` 도 주 `up` 을 건드리지 않지만 **env_file 을 다시 읽지 않는다**
  — 그래서 prod 의 응답기 단독 절차는 restart 가 아니라 재생성이다(`infra/media-host/bin/lib/ops.sh`
  `ops::recreate_ingest_auth`). 진행 중 송출 유지는 c1 C13 이 본다. 상설 CI 사례는
  `infra/media-host/test/unit_restart_test.sh` 사례 22(응답기만 재생성 — 「Aborting on
  container exit」 0 · MainPID · NRestarts 불변 · 부착 서비스 ID 불변 · 응답기 ID 교체)와 양성 대조
  사례 23(부착 nginx 재생성 → Aborting · ID 바뀜)이고, CI 잡 media-host-systemd 가 3회 돌린다(M15
  c7 — e7cf1701).
- **선택지**: (가) 브랜치대로 두고 M15 에서 위를 실측해 계획 문구를 정정. (나) 계획 문구대로 전체 단위 — 응답기 재시작이 모든
  송출을 끊으므로 해제 절차가 성립하지 않는다. 추천은 (가). **M15 가 (가)로 정했다** — 응답기만 다시 만드는 길은 운영 스크립트
  둘(`ingest-auth-deny-sync.sh --release` ·
  `ingest-auth-ip-limit.sh`)뿐이다(`infra/media-host/README.md` 운영 규칙 1 의 예외).
- **응답기 코드 변경**: 없음.

### D9. 4C 호출 경로 · 인증 방식 · 출발지 잠금 (prod)

- **계획이 가정한 것**: 「응답기 → auth 는 런타임 2단 규칙」, Media → auth SG 짝은 런타임 2단(계획 747 · 1724행). prod auth 는 `native` 프록시 신뢰 + 좁힌 `internal-proxies`(계획 1113 · 1524행).
- **실물**: dev 는 `http://<dev auth 사설 주소>:8082` — 값이 **사설 주소여야** 한다(공인 주소면 auth 의 출발지 잠금에 걸려 403 — `.env.example:6`). 응답기 코드는 호스트 이름 · https 도 받는다(1-3 의 1). 계약4 2절은 「운영 Media 의 resolve 경로는 아직 정하지 않았다」이고 auth 의 전용 토큰 출발지는 「설정된 주소(지금은 media-dev 하나)」다(개정 전 판 — 2026-10-24 개정으로 운영도 전용 헤더 · 출발지 IPv4 대역, 1-3 의 8). 4C 승인권은 3번이다(계약4 4절 · ADR-033).
- **prod 창구(kty 결정 2026-10-24 · POK-429)**: prod 응답기는 **전용 창구**
  (`X-Stream-Key-Resolve-Token` — `STREAM_KEY_RESOLVE_CHANNEL=resolve-token`,
  값 = 런타임의 비밀 `pokeclip/prod/stream-key-resolve-token` · 출력
  `ingest_auth_resolve_token_secret_arn`)로
  `http://auth.pokeclip.internal:<auth 포트>` 의 4C 를 부른다. 아래 「선행 조건 해소」 의
  공용 창구를 대체한다 — 그 근거(prod auth `native` 와 전용 창구 양립 불가)는 POK-316 으로
  사라졌다(`native` 위에서도 전용 창구가 열리고, 전달 머리글을 단 전용 헤더 요청은 403). 주소 ·
  보안 그룹 출발지 잠금은 그대로이고, auth 의 전용 창구 허용 대역은 Media 프라이빗 서브넷
  대역이다(아래 선택지 (나) — 런타임 1단-나 ② K3). 한계: 그 대역은 같은 AZ 의
  다른 ECS 태스크도 출발지 조건을 통과한다 — 통제는 토큰이다.
- **선행 조건 해소(POK-307 코멘트 10640, 3번 답 — 창구는 위 「prod 창구」 가 대체)**: prod
  응답기는 **공용 창구**(`X-Internal-Token`
  — `STREAM_KEY_RESOLVE_CHANNEL=internal-token`, 값 = INTERNAL_API_TOKEN 비밀)로
  `http://auth.pokeclip.internal:<auth 포트>` 의 4C 를 부른다. 출발지 잠금은 주소가 아니라 **보안
  그룹(Media SG → auth)** 이다. 그래서 아래 전용 창구 · `native` 충돌과 출발지
  단위(K6)는 prod 에서 다루지 않았다(POK-316 이전 판 — 지금 코드와 다름 · 지금은
  위 「prod 창구」). 아래 원문은 그 판단의 기록으로 둔다.
- **선행 조건(원문 · POK-316 이전 판 — 지금 코드와 다름) — prod auth 와의
  양립(3번과 먼저 합의)**: auth 의 전용 토큰 창구는 켜져 있으면 부팅 검사가
  `server.forward-headers-strategy` 가 정확히 `none` 인지(미설정 포함 아님)를
  요구한다
  (`services/auth/.../config/StreamKeyResolveSecurityConfig.java:66-77,102-108`;
  허용 대역 IPv4 · 톰캣 remoteip 헤더 속성 없음 · 공용 토큰과 다름도 함께 검사 —
  `application.yml:185-192` 주석). 반면 prod auth 계획은 `native` 다. **prod auth 에 전용
  토큰을 켜면 `native` 와 부딪혀 auth 가 뜨지 않는다**(실패가 부팅 거부라 눈에 띈다). 운영 auth 는 지금 이 창구를 쓰지
  않아 토큰을 비워 둔다(`application.yml` 같은 자리 주석). 따라서 「어떤 경로 · 어떤 인증 방식으로 prod 응답기가 4C
  를 부르는가」(예: 전용 토큰 창구를 쓰는 별도의 `none` 인스턴스 · 포트, 또는 공용 토큰 창구 등 — 후보일 뿐 3번이 정한다)가
  K6 보다 먼저다. `native` 에서는 피어 IP 의 의미도 달라져 아래 출발지 잠금 판단에 영향이 있다.
  위 줄 번호는 POK-316 이전 판이다. 지금 코드는 `requireSafeToOpen`
  (`StreamKeyResolveSecurityConfig.java:106-141`)이 `none` · `native` 를 둘 다
  받고(126-132행), 설정 주석은 `application.yml:219-222` 다.
- **코드가 지원하는 범위와 계약의 현재 허용 범위는 다르다**: auth 는 이미 **IPv4
  CIDR 목록**(`allowedCidrs` — `StreamKeyResolveAccessProperties.java:13-20`, 설정
  `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` 쉼표 목록 — `application.yml:219-225`)을
  받는다. 계약4 2절의 문구만 「설정된 주소(지금은 하나)」다(개정 전 판 — 지금은 IPv4 대역).
- **prod 에서 정할 것(위 선행 조건이 정해진 뒤)**: (a) auth 의 주소(내부 DNS 이름 vs 고정 주소), (b) auth 가 허용할 출발지. prod 의 응답기는 상자에서 나가므로 출발지는 상자 쪽 주소(컨테이너가 브리지 뒤면 변환된 상자 주소일 것이나 — 추론, 미확인)다. 상자를 교체하면 주소가 바뀔 수 있다.
- **선택지(출발지 단위)**:
  - (가) 상자에 고정 사설 주소(고정 ENI 등)를 두고 허용 대역을 그 `/32` 하나로. 장: 가장 좁다. 단: 상자 교체 절차에 주소 고정이 들어간다(M9 몫). 코드 · 설정은 이미 지원(`/32` CIDR).
  - (나) 허용 대역을 Media 서브넷 CIDR 로. 장: 교체에 강하다. 단: 대역 안의 다른 호스트도 출발지 조건을 통과한다(토큰은 여전히 필요). **auth 코드 확장은 필요 없다** — 필요한 것은 **계약4 2절 문구 개정 · 3번 승인 · 위 선행 조건과의 기동 호환 확인**이다.
- **prod 값(M15)**: compose 응답기 env `STREAM_KEY_RESOLVE_URL:
  ${stream_key_resolve_url}`(M9 이 넘기는 템플릿 변수) · `STREAM_KEY_RESOLVE_CHANNEL:
  resolve-token`(기본과 같아도 명시) · `STREAM_KEY_RESOLVE_TOKEN_FILE`(시작 스크립트가
  `media.env` 의 `STREAM_KEY_RESOLVE_TOKEN_SECRET_ARN` 에서 읽어 렌더 —
  `infra/media-host/bin/start-media.sh` `read_resolve_token`). 호스트 이름 URL 은 응답기
  코드가 이미 받는다(1-3 의 1). 계약4 2절 문구 개정은 3절 항목이다(닫힘).
- **응답기 코드 변경**: 더 없다. 트랙 A 가 창구 설정 `STREAM_KEY_RESOLVE_CHANNEL` 을 더해
  전용(`X-Stream-Key-Resolve-Token`) · 공용(`X-Internal-Token`) 헤더를 설정으로
  고른다(1-3 의 2 · 1-5 의 15 — `keyresolve/client.go` `Channel`). 그 전 dev 가동판은 전용 헤더
  이름을 고정했다. 계약 문서 개정은 어느 창구든 필요하다(위키 PR — 이번 개정은
  kty 승인 머지 · 3번 알림, 3절).

### D10. 시한 · 예산 상수

- **실물**: `MaxDeadline` 1.125초(설계 3-7 의 `B_obs` 2.75초에서 계산한 값) · 예산 1.375초 / 0.5초 보충 · 연결 준비 2초는 **코드 상수**다(1-1 의 12 · 13). 「올리려면 B_obs 를 다시 재야 한다」(`handler.go:17-20`).
- **prod 에서의 의미**: dev 의 측정은 같은 VPC 사설 구간이다. prod 는 상자 → ECS auth 왕복이 다를 수 있고, 계약4 는 「넘으면 판단 불가」라고 적어 두었다(2절). 시한을 지키지 못하면 새 연결이 거부되는 쪽으로 실패한다(fail-closed).
- **선택지**: (가) 상수 유지, O8a 3 의 거부 실측에서 resolve 분포(`resolve_ms`)를 같이 기록해 확인. (나) 분포가 모자라면 B_obs 를 재서 코드 상수를 바꾼다. 추천은 (가) 후 필요 시 (나).
- **응답기 코드 변경**: (가) 없음, (나) 상수 변경.

### D11. compose 자리가 비어 있다 — M15 c2 가 채울 목록

M2 브랜치의 응답기 자리는 이미지 · netns · `depends_on` · `restart: on-failure` · `no-new-privileges` · `cap_drop: [ALL]` · 로그뿐이다(`compose.yml.tmpl:157-171`). 실물과 맞추려면 아래가 더 필요하다.

**채움(M15 c4)**: 아래 1–9 를 `infra/media-host/compose.yml.tmpl` 의 응답기
갈래(`ingest_auth_image` 가 찬 렌더)에 채웠다. env 는 정확히 1 의 집합이고
`INGEST_AUTH_CALLER_BASIC` · `LOG_LEVEL` · `INGEST_AUTH_IP_LIMIT` 은 적지 않는다(IP
한도는 `env_file` `ingest-auth.env` 한 줄 — M15 계획 Q3). bind 는 다섯(비밀 셋 ro · 거부 목록
**디렉터리** ro · 상태 rw — 모두 `create_host_path: false`)이다. 5 의 시험 이음매는
`infra/media-host/README.md` 「시험 이음매」 표, 7 은
`infra/media-host/test/ingest_auth_contract.sh` 층 B, 9 는 `render_test.sh` 사례
11(비밀 계열 env 이름 금지 유지) · 36(`_FILE` 값 = 읽기 전용 bind 대상)이다.
응답기 서비스의 렌더 단언은 `render_test.sh`(`test/lib/render_auth_cases.sh`)
사례 35–42 · 44–46 이다 — 35 env 정확 집합 10개 · 37 healthcheck · 읽기 전용
루트 · `user` 없음 · 38 env_file · 39 모드 일치 · 40 망 칸 포트 · 42 bind 원천
= 시작 스크립트 기본 자리 · 44 계약 시험 compose 드리프트 · 45 응답기 실행
계정(`Dockerfile.ingest-auth` USER = 시작 스크립트 기본 소유) · 46 유닛 시험
compose 드리프트(M15 c4 — 2cd4ee4b · 45 · 46 은 77e3f537). 43 은 옛 갈래
골든이다.

| # | 채울 것 | 값 · 근거 |
|---|---|---|
| 1 | `environment` | 비밀 아닌 값: `INGEST_AUTH_LISTEN` · `INGEST_AUTH_CALLER_IP` · `MTX_API_URL` · `MTX_API_USER` · `STREAM_KEY_RESOLVE_URL` · `INGEST_AUTH_BASIC_USER`(후보값 표 3) · `STREAM_KEY_RESOLVE_CHANNEL`(1-5 의 15). 비밀 셋(K3)은 파일 경로 변수 `MTX_API_PASS_FILE` · `STREAM_KEY_RESOLVE_TOKEN_FILE` · `INGEST_AUTH_BASIC_PASS_FILE`(1-5 의 6 · 9 · 13). `INGEST_AUTH_CALLER_BASIC` 은 적지 않는다(`off` 면 Basic 검사가 꺼진다 — 1-5 의 14 · 1-9 의 3). `LOG_LEVEL` 도 적지 않는다(기본 info — M15 c8 검수 r1 에서 이 칸을 정확 집합 10개에 맞춤) |
| 2 | `read_only: true` | dev 가 쓴다(1-7 의 3). 쓰는 곳은 상태 디렉터리 하나뿐 |
| 3 | `volumes` | 거부 목록 디렉터리 → `/etc/ingest-auth`(읽기 전용, 디렉터리 마운트) · 상태 디렉터리 → `/var/lib/ingest-auth` · 비밀 파일(읽기 전용). D7 |
| 4 | `healthcheck` | `["CMD","/ingest-auth","healthcheck"]` 30초 · 3회 · 유예 10초(1-8). 같은 netns 의 `127.0.0.1:8081` 이라 포트 충돌 점검(D1) |
| 5 | 시험 이음매 | 계획 M2 c4 「시험 이음매」 표에 응답기 항목 추가(비밀 파일 소유 = 10003, 사이드카 파일 = 10001 — D3 (나)면 두 파일) |
| 6 | `user:` | 적지 않는다(M2 규칙 — 이미지 USER 10003 이 정본, `Dockerfile.ingest-auth:34`) |
| 7 | 실물 조합 시험 | 가짜 응답기(c1)에 더해 **실물 응답기 + 실물 MediaMTX 루프백** 한 사례(D1 의 미확인을 닫는다) |
| 8 | 포트 · 비밀 파일 경로 | 아래 「후보값」 표(미결 — K8) |
| 9 | 시험 | M2 `render_test.sh:387` 의 환경 변수 이름 금지 검사를 `*_FILE` 허용으로 조정(D4) |

### D12. prod MediaMTX 에 SRT 경로 잠금이 없다 (D6 의 필수 전제)

- **계획이 가정한 것**: c6 변경표(계획 860~877행)와 계획 전체에 `srtPublishPassphrase` · `srtReadPassphrase` · `overridePublisher` 가 **0건**이다. prod 의 송출 통제는 M15 에서 응답기로 바꾸고, 읽기는 CDN 모드 Bearer 로만이라고 적혀 있다.
- **실물**: dev 템플릿은 `pathDefaults` 에 둘을 둔다 — `srtPublishPassphrase`(응답기의 2xx 에 값이
  없으면 이 값으로 판정돼 거부 = 빈 응답 방어), `srtReadPassphrase`(SRT 읽기 잠금 — 경로 이름이 공개 토큰이라 막지
  않으면 원 스트림을 무암호로 읽는다), `overridePublisher: no`(passphrase 를 아는 둘째 SRT 가 송출을
  탈취하고 DVR 을 리셋하는 것을 막는다). 값은 렌더마다 새로 만들고 저장소 · `.env` · 로그 어디에도
  없다(`mediamtx.demo.yml.tmpl:56-61` · `render.sh`). prod 바탕
  `infra/compose/mediamtx.yml:90-95` 에는 이 셋이 없다. 응답기는 SRT `read` 를 200 으로
  답하므로(`handler.go` `read`) SRT 읽기의 방어는 **이 잠금뿐**이다. 포크 `v1.21.1-pokeclip.2` 의
  SRT 읽기 경로는 값이 비면 통과시킨다고 검수가 확인했다(`internal/servers/srt/conn.go` — 이 조사에서 직접 열지
  않았다).
- **실패 시나리오(정적 분석)**: O8a 로 SRT 를 공개한 뒤 공개 토큰을 아는 사용자가 SRT read 로 접속하면 CDN 접근 통제를 거치지 않고 원 스트림을 읽는다. 잠금이 없고 `overridePublisher` 기본값(허용)이면 송출 탈취 · DVR 리셋도 가능하다(passphrase 를 알아야 하므로 D2 의 오라클과 연결된다).
- **선택지**:
  - (가) M15 c2 가 `mediamtx.yml.tmpl` 에 세 줄을 옮긴다: `pathDefaults` 의 `srtPublishPassphrase` · `srtReadPassphrase`(값은 시작 스크립트가 부팅마다 무작위로 만든 tmpfs 렌더 — 고정 토큰 치환이 하나 더 늘어 M2 c4 의 「토큰 1개 치환 · 잔여 0」 단언도 갱신) + `overridePublisher: no`. `pathDefaults` 가 `all_others` 에 상속되는지는 바탕 설정의 주석이 확인했다고 적고 있다(`infra/compose/mediamtx.yml:99`). 장: dev 와 같은 원리, 코드 변경 0. 단: 렌더 · 시험이 늘어난다.
  - (나) 옮기지 않는다 — **권하지 않는다**(위 시나리오).
- **확인됨(M15)**: 포크 `internal/servers/srt/conn.go` 의 `srtCheckPassphrase` 는 값이 비면
  통과(nil)이고, publish 는 응답 `SRTPassphrase` 가 비면 `SRTPublishPassphrase`, read 는
  `SRTReadPassphrase` 로 떨어진다(2026-10-23 소스 판독). 실측은 c1 계약 시험 C7(빈 응답 거부) ·
  C8(passphrase 없는 SRT read 실패) · C10(둘째 송출자 거부) · C17(응답기 200 인 SRT read 도 잠금으로
  실패)이다.
- **채택(M15 c2a · c2b)**: (가). 단 **응답기 모드에만** 둔다 — 옛 모드(익명 송출)에 송출 잠금을 두면 내부 인증
  성공에 passphrase 가 없어 잠금 값으로 판정돼 익명 송출이 깨진다. 두 잠금은 서로 다른
  값이고(`@@SRT_PUBLISH_LOCK@@` · `@@SRT_READ_LOCK@@`), 시작 스크립트가 기동마다 만든다.
- **응답기 코드 변경**: 없음. MediaMTX 설정 변경이다. 시험: c1 에 「잠금 값이 빈 응답일 때 거부 · SRT read 가
  passphrase 없이는 실패 · 둘째 SRT 송출이 진행 중 송출을 탈취하지 못함」 사례를 더한다(위 C7 · C8 · C10 ·
  C17).

**후보값(착수 계약의 핵심 값. 전부 후보이며 K8 이 확정한다)**

| # | 항목 | 후보값 | 조건 · 근거 |
|---|---|---|---|
| 1 | 인증 리스너 | `127.0.0.1:8080`, 경로 `POST /mediamtx/auth` → MediaMTX `authHTTPAddress: http://127.0.0.1:8080/mediamtx/auth` | 같은 netns 의 MediaMTX(8888 · 9996 · 9997 · 8890/udp)와 겹치지 않는다. 프록시 네 포트와의 충돌은 M15 렌더 시험이 시험 값으로 보고, prod 값은 M9 모듈이 단언한다(`infra/media-host/README.md` 「후속」 M9 행). 확정(M15 c2a · c4): Basic 을 실은 `http://pokeclip-mediamtx:@@INGEST_AUTH_BASIC_PASS@@@127.0.0.1:8080/mediamtx/auth` |
| 2 | healthz | `127.0.0.1:8081`(고정, 바꿀 수 없다) | `main.go:34` |
| 3 | 응답기가 읽을 비밀 파일 셋 | 컨테이너 안 `/run/secrets/stream_key_resolve_token` · `/run/secrets/mtx_api_pass` · `/run/secrets/ingest_basic_pass`(Basic 비밀번호). Basic 사용자 이름은 비밀이 아니라 설정값 — 응답기에는 환경 변수 `INGEST_AUTH_BASIC_USER`(1-5 의 11)로, MediaMTX 에는 같은 값을 4b 의 URL 렌더로 넣는다 | 응답기에는 `STREAM_KEY_RESOLVE_TOKEN_FILE` · `MTX_API_PASS_FILE` · `INGEST_AUTH_BASIC_PASS_FILE`(1-5 의 9 · 6 · 13)로 이 경로를 준다 — K3 (가)로 트랙 A 가 더했다. 경로 꼴은 사이드카 README 의 예시와 같다(`media/README.md` 의 사이드카 환경변수 표 `MTX_API_PASS_FILE` 행) |
| 4 | 사이드카의 비밀 파일 | 컨테이너 안 `/run/secrets/mtx_api_pass`(사이드카 `MTX_API_PASS_FILE`) | D3 (나)일 때. 응답기 파일과 **내용이 같은 별개 파일**. 호스트 쪽은 5 의 편차 |
| 4b | MediaMTX 쪽 Basic 자격 | `authHTTPAddress: http://<user>:<pass>@127.0.0.1:<포트>/mediamtx/auth` — `<user>` 는 응답기 `INGEST_AUTH_BASIC_USER` 와 **같은 설정값**(비밀 아님), `<pass>` 만 시작 스크립트가 `ingest_basic_pass` 원본 파일에서 읽어 **렌더**한다(bash 내장 문자열 치환만 — M2 c4 규칙). 렌더 결과 `mediamtx.yml` 은 호스트 `/run/pokeclip-media/mediamtx.yml` `10002:10002 0400` 그대로 | 비밀번호는 URL 에 퍼센트 인코딩이 필요 없는 unreserved 문자(예: 무작위 16진)로 **24자 이상** 만든다 — 어기면 응답기가 기동을 거부한다(1-5 의 12). 같은 비밀번호가 응답기 파일 · `mediamtx.yml` 두 곳에 있다. Control API 설정 조회로 평문이 보인다(D2 ③) |
| 5 | 호스트 쪽 경로 | `/run/pokeclip-media/ingest-auth/{stream_key_resolve_token,mtx_api_pass,ingest_basic_pass}` · `/run/pokeclip-media/sidecar/mtx_api_pass` (tmpfs) | M2 c4 의 `mediamtx.yml` 렌더 자리(`/run/pokeclip-media`)와 같은 계열. 디렉터리 `root` 소유 `0711`. **편차(M15 — kty Q1 답, 2026-10-23)**: 사이드카 파일은 M2 의 자리 `/run/pokeclip-media/mtx_api_pass` 그대로 둔다(옛 모드 compose 렌더가 develop 과 같고 secret-count · stop-media 파급이 0). 응답기 파일 셋만 `ingest-auth/` 아래다 |
| 6 | 소유 · 모드 | 응답기 파일 셋 `10003:10003 0400` · 사이드카 파일 `10001:10001 0400` · `mediamtx.yml` `10002:10002 0400`. 읽기 전용 bind | 사이드카 규칙: other 권한 · group 쓰기 비트 거부(`media/README.md` 「관측 자격 파일 규칙」). 응답기 쪽도 트랙 A 가 같은 규칙으로 더했다(1-5 표 위 문단 — `internal/secretfile`). 소유자 uid 는 코드가 보지 않으므로 이 소유 값은 배치가 지킨다(1-9 아래 「잔여 위험(비밀 파일 소유자)」) |
| 7 | 거부 목록(컨테이너 안) | `/etc/ingest-auth/deny-tokens`(고정) · 호스트 쪽은 K5 가 정한다 | 확정(M15 c3 · c4): 호스트 `/var/lib/pokeclip-media/ingest-auth/deny/deny-tokens` — 디렉터리 `root:10003 0750` · 파일 `root:10003 0640`, **디렉터리**를 읽기 전용 bind(동기화가 rename 으로 바꿔 끼우므로 파일 bind 는 옛 파일을 계속 본다) |
| 8 | 상태(컨테이너 안) | `/var/lib/ingest-auth`(고정) · 호스트 후보 `/run/pokeclip-media/ingest-auth/state` `10003:10003 0700` | D7. 재부팅 때 연결도 사라지므로 tmpfs 가능 |

**2절 차이 개수**: D1 ~ D12 = **12개**.

---

## 3. 결정됨 — kty 결정 (2026-10-20)

선행 조건(결정이 아니라 3번과 먼저 합의할 것): **prod 의 4C 호출 경로 · 인증 방식**(D9). **닫힘** — POK-316
으로 `native` 와 전용 창구가 양립하고, 운영 경로는 전용 창구 · 출발지 Media 프라이빗 서브넷으로 정했다(POK-429 · kty
2026-10-24 확정). 합의 수단은 POK-307 Jira 코멘트였다(2026-10-20).

| # | 결정 | 확정 내용 | 응답기 코드 | 연결 |
|---|---|---|---|---|
| K1 · K2 | 호출자 구별 · Control API 인증 | **K1 = (라) Basic 자격** — `authHTTPAddress` URL 자격으로 응답기가 호출자를 검증(위 D2 — 포크 실측으로 **확인됨**). 잔여 위험: nginx 침해 → 막힘 / 사이드카 침해 → Basic 자격을 설정 조회로 읽고 전 계정 passphrase 가능(D2). **K2 = (나)** — `api` 는 응답기 경유(`authHTTPExclude` 에 넣지 않는다) | **Basic 검사 추가** — 트랙 A 가 더했다(1-1 의 14) | D2 · D3 |
| K3 | 응답기 비밀 입력 | **(가) `_FILE` 입력 추가.** 받을 비밀은 **셋**: (1) 4C resolve 토큰 (2) Control API 비밀번호(`MTX_API_PASS` — 응답기 · 사이드카가 공유) (3) Basic 자격(MediaMTX → 응답기). 값 출처는 D4 · K1 에 따른다(resolve 토큰 = Secrets Manager, 나머지 둘 = 시작 스크립트가 부팅마다 생성한 tmpfs 렌더 — D4 의 「값의 출처」 조건 하에) | **필요** — 트랙 A 가 더했다(1-5 의 6 · 9 · 13) | D4 |
| K4 | `authHTTPExclude` | **`playback` 하나**(kty 결정 1, 2026-10-23 — 2026-10-20 의 (가) `read` · `playback` 을 대체). `read` 를 빼면 Bearer 없는 LL-HLS 읽기가 열리기 때문이다(M15 계획 0-1). `metrics` · `pprof` 는 넣지 않는다 — prod 설정이 둘 다 `no` 다(`infra/media-host/mediamtx.yml.tmpl`). **D12 의 SRT 경로 잠금 이행을 같은 PR 의 전제로** | read 판정 변경(M15 c0 — SRT 만 200, 1-2 의 2 · 8) | D6 · D12 |
| K5 | 거부 목록 정본 | **(나) 상자 밖 정본 + 부팅 시 렌더**(없거나 비면 기동 차단). 상세(M15 — kty 답 2026-10-23): 정본 = SSM Parameter `/pokeclip/prod/media/ingest-auth-deny-tokens`(String · Standard · 초기값 머리 줄 `# pokeclip deny-tokens v1` · Terraform `ignore_changes = [value]` — 자원은 M9). 「빔」 = 파라미터가 없거나 공백뿐 — 머리 줄만 있으면(토큰 0건) 정상. 받기 실패 · 꼴 밖이면 시작 스크립트가 기동을 막는다(`… ingest-auth denylist unavailable` · `… invalid`). 덧붙이기 · 해제는 `infra/media-host/bin/ingest-auth-deny-sync.sh`(SSM 실행 · 승인 — 덧붙이기는 재시작 없이, 해제는 `--release` 로 응답기만 재생성) | 없음 | D7 |
| K6 | 4C 출발지 잠금 단위 | **(나) Media 프라이빗 서브넷 CIDR**(POK-429 · kty 2026-10-24 — 2026-10-20 의 (가) 고정 주소 `/32` 를 대체). 값은 런타임이 넣는다 — `app_env.auth` 의 `STREAM_KEY_RESOLVE_ALLOWED_CIDRS` = 서브넷 조회(`data.aws_subnet.media_private`) 결과. 보안 그룹 규칙(Media 이그레스 → auth · auth 인그레스 ← Media)은 따로 media 루트 몫(kty 결정 2026-10-25 (나) — `infra/terraform/CONTRACT-runtime.md`) | 없음 | D9 |
| K7 | 송출자 `ip` 확인 수단 | **(가)** c1 의 가짜 응답기 + MediaMTX 연결 로그 | 없음 | D5 |
| K8 | 포트 · 비밀 파일 경로 | **2절 끝 후보값 표를 후보값으로 채택**(Basic 자격 파일이 하나 늘어 비밀 파일은 응답기 쪽 `/run/secrets/` 아래 셋 · 사이드카 쪽 `mtx_api_pass` 하나 · MediaMTX 쪽은 `mediamtx.yml` 의 `authHTTPAddress` 렌더 — 위 후보 표 3 · 4 · 4b). 포트 충돌은 prod 포트 값 확인 뒤. **M15 에서 유지 — 편차 하나**: 사이드카 호스트 파일은 M2 자리 그대로(후보 표 5 — kty Q1 답) | 없음(K3 가 전제) | D11 |

선행 조건(3번의 답)은 위처럼 닫혔다(POK-429). 정본 위치(이 파일)와 합의 수단(POK-307 코멘트)은 2026-10-20 에
정했다.

kty 결정이 아닌 **확인 · 실측 항목**(결정 없이 M15 착수 때 한다). 처분은 M15(2026-10-23 · 24) 기준이다.

- 실물 MediaMTX 가 루프백 `authHTTPAddress` 로 인증을 묻는지, 실물 응답기와 조합해 publish / api / cut
  이 도는지(D1 · D11 의 7). — **publish · api 닫힘**: c1 계약 시험 층 B C9(실물 경유 송출 ·
  `caller_auth=basic` · `auth_channel=resolve-token` — M15 c11 에서 전용 창구로 다시 돎) ·
  C12(사이드카 자격 200 · 무자격
  401). **cut 도 닫힘**: C18(실물 cut — 출력의 `"kicked":1` · 그 연결이 사라짐 · 같은 토큰 재송출 거부,
  M15 c5 — 45616a46 에서 GREEN). 306f7a3e 부터 C18 은 cut 의 종료 0 ·
  `ingestauth_cut_completed` · `"kicked":1` · 조회 성공한 소멸(경로 상태 `idle` — `error` 는
  조회 실패)까지 단언한다. M15 c12 부터 C13 · C15 · C18 의 운영 스크립트는
  보조 컨테이너 안에서 돌고 거부 목록은 named volume 이다 — 맥 호스트
  bind(virtiofs)에서 rename 직후 첫 읽기가 잘려 C18 이 흔들린 시험 경로를
  걷었다(제품 무변경).
- 응답기 인증 포트(8080 대) · 고정 healthz 8081 이 prod 포트 값과 겹치지 않는지(D1). — **이관**: 렌더 시험이
  시험 값으로 보고, prod 값은 M9 모듈 단언(`infra/media-host/README.md` 「후속」 M9 행).
- `docker compose restart ingest-auth` 가 시작 스크립트의 전체 종료를 건드리지 않는지(D8). — **닫힘**:
  S-3 스파이크 5/5(D8 — restart 대신 재생성). 상설 CI 사례는
  `infra/media-host/test/unit_restart_test.sh` 사례 22 · 23(M15 c7 — e7cf1701,
  D8).
- `authHTTPExclude` 의 `playback` 이름 · 동작을 포크 소스로 확인(D6, 계획 G17 닫기). — **닫힘**: 포크
  `internal/conf/auth_action.go`(D6).
- SRT 읽기 잠금(`srtReadPassphrase`)이 값이 있을 때 읽기를 막고 비면 통과시키는지 포크 `.2` 에서 확인(D12). —
  **닫힘**: 포크 `internal/servers/srt/conn.go` 판독 + c1 C8 · C17 실측(D12).
- O8a 3 의 거부 실측에서 `resolve_ms` 분포를 기록(D10). — **남음**(O8a 3 몫).
- 계약4 2절 개정(운영 Media 의 resolve 경로 · 출발지 문구) — 위키 PR, kty 승인
  머지 · 3번 알림(2026-10-24 위임 — 런타임 1단-나 ② K6 · D9). — **닫힘**(위키
  62eaf27 · 3d6b66d, 2026-10-24 — 운영도 전용 헤더 · 출발지 Media 프라이빗 서브넷
  IPv4 대역).
- **설정 조회 노출 완화(신규)**: 응답기가 `action=api` 요청 가운데 설정 조회(`/v3/config/…`)를 사이드카 자격에
  대해 거부할 수 있는지 — 인증 요청 본문의 `path` 에 API 경로가 실리는지부터 확인(D2). — **남음**(M15 범위 밖).
  응답기 모드에서는 설정 조회가 Basic 비밀번호와 SRT 잠금 둘도 평문으로 돌려준다(`infra/media-host/README.md`
  「보안 경계」).
- ~~포크 소스에서 `authHTTPAddress` URL 사용자 정보 전달 여부 확인(D2 (라))~~ — **완료**(2026-10-20 로컬 실측 · D2 ① ~ ③).
- **(M15 추가) 포크가 `authMethod: http` + `authInternalUsers: []` 를 받는지(C0)** —
  **완료**: c1 C0 — 포크 `v1.21.1-pokeclip.2` 가 이 최소 설정으로 기동하고 설정 조회가
  `authInternalUsers=[]` 를 돌려줬다(기본 사용자 되살아나지 않음 · 5/5). 응답기 모드 템플릿이
  `authInternalUsers: []` 를 둔다.

**3절 집계**: 결정됨 표 7행(K1·K2 묶음 + K3–K8) · 결정 8개(K1–K8 — K4 는 2026-10-23 개정, K6 은
2026-10-24 (나) 로 개정 · POK-429) · 확인 항목 전체 10개(M15 가 C0 를 더함) = 완료 · 닫힘 7(D2 (라) · C0 · 실물 조합 publish ·
api · cut · playback · SRT 잠금 · D8 · 계약4 개정) + 이관 1(포트 → M9) + 남음 2(`resolve_ms`
· 설정 조회 완화). 선행 조건 1개는 해소.

---

### 3-1. 계획 규칙의 근거

계획의 두 규칙이 어디서 왔는지 적는다(D1 · D2 · D4 · K1 · K3 의 전제).

- **(a) 같은 netns** — 엣지 설계 r7.5.8 의 3-3-1(엣지 설계 kty 결정): MediaMTX 의 HLS(8888)와 재생 서버(9996)가 `127.0.0.1` 에만 바인딩되므로 nginx 가 루프백으로 닿으려면 같은 netns 여야 한다. 설계가 한계로 적은 것은 **IMDS 공유**(같은 netns 컨테이너가 모두 역할 자격에 닿음 — 계획 G22)뿐이고, **응답기가 passphrase 를 돌려주는 위험은 설계가 다루지 않았다** — 설계의 원문은 「응답기 자체(코드 · S1 한도 · prod 배치)는 1번 몫이고(kty 15차), 이 설계는 compose 의 자리와 SG · 읽기 정책 연결만 둔다」(괄호 안 차수는 엣지 설계의 결정 번호)이다. 응답기를 다른 망으로 빼더라도 nginx 가 MediaMTX 와 같은 netns 라서 MediaMTX 의 출발지 주소로는 구별되지 않는다(D1 (나)의 논거). 그래서 구별 수단은 주소가 아니라 **자격(Basic)** 이 맞다(K1).
- **(b) 비밀을 환경 변수로 넘기지 않는다** — 엣지 계획 r6.7 98행(docker 가 `config.v2.json` ·
  `inspect` 에 남긴다) · 노출 표 816행부터 · M2 `render_test`(환경 변수 이름 금지 검사). 그런데
  **dev-media 는 이 규칙 밖**이다 — compose 가 비밀을 값 변수로 준다(기존 가동판 응답기는 `_FILE`
  입력이 없어 다른 길이 없었다). prod 는 이 규칙을 따라야 하므로 **K3 (가)(`_FILE` 입력 추가)** 가
  필요했고, 트랙 A 가 더했다(1-5 의 6 · 9 · 13).

### 3-2. 응답기 코드 변경 한 묶음

K1 · K3 가 요구하는 응답기 코드 변경은 아래 둘이고, **한 PR(POK-310 트랙 A)로 묶었다**(둘 다
`media/cmd/ingest-auth` · `media/internal/ingestauth` 안, 시험은 같은 누설 · 판정 시험 틀을
쓴다).

상태: **구현 — 코드 PR(POK-310 트랙 A)**. 아래 2 의 순서와 dev 호환은 그 PR 에서 정해 1절에 실물로 적었다(순서 =
1-1 의 4 → 14 → 예산 · 판정, dev 호환 = 1-5 의 14 명시 끔). 4C 창구 선택(118차)과 Basic 비밀번호 최소
24자(120차)도 같은 PR 이다(1-3 의 2 · 1-5 의 12 · 15).

| # | 변경 | 요건 |
|---|---|---|
| 1 | `_FILE` 입력(resolve 토큰 · Control API 비밀번호 · Basic 자격) | 환경 변수와 파일 동시 지정은 기동 거부 · 파일 규칙은 사이드카 `MTX_API_PASS_FILE` 과 같게(한 줄 · 끝 줄바꿈 하나 · other 권한 · group 쓰기 거부 · 4 KiB · 실패 = 기동 실패 — `media/README.md` 「관측 자격 파일 규칙」) · 에러에 경로 대신 변수 이름(경로가 곧 값일 수 있다) |
| 2 | Basic 검사(publish 포함 모든 인증 요청) | 자격이 없거나 틀리면 **401** · 비교는 **상수 시간**(다이제스트끼리 — `apiCredentialsMatch` 와 같은 방식) · **로그 · 에러에 자격 0**(`Secret` 타입 · 누설 시험 확장) · 순서는 호출자 IP 403 → Basic 401 → 예산 · 판정으로 정했다(1-1 의 4 · 14) · dev 호환은 명시 끔으로 정했다(1-5 의 14) · 비밀번호는 24자 이상(120차 — 1-5 의 12) |

**M2 몫(응답기 PR 이 아님)**: `render_test.sh:387` 의 `TOKEN` 이름 금지 완화(`*_FILE` 허용 · 비밀 값 주입 금지는 유지 — D4)와 계획 743행 문구.

## 4. 출처

저장소 `3K-PokeClip/pokeclip-mono` 의 `git show origin/develop:<경로>` 로 읽었다(판 64633374).

- 응답기 진입점: `media/cmd/ingest-auth/` — `main.go` · `config.go` · `serve.go` · `health.go` · `cut.go`
- 응답기 판정: `media/internal/ingestauth/` — `doc.go` · `handler.go` · `request.go` · `publish.go` · `secret.go` · `denylist.go` · `connlog.go` · `cut.go` · `holdbudget.go`
- 4C 클라이언트: `media/internal/keyresolve/client.go` · Control API 클라이언트: `media/internal/mtxctl/client.go`
- 이미지: `media/Dockerfile.ingest-auth`
- 사이드카 쪽 Control API 자격 수단: `media/README.md`(env 표 · 「관측 자격 파일 규칙」 · 「M5(보안 강화)로 넘어간 것」)
- dev-media 구성: `infra/dev-media/compose.yml` · `mediamtx.demo.yml.tmpl` · `render.sh` · `.env.example` · `README.md` · `RUNBOOK.md`(7-4 · 8 · 9-5 · 11-1)
- prod 기반 설정(참고): `infra/compose/mediamtx.yml`
- M2 compose 자리: `git show origin/feat/infra-media-host:infra/media-host/compose.yml.tmpl`(판 14a57f37) · `test/render_vars.json`
- Auth(3번) 코드: `services/auth/src/main/java/com/pokeclip/auth/config/StreamKeyResolveSecurityConfig.java` · `StreamKeyResolveAccessProperties.java` · `services/auth/src/main/resources/application.yml`
- 계약4 4C: 팀 위키 저장소 `contracts/계약4-OBS-Control-API.md` 0 · 1 · 2 · 4절 및 각주 `[^responder]`
- 엣지 설계: 엣지 설계 r7.5.8 3-3-1(볼트 원문)
- 엣지 계획: 엣지 계획 r6.7(M4 판, 2026-10-17 · 볼트 원문) — 98행 · 816행~(노출 표) · G22(1929행) · M2 절(658행~; 718 · 731 · 738~747행), M15(1546~1555행), O8a(1500~1512행), c6 변경표(860~877행), G17(1924행), 3-6 계약 요지(1020~1026행 · 1724행)

이 문서가 **읽지 않았거나 실행하지 않은 것**: 포크 MediaMTX 소스 `conf/auth_action.go` · 실물 MediaMTX
+ 응답기의 루프백 조합 · prod 포트 값 · `failedtokens.go` · `internal/ingestauth/cut.go` 의
판독 세부 · 응답기 시험 파일. 설계 검수가 인용한 포크 · Auth 소스는 이 문서 작성자가 직접 열지 않고 검수 기록을 따랐다 — D9 ·
D12 에 그렇게 표시했다. D2 (라)의 포크 동작은 포크 `internal/auth/manager.go` 소스와 2026-10-20 로컬
실측(Basic 헤더 · 로그 가림 · 설정 조회 평문 노출)으로 확인했다. 위 본문의 「미확인」 · 「추론」 표시가 그 자리다. **M15
갱신(2026-10-24)**: 이 목록 가운데 `conf/auth_action.go`(D6) · 실물 MediaMTX + 응답기 루프백
조합(3절 — c1 C9 · C12 · C18) · D12 의 SRT 읽기 경로(포크 `internal/servers/srt/conn.go`)는
M15 가 확인했다. prod 포트 값은 M9 단언으로 이관했다(3절). 나머지(`failedtokens.go` ·
`internal/ingestauth/cut.go` 판독 세부 · 응답기 시험 파일)는 그대로다.
