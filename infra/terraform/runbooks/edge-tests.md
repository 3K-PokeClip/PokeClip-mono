# 엣지 시험 런북

prod 엣지(CloudFront · 공개 ALB · NLB)를 운영 이름 그대로 시험하는
절차입니다. 설계서 5-6(시험 계획과 시험 접속법)과 계획 M10 c10 에 해당하고,
7단계 O7f 의 KVS 값 입력(멈춤 자리 ②)도 여기에 있습니다. 8단계(O8)는
「경로 우회 시험」 을 통과한 뒤에 시작하고, 개통 뒤 관찰은 이 시험표를
`--resolve` 없이 다시 돌립니다. /dvr 하한 함수를 켜는 걸음과 그 시험(POK-525)은
「/dvr 하한 시험(POK-525)」 입니다.

*   실행자는 kty 입니다. **kty 로컬 터미널**에서 kty IAM 사용자 프로필로
    돌립니다. 출력에 계정 ID 가 든 ARN · 엣지 IP 가 섞여 에이전트 세션이나
    세션의 `!` 로 돌리지 않고, 결과 원문을 세션 · 문서 · 채팅에 붙여 넣지
    않습니다.
*   AWS 쓰기(KVS 값 입력 · apply)와 SSM 명령(Media 상자 안 조회)은 실행
    직전에 kty 가 승인합니다. 시험 판정도 kty 가 합니다.
*   AWS 호출이 `AccessDenied` 로 실패하면 계획 3절 「SCP 멈춤 규칙」 을
    따릅니다 — 다시 시도하지 않고 kty 에게 보고합니다. KVS 값 입력의 거부만
    예외로 멈춤 자리 ② 입니다(아래 「KVS 값 입력」).

용어:

*   **배포 기본 도메인**: CloudFront 가 배포에 준 `*.cloudfront.net`
    이름입니다(edge 출력 `media_distribution_domain`). 시험은 이 이름으로
    하지 않고, 이 이름을 푼 엣지 IP 로 운영 이름에 붙습니다.
*   **판정 줄**: 원본(Media 상자) 쪽에서 SSM 으로 받는 결과 한두 줄입니다
    (건수 · 상태 코드). 로그 원문은 받지 않습니다.
*   **멈춤 자리 · A1 · A2**: 단계 직전 SCP 재판정에서 거부되면 그 동작만
    멈추고 나머지를 부분 진행하는 자리입니다. A1 = 멈춤 자리 ①(`O7e-stop1`
    거부 — Media 미기동), A2 = 멈춤 자리 ②(`O7f-stop2` 거부 — KVS 값을 넣을
    수 없음). [단계 직전 점검 런북](stage-precheck.md)이 판정합니다.
*   **경보 검증 보류**: 판정은 응답 · 로그 고정 문자열로만 하고, 그 신호의
    경보(설계 7절)가 실제로 울리는지는 이 시험에서 보지 않는다는 뜻입니다
    (설계 1-2).
*   **VPC origin**: CloudFront 가 VPC 안의 사설 자원(내부 ALB)을 공개 주소
    없이 원본으로 쓰는 기능입니다.

## O7f 순서

계획 M10 O7f 의 정상 갈래 번호입니다. 멈춤 자리 ① · ② 가 걸리면(A1 · A2)
번호별 실행 · 건너뛰기 · 재개 위치는 계획 M10 뒤 「O7f 실행표」 를
따릅니다. 아래 「멈춤 자리일 때」 는 그 요지입니다.

| 번호 | 동작 | 이 런북 |
|---|---|---|
| 0 | 선행 0 — SCP 재판정 공통 묶음 `O7f` · 멈춤 자리 묶음 `O7f-stop2` · 2-3 판정 · 이벤트 기록 | [단계 직전 점검 런북](stage-precheck.md) |
| 1 | edge apply | [prod/edge](../prod/edge/README.md) 「적용 순서(O7f)」 |
| 2 | T6b(KVS 값 입력 전) | 「시험표」 · 「판정 문구」 |
| 3 | KVS 값 입력(멈춤 자리 ②) | 「KVS 값 입력」 |
| 4 | 엣지만 시험 — T0 · T8e 해석 · T11 404 · T2b 기본 거절 · T2b 경로 우회 · T12 ⓐ | 「엣지만 시험」 · 「경로 우회 시험」 |
| 5 | 랜딩 업로드(kty 로컬) | [prod 산출물 런북](prod-artifacts.md) 「랜딩 업로드(O7f 의 5)」 |
| 6 | 런타임 트랙 2단 — rewind 버킷 OAC 허용문 apply(1 뒤 언제든 · Media 무관) | [prod/runtime-2](../prod/runtime-2/README.md) 「적용(O7f 의 6)」 · [런타임 트랙 계약](../CONTRACT-runtime.md) |
| 7 | auth · clip · next 정상 기동 확인 | 계획 M10 O7f 7 |
| 8 | 시험 스트림 키 발급 · 입력 | [엣지 비밀 런북](edge-secrets.md) 「O7f」 |
| 9 | 루프백 헬스 · `secret-count` | 계획 M10 O7f 9 |
| 10 | clip 쿠키 발급 1회 200 | 계획 M10 O7f 10 |
| 11 | 7-R(2 에서 403 이었을 때만) | [엣지 비밀 런북](edge-secrets.md) 「T6b 대체 절차」 |
| 12 | 런타임 뒤 시험(T1–T5 · T7 · T8 · T10 · T11 202 · T12 ⓑ · T13) | 「시험표」 · 「런타임 뒤 시험의 추가 항목」 |

**T6a · T9b 는 prod 에서 하지 않습니다**(kty 20차 · 설계 5-6). 근거는
[엣지 비밀 런북](edge-secrets.md) 「prod 에서 하지 않는 시험(T6a · T9b)」
입니다 — T6a 는 M2 c6 로컬 compose 실측, T9b 는 clip 코드 시험(I3-10)과 ECS
문서입니다.

### 멈춤 자리일 때

*   **A1(Media 미기동)**: Media 가 없어도 되는 것만 합니다 — edge apply ·
    T0 · T8e 해석 · 런타임 2단 · 정상 기동 확인 · 시험 키 발급 · 입력 ·
    랜딩 업로드. T6b · KVS 값 입력 · T11 404 · T2b · 루프백 헬스 · 쿠키
    200 · 7-R · 런타임 뒤 시험은 Media 가 뜬 뒤입니다(T6b · T11 은 Media
    상자의 nginx 가 응답을 냅니다).
*   **A2(KVS 값 입력 불가)**: 값이 필요 없는 뒤 단계만 합니다 — 엣지만
    시험 · 런타임 2단 · 정상 기동 확인 · 시험 키 · 루프백 헬스 · 쿠키 200 ·
    T10 · T11 202 · T13. T13 의 `/dvr/*` · `/vod/*` 비헤이비어는 이 KVS
    값과 무관합니다(`modules/cdn_media/main.tf` — `/dvr/*` 에 붙는 하한
    함수는 다른 KVS(edge-bounds)를 읽습니다). `/live` 재생
    시험(T1 · T2 의 `/live` 사례 · T3 · T4 · T5 · T7 · T8a–T8d) · 7-R ·
    8단계는 값 입력 뒤입니다. 대안은 설계하지 않습니다(34차 Q2 — 막히면
    kty 회부).
*   **T12**: ⓐ 는 Media 상자의 nginx 가 응답을 내므로 A1 이면 Media 가
    뜬 뒤이고, A2 에서는 엣지만 시험과 함께 합니다. ⓑ 는 KVS 값과 무관해
    A2 에서도 합니다(회차 라우트 배포 뒤).
*   **A1 · A2 가 함께면** Media 가 필요한 동작을 먼저 빼고, 남은 것에서 KVS
    값 동작과 `/live` 재생 시험을 뺍니다.
*   **재개**는 번호 순서로 아직 하지 않은 것 전부입니다(건너뛴 것 · 끝내지
    못한 것). 다시 시작하는 첫 동작 직전에 `O7f` 공통 묶음을 다시
    판정합니다. 부분 진행 중의 하위 단계는 시작 직전에 그 묶음(`testkey` ·
    `loopback`)을 다시 판정합니다.
*   **A2 중 edge apply 가 실패하면** 부분 진행이 아니라 전체 중단입니다
    (계획 3절 SCP 멈춤 규칙).

## 접속법

공개 DNS 를 열기 전에도 운영 이름 그대로 시험합니다(설계 5-6). 요청 URL ·
SNI · Host 를 `media.pokeclip.com` · `api.pokeclip.com` ·
`app.pokeclip.com` 으로 둡니다. `*.cloudfront.net` 으로는 시험하지
않습니다. 전제는 media 배포에 대체 도메인과 us-east-1 인증서가 붙어 있는
것입니다(edge apply).

1.  증거 디렉터리를 만듭니다(저장소 밖 · 0700). 규칙은
    [단계 직전 점검 런북](stage-precheck.md) 「증거」 입니다.

    ```shell
    umask 077
    evidence="${HOME}/.pokeclip-precheck/edge-tests-$(date -u +%Y%m%dT%H%M%SZ)"
    mkdir -p "${evidence}" && chmod 700 "${evidence}"
    ```

1.  **엣지 IP 는 시험 직전에 다시 풉니다.** CloudFront 의 응답 IP 는 바뀔 수
    있습니다. api · app 은 공개 ALB DNS 를 푼 IP 입니다.

    ```shell
    media_cf="$(terraform -chdir=infra/terraform/prod/edge output -raw media_distribution_domain)"
    public_alb="$(terraform -chdir=infra/terraform/prod/platform output -raw public_alb_dns_name)"
    edge_ip="$(dig +short "${media_cf}" | grep -Em1 '^[0-9.]+$')"
    alb_ip="$(dig +short "${public_alb}" | grep -Em1 '^[0-9.]+$')"
    test -n "${edge_ip}" && test -n "${alb_ip}" && echo resolved
    ```

1.  **CLI**: `curl --resolve` 로 이름을 IP 에 묶습니다.

    ```shell
    curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
      -w '%{http_code}\n' https://media.pokeclip.com/<경로>
    curl --resolve "api.pokeclip.com:443:${alb_ip}" -sS -o /dev/null \
      -w '%{http_code}\n' https://api.pokeclip.com/<경로>
    ```

1.  **브라우저**: 시험 기기의 hosts 에 같은 묶음을 적고 보안 DNS(DoH)를
    끕니다(DoH 가 켜져 있으면 hosts 를 거치지 않습니다). 줄 끝 표식으로
    나중에 정확히 지웁니다.

    ```shell
    sudo cp /etc/hosts "${evidence}/hosts.before"
    printf '%s %s # pokeclip-edge-test\n' "${edge_ip}" media.pokeclip.com \
      "${alb_ip}" api.pokeclip.com "${alb_ip}" app.pokeclip.com \
      | sudo tee -a /etc/hosts >/dev/null
    sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder
    ```

1.  **정리 — 원복과 원복 확인**: 시험이 끝나면(중단해도) hosts · DoH 를
    되돌리고 확인합니다.

    ```shell
    sudo sed -i '' '/# pokeclip-edge-test$/d' /etc/hosts
    diff "${evidence}/hosts.before" /etc/hosts && echo restored
    grep -c 'pokeclip-edge-test' /etc/hosts   # 0
    sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder
    ```

    브라우저의 보안 DNS 설정을 시험 전 값으로 되돌렸는지 kty 가 눈으로
    봅니다.

## 시험표

설계 5-6 의 표에서 T6a · T9b 를 빼고, T13(런타임 2단 확인 —
[prod/runtime-2](../prod/runtime-2/README.md))을 더한 것입니다. 「언제」 의
「엣지만」 은 송출원 · 서명 쿠키 없이 O7f 의 2 · 4 에서 하는 시험이고,
「런타임 뒤」 는 런타임 트랙 2단 · 시험 키 · clip 쿠키 뒤(O7f 의 12)입니다.

| # | 시험 | 통과 조건 | 언제 |
|---|---|---|---|
| T0 | 세 이름 TLS 연결 | 인증서 이름 일치 · 정상 응답(앱이 아직 healthy 가 아니면 ALB 503 도 TLS 통과로 봄) | 엣지만 |
| T6b | KVS 키 없음 | `/live` 503 · 함수 로그 고정 문자열 1건 이상 · `/dvr` · 제어면 응답은 503 이 아님(경보 검증 보류) | 엣지만(KVS 값 입력 전) |
| T9a | Media 빈 CDN 비밀 기동 | Media 미기동 · 인스턴스 로그 고정 문자열 · 복구 절차(경보 검증 보류) | O7e([Media 교체 런북](media-swap.md)) |
| T8e | NLB IP · AZ | ingest 해석 IP 하나 · 교체 전후 같음 | 해석은 엣지만, 비교는 T8a 와 함께 |
| T11 | 종료 신호 경로 | media 이름으로 `POST /api/ingest/end-signal` 이 원본(제어면 서버 배포 전에는 nginx 제어면 포트)에 닿음 · 캐시 안 됨 · 시청자 쿠키 없이 통과 · 표준 로그 · 원본 로그에 Authorization 값 0건 · 4D 수신부 배포 전에는 404 그대로 전달 | 404 는 엣지만, 유효 요청 202 는 제어면 서버 반영 뒤 |
| T2b | 기본 거절 · 경로 우회 | 아래 「T2b 기본 거절」 · 「경로 우회 시험」(O8 개통 조건) | 엣지만 |
| T1 | 다른 쿠키 두 시청자 | 목록 종류별 Cache-Control 기록 · 조각 · 파트 두 번째 시청자 적중 · 블로킹 목록은 정상 전달만 · 원본 302 · Set-Cookie · `session` 0건 | 런타임 뒤 |
| T2 | 쿠키 없음 · 다른 방송 쿠키 · 경로 우회 | CloudFront 가 낸 403(서명 거절 또는 live-auth 경로 거절) · 원본 · 캐시 도달 0 · 경로 우회는 「경로 우회 시험」(O8 개통 조건) | 런타임 뒤 |
| T3 | 쿠키 만료 | 만료 뒤 403, 재발급 뒤 복구 | 런타임 뒤 |
| T4 | 블로킹 리로드 | 쿼리마다 다른 캐시 키, 504 0건 | 런타임 뒤 |
| T5 | Host · Authorization 전달 | 원본에서 Host = `media.pokeclip.com` · Bearer 있음 · 쿠키 미전달. 시청자 임의 Authorization 은 원본에 안 닿음 | 런타임 뒤 |
| T7 | 경로 변환(ALB transform) | 재생 성공 · 원본 요청 경로에 `/live` 없음 · `_HLS_*` 쿼리 보존 · `.m3u8` · `.mp4` 로 끝나지 않는 `/live` 요청은 엣지 거절 · Location 0(아래 「런타임 뒤 시험의 추가 항목」 T7) | 런타임 뒤 |
| T8a | MVP 정상 교체 | 정상 교체 · 새 방송 재생 · CloudFront 무변경 | 런타임 뒤 |
| T8b | force | 재생 복구 시간 · 연결 종료까지 걸린 시간(30초 안 · A 정지로 줄어드는지) · 재시도 20회 소진 시간 · SRT 가 A 에 남지 않음 · 수동 복구 런북 리허설 | 런타임 뒤 |
| T8c | 교체 창 중 신규 송출 시도 | 한계 관찰 · 결과 기록(차단 기대 없음) | 런타임 뒤 |
| T8d | 부분 실패(B 등록 전 실패 포함) | 회복 · force 였다면 런북으로 기존 방송 복구 확인 | 런타임 뒤 |
| T10 | 재생 API | 아래 「T10」 | 런타임 뒤(2번 · 3번 배포 전이면 보류) |
| T12 | 회차별 되감기 경로 | ⓐ **원본 도달**: media 이름으로 쿠키 없이 `GET /api/streams/<표식>/rounds/<표식>/rewind`. 원본 로그 = nginx 제어면 접근 로그의 같은 경로 원문 줄. `control_upstream` 반영 전 = 원본 로그 1건 · 상류 호출 0 · nginx 404 / 반영 뒤 · 회차 라우트 등록 전 = 원본 로그 1건 · 상류 도달 · 제어면 ServeMux 404 / 회차 라우트 등록 뒤 = 원본 로그 1건 · 상류 도달 · 제어면 처리기의 JSON 404 `{"error":"not_found"}`(표식 회차는 장부에 없다) 또는 JSON 503 `{"error":"unavailable"}`(기동 직후 · DB 읽기 실패) / 셋 다 응답이 CloudFront 기본 동작(dvr 쿠키 · 403)이 아님. ⓑ **200 JSON**(회차 라우트 — 제어면 서버의 이 경로 처리기 — 배포 뒤 · 실재 시험 회차로): 200 · `application/json` · 제어면 응답 계약(계약3 7-2절)의 키 다섯(`ready` · `mode` · `url` · `firstPdt` · `headPdt`). 실패 = 엣지가 404(원본 로그 0건) · 403 · 5xx · JSON 아닌 200 → 엣지 경로 패턴을 맡은 후속 작업에서 고침 | ⓐ 는 엣지만 · ⓑ 는 런타임 뒤(회차 라우트 배포 뒤) |
| T13 | rewind 객체 읽기(런타임 2단 OAC 허용문) | 시험 방송의 clip 쿠키로 사이드카가 올린 실재 키의 `/dvr/…/index.m3u8`(되감기 목록) · 조각 각 1건 200 · 같은 경로 쿠키 없음 403. VOD 목록이 있으면 `/vod/…` 1건 200(아직 없으면 「보류」). 실패 = 쿠키가 있는데 403 → 키 실재부터 봄(`s3:ListBucket` 이 없어 없는 키도 403) · 키가 있으면 런타임 2단 정책 · OAC 문제로 kty 에게 보고 | 런타임 뒤 |

런타임 뒤 시험에서 시험 계정 방송은 계약9 이벤트를 내보내지 않거나 시험
표식으로 거릅니다(설계 5-3 조건).

## 엣지만 시험

O7f 의 2(T6b)와 4(T0 · T8e 해석 · T11 404 · T2b · T12 ⓐ)입니다. 아래 명령은
「접속법」 의 `edge_ip` · `alb_ip` 를 씁니다. `<id>` 는 시험에 쓰는
`ingestStreamId` 꼴의 아무 값입니다(방송이 없어도 됨).

*   **T0**: 세 이름이 인증서 검증을 통과하고 응답합니다(`curl` 종료 0 ·
    `ssl_verify_result` 0).

    ```shell
    {
      curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
        -w 'media %{http_code} %{ssl_verify_result}\n' https://media.pokeclip.com/
      curl --resolve "api.pokeclip.com:443:${alb_ip}" -sS -o /dev/null \
        -w 'api %{http_code} %{ssl_verify_result}\n' https://api.pokeclip.com/
      curl --resolve "app.pokeclip.com:443:${alb_ip}" -sS -o /dev/null \
        -w 'app %{http_code} %{ssl_verify_result}\n' https://app.pokeclip.com/
    } | tee "${evidence}/t0.txt"
    ```

    media `/` 는 403(키 그룹) 또는 404(deny-default)이고, 그것도 TLS 통과로
    봅니다.

*   **T6b**(KVS 값 입력 전 · A1 이면 하지 않음):

    ```shell
    for req_path in "/live/<id>/index.m3u8" "/dvr/<id>/index.m3u8"; do
      curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
        -w "${req_path} %{http_code}\n" "https://media.pokeclip.com${req_path}"
    done | tee "${evidence}/t6b.txt"
    curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
      -X POST -w 'end-signal %{http_code}\n' \
      https://media.pokeclip.com/api/ingest/end-signal | tee -a "${evidence}/t6b.txt"
    aws logs filter-log-events --region us-east-1 \
      --log-group-name /aws/cloudfront/function/pokeclip-prod-live-auth \
      --filter-pattern '"CDN SECRET UNAVAILABLE"' \
      --query 'length(events)' | tee -a "${evidence}/t6b.txt"   # 1 이상
    ```

    `/live` 503 · `/dvr` 403(키 그룹) · end-signal 404(nginx) 가 기대입니다.
    함수 로그는 CloudWatch Logs us-east-1 의
    `/aws/cloudfront/function/<함수 이름>` 에 남습니다(로그가 늦게 보이면 몇
    분 뒤 다시 봅니다).

*   **T8e 해석**: `dig +short` 로 NLB 이름(platform 출력 `nlb_dns_name`)이 IP
    하나로 풀리는지 봅니다. ingest 공개 이름은 8단계 전이라 아직 없습니다.

*   **T11 404**(제어면 서버 배포 전 · A1 이면 하지 않음): nginx 제어면 포트가
    직접 404 를 내고, 그 404 가 그대로 전달되며 캐시되지 않습니다(설계
    3-3-1). 502 가 나오면 실패이고, 원인 1순위는 아래 「VPC origin TLS
    전제」 입니다. 두 번 보내 둘 다 원본까지 가는지
    (`x-cache` 가 `Miss` 또는 `Error`, `Hit` 아님) 봅니다.

    ```shell
    for n in 1 2; do
      curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
        -X POST -D - https://media.pokeclip.com/api/ingest/end-signal \
        | grep -Ei '^(HTTP/|x-cache:)'
    done | tee "${evidence}/t11-404.txt"
    ```

*   **T12 ⓐ 원본 도달**(A1 이면 Media 가 뜬 뒤): 엣지가 회차별 되감기 경로를
    원본까지 넘기는지만 봅니다. 응답이 404 여도 원본에 닿았으면 통과입니다.
    *   `control_upstream` 반영 전: nginx 가 직접 낸 404 · 원본 로그 1건 ·
        상류 호출 0 입니다.
    *   반영 뒤 · 회차 라우트 등록 전: 제어면 서버의 ServeMux 기본 404 · 원본
        로그 1건 · 상류 도달입니다. 응답 머리가 스트림 경로의 404 와 달라도
        정상입니다(기본 404).
    *   회차 라우트 등록 뒤(POK-196 M6 사이드카 배포 뒤): 제어면 처리기가
        장부를 읽고 JSON 404 `{"error":"not_found"}` 를 냅니다 — 표식 회차는
        장부에 없습니다. 기동 직후나 DB 읽기 실패면 JSON 503
        `{"error":"unavailable"}` 이고, 이것도 원본 도달이라 통과입니다. 원본
        로그 1건 · 상류 도달입니다.
    *   어느 단계든 `server: CloudFront` 응답(dvr 쿠키 403 · deny-default
        404)이나 원본 로그 0건이면 엣지가 막은 것이라 실패입니다.

    ```shell
    probe="pcrw$(date -u +%H%M%S)"
    printf '%s\n' "${probe}" > "${evidence}/t12a-probe.txt"
    printf '@%s\n' "$(( $(date -u +%s) - 60 ))" > "${evidence}/t12a-since.txt"
    curl --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null -D - \
      -w 't12a %{http_code}\n' \
      "https://media.pokeclip.com/api/streams/${probe}/rounds/${probe}/rewind" |
      grep -iE '^server:|^x-cache:|^t12a ' | tee "${evidence}/t12a.txt"
    ```

    `since` 는 요청 1분 전 시각입니다. 노트북 시계가 상자 시계보다 조금
    빨라도 요청 줄이 빠져 `COUNT 0` 으로 잘못 나오지 않게 합니다. `probe` 는
    이 요청에만 쓰는 표식이라, 앞당긴 1분 동안의 다른 줄은 세지 않습니다.
    `@` 뒤 숫자(1970년부터 센 초)는 `journalctl --since` 가 그대로 읽습니다.

    판정 줄은 Media 상자에서 SSM 으로 받습니다(건수만 · 실행 직전 승인).
    `since` · `probe` 에 위 두 파일의 값을 넣습니다. 꼴은
    [media-host README](/infra/media-host/README.md) 「시작 · 정지」 의 건수
    꼴입니다 — 원문은 내지 않고, 조회 실패는 0건이 아니라 종료 2 입니다.
    nginx 접근 로그의 끝에서 둘째 칸이 상류 주소(`$upstream_addr`)이고,
    nginx 가 직접 답했으면 `-` 입니다.

    ```shell
    fail() { echo "T12A COUNT FAIL $1"; exit 2; }
    since='<t12a-since.txt 의 값>'
    probe='<t12a-probe.txt 의 값>'
    log="$(journalctl -q -u pokeclip-media --since "${since}" -o cat --no-pager)" \
      || fail journal
    printf '%s\n' "${log}" |
      awk -v p=" /api/streams/${probe}/rounds/${probe}/rewind " \
        'index($0, p) { n++; if ($(NF - 1) != "-") u++ }
         END { printf "T12A COUNT %d UPSTREAM %d\n", n, u }' || fail count
    ```

    `T12A COUNT 1 UPSTREAM 0` 이면 반영 전 통과, `T12A COUNT 1 UPSTREAM 1`
    이면 반영 뒤 통과입니다(회차 라우트 등록 전은 ServeMux 404 · 등록 뒤는
    처리기의 JSON 404 또는 503 · 어느 쪽이든 `server` 가 CloudFront 아님).
    `COUNT 0` 이면 실패이고 kty 에게 보고합니다 — 엣지 경로 패턴은 그
    패턴을 맡은 후속 작업에서 고칩니다. `COUNT` 가 2 이상이면 엣지가 같은
    요청을 원본에 다시 보낸 것입니다. `UPSTREAM` 이 0 이거나
    `COUNT` 와 같으면 위와 같은 단계의 통과로 보고 비고에 건수를 적습니다.
    그 사이 값이면 보류로 두고 kty 에게 보고합니다.

### T2b 기본 거절

*   media 이름으로 `/live` 가 없는 경로 `/<id>/index.m3u8` · `/x` · `/` 를
    요청하면 403(키 그룹) 또는 404(deny-default)입니다.
*   **판정 근거는 응답 코드와 원본 nginx 접근 로그의 그 요청 건수 0**
    입니다(SSM 판정 줄 — 건수만, 실행 직전 승인). ALB 접근 로그 자원은 두지
    않으므로 근거로 쓰지 않습니다.
*   공개 ALB: `api.pokeclip.com/unknown` · 모르는 Host 는 404 입니다.
*   런타임 뒤: 유효 쿠키로 `/live` 가 없는 경로를 요청해도 404 이고, nginx
    로그 도달은 0 입니다.
*   쿠키 없는 경로 우회 사례도 T2b 에서 합니다(아래 「경로 우회 시험」).

## 경로 우회 시험

**O8(이름 공개)의 개통 조건입니다 — 통과하기 전에는 O8 을 시작하지
않습니다.** 쿠키 없는 사례는 T2b(엣지만)에서, 방송 쿠키 사례는 T2(런타임
뒤)에서 합니다. 공개 이름이 열리기 전에도 `--resolve` 로 O7f 뒤 배포에 바로
닿으므로, DNS 미등록은 노출을 막지 못합니다.

배경:

*   CloudFront 는 URI 경로를 RFC 3986 대로 정규화한 뒤 비헤이비어를 고르고,
    고른 뒤에는 원 경로(raw URI path)를 원본에 보냅니다. 정규화에서 `//` ·
    `..` 같은 문자가 지워집니다(AWS 개발자 안내서
    [캐시 비헤이비어 설정](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistValuesCacheBehavior.html)
    「Path normalization」 — 2026-10-24 원문 확인). 그래서
    `/live/../api/ingest/x` 는 쿠키 · 함수가 없는 `/api/ingest/*`
    비헤이비어로 골라지지만 원본에는 `/live/…` 그대로 갑니다.
*   내부 ALB 도 규칙을 정규화 경로로 고릅니다 — 「The rule evaluation is
    performed only after URI normalization occurs.」(AWS ALB 안내서
    [규칙 조건 유형](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/rule-condition-types.html)
    「Path conditions」 — 2026-10-25 원문 확인). 대상 그룹 선택은 정규화
    경로 기준입니다.
*   Media 상자 nginx 에는 원문 경로 거절이 이미 있습니다.
    `infra/media-host/nginx/nginx.conf.tmpl` 의 `$raw_path_unsafe` 가 live ·
    제어면 포트에서 400 을, playback 원문 대조가 403 을 냅니다. 계약 시험
    `infra/media-host/test/proxy_contract.sh` 가 정규화 우회 사례
    (`test/lib/normalization_cases.sh`)로 거절과 상류 호출 0 을 봅니다.
*   미확인 셋 — ① ALB 가 상류에 원 경로를 넘기는지 정규화 경로를 넘기는지
    ② 서명 쿠키의 리소스 대조가 원 경로 기준인지 ③ CloudFront 가 `%2e` ·
    `%2f` · `%5c` 를 정규화하는지. 셋 다 방송 간 우회의 결과를 가르므로
    **T2 사례가 가장 중요한 실측**입니다.
*   이 거절을 넣은 PR(POK-458 결함 묶음)의 edge apply 뒤부터
    live-auth(`/live` 요청마다 원본 전에 도는 CloudFront 함수)는 받은
    경로에 아래 가운데 하나라도 있으면 403 으로 거절합니다.
    *   `%` 한 글자라도(이스케이프 종류 · 대소문자 · 짝 여부 무관 — 정상
        `/live` 경로에는 `%` 가 없습니다).
    *   역슬래시.
    *   빈 구간(`//`).
    *   점 구간(`/./` · `/../` 처럼 구간 전체가 `.` 이나 `..`).
*   그중 `%2f` · `%5c` · `%25` 와 역슬래시는 정규화(RFC 3986 기준)가 풀지
    않아 정규화 뒤에도 남습니다. 그래서 ③ 과 상관없이 막힐 것으로 봅니다
    — **미확인**입니다. CloudFront 가 실제로 무엇을 정규화하는지가 ③ 이라
    실측 전에는 확정하지 않습니다. 함수가 원 경로를 받는지(G1)는
    [POK-548](https://xodbs9810.atlassian.net/browse/POK-548) 에서 잽니다.
    같은 계정의 실험 배포에서는 `/dvr/*` 함수(dvr-floor)가 원 경로를
    받았습니다(2026-11-11 — 「/dvr 하한 실험대 결과」 의 R9). live-auth 의
    prod 배포는 따로 봅니다.

| 언제 | 요청(쿠키) | 통과 조건 |
|---|---|---|
| T2b | `/live/../api/ingest/<표식>` · `/playback/../api/ingest/<표식>` · `/live/../api/streams/<표식>/rewind`(쿠키 없음) | 차단 계층 · 응답 코드 기록 · 엣지 차단이면 nginx 도달 0 · nginx 차단이면 거절 응답 + 보호 대상 상류 전달 0 |
| T2 | `/live/<A>/../<B>/index.m3u8`(방송 A 쿠키) | 앞뒤 양성 대조가 둘 다 유효(HTTP 응답이 왔고 `server` 가 CloudFront 가 아님 — 아니면 그 회차 무효)하고, 모든 사례가 CloudFront 가 낸 403(`server: CloudFront`)이며, 그 시각대 WAF 차단 로그의 시험 IP 건수가 0 — 사례가 그 밖(nginx 응답 · `Miss` · 2xx · 5xx · 통신 실패)이면 A 쿠키가 엣지를 통과한 것이라 실패 · WAF 건수가 0 이 아니면 403 이 엣지 거절(서명 · live-auth)인지 가를 수 없어 실패 |

*   **변형**: 각 사례의 `/../` 를 `/%2e%2e/` · `/..%2f` · `/..%5c` ·
    `//../` 로 바꾼 것도 같은 조건입니다.
*   **명령**: `curl --path-as-is` 로 보냅니다(없으면 curl 이 `..` 를 먼저
    접습니다). 시험 직전 시각(`date -u`)을 적어 두고 아래 SSM 조회의 시작
    시각으로 씁니다.

    ```shell
    probe="pcpt$(date -u +%H%M%S)"
    date -u '+%Y-%m-%d %H:%M:%S UTC' > "${evidence}/path-since.txt"
    for dots in /../ /%2e%2e/ /..%2f /..%5c //../; do
      for req_path in "/live${dots}api/ingest/${probe}" \
        "/playback${dots}api/ingest/${probe}" \
        "/live${dots}api/streams/${probe}/rewind"; do
        curl --path-as-is --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
          -o /dev/null -w "${req_path} %{http_code}\n" \
          "https://media.pokeclip.com${req_path}"
      done
    done | tee "${evidence}/t2b-path.txt"
    ```

*   **T2(방송 쿠키)**: `a_id` · `b_id` 는 시험 방송 둘의 `ingestStreamId`
    입니다. `jar_a` 는 방송 A 의 서명 쿠키 셋(O7f 의 10 과 같은 발급)을 담은
    쿠키 파일이고, 증거 디렉터리 밖에 둡니다(`mktemp` — 0600). 쿠키는 이
    파일로만 넘기고 시험 뒤 지웁니다(명령 인자 · 증거에 값이 남지 않게).
*   **T2 는 엣지 거절만 통과입니다**(kty 2026-10-25 — 서명 거절.
    2026-11-04 live-auth 경로 거절을 더함). 이 판정이 지키는 것은 「요청이
    원본 · 캐시에 닿지 않음」 입니다. 모든 사례의 응답이 CloudFront 가 직접
    낸 403(`server: CloudFront` 헤더 — nginx 가 낸 응답이면 `server` 가
    nginx)이어야 합니다. 서명 거절과 live-auth 거절은 둘 다 원본 · 캐시
    앞에서 멈추므로 똑같이 통과입니다 — `..%5c` 는 live-auth 가 거절할 것으로
    봅니다(위 「미확인」 — nginx 까지 가면 이 시험이 실패로 잡습니다 · 그
    edge apply 전에는 nginx 400 까지 갔습니다 — 2026-11-04 실측). 그 밖의
    응답은 모두
    실패입니다 — nginx 까지 간 응답(400 · 403 · 404 · `Miss`)은 A 쿠키가
    엣지(서명 검사 · live-auth)를 통과했다는 뜻이고, 그러면 캐시가 차 있는
    실제 운영에서 A 쿠키로 B 목록 · 조각(조각은 최대 1시간 캐시)이 나갈 수
    있습니다. 2xx 는 노출 그 자체입니다. 판정이 캐시 상태에 기대지 않으므로
    예열은 하지 않습니다.
*   **A 쿠키 양성 대조를 먼저 합니다.** 같은 `jar_a` 로 A 자신의 목록
    `/live/<A>/index.m3u8` 를 한 번 요청합니다. 대조는 HTTP 응답이 왔고
    (`control` 줄의 코드가 `000` 이 아님) `server:` 줄의 값이 CloudFront 가
    아닐 때만 유효합니다(A 송출 여부와 무관 — 엣지 서명 검사를 지나 원본까지
    갔다는 뜻). `server:` 줄이 없으면 무효입니다 — `-w` 가 늘 `control` 줄을
    내므로 출력은 비지 않고, 통신 실패면 `control 000` 한 줄만 남습니다.
    `server: CloudFront` 여도 무효입니다(엣지에서 멈춤 — 403 이면
    쿠키가 무효: 만료 · 다른 방송 · 안 실림). 무효면 경로 사례의 403 이 경로
    때문인지 가를 수 없으므로 그 회차는 판정을 적지 않습니다. 대조는 사례
    뒤에 한 번 더 하고, 앞뒤 대조의 출력도 사례와 같은 증거 파일에 남깁니다.

    ```shell
    t2_since_ms="$(($(date -u +%s) * 1000))"
    control() {
      curl -b "${jar_a}" --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
        -o /dev/null -D - -w "control %{http_code}\n" \
        "https://media.pokeclip.com/live/${a_id}/index.m3u8" |
        grep -iE '^server:|^control '
    }
    control | tee "${evidence}/t2-path.txt"
    for dots in /../ /%2e%2e/ /..%2f /..%5c //../; do
      req_path="/live/${a_id}${dots}${b_id}/index.m3u8"
      curl --path-as-is -b "${jar_a}" \
        --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
        -D - -w "${req_path} %{http_code}\n" "https://media.pokeclip.com${req_path}" |
        grep -iE '^server:|^x-cache:|^/live/'
    done | tee -a "${evidence}/t2-path.txt"
    control | tee -a "${evidence}/t2-path.txt"
    rm -f "${jar_a}" && test ! -e "${jar_a}" && echo removed
    aws logs filter-log-events --region us-east-1 \
      --log-group-name /aws/cloudfront/function/pokeclip-prod-live-auth \
      --start-time "${t2_since_ms}" --filter-pattern '"LIVE PATH DENY"' \
      --query 'length(events)' | tee -a "${evidence}/t2-path.txt"   # 1 이상
    ```

    마지막 줄은 그 시각 뒤 live-auth 가 경로 때문에 거절한 건수입니다(함수
    로그의 고정 문자열 `LIVE PATH DENY`). `..%5c` 사례가 있으므로 1 이상이
    기대입니다. 함수 로그는 몇 분 늦게 보일 수 있습니다 — 0 이면 몇 분 뒤
    다시 셉니다. 이 건수는 판정이 아니라 비고 기록입니다.

*   **판별 사례(기록용 — O8 판정 밖)**: A 쿠키로
    `/live/<B>/%2e%2e/<A>/index.m3u8` 를 한 번 보냅니다. 원 경로는 B 아래이고
    정규화한 경로는 A 아래라, 엣지가 어느 경로로 대조하는지를 가르는
    사례입니다. `jar_a` 는 쿠키 Path 가 `/live/<A>/` 라 curl 이 이 요청에
    쿠키를 싣지 않습니다. 그래서 같은 발급의 쿠키 셋을 Path `/live/` 로 담은
    별도 쿠키 파일 `jar_wide` 로 보냅니다. 보관 규칙은 `jar_a` 와 같습니다
    — 증거 디렉터리 밖 · 값은 명령 줄에 쓰지 않음 · 끝에 지움.
*   **`jar_wide` 만드는 법**: 위 T2 명령이 끝에 `jar_a` 를 지웠으므로, 같은
    발급으로 `jar_a` 를 새로 만듭니다. 그 파일에서 Path 칸(쿠키 파일 한 줄의
    셋째 칸)만 `/live/` 로 바꿔 `jar_wide` 를 쓰고, `jar_a` 는 바로 지웁니다.
    `awk` 가 파일을 직접 읽어 값은 화면 · 명령 줄에 나오지 않습니다.
    마지막 줄은 Path 를 바꾼 쿠키 수입니다(서명 쿠키 셋이라 3).

    ```shell
    jar_wide="$(mktemp)"   # 0600
    awk 'BEGIN { FS = OFS = "\t" } NF == 7 { $3 = "/live/" } { print }' \
      "${jar_a}" > "${jar_wide}"
    rm -f "${jar_a}" && test ! -e "${jar_a}" && echo removed
    grep -c "$(printf '\t')/live/$(printf '\t')" "${jar_wide}"   # 3
    ```

    판별 사례 명령입니다.

    ```shell
    probe_since_ms="$(($(date -u +%s) * 1000))"
    req_path="/live/${b_id}/%2e%2e/${a_id}/index.m3u8"
    curl --path-as-is -b "${jar_wide}" \
      --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
      -D - -w "${req_path} %{http_code}\n" \
      "https://media.pokeclip.com${req_path}" |
      grep -iE '^server:|^x-cache:|^/live/' | tee "${evidence}/t2-probe.txt"
    rm -f "${jar_wide}" && test ! -e "${jar_wide}" && echo removed
    aws logs filter-log-events --region us-east-1 \
      --log-group-name /aws/cloudfront/function/pokeclip-prod-live-auth \
      --start-time "${probe_since_ms}" --filter-pattern '"LIVE PATH DENY"' \
      --query 'length(events)' | tee -a "${evidence}/t2-probe.txt"
    ```

    결과는 `results.tsv` 의 「경로 우회(T2)」 비고에 코드 · `server` ·
    `x-cache` · `LIVE PATH DENY` 건수로 적고
    [POK-548](https://xodbs9810.atlassian.net/browse/POK-548) 에 넘깁니다.
    건수에는 단서가 둘 있습니다.

    *   **요청과 묶이지 않습니다.** 건수는 그 시각 뒤 live-auth 가 경로로
        거절한 모든 요청의 합입니다. 다른 시청자 · 스캐너의 요청이 섞일 수
        있습니다.
    *   **늦게 보일 수 있습니다.** 함수 로그는 몇 분 늦게 보일 수 있습니다.
        0 이면 몇 분 뒤 마지막 `aws logs` 줄만 다시 돌려 그 값을 씁니다.

    읽는 법은 넷입니다.

    *   CloudFront 403 이고 건수 1 이상: live-auth 가 원 경로를 받은 것으로
        봅니다. 다른 요청이 섞였을 수 있어 「추정」 으로 적습니다.
    *   CloudFront 403 이고 다시 센 건수도 0: 서명이 원 경로로 대조했거나
        다른 기제가 막았습니다(둘 가운데 어느 쪽인지는 이 사례로 가르지
        못합니다).
    *   엣지를 지난 응답(`server` 가 CloudFront 가 아님 — nginx 가 원 경로를
        400 으로 막을 수 있음): 엣지가 정규화한 경로로 대조했습니다.
    *   **가르지 못함**: 위 셋에 들지 않는 결과입니다. 통신 실패 · 5xx ·
        CloudFront 403 이 아닌 엣지 응답 · 건수 조회 실패가 여기에 듭니다.
        코드 · `server` · `x-cache` · 건수만 적고 해석은 적지 않습니다.

    어느 쪽이든 A 쿠키로 A 를 읽는 것이라 노출이 아니고, O8 판정에 쓰지
    않습니다.

*   **판정 근거 = 응답 코드 + SSM 판정 줄의 건수**(건수만 · 실행 직전
    승인). Media 상자의 유닛 저널(nginx 접근 로그가 남는 곳 —
    [media-host README](/infra/media-host/README.md) 「로그」)에서 시작
    시각 뒤의 줄을 셉니다. 꼴은 그 README 「시작 · 정지」 의 건수 꼴(`awk` ·
    조회 실패는 0 건이 아니라 실패)을 따르고, 원문은 내지 않습니다. T2 는
    SSM 건수를 쓰지 않고 응답 코드 · `server` 헤더 · 앞뒤 양성 대조의 유효 ·
    그 시각대 WAF 차단 로그의 시험 IP 건수(0)로 판정합니다(아래 T2 항목).
*   **응답 코드가 먼저**입니다(T2 는 아래 T2 항목이 더 좁습니다). T2b 의
    모든 사례에서 응답은 거절 코드(CloudFront
    403 · 404 또는 nginx 400 · 403 · 404)여야 합니다. 2xx · 3xx · 5xx · 통신
    실패는 로그와 무관하게 실패입니다 — 캐시 HIT 면 엣지가 원본에 가지 않고
    콘텐츠를 내주므로 nginx · 상류 기록이 0 이어도 노출일 수 있습니다.
*   **차단 계층**: 사례마다 응답 코드와 막은 계층을 `results.tsv` 비고에
    적습니다. T2b 는 거절 코드이면서 표식이 든 nginx 줄이 없으면 엣지
    차단(CloudFront 키 그룹 403 · deny-default 404)이고, 있으면 nginx 까지 온
    것입니다.
    *   엣지 차단: nginx 도달 0 이면 통과입니다.
    *   nginx 차단: 거절 응답(400 · 403 · 404)이고, 보호 대상 상류(MediaMTX
        `127.0.0.1:8888` · 재생 서버 `127.0.0.1:9996`)로 넘긴 줄이 0(그
        줄의 `$upstream_addr` 가 `-`)이면 통과입니다. 제어면 상류로 넘긴
        줄은 실패로 세지 않습니다 — `/…/../api/ingest/…` 가 정규화되면
        원래 서명 쿠키 없이 열린 `/api/ingest/…` 와 같은 요청입니다.
    *   T2: 모든 사례가 CloudFront 403(`server: CloudFront`)이어야 통과입니다
        (위 「T2 는 엣지 거절만 통과」) — 앞뒤 양성 대조가 둘 다
        유효할 때(HTTP 응답이 왔고 `server` 가 CloudFront 가 아님)만
        판정합니다. `results.tsv` 비고에는 대조 결과 · 응답 코드 · `server` ·
        `x-cache` 와, 그 시각대 WAF 차단 로그의 시험 IP 건수(0 이어야 「엣지
        거절」(서명 · live-auth) — WAF 403 도 `server: CloudFront`)를 적습니다.
        그 건수가 0 이 아니면 실패입니다(회차 무효로 다시 돌리지 않습니다).
        nginx 건수는 T2 판정에 쓰지 않습니다.
    *   T2 비고의 거절 출처: 2026-11-04 POK-458 실측의 CloudFront 403(서명
        거절로 추정 — 기제는 POK-548)은 `x-cache: Error from cloudfront`
        였습니다. live-auth 거절의 `x-cache` 값은 첫 실측에서 적습니다(예상
        `FunctionGeneratedResponse from cloudfront` · 미확인). 함수 로그
        `LIVE PATH DENY` 건수(위 T2 명령의 마지막 줄)도 함께 적습니다.
*   **실패면 O8 을 중단합니다.** kty 에게 보고하고, 막는 자리(T2 면
    CloudFront 함수 등 엣지 · T2b 면 Media 상자 nginx)를 정해 고친 뒤 다시
    시험합니다(확인은
    [POK-458](https://xodbs9810.atlassian.net/browse/POK-458) · 고치는 일은
    그 결과로 여는 후속 티켓).
*   통과 뒤 O8 은 [prod/records](../prod/records/README.md) 「적용 —
    이름마다 apply(O8)」 대로 합니다. 이 루트의 첫 apply(처음 여는 이름)는
    `terraform_data.remote_contract` 가 함께 생성되어 plan 이 「추가 = 그
    이름의 레코드 수 + 1 · 그 밖 0」 이고, 그 뒤 이름은 「추가 = 그 이름의
    레코드 수 · 그 밖 0」 입니다.

## 판정 문구

모두 「경보 검증 보류」 입니다.

*   **T6b**: `/live` 503 · 함수 로그 고정 문자열(`CDN SECRET UNAVAILABLE`)이
    있고, `/dvr` · 제어면은 503 이 아닙니다. 쿠키 없는 `/live` 가 403 이면
    서명 검증이 함수보다 먼저이므로 T6b 를 7-R 로 옮깁니다([엣지 비밀
    런북](edge-secrets.md) 「T6b 대체 절차」).
*   **T9a**: Media 가 미기동이고, 저널에 `MEDIA START BLOCKED: CDN secret
    unavailable` 이 1건 이상입니다(`MEDIA PREREQ FAIL` 과 구분합니다).

## KVS 값 입력

멈춤 자리 ② 입니다(설계 5-4 의 9 · O7f 의 3). Secrets Manager 의 CDN 비밀
(`pokeclip/prod/cdn-secret` — O7e 에서 넣은 값)을 읽어 KVS 의 키
`cdn-secret` 에 넣습니다. 값은 셸 변수와 표준 입력으로만 지나갑니다 —
명령 인자 · 환경 변수 · 임시 파일 · 출력에 남지 않습니다(스크립트 머리
주석).

**선행:**

1.  T6b 를 끝냈습니다(T6b 가 값 입력보다 먼저 — 설계 8절).
1.  입력 직전에 `O7f-stop2` 를 다시 판정해 허용입니다
    (`bash infra/terraform/scripts/precheck/scp-recheck.sh O7f-stop2`).
    거부면 값을 넣지 않고 A2 로 갑니다. 종료 2(검사 불가)면 넣지 않고 kty
    에게 보고합니다.
1.  AWS CLI 명령 기록이 꺼져 있습니다([엣지 비밀 런북](edge-secrets.md)
    「시험용 스트림 키 넣기」 의 1 과 같은 블록 — `CLI HISTORY OK`). 이
    단계는 이중 확인입니다 — 스크립트도 값을 읽기 전에 같은 검사로 막습니다
    (종료 1 — 이름 풀이 · 값 읽기 · KVS 호출 0). `CLI HISTORY ENABLED` 면
    기록을 끈 뒤 다시 하고, `CLI HISTORY UNCHECKED` 면 사유 줄을 보고 설정 ·
    프로필 · lib 경로를 고친 뒤 다시 합니다(모르면 kty 에게 보고).
1.  `aws --version` 이 `aws-cli/2.38.1` 이 아니면
    `bash infra/terraform/scripts/test/kvs_put_test.sh` 의
    `ok: real-cli-contract(` 줄을 본 뒤 진행합니다. 그 줄이 아니면(skip ·
    FAIL) 시작하지 않습니다 — 그 판에서 `--value file:///dev/stdin` 이 값을
    한 번만 읽어 요청까지 보내는지 확인되지 않은 것입니다.

**필요 권한**(kty IAM 사용자): `secretsmanager:GetSecretValue`(CDN 비밀) ·
`cloudfront-keyvaluestore:DescribeKeyValueStore` ·
`cloudfront-keyvaluestore:PutKey`(KVS). `sts` 는 부르지 않습니다 — 이름
풀이 호스트의 계정 번호는 KVS ARN 에서 옵니다.

**실행**(저장소 뿌리에서 · 실행 직전 승인):

```shell
kvs_arn="$(terraform -chdir=infra/terraform/prod/edge output -raw cdn_kvs_arn)"
secret_id="$(terraform -chdir=infra/terraform/prod/secrets output -raw cdn_secret_arn)"
bash infra/terraform/scripts/kvs-put-cdn-secret.sh \
  --kvs-arn "${kvs_arn}" --secret-id "${secret_id}"
echo "exit=$?"
```

`KVS PUT OK` 한 줄과 `exit=0` 이 나와야 합니다. ARN 은 변수에만 두고 화면 ·
문서에 옮기지 않습니다. 넣은 뒤 함수가 값을 읽기까지 전파 시간이 있습니다
— `/live` 판정은 런타임 뒤 시험(쿠키 필요)에서 합니다.

### KVS 값 입력의 종료 코드

| 종료 | 출력(표준 오류 · 0 만 표준 출력) | 뜻 | 할 일 |
|---|---|---|---|
| 0 | `KVS PUT OK` | 넣음 | 다음 번호로 |
| 1 | `KVS PUT FAILED <단계> <코드>` · `SECRET VALUE EMPTY` · `SECRET VALUE INVALID` | 그 밖의 실패(값 검사 포함) | 다시 시도하지 않고 kty 에게 보고합니다. SCP 거부가 아니므로 A2 로 기록하지 않습니다 |
| 1 | `CLI HISTORY ENABLED` + 사유 한 줄 | aws CLI 명령 기록이 켜짐(값 읽기 · KVS 호출 0) | 기록을 끄고 다시 합니다 |
| 1 | `CLI HISTORY UNCHECKED` + 사유 한 줄 | 기록 검사를 하지 못함(설정 읽기 실패 · 공용 함수 없음 — 값 읽기 · KVS 호출 0) | 사유 줄을 보고 설정 · 프로필 · lib 경로를 고친 뒤 다시 합니다. 모르면 kty 에게 보고합니다 |
| 2 | 사용법 | 인자 · KVS ARN 꼴 · 비밀 ID 꼴이 틀림 | 출력 이름(`cdn_kvs_arn` · `cdn_secret_arn`)을 다시 봅니다 |
| 3 | `KVS HOST UNRESOLVED` + 사유 | 이름 풀이 실패 · KVS 엔드포인트 연결 실패(Could not connect) | 아래 「이름 풀이 우회」 뒤 다시 합니다. SCP 거부로 기록하지 않습니다 |
| 4 | `KVS PUT DENIED` | KVS 호출(ETag 읽기 · PutKey)의 AccessDenied | 멈춤 자리 ② — 값 입력만 멈추고 승인 지점 A2(「멈춤 자리일 때」) |
| 5 | `SECRET READ DENIED` | Secrets Manager 값 읽기의 AccessDenied(KVS 호출 0) | 멈춤 자리 밖 — 전체 중단(계획 3절 SCP 멈춤 규칙) |

### 이름 풀이 우회

종료 3 은 실행 위치의 리졸버가 KVS 데이터면 이름
(`<계정>.cloudfront-kvs.global.api.aws`)을 풀지 못한 것입니다. 2026-10-13
실측(KVS 확인 실험)에서 로컬 리졸버가 이 이름을 풀지 못했고, 공용 리졸버를
지정한 컨테이너에서는 통과했습니다. **그 실행에서만** 공용 리졸버를 지정한
컨테이너로 같은 스크립트를 돌립니다. 시스템 DNS 설정은 바꾸지 않습니다.

*   이미지는 다이제스트로 고정합니다 — `amazon/aws-cli:2.38.1` 의 다중
    아키텍처 색인(Docker Hub 조회 2026-10-24). 이 이미지에는 `dig` 가 없어
    컨테이너 안에서 `bind-utils` 를 판까지 고정해 설치합니다(Amazon Linux
    2023 저장소는 이미지 판에 묶여 같은 판이 나옵니다 — 2026-10-24 로컬
    확인: `bind-utils-9.18.50-1.amzn2023.0.4`, `bash` 있음).
*   `~/.aws` 전체를 붙이지 않습니다. 쓰는 프로필 하나의 자격만
    `aws configure export-credentials` 로 꺼내 임시 디렉터리(`mktemp -d` —
    0700)의 `credentials` 파일 하나(`[default]` 한 절)에 쓰고, 그 파일만 읽기
    전용으로 붙입니다. 자격 값은 파이프로만 지나가고 화면 · 명령 인자 · 환경
    변수에 남지 않습니다. 리전은 스크립트가 `--region` 으로 정하므로 `config`
    는 붙이지 않습니다.
*   자격을 꺼내지 못하면(`CREDS FILE OK` 없음 — 프로필 · 로그인 확인 · 키
    둘 중 하나라도 비면 여기서 멈춤) 컨테이너를 띄우지 않습니다.
*   자격 디렉터리는 서브셸의 `EXIT` 트랩이 지웁니다 — 정상 · 실패 · 중단
    (Ctrl-C · TERM · HUP) 모두 `removed` 가 나옵니다. 컨테이너는 끝나면
    지워집니다(`--rm`). `--init` 과 `TINI_KILL_PROCESS_GROUP=1` 은 Ctrl-C 를
    컨테이너 안 프로세스 묶음 전체에 넘겨 컨테이너도 끝나게 합니다(`--init`
    만이면 PID 1 bash 가 신호를 받고도 자식을 기다려 끝나지 않습니다 —
    2026-10-25 로컬 확인).
*   도커 데몬이 userns-remap 을 쓰면
    (`docker info --format '{{json .SecurityOptions}}'` 에 `name=userns`)
    컨테이너 root 가 호스트의 다른 uid 라 0600 자격 파일을 읽지 못합니다 —
    그 환경에서는 띄우지 않고 kty 에게 보고합니다(이 기계의 OrbStack 은 해당
    없음 — 2026-10-25 확인).

```shell
image='amazon/aws-cli@sha256:733d5be1015825e4038c6016f3fa9954868ddee1f611b73be795a9cc2e706bfd'
had_image=no
docker image inspect "${image}" >/dev/null 2>&1 && had_image=yes
(
  creds_dir="$(mktemp -d "${TMPDIR:-/tmp}/pokeclip-kvs-creds.XXXXXX")" || exit 1
  cleanup='rm -rf -- "${creds_dir}" && test ! -e "${creds_dir}" && echo removed'
  trap "${cleanup}" EXIT
  trap 'exit 130' INT TERM HUP
  umask 077
  aws configure export-credentials --profile "${AWS_PROFILE:-default}" |
    jq -er 'select((.AccessKeyId // "") != ""
        and (.SecretAccessKey // "") != "")
      | "[default]",
        "aws_access_key_id = \(.AccessKeyId)",
        "aws_secret_access_key = \(.SecretAccessKey)",
        (.SessionToken // empty | "aws_session_token = \(.)")' \
    > "${creds_dir}/credentials" || exit 1
  echo "CREDS FILE OK"
  docker run --rm --init -e TINI_KILL_PROCESS_GROUP=1 \
    --label com.pokeclip.bench=kvs-put --dns 1.1.1.1 --dns 8.8.8.8 \
    -v "${creds_dir}/credentials:/root/.aws/credentials:ro" \
    -v "${PWD}/infra/terraform/scripts:/work:ro" \
    --entrypoint /bin/bash "${image}" -c \
    'dnf install -y -q bind-utils-9.18.50-1.amzn2023.0.4 >/dev/null &&
      exec bash /work/kvs-put-cdn-secret.sh "$@"' _ \
    --kvs-arn "${kvs_arn}" --secret-id "${secret_id}"
  echo "exit=$?"
)
```

끝나면 잔여 0 을 확인합니다. 이 실행이 받은 이미지(전에 없던 것)만
지웁니다.

```shell
docker ps -a -q --filter label=com.pokeclip.bench=kvs-put | wc -l   # 0
find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'pokeclip-kvs-creds.*' | wc -l   # 0
if [ "${had_image}" = no ]; then docker image rm "${image}"; fi
docker image inspect "${image}" >/dev/null 2>&1 && echo kept || echo removed
# removed(전에 있었으면 kept)
```

종료 코드는 위 표와 같습니다. 컨테이너 안에서도 3 이면 멈추고 kty 에게
보고합니다.

## VPC origin TLS 전제

media 배포의 media-alb 원본 도메인은 내부 ALB DNS 이름이라 내부 ALB
인증서(`*.pokeclip.com` · 서울 ACM)와 맞지 않습니다. 모든 media-alb
비헤이비어가 Host = `media.pokeclip.com` 을 원본에 넘기므로 CloudFront 는
그 Host 로 인증서 이름을 맞춥니다(AWS 문서 using-https-cloudfront-to-custom-origin
· cnames-and-https-requirements). 이 규칙이 VPC origin 경로에도 그대로
적용되는지는 첫 edge apply 뒤 원본까지 가는 첫 요청인 T6b 의 end-signal ·
T11 404 에서 판정합니다([prod/edge](../prod/edge/README.md) 「VPC origin TLS
전제」 와 같은 뜻).

*   T6b 의 end-signal · T11 404 · 런타임 뒤 `/live` 에서 **502 가 나면 원인
    1순위**입니다.
*   502 가 나도 원본 프로토콜을 http-only 로 내리지 않습니다 — 내리면
    Bearer 가 VPC 안에서 평문으로 갑니다(kty 결정 없이 금지).
*   그때 VPC 안(Media 상자 — SSM · 실행 직전 승인)에서 내부 ALB 인증서의
    이름을 봅니다. `*.pokeclip.com` 이 나와야 합니다.

    ```shell
    openssl s_client -connect <내부 ALB DNS>:443 \
      -servername media.pokeclip.com </dev/null 2>/dev/null \
      | openssl x509 -noout -ext subjectAltName
    ```

## 런타임 뒤 시험의 추가 항목

*   **T1**(계획 29_re):
    *   목록 종류별 `Cache-Control` 을 원본 루프백(SSM 판정 줄)과 시청자
        응답에 나란히 기록합니다.
    *   조각 · 파트 · init 를 두 번째로 요청한 시청자에게 `x-cache: Hit from
        cloudfront` 가 오고 `Age` 가 늘어나는지 봅니다.
    *   m3u8 의 `x-cache` 를 기록합니다.
    *   `Origin: https://app.pokeclip.com` 요청 → ACAO = app · ACAC = true.
        `Origin: https://example.invalid` → ACAO 없음.
*   **T2**: 「T2b 기본 거절」 의 런타임 뒤 항목을 함께 봅니다. 방송 쿠키의
    경로 우회는 「경로 우회 시험」 입니다(O8 개통 조건).
*   **T4**:
    *   두 플레이어를 동시에 붙여, 첫 요청은 Miss 이고 나머지는 Hit 인지
        봅니다(`x-edge-result-type`).
    *   없는 파트 URI 가 `Error from cloudfront` 를 받고, 1초 뒤 원본에 다시
        요청하는지 봅니다(오류 캐싱 1초).
*   **T7**:
    *   재생 성공 · 원본 요청 경로에 `/live` 없음 · `_HLS_*` 쿼리 보존을
        봅니다(시험표 T7).
    *   live-auth 는 `.m3u8` · `.mp4` 로 끝나는(대소문자 구분) `/live`
        요청만 원본에 넘깁니다. MediaMTX 는 그 밖의 경로(끝 슬래시 없는 방송
        경로 등)에 리다이렉트를 내고, ALB 경로 변환 때문에 그 `Location` 에
        `/live` 가 빠집니다(2026-11-04 O8a 제자리 전환 때 관찰). 그래서
        응답을 고치지 않고(viewer-response 보정 안 함) 요청 단계에서
        막습니다.
    *   아래 여덟 경로가 모두 `server: CloudFront` 403 이고 `Location` 헤더가
        0건이어야 통과입니다. 리다이렉트 코드는 소스상 302 이고 그때 기록은
        301 입니다 — 판정은 코드와 상관없이 「`Location` 0」 입니다.
    *   `/live/<A>` · `/live/favicon.ico` 는 쿠키 정책 Resource
        (`/live/<A>/*`) 밖이라 서명 거절일 수 있습니다. 판정은 같습니다.
    *   `x.ts` 가 막히는 것은 lowLatency(fMP4 조각) 설정이 전제라서입니다.
        `hlsVariant` 를 mpegts 로 바꾸면 `.ts` 조각이 막혀 재생이 깨집니다.
    *   `jar_a` 는 「경로 우회 시험」 과 같은 발급으로 새로 만듭니다(그
        시험 끝에 지웠습니다).
    *   **앞뒤 양성 대조**: 여덟 경로 앞과 뒤에 같은 `jar_a` 로 A 자신의
        `/live/<A>/index.m3u8` 를 요청해 둘 다 200 이어야 합니다. 하나라도
        200 이 아니면(쿠키 무효 · A 송출 끊김 · 통신 실패) 여덟 경로의 403 이
        경로 규칙 때문인지 가를 수 없으므로 그 회차는 판정을 적지 않습니다.
        `control` 줄은 헤더를 싣지 않아 `Location` 건수에 들지 않습니다.
    *   판정 줄은 명령 끝의 넷입니다 — `control 200` 2건 · ` 403` 으로 끝나는
        경로 줄 8건 · `server: CloudFront` 8건 · `Location` 0건. 넷이 모두
        맞아야 통과입니다. `control` 줄은 헤더를 싣지 않아 `server` 건수에도
        들지 않습니다.

    ```shell
    t7_control() {
      curl -b "${jar_a}" --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
        -o /dev/null -w "control %{http_code}\n" \
        "https://media.pokeclip.com/live/${a_id}/index.m3u8"
    }
    t7_control | tee "${evidence}/t7-deny.txt"
    for req_path in "/live/${a_id}" "/live/${a_id}/" "/live/${a_id}/nope" \
      "/live/${a_id}/hls.min.js" /live/favicon.ico \
      "/live/${a_id}/index.M3U8" "/live/${a_id}/x.ts" "/live/${a_id}/x.mp"; do
      curl -b "${jar_a}" --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
        -o /dev/null -D - -w "${req_path} %{http_code}\n" \
        "https://media.pokeclip.com${req_path}" |
        grep -iE '^server:|^location:|^/live/'
    done | tee -a "${evidence}/t7-deny.txt"
    t7_control | tee -a "${evidence}/t7-deny.txt"
    grep -cx 'control 200' "${evidence}/t7-deny.txt"   # 2
    grep -c ' 403$' "${evidence}/t7-deny.txt"          # 8
    grep -ci '^server: cloudfront' "${evidence}/t7-deny.txt"   # 8
    grep -ci '^location:' "${evidence}/t7-deny.txt"    # 0
    rm -f "${jar_a}" && test ! -e "${jar_a}" && echo removed
    ```
*   **T10**:
    *   새 브라우저 세션에서 VOD · 편집기 직접 진입 각각으로 판정합니다.
        쿠키 발급 → `/playback/{id}/list` 200 → `/get` mp4 재생.
    *   개발자 도구: ACAO = app · ACAC = true · OPTIONS 0건(생기면 G14 —
        [런타임 트랙 계약](../CONTRACT-runtime.md) 「rewind 버킷 CORS」) ·
        CORS 오류 0건.
    *   쿠키 없음 403 · 다른 방송 403 · 경로 번호 ≠ 쿼리 path 403. VOD 목록
        연속 로드(G6).
    *   루프백 출처 한정 playback 항목(설계 3-3)이 실제로 동작하는지도 여기서
        봅니다.
    *   2번 · 3번 배포 전이면 「보류」 로 기록합니다.
*   **T11**:
    *   제어면 서버(I1-6) 배포 전에는 위 「T11 404」 그대로입니다.
    *   배포 뒤(`control_upstream` 반영 교체 뒤)에는 유효 요청 202 입니다
        (POK-322 — 후속). 그 전이면 「보류」 로 기록합니다.
    *   표준 로그 · nginx 로그에 Authorization 0건(아래 「비밀 · 쿠키 0건 확인」).
    *   내부 ALB → Media HTTP 는 계약4 개정본(팀 위키 PR 171) 기준으로
        정상입니다.
*   **T12 ⓑ**(ⓐ 는 위 「엣지만 시험」):
    *   회차 라우트 배포 뒤 실재 시험 회차의 경로로 200 ·
        `application/json` · 키 다섯을 봅니다. 배포 전이면 「보류」 로
        기록합니다.
    *   **ⓑ 통과가 회차 라우트를 여는 조건입니다.**
    *   실패 때 웹이 하는 일: 엣지 404 → 「아직 모름」 → 360초 뒤 녹화(VOD
        제안 없음) · JSON 아닌 200 · 403 · 5xx → `unavailable` → 20분 시한
        뒤 녹화입니다.

## WAF 속도 제한

CLOUDFRONT WAF 의 속도 규칙 기본값 20000(5분 · IP 하나)은 추정입니다.
시청자 한 명이 5분에 1,200–2,400건을 보내고(LL-HLS 블로킹 리로드 · 파트),
같은 IP 뒤에 시청자가 여럿일 수 있다는 가정입니다(kty 2026-10-24).

*   T1 · T4 때 시청자 하나 · 둘의 5분 요청 수를 표준 로그로 세어 기록합니다.
*   WAF 로그(`aws-waf-logs-pokeclip-prod-cloudfront` · 차단만)에 시험 IP 의
    속도 규칙 차단이 있으면 실패로 기록합니다.
*   조정은 값만 바꾸는 edge apply 입니다(`waf_rate_limit` — 실행 직전 승인 ·
    plan 「WAF 규칙 값 변경만 · 그 밖 0」 확인).

## 비밀 · 쿠키 0건 확인

시험이 남긴 로그 · 기록에 서명 쿠키 값과 Authorization 값이 0건이어야
합니다.

*   **표준 로그**(`pokeclip-prod-media-access` · us-east-1): 필드 목록에
    쿠키 · Authorization 성격의 필드를 넣지 않았습니다(`modules/cdn_media`
    의 `record_fields`). 그래도 시험 뒤 로그에 쿠키 이름
    (`CloudFront-Policy` · `CloudFront-Signature` · `CloudFront-Key-Pair-Id`)과
    `Bearer` 가 0건인지 셉니다.
*   **WAF 로그**: `authorization` · `cookie` 헤더는 가려 남깁니다(값 대신
    `REDACTED`). 같은 이름들이 값으로 0건인지 셉니다.
*   **WAF 요청 샘플**: 웹 ACL 데이터 보호(POK-459)가 apply 되면 요청
    샘플에서도 `authorization` · `cookie` 헤더 값이 `REDACTED` 로 바뀝니다.
    다만 아래 둘을 실측으로 확인하기 전까지는 시험 중 · 뒤에 WAF 콘솔의
    샘플 요청을 열지 않습니다. 그 실측이 POK-459 완료 조건 「apply 뒤 콘솔
    샘플 실측」 입니다.
    *   (가) 헤더 이름 대소문자 — HTTP/1.1 클라이언트가 보내는
        `Authorization` · `Cookie` 도 가려지는가.
    *   (나) `cookie` 헤더를 보호할 때 쿠키 이름(`CloudFront-Policy` 등)이
        남는가.

    실측은 실제 비밀이 아닌 고정 표식 값으로, 앞 3시간 안에 서명 쿠키를 쓴
    시험이 없을 때 합니다(샘플은 최근 3시간을 보관). 아래 두 요청을 보내고
    샘플에서 두 헤더 값이 `REDACTED` 인지, `x` 같은 쿠키 이름이 남는지
    봅니다. 대소문자를 구별하는 것으로 나오면 `protected_headers` 에
    변형(`Authorization` · `Cookie`)을 더하는 후속을 엽니다.

    ```shell
    curl --http1.1 --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
      -o /dev/null -w '%{http_code}\n' \
      -H 'Authorization: Bearer <표식>' -H 'Cookie: x=<표식>' \
      https://media.pokeclip.com/<경로>
    curl --http2 --resolve "media.pokeclip.com:443:${edge_ip}" -sS \
      -o /dev/null -w '%{http_code}\n' \
      -H 'authorization: Bearer <표식>' -H 'cookie: x=<표식>' \
      https://media.pokeclip.com/<경로>
    ```

*   **함수 시험**: live-auth 에 `aws cloudfront test-function` · 콘솔 함수
    시험 탭을 쓰지 않습니다(출력에 KVS 값이 든 Authorization 이 찍힙니다).
    dvr-floor 는 씁니다 — 연결된 KVS 에 하한 수만 있고 사건에 쿠키 ·
    Authorization 이 없습니다(「판정 B′」).
*   **원본 쪽**(nginx · MediaMTX · 사이드카 · 저널): O7f 의 9 의
    `secret-count.sh` 결과(`SECRET COUNT … 0`)를 씁니다.
*   **증거 디렉터리**: 이 런북이 남긴 파일(`curl -D -` 헤더 포함)에 쿠키 ·
    Bearer 값이 없는지 봅니다. 헤더를 저장하는 명령은 응답 헤더만 남기고
    요청 헤더를 찍지 않습니다(`-v` 쓰지 않음). 「경로 우회 시험」 의 쿠키
    파일은 지워졌어야 합니다(`removed`).

```shell
aws logs filter-log-events --region us-east-1 \
  --log-group-name pokeclip-prod-media-access \
  --filter-pattern '?"CloudFront-Signature" ?"CloudFront-Policy" ?"Bearer"' \
  --query 'length(events)'   # 0
grep -rlE 'CloudFront-(Signature|Policy)=|Bearer ' "${evidence}" | wc -l   # 0
```

## /dvr 하한 시험(POK-525)

/dvr 하한 함수 `pokeclip-prod-dvr-floor`(dvr-floor — 되감기 조각 가운데 그
회차 하한보다 앞 seq 를 404 로 막는 `/dvr/*` CloudFront 함수)를 prod 에 켜는
걸음과 시험입니다. 켜는 것은 두 단 apply 입니다. 1단은 함수를 게시만 하고
(`dvr_floor_enabled = false`), 2단은 `/dvr/*` 에 붙입니다(`true`). 두 단
사이에 함수만 시험하고(판정 B′ · K), 붙인 뒤 실제 엣지로 시험합니다(판정
C′). 걸음마다 실행 직전에 kty 가 승인합니다. 근거는 계획 Ⓓ 3절(걸음
6.0–6.6 · 롤백)과 [prod/edge](../prod/edge/README.md) 「/dvr 하한(POK-525)」
입니다.

용어:

*   **하한(floor)**: 회차마다 사이드카 Writer(media 사이드카 안에서 하한을
    원장 표와 KVS 에 쓰는 몫)가 올리는 seq 입니다. KVS 키는
    `{스트림}/{회차}`, 값은 십진 수 하나입니다. 회차가 닫히면 Writer 가 키를
    지웁니다([media README](/media/README.md) 「/dvr 하한 Writer」).
*   **판정 B′ · K · C′**: B′ 는 1단 뒤 게시된 함수를 `test-function`(AWS 가
    함수를 배포에 붙이지 않고 사건 하나로 한 번 돌려 주는 API)으로 보는
    판정, K 는 경보 셋의 지표 필터 · 알림 경로 판정, C′ 는 2단 뒤 실제 엣지
    요청으로 보는 판정입니다.
*   **계산 사용률**: `test-function` 결과의 `ComputeUtilization`(함수가 쓸 수
    있는 최대 실행 시간 가운데 실제로 쓴 몫, 0–100)입니다. AWS 는 71 이상이면
    배포에 붙였을 때 스로틀(실행 거부)할 수 있다고 합니다(AWS 개발자 안내서
    [Test a function](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/test-function.html)
    — 2026-11-11 원문 확인).
*   **fail-open**: 판단 재료(키 · 값 · KVS 읽기)를 못 얻으면 막지 않고
    넘기는 동작입니다. 하한은 되감기 창 정책이고 1차 통제는 함수보다 먼저
    검사되는 서명 쿠키라 이렇게 둡니다(kty K5 (가)).
*   **관측 불충분**: 함수 로그는 best-effort(늦게 오거나 드물게는 아예 오지
    않을 수 있음)로 전달됩니다(AWS 개발자 안내서
    [Edge function logs](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/edge-functions-logs.html)
    — 2026-11-11 원문 확인). 기한 안에 로그가 다 오지 않은 것은 실패가
    아니라 관측 불충분이고, 다시 잽니다.

### /dvr 하한 셸 전제

아래 블록은 한 셸에서 이어 갑니다. 앞 블록이 만든 변수 · 함수를 뒤 블록이
씁니다. 새 셸이면 아래 표의 이름을 그 자리에서 다시 만듭니다. 함수는 그
블록의 정의 부분만 붙여 넣습니다 — 같은 블록의 `apply` · 시험 줄은 다시
돌리지 않습니다.

| 이름 | 만드는 곳 |
|---|---|
| `evidence` | 「접속법」 1 — 새 셸이면 새로 만들지 않고 쓰던 증거 디렉터리 경로를 다시 넣습니다 |
| `edge_ip` | 「접속법」 2 — C′ · 끄기 확인 직전에 다시 풉니다 |
| `edge_plan` | 「/dvr 하한 apply」 |
| `fn` · `stream` · `round` · `fn_etag` · `dvr_case` | 「판정 B′」 첫 블록 |
| `floor` · `seg` | 「판정 B′」 둘째 블록(`floor` 는 `deny0` 의 `floor=` 값) |
| `topic_arn` · `alarms` · `log_group` | 「판정 K」 |
| `jar` | 「판정 C′」 머리 — T2 의 `jar_a` 와 같은 발급 |
| `c_since_ms` · `c_since` · `c_get` · `c_val` · `c_line` · `below` | 「판정 C′」 첫 블록 |
| `e14_seq` · `e14_pad` · `e14` | 「판정 C′」 의 「E14 경로 우회」 블록 |

### /dvr 하한 걸음

| 걸음 | 무엇 | 선행 | 통과 |
|---|---|---|---|
| 6.0 | 선행 확인 | — | 다섯 다 참: ① prod/runtime 을 POK-525 판으로 apply(출력 `edge_bounds_kvs_arn` 있음) ② [Media 교체 런북](media-swap.md) 4.10 시험 방송 통과 ③ POK-528(하한 404 를 받는 웹)의 prod 반영 — 웹 배포 일정이라 kty 가 확인 ④ 실험대 (3) · (4) 통과(아래 「/dvr 하한 실험대 결과」) ⑤ 이 함수를 더한 PR 의 종결 |
| 6.1 | 그 PR 머지 | 6.0 | 1단 apply 바로 앞에 머지합니다(kty K-D3 (가) — 머지 뒤 첫 edge apply 는 스위치가 꺼져 있어도 함수를 만듭니다). PR 종결 뒤 develop 이 움직였으면 머지 전에 다시 봅니다 — 충돌이 나거나 새 커밋이 `infra/terraform/prod/edge` · `modules/cdn_media` · 공유 골든을 건드렸으면 머지하지 않고 kty 에게 보고 |
| 6.2 | 단계 직전 점검 | 6.1 | `scp-recheck.sh O7f` · `principal-check.sh O7f` 거부 0([단계 직전 점검 런북](stage-precheck.md)) |
| 6.3 | 1단 apply(`false`) | 6.2 | plan 범위 = 아래 「1단 기대 목록」 · 배포 변경 0 |
| 6.4 | 판정 B′ · 판정 K | 6.3 · 1단 뒤 주제 판정 · 새 시험 방송 6분 넘김 | 아래 「판정 B′」 · 「판정 K」 |
| 6.5 | 2단 apply(`true`) | 6.4 | plan 범위 = media 배포 변경 한 줄 · apply 끝(배포 Deployed) |
| 6.6 | 판정 C′ | 6.5 | 아래 「판정 C′」. 실패하면 곧바로 「/dvr 하한 끄기」 |

*   **1단 뒤 주제 판정**(6.3 뒤 · 6.4 앞): `principal-check.sh O7f` 를 한 번
    더 돌립니다. 6.2 때는 알림 주제가 없어 `edge-alarm-topic` 행을 정책
    없이 「자원 없음」 으로 판정했습니다([단계 직전 점검
    런북](stage-precheck.md) 한계 A13). 1단이 만든 주제 정책의 CloudWatch
    문장에는 조건 키(`aws:SourceArn` · `aws:SourceAccount` —
    `prod/edge/dvr_floor_alarms.tf`)가 있습니다. 시뮬레이터가 그 문맥 없이
    `MISSING_CONTEXT` 로 답하면 첫 줄이 `PRINCIPAL CHECK UNDECIDED` 가 됩니다.
    첫 줄이 `PASS` 가 아니면 6.4 로 가지 않고, 표준 출력의 표 행(자원 키 ·
    사유)을 kty 에게 회부합니다. 판정을 손으로 통과시키지 않습니다.
*   **6.4 의 시험 방송은 4.10 과 다른 새 방송입니다.** 송출 직전에 따로
    승인받습니다. 시험 키 스트림(`<test>` — 4.10 과 같은 시험 키의
    `ingestStreamId`)으로 6분 넘게 보낸 뒤 판정 B′ 를 합니다. Writer 는 첫
    발행 뒤 Margin(60초)이 지난 다음 올림 틱(5분 주기)에 하한을 올려, 키가
    생기기까지 늦어도 약 6분이 걸립니다.
*   **판정 C′ 도 열린 회차가 필요합니다.** 2단 apply 동안 방송을 이어 가도
    됩니다. 끊겼으면 C′ 앞에 새 시험 방송을 다시 따로 승인받습니다.
*   2단 뒤의 edge apply(표준 로그 끄기 · WAF 속도 조정 포함)는 모두
    `-var dvr_floor_enabled=true` 를 함께 넘깁니다. 빠뜨리면 plan 에 `/dvr/*`
    연결 제거가 끼어듭니다([prod/edge](../prod/edge/README.md)
    「/dvr 하한(POK-525)」).

### /dvr 하한 apply

apply 마다 plan 파일을 만들고, 바뀌는 관리 자원 목록이 그 걸음의 기대
목록과 같을 때만 apply 합니다([Media 교체 런북](media-swap.md) 「plan 범위」
와 같은 규칙). 하나라도 다르면 apply 하지 않고 kty 에게 보고합니다. prod/edge
를 `init -backend-config=backend.hcl` 로 연 체크아웃의 저장소 뿌리에서
돌리고, plan 파일은 증거 디렉터리에 둡니다(계정 ID 가 든 ARN 이 있습니다).

아래 `edge_plan` 은 plan 파일을 쓰고 바뀌는 자원 목록을 냅니다.

```shell
edge_plan() {  # edge_plan <true|false> <plan 이름>
  local dir=infra/terraform/prod/edge
  terraform -chdir="${dir}" plan -input=false \
    -var "dvr_floor_enabled=$1" -out="${evidence}/$2.plan" >/dev/null &&
    terraform -chdir="${dir}" show -json "${evidence}/$2.plan" |
    jq -r '.resource_changes[] | select(.mode == "managed")
        | select(.change.actions != ["no-op"])
        | "\(.change.actions | join(",")) \(.address)"'
}
edge_plan false dvr-stage1                    # 6.3 1단
terraform -chdir=infra/terraform/prod/edge apply "${evidence}/dvr-stage1.plan"
edge_plan true dvr-stage2                     # 6.5 2단
terraform -chdir=infra/terraform/prod/edge apply "${evidence}/dvr-stage2.plan"
```

| 걸음 | 기대 목록 |
|---|---|
| 1단 | `create module.media.aws_cloudfront_function.dvr_floor` · `create aws_cloudwatch_log_group.cloudfront_function["dvr_floor"]` · 경보 묶음 `create` 만 — 지표 필터 둘 · 경보 셋(`VALUE INVALID` · `KVS ERROR` · FunctionExecutionErrors) · us-east-1 알림 주제 · 그 주제 정책(`aws_cloudwatch_log_metric_filter.dvr_floor["value_invalid"]` · `["kvs_error"]` · `aws_cloudwatch_metric_alarm.dvr_floor_log["value_invalid"]` · `["kvs_error"]` · `aws_cloudwatch_metric_alarm.dvr_floor_execution_errors` · `aws_sns_topic.edge_alarms` · `aws_sns_topic_policy.edge_alarms`). `module.media.aws_cloudfront_distribution.this` 는 없어야 합니다 |
| 2단 | `update module.media.aws_cloudfront_distribution.this` 한 줄 |
| 끄기 | `update module.media.aws_cloudfront_distribution.this` 한 줄 |

apply 는 배포가 Deployed 가 될 때까지 기다립니다(provider 기본
`wait_for_deployment`). 2단 · 끄기는 몇 분 걸립니다.

### /dvr 하한 1단 도중 실패

1단 apply 가 도중에 멈추면 함수는 생겼는데 로그 그룹 · 경보 가운데 일부가
없는 상태가 남습니다. 스위치가 꺼져 있어 함수가 `/dvr/*` 에 붙지 않았으므로
시청자 영향은 없습니다 — 서두르지 않습니다.

1.  오류 원문의 자원 주소 · 오류 코드를 kty 에게 보고합니다. 값 · ARN 원문은
    옮기지 않습니다.
1.  갈래를 고릅니다.
    *   `AccessDenied` — 계획 3절 「SCP 멈춤 규칙」. 다시 시도하지 않습니다.
        `PublishFunction` 이나 함수의 KVS 연결에서 난 `AccessDenied` 는
        실험대 R6 이 재지 않은 갈래입니다(R6 은 Create · Update 만 실측 —
        아래 「/dvr 하한 실험대 결과」). 멈추고 그 동작 이름과 함께 kty 에게
        회부합니다.
    *   함수 로그 그룹의 `ResourceAlreadyExistsException` — 함수가 그룹보다
        먼저 로그를 낸 것입니다. [prod/edge](../prod/edge/README.md)
        「적용 순서(O7f)」 의 import(키 `dvr_floor`) 뒤 1단을 다시 plan 합니다.
        이 갈래는 dvr-floor 에서 미실측입니다. 1단에서는 함수가 `/dvr/*` 에
        붙지 않고 `test-function` 도 CloudWatch 로 로그를 보내지 않아(아래
        「판정 K」), 그룹보다 먼저 로그가 날 길이 확인된 것은 없습니다.
        README 의 기제(붙은 함수가 그룹보다 먼저 로그를 냄)를 따른
        대비입니다.
    *   그 밖(코드 · 설정 결함) — **고친 PR**(1순위)을 머지한 뒤 1단을 다시
        plan 합니다. **되돌림 PR**(이 함수를 더한 PR 을 되돌림)은 kty 가
        고릅니다. 되돌림 PR 의 plan 에는 이미 생긴 함수의 삭제가 끼는데, `O7f`
        묶음에 `cloudfront:DeleteFunction` 이 없어 그 apply 전에 kty 에게
        회부합니다.
1.  **그 사이의 다른 edge apply**(Budgets 경보의 「1차 대응 — 표준 로그
    끄기」 포함)는 plan 에 1단의 남은 생성이 끼어듭니다. `-target` 으로 원래
    목적의 자원만 고르지 않습니다 — 상태가 코드와 갈라진 채 남습니다. plan
    목록을 「원래 기대 목록」 과 「1단의 남은 생성」 둘로 나눠 kty 에게 보이고
    승인받아 함께 apply 합니다. 남은 생성이 같은 원인으로 다시 실패할 수
    있으므로, apply 뒤 plan 을 다시 돌려 원래 목적(예: 전달 자원 삭제)이
    끝났는지 봅니다.

### 판정 B′

1단 뒤 게시된(LIVE) prod 함수를 `test-function` 으로 갈래마다 세 번씩
돌립니다. 막는 갈래는 6.4 의 시험 방송 중 Writer 가 올린 실제 키로 봅니다.
**prod KVS 에 손으로 키를 넣지 않습니다**(E49 — 시험 키가 남으면 그 회차를
막습니다). 그래서 `VALUE INVALID`(값 이상) · `KVS ERROR`(KVS 읽기 실패)
갈래는 prod 에서 보지 않습니다 — 실험대 (3) · node 시험이 맡았습니다(아래
「/dvr 하한 실험대 결과」).

dvr-floor 는 `test-function` 을 써도 됩니다. 연결된 KVS(edge-bounds)에는
하한 수만 있고, 아래 사건에는 쿠키 · Authorization 이 없습니다(live-auth
와 다릅니다 — 「비밀 · 쿠키 0건 확인」 의 「함수 시험」).

**키 찾기**: 시험 방송을 6분 넘게 보낸 뒤, 런타임 1단-가 런북
[POK-525 원장 질의](runtime-1a.md) 를 「한 스트림만 보기」(`<test>`)로
돌립니다. `open` 줄의 `sample` 에 `<test>/<회차>` 가 나와야 합니다. 없으면 5분
뒤 다시 보고, 그래도 없으면 B 사이드카 저널의 `edge_bounds_write_failing`
(media README 「/dvr 하한 신호」)을 봅니다.

아래 `dvr_case` 는 사건 JSON 을 만들어 세 번 돌리고, 한 번에 판정 줄 하나를
냅니다. 줄에는 결과(`pass` 또는 `status=404`) · 사용률 · 함수 오류 · 404
머리 넷 · 함수 로그가 실립니다.

```shell
fn=pokeclip-prod-dvr-floor
stream='<test>'
round='<회차>'
fn_etag="$(aws cloudfront describe-function --name "${fn}" --stage LIVE \
  --query ETag --output text)"
dvr_case() {  # dvr_case <이름> <메서드> <경로>
  local k
  jq -n --arg m "$2" --arg u "$3" '{version: "1.0",
      context: {eventType: "viewer-request"}, viewer: {ip: "198.51.100.7"},
      request: {method: $m, uri: $u, querystring: {}, cookies: {},
        headers: {host: {value: "media.pokeclip.com"},
          origin: {value: "https://app.pokeclip.com"}}}}' \
    > "${evidence}/dvr-$1.event.json" || return 1
  for k in 1 2 3; do
    if ! aws cloudfront test-function --name "${fn}" --if-match "${fn_etag}" \
      --stage LIVE --event-object "fileb://${evidence}/dvr-$1.event.json" \
      --output json > "${evidence}/dvr-$1.r${k}.json"; then
      echo "$1 r${k} call-failed"
      continue
    fi
    jq -r --arg id "$1 r${k}" '.TestResult
      | (.FunctionOutput // "{}" | fromjson) as $o
      | ($o.response.headers // {}) as $h
      | [$id,
         (if $o.response then "status=\($o.response.statusCode)"
          else "pass" end),
         "util=\(.ComputeUtilization)",
         "err=\(.FunctionErrorMessage // "")",
         "cc=\($h["cache-control"].value // "-")",
         "acao=\($h["access-control-allow-origin"].value // "-")",
         "acac=\($h["access-control-allow-credentials"].value // "-")",
         "vary=\($h.vary.value // "-")",
         "logs=\(.FunctionExecutionLogs // [] | join("|"))"]
      | join(" ")' "${evidence}/dvr-$1.r${k}.json"
  done
}
```

B′ 줄이 안 나오고 jq 오류만 남으면 함수 오류로 `FunctionOutput` 이 빈
문자열이라 `fromjson` 이 멈춘 것입니다. 그 회차 응답의
`FunctionErrorMessage` 를 보고, B′ 실패로 적습니다.

```shell
jq -r .TestResult.FunctionErrorMessage "${evidence}/dvr-<이름>.r<k>.json"
```

첫 묶음은 하한 값 없이 갈래를 봅니다. `deny0`(seq 0)은 하한이 1 이상이라
키가 있으면 늘 막히고, 그 로그의 `floor=` 가 지금 하한입니다.

```shell
long="$(printf '%0129d' 0 | tr 0 a)"          # 129자 성분 — 정규식 밖
{
  dvr_case options OPTIONS "/dvr/${stream}/${round}/seg/000000.m4s"
  dvr_case nokey GET "/dvr/${stream}/pcnokey/seg/000000.m4s"
  dvr_case old GET "/dvr/${stream}/seg/000000.m4s"
  dvr_case long129 GET "/dvr/${long}/${round}/seg/000000.m4s"
  dvr_case malformed GET "/dvr/${stream}/${round}/seg/../seg/000000.m4s"
  dvr_case deny0 GET "/dvr/${stream}/${round}/seg/000000.m4s"
} | tee "${evidence}/dvr-b1.txt"
```

둘째 묶음은 `deny0` 로그의 하한 `<F>` 앞뒤를 봅니다. 끝의 `deny0b` 는 그
사이 하한이 바뀌지 않았는지 봅니다.

```shell
floor='<F>'
seg() { printf '/dvr/%s/%s/seg/%06d.m4s' "${stream}" "${round}" "$1"; }
{
  dvr_case below GET "$(seg "$((floor - 1))")"
  dvr_case eq GET "$(seg "${floor}")"
  dvr_case above GET "$(seg "$((floor + 1))")"
  dvr_case deny0b GET "$(seg 0)"
} | tee "${evidence}/dvr-b2.txt"
grep -ho 'util=[0-9]*' "${evidence}"/dvr-b[12].txt | cut -d= -f2 | sort -n |
  tail -1                                      # 최대 사용률
```

통과 조건입니다. 사건마다 세 줄이 모두 같아야 하고, 모든 줄이 `err=` 빈
값입니다. **404 머리 넷**은 `cc=no-store` · `acao=https://app.pokeclip.com` ·
`acac=true` · `vary=origin` 입니다.

| 사건 | 통과 줄 |
|---|---|
| `options` · `nokey` · `old` · `long129` · `eq` · `above` | `pass` · `logs=` 빈 값 · 머리 칸 `-` |
| `malformed` | `status=404` · 404 머리 넷 · `logs=DVR FLOOR MALFORMED` |
| `deny0` · `deny0b` | `status=404` · 404 머리 넷 · `logs=DVR FLOOR DENY <test> <회차> 0 floor=<F>` — 두 사건의 `<F>` 가 같음 |
| `below` | `status=404` · 404 머리 넷 · `logs=DVR FLOOR DENY <test> <회차> <F−1> floor=<F>` |

*   **`deny0` 가 `pass` 면** 키가 아직 엣지에 퍼지지 않은 것일 수 있습니다
    (KVS 값 반영은 1분쯤 걸립니다 — 81 실험대 E4 의 안정 37–61초).
    10초 간격으로 여섯 번까지 `deny0` 만 다시 돌립니다. 그래도 `pass` 면
    멈추고 B 사이드카의 `edge_bounds_write_failing` 을 kty 에게 보고합니다.
*   **`deny0b` 의 `<F>` 가 다르면** 그 사이 하한이 올랐습니다. 둘째 묶음을
    새 `<F>` 로 다시 돌립니다.
*   **`call-failed`** 는 호출 실패입니다. `AccessDenied` 면 계획 3절 「SCP
    멈춤 규칙」 입니다.
*   **사용률 합격선**(kty (가) — 실험대 결정 1): 최대 50 이하 합격 · 51–70
    조건부(2단 apply 전에 kty 가 판단) · 71 이상 불합격(2단 apply 하지 않고
    함수 최적화 PR). 이 합격선은 `test-function` 값에만 씁니다. 실험대 (3) 은
    최대 9 였습니다.
*   **B′ 실패**(통과 줄과 다름 · 불합격)면 2단으로 가지 않습니다. 함수가
    붙지 않아 시청자 영향은 없습니다 — 원인을 kty 에게 보고하고, 코드
    결함이면 고친 PR 뒤 6.3 부터 다시 합니다.

### 판정 K

경보 셋이 맞는 줄을 세고 알림이 실제로 오는지 봅니다. `test-function` 은
CloudWatch 로그를 내지 않아(AWS 개발자 안내서 Edge function logs —
2026-11-11 원문 확인) B′ 로는 지표 필터가 움직이지 않습니다. 그래서 필터는
`aws logs test-metric-filter`(표본 줄에 패턴을 대 보는 API)로, 알림 경로는
`aws cloudwatch set-alarm-state`(경보 상태를 손으로 바꾸는 API)로 봅니다.
경보 이름 · 주제 출력 이름은 `prod/edge/dvr_floor_alarms.tf` 의 것입니다.

| 경보 | 무엇을 세나 |
|---|---|
| `pokeclip-prod-dvr-floor-value-invalid` | 함수 로그 `DVR FLOOR VALUE INVALID` 줄 — 5분 합 1 이상(kty K-D4) |
| `pokeclip-prod-dvr-floor-kvs-error` | 함수 로그 `DVR FLOOR KVS ERROR` 줄 — 5분 합 1 이상(K-D4) |
| `pokeclip-prod-dvr-floor-execution-errors` | AWS/CloudFront 기본 지표 FunctionExecutionErrors(잡히지 않은 예외 — `/dvr` 503) · 차원 `FunctionName=pokeclip-prod-dvr-floor` · `Region=Global`(kty K-D5 (a)) |

**1. 알림 구독**(kty K-D1 (가) — 처음 한 번 · 주제를 다시 만든 뒤에도).
알림 주제는 Terraform 이 만들고, 이메일 구독은 저장소 밖에서 kty 가 CLI 로
한 번 겁니다. 구독은 Terraform 상태 밖이라 주제가 교체되거나 계정이 지워진
뒤 다시 만들면 다시 겁니다. 메일 주소는 명령에만 넣고 저장소 · 증거 · 채팅에
옮기지 않습니다. 이 걸음(구독 걸기 · 확인된 구독 세기)은 kty 가 저장소
밖에서 하는 K-D1 단계이고, `O7f` 묶음이 아닌 kty 자격으로 합니다. 필요
권한은 kty IAM 사용자의 `sns:Subscribe` ·
`sns:ListSubscriptionsByTopic` 입니다.

```shell
topic_output=edge_alarm_topic_arn
topic_arn="$(terraform -chdir=infra/terraform/prod/edge \
  output -raw "${topic_output}")"
aws sns subscribe --region us-east-1 --topic-arn "${topic_arn}" \
  --protocol email --notification-endpoint '<kty 메일 주소>' \
  --query SubscriptionArn --output text      # pending confirmation
```

받은 확인 메일의 링크를 누른 뒤 확인된 구독 수가 1 이상인지 봅니다.

```shell
confirmed="length(Subscriptions[?Protocol=='email'"
confirmed+=" && SubscriptionArn!='PendingConfirmation'])"
aws sns list-subscriptions-by-topic --region us-east-1 \
  --topic-arn "${topic_arn}" --query "${confirmed}"   # 1 이상
```

**2. 지표 필터**: 함수 로그 그룹의 필터마다 표본 다섯 줄에 패턴을 대 봅니다.
표본은 실제 로그 꼴(앞에 요청 ID 가 붙음)입니다.

```shell
log_group=/aws/cloudfront/function/pokeclip-prod-dvr-floor
aws logs describe-metric-filters --region us-east-1 \
  --log-group-name "${log_group}" --output json |
  jq -r '.metricFilters[] | [.filterName, .filterPattern,
      .metricTransformations[0].metricNamespace,
      .metricTransformations[0].metricName] | join(" ")' |
  tee "${evidence}/dvr-k-filters.txt"
aws logs describe-metric-filters --region us-east-1 \
  --log-group-name "${log_group}" --output json |
  jq -r '.metricFilters[].filterPattern' |
  while IFS= read -r pattern; do
    printf '%s => ' "${pattern}"
    aws logs test-metric-filter --region us-east-1 \
      --filter-pattern "${pattern}" \
      --log-event-messages 'req1 DVR FLOOR VALUE INVALID s r' \
      'req2 DVR FLOOR KVS ERROR s r' 'req3 DVR FLOOR DENY s r 0 floor=3' \
      'req4 DVR FLOOR MALFORMED' 'req5 START DistributionID: E0000000000000' \
      --output json | jq -c '[.matches[].eventMessage]'
  done | tee "${evidence}/dvr-k-match.txt"
```

통과: 필터 둘이고, `VALUE INVALID` 필터는 `req1` 줄 하나만, `KVS ERROR`
필터는 `req2` 줄 하나만 고릅니다. 패턴 글자는 함수 상수(`dvr-floor.js` 의
`VALUE_INVALID_LOG` · `KVS_ERROR_LOG`)와 같습니다.

**3. 경보 설정 · 알림 경로**: 경보 셋의 설정을 읽고, 하나씩 `ALARM` 으로
바꿔 메일이 오는지 본 뒤 `OK` 로 되돌립니다.

```shell
alarms=(pokeclip-prod-dvr-floor-value-invalid
  pokeclip-prod-dvr-floor-kvs-error
  pokeclip-prod-dvr-floor-execution-errors)
aws cloudwatch describe-alarms --region us-east-1 --alarm-names "${alarms[@]}" \
  --query "MetricAlarms[].[AlarmName, Namespace, MetricName,
    join(',', Dimensions[].Value), Statistic, Period, Threshold,
    ComparisonOperator, TreatMissingData, StateValue, length(AlarmActions)]" \
  --output text | tee "${evidence}/dvr-k-alarms.txt"
for alarm in "${alarms[@]}"; do
  aws cloudwatch set-alarm-state --region us-east-1 --alarm-name "${alarm}" \
    --state-value ALARM --state-reason 'POK-525 판정 K 알림 경로 시험'
done
```

*   설정 통과: 세 줄 · 로그 경보 둘의 네임스페이스 · 지표 이름이 위 필터의
    것과 같음 · `Sum` · `300` · `1.0` · `GreaterThanOrEqualToThreshold` ·
    `notBreaching`(K-D4) · FunctionExecutionErrors 경보는 `AWS/CloudFront` ·
    차원 `pokeclip-prod-dvr-floor,Global` · 알림 동작 수 1 이상.
*   알림 통과: 경보마다 메일 한 통(제목에 경보 이름). 받은 시각을 적습니다.
    15분 안에 오지 않으면 구독(1)부터 다시 봅니다.

메일을 다 받으면 셋 다 `OK` 로 되돌리고 상태를 다시 읽습니다.

```shell
for alarm in "${alarms[@]}"; do
  aws cloudwatch set-alarm-state --region us-east-1 --alarm-name "${alarm}" \
    --state-value OK --state-reason 'POK-525 판정 K 끝 — OK 로 되돌림'
done
aws cloudwatch describe-alarms --region us-east-1 --alarm-names "${alarms[@]}" \
  --query 'MetricAlarms[].StateValue' --output text   # OK OK OK
```

K 가 실패해도 시청자 영향은 없습니다(경보만의 문제). 2단으로 갈지는 kty 가
정합니다. 고치는 것은 문턱 · 패턴 · 주제를 고치는 PR 입니다.

### 판정 C′

2단 뒤 실제 엣지에 시험 방송의 서명 쿠키와 앱 출처(`Origin:
https://app.pokeclip.com`)를 실어 요청합니다. 요청은 「접속법」 의 `edge_ip`
를, 판정 B′ 와 같은 셸의 `stream` · `round` · `floor` · `seg` 를 씁니다.
`jar` 는 시험 방송의 서명 쿠키 셋을 담은 쿠키 파일입니다(「경로
우회 시험」 T2 의 `jar_a` 와 같은 발급 · `mktemp` 0600 · 증거 디렉터리 밖 ·
시험 뒤 지움). C′ 직전에 판정 B′ 의 `deny0` 를 한 번 더 돌려 그 `floor=` 값을 `floor` 에
다시 넣습니다.

**실패하면 곧바로 「/dvr 하한 끄기」 입니다.** 단, 아래 DENY 로그의 「관측
불충분」 은 실패가 아닙니다.

아래 `c_get` 은 요청 하나를 보내 머리를 남기고, `c_line` 은 판정에 쓰는
머리를 한 줄로 냅니다. `acao` 칸에 값이 둘 이상이면 쉼표로 이어져 나옵니다
(머리 두 벌 — E48).

```shell
c_since_ms="$(($(date -u +%s) * 1000))"
c_since="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
c_get() {  # c_get <라벨> <경로>
  # 쿠키 파일이 없으면 curl 은 쿠키 없이 보내고, 서명 거절 403 이 「함수
  # 생성 아님」 판정을 거짓으로 통과시킨다 — 그래서 없으면 멈춘다.
  test -s "${jar:-}" ||
    { echo "$1 jar 없음 — T2 와 같은 발급으로 다시 만드세요"; return 1; }
  curl --path-as-is -b "${jar}" -H 'Origin: https://app.pokeclip.com' \
    --resolve "media.pokeclip.com:443:${edge_ip}" -sS -o /dev/null \
    -D "${evidence}/dvr-c-$1.h" -w "$1 %{http_code}" \
    "https://media.pokeclip.com$2"
  c_line "$1"
}
c_val() {  # c_val <머리 이름> <라벨>
  grep -i "^$1:" "${evidence}/dvr-c-$2.h" | tr -d '\r' | cut -d' ' -f2- |
    paste -sd, -
}
c_line() {  # c_line <라벨>
  printf ' acao=%s acac=%s cc=%s xcache=%s\n' \
    "$(c_val access-control-allow-origin "$1")" \
    "$(c_val access-control-allow-credentials "$1")" \
    "$(c_val cache-control "$1")" "$(c_val x-cache "$1")"
}
below="$(seg "$((floor - 1))")"
{
  c_get below1 "${below}"
  c_get below2 "${below}"
  c_get below3 "${below}"
  c_get eq "$(seg "${floor}")"
  c_get old "/dvr/${stream}/seg/<옛 꼴 seq>.m4s"
  c_get live "/live/${stream}/index.m3u8"
} | tee "${evidence}/dvr-c.txt"
```

| 라벨 | 통과 |
|---|---|
| `below1`–`below3` | `404` · `xcache=FunctionGeneratedResponse from cloudfront` · `acao=https://app.pokeclip.com`(한 벌) · `acac=true`(한 벌) · `cc=no-store` |
| `eq` | `200` · `acao` · `acac` 한 벌 · `xcache` 가 함수 생성이 아님 |
| `old` | `xcache` 가 함수 생성이 아님(함수 통과). 4.2 루프백이 남긴 옛 꼴 키가 있으면 `200`, 없으면 버킷의 `403`(`s3:ListBucket` 이 없어 없는 키도 403) |
| `live` | `200`(`/live` 무변 — 방송 중) |

**E14 경로 우회**: 우회 경로가 가리키는 하한 아래 조각이 버킷에 있어야
시험이 뜻을 가집니다. 조각이 없으면 우회가 원본까지 가도 버킷 `403`
(`s3:ListBucket` 이 없어 없는 키도 403)이라 거짓 통과입니다. 그래서 조각이
있다고 확인된 seq 를 하한이 넘어선 뒤 그 seq 로 시험합니다.

1.  `e14_seq` 는 위 `eq` 의 seq(`floor`)입니다. `eq` 가 `200` 이 아니면
    조각이 확인되지 않은 것이라 **E14 시험 무효**입니다(통과로 적지 않고
    kty 에게 보고).
1.  하한이 `e14_seq` 를 넘기를 기다립니다. Writer 는 올림 틱(5분 주기)에
    하한을 올립니다. 1분마다 판정 B′ 의 `dvr_case` 로 `deny0c` 를 돌려
    `floor=` 가 `e14_seq` 보다 커질 때까지 봅니다. 방송이 이어져야 하고,
    10분 안에 커지지 않으면 E14 시험 무효로 kty 에게 보고합니다.
1.  `e14-direct`(조작 없는 경로)가 함수 404 여야 그 seq 가 이제 하한
    아래라는 양성 대조가 섭니다. 아니면 E14 시험 무효입니다.

```shell
e14_seq="${floor}"                     # eq 가 200 이었던 seq
dvr_case deny0c GET "$(seg 0)" | tee -a "${evidence}/dvr-c-floor.txt"
# 1분마다 위 줄 — floor= 가 e14_seq 보다 커지면 아래로.
e14_pad="$(printf '%06d' "${e14_seq}")"
e14="$(seg "${e14_seq}")"
{
  c_get e14-direct "${e14}"
  c_get e14-vod "/vod/${stream}/../..${e14}"
  c_get e14-dotdot "/dvr/${stream}/x/../${round}/seg/${e14_pad}.m4s"
  c_get e14-dot "/dvr/${stream}/${round}/seg/./${e14_pad}.m4s"
  c_get e14-pct2e "/dvr/${stream}/x/%2e%2e/${round}/seg/${e14_pad}.m4s"
  c_get e14-dslash "/dvr/${stream}/${round}//seg/${e14_pad}.m4s"
} | tee -a "${evidence}/dvr-c.txt"
grep '^e14-' "${evidence}/dvr-c.txt" | grep -c ' 200 '   # 0
```

| 라벨 | 통과 |
|---|---|
| `e14-direct` | `404` · `xcache=FunctionGeneratedResponse from cloudfront`(양성 대조 — 아니면 E14 시험 무효) |
| 그 밖 `e14-*` | `200` 이 아님(경로 조작으로 하한을 비켜 가지 못함 — E14). 기대는 함수 MALFORMED 404 입니다(실험대 (4) 의 R9) |

`e14-*` 가운데 `200` 이 하나라도 있으면 실패로 끄고, 그 경로를 kty 에게
보고합니다(D4 회부). 끈다고 그 우회가 막히지는 않습니다 — 하한이 통째로
없어질 뿐이라 시청자에게 더 나빠지지 않는 쪽으로 되돌리는 것입니다.

**앱 출처 credentials fetch**: 시험 방송을 앱에서 되감기로 연 탭
(`https://app.pokeclip.com`)의 개발자 도구 콘솔에서 아래를 돌립니다. 하한 아래
셋은 `404`, 하한 조각은 `200` 이어야 합니다. `TypeError: Failed to fetch` 면
CORS 실패(머리 없음 · 두 벌)입니다.

```js
const base = 'https://media.pokeclip.com/dvr/<test>/<회차>/seg/';
for (const seq of ['<F−1 6자리>', '<F−1 6자리>', '<F−1 6자리>', '<F 6자리>']) {
  try {
    const r = await fetch(`${base}${seq}.m4s`, {credentials: 'include'});
    console.log(seq, r.status);
  } catch (err) {
    console.log(seq, String(err));
  }
}
```

**DENY 로그**: `below1`–`below3` 의 요청 ID(응답 머리 `x-amz-cf-id`)마다 함수
로그에 DENY 줄이 있는지 봅니다. 함수 로그 줄은 앞에 그 요청 ID 를 답니다.
마지막 요청부터 **10분**까지 1분마다 아래를 다시 돌립니다. 셋 다 대응하면
그때 멈춥니다.

```shell
grep -hi '^x-amz-cf-id:' "${evidence}"/dvr-c-below[123].h | tr -d '\r' |
  cut -d' ' -f2 > "${evidence}/dvr-c-ids.txt"
aws logs filter-log-events --region us-east-1 \
  --log-group-name /aws/cloudfront/function/pokeclip-prod-dvr-floor \
  --start-time "${c_since_ms}" --filter-pattern '"DVR FLOOR DENY"' \
  --output json | jq -r '.events[].message' > "${evidence}/dvr-c-deny.txt"
grep -cFf "${evidence}/dvr-c-ids.txt" "${evidence}/dvr-c-deny.txt"   # 3
```

*   셋 다 대응 — 통과.
*   10분 안에 셋이 안 됨 — **관측 불충분**입니다(실패 아님 · 끄지 않음).
    `below` 요청을 다시 보내 새 요청 ID 로 한 번 더 잽니다. 두 번째도 모자라면
    kty 에게 보고합니다.
*   로그 수를 요청 수의 대조로 곧바로 쓰지 않습니다. 「로그 수 = 요청 수」
    가 아니라는 것만으로는 끌 근거가 아닙니다 — 전달이 best-effort 입니다.

**지표 관측**: C′ 창의 CloudFront 함수 지표를 판정이 아니라 기록으로
남깁니다. 사용률 합격선(kty (가))은 `test-function` 값에만 씁니다.

*   **남기는 것**: C′ 창 전체를 한 period(창 길이를 60초 단위로 올린 값)로
    받은 FunctionComputeUtilization 의 Average · Maximum · SampleCount(표본
    수), FunctionThrottles 합, FunctionExecutionErrors 합입니다. 1분 단위
    값은 쓰지 않습니다 — 표본이 적은 1분 창의 Average 는 첫 호출 피크 하나로
    94 가 됩니다(live-auth 의 호출 1번뿐인 날 Average 94 · 실험대 (4) 1분 창
    표본 2개).
*   **창 값 합치기**: 창이 분 경계에 걸쳐 지표 점이 둘 이상 나오면 점마다
    적고, 창 값은 이렇게 합칩니다. SampleCount 는 합, 창 평균은
    Σ(Average × SampleCount) ÷ ΣSampleCount(점별 Average 의 단순 평균이
    아님), Maximum 은 점 가운데 최대, FunctionThrottles ·
    FunctionExecutionErrors 는 Sum 의 합입니다. 아래 블록 끝의 `jq` 가 이
    규칙으로 한 줄을 냅니다.
*   **보는 차례**(위부터 봅니다 · 어느 조건만으로도 끄지 않습니다):
    1.  FunctionExecutionErrors 합이 0 보다 큼 — SampleCount 와 무관하게
        판정 보류 · kty 회부.
    1.  FunctionThrottles 합이 0 보다 큼 — SampleCount 와 무관하게 이것만으로
        판정 보류 · kty 보고.
    1.  SampleCount 합이 20 미만 — **관측 불충분**입니다(판정 아님). 요청을
        더 보낸 뒤 같은 창 규칙으로 한 번 더 잽니다. 두 번째도 20 미만이면
        더 재지 않고 kty 에게 회부합니다.
    1.  SampleCount 합 20 이상에서 창 평균이 50 을 넘음 — 판정 보류 · kty
        보고.
*   **20 의 근거**: 한 점 평균이 한두 호출에 휘둘리지 않게 하려는
    보수값입니다. 실측 근거는 없습니다.
*   **Maximum 하나만으로는 보고 조건이 아닙니다.** KVS 를 읽는 다른 prod
    함수 live-auth 의 7일 지표(2026-11-04–11-11 · 일 단위 · 읽기 전용 조회)에서
    Maximum 이 매일 94 였습니다(호출이 1번뿐인 날도 94). Average 는 표본이
    많은 날 16.4–18.8 이었습니다 — 호출이 1번뿐인 11-08 은 Average 94 ·
    SampleCount 1 이라 이 범위에서 뺐습니다. FunctionThrottles ·
    FunctionExecutionErrors 는 0 이었습니다.
    94 는 이 계정 CloudFront 함수의 평상 최대(첫 호출 등)이고 스로틀로
    이어지지 않았습니다. 실험대 (4) 의 dvr-floor 1분 Maximum 94
    (`test-function` 최대는 9)도 계정 평상 피크(live-auth 대조)로 봅니다.
*   **FunctionInvocations 는 요청 수 대조 근거가 아닙니다.** 분 단위 짧은
    창에서는 표본 · 지연 때문에 맞지 않습니다 — 실험대 (4) 에서 요청 49 에
    합 2 였습니다. 요청 수 대조는 위 「DENY 로그」 처럼 요청 ID 로만 합니다.

아래는 창 끝(지금 + 2분)과 한 period 를 정한 뒤 넷을 받고, 위 「창 값
합치기」 규칙으로 한 줄을 냅니다.

```shell
c_end_s="$(($(date -u +%s) + 120))"
c_period="$(((c_end_s - c_since_ms / 1000 + 59) / 60 * 60))"
c_end="$(date -u -r "${c_end_s}" '+%Y-%m-%dT%H:%M:%SZ')"   # macOS date
c_metric() {  # c_metric <지표> <통계>… — C′ 창 전체를 한 period 로.
  aws cloudwatch get-metric-statistics --region us-east-1 \
    --namespace AWS/CloudFront --metric-name "$1" \
    --dimensions Name=FunctionName,Value=pokeclip-prod-dvr-floor \
    Name=Region,Value=Global --start-time "${c_since}" \
    --end-time "${c_end}" --period "${c_period}" \
    --statistics "${@:2}" --output json |
    jq -c --arg m "$1" '{($m): [.Datapoints[] | del(.Timestamp, .Unit)]}'
}
{
  c_metric FunctionComputeUtilization Average Maximum SampleCount
  c_metric FunctionThrottles Sum
  c_metric FunctionExecutionErrors Sum
} | tee "${evidence}/dvr-c-metrics.txt"
jq -rs 'add | .FunctionComputeUtilization as $u
  | ($u | map(.SampleCount) | add // 0) as $n
  | [ "samples=\($n)",
      "avg=\(if $n > 0 then ($u | map(.Average * .SampleCount) | add) / $n
             else "-" end)",
      "max=\($u | map(.Maximum) | max // "-")",
      "throttles=\(.FunctionThrottles | map(.Sum) | add // 0)",
      "errors=\(.FunctionExecutionErrors | map(.Sum) | add // 0)" ]
  | join(" ")' "${evidence}/dvr-c-metrics.txt" |
  tee "${evidence}/dvr-c-metrics-window.txt"
```

**닫힌 회차**: 시험 방송을 끝내고 그 회차 국면이 `vod` 로 넘어간 뒤(Media
교체 런북 4.10 의 둘째 통과 조건과 같은 확인 — Writer 가 키를 지우고 120초
유예가 지남) 아래를 봅니다. `<head>` 는 그 회차 VOD 문서의 머리 세션
id 입니다.

```shell
{
  c_get closed-below "${below}"
  c_get closed-eq "$(seg "${floor}")"
  c_get vod "/vod/${stream}/<head>/index.m3u8"
} | tee -a "${evidence}/dvr-c.txt"
```

| 라벨 | 통과 |
|---|---|
| `closed-below` | `xcache` 가 함수 생성이 아님(키가 지워져 함수 통과). 조각이 없으면 버킷 `403` — 같은 쿠키로 `closed-eq` 가 `200` 이어야 그 `403` 이 서명 거절이 아니라 버킷 몫입니다 |
| `closed-eq` | `200` · `acao` · `acac` 한 벌(쿠키가 유효하다는 양성 대조) |
| `vod` | `200` · `acao` · `acac` 한 벌(`/vod` 무변) |

`closed-below` 가 아직 함수 404 면 KVS 삭제가 엣지에 덜 퍼진 것일 수
있습니다(결정 8 — 그동안의 VOD 조각 404 는 웹의 기존 재시도가 약 60초
가립니다). 2분 뒤 같은 셸에서 위 블록을 다시 돌려(쿠키 파일은 아직
지우지 않습니다) `closed-eq` 가 `200` 인 채 `closed-below` 를 보고, 그래도
함수 404 면 끄고 kty 에게 보고합니다.

재측정까지 끝나면 쿠키 파일을 지웁니다. 「/dvr 하한 끄기」 확인도 쿠키가
필요하므로, 그 전에 끌 일이 생기면 지우지 말고 끄기 확인까지 마친 뒤
지웁니다.

```shell
rm -f "${jar}" && test ! -e "${jar}" && echo removed
```

### /dvr 하한 끄기

1순위 롤백입니다. `dvr_floor_enabled = false` 로 edge apply 해 `/dvr/*` 에서
함수를 뗍니다. 함수 · 로그 그룹 · 경보 · KVS 키는 그대로 둡니다(Writer 는
계속 하한을 쓰지만 읽는 쪽이 없습니다). 실행 직전에 승인받습니다.

```shell
# 기대: update module.media.aws_cloudfront_distribution.this 한 줄
edge_plan false dvr-off
terraform -chdir=infra/terraform/prod/edge apply "${evidence}/dvr-off.plan"
```

apply 가 끝나면(Deployed) 확인합니다. 확인에는 유효한 서명 쿠키가
필요합니다 — 쿠키 없는 요청은 함수 전에 서명 거절 `403` 이 되어 「함수
생성 아님」 을 늘 만족하기 때문입니다. 쿠키 파일이 없으면 「경로 우회
시험」 T2 와 같은 발급으로 `jar` 를 다시 만듭니다(`c_get` 은 `jar` 가 없으면
멈춥니다).

새 셸이면 아래를 먼저 다시 만듭니다(「/dvr 하한 셸 전제」). `jar` 는 위와
같고, `edge_ip` 는 「접속법」 2 로 다시 풀고, `c_get` · `c_val` · `c_line` 은
「판정 C′」 첫 블록의 정의 부분만 붙여 넣습니다. `floor` 는 판정 B′ 의
`deny0` 로 다시 받습니다(`fn` · `fn_etag` · `dvr_case` 도 B′ 첫 블록의
정의로 다시 만듦 — 함수는 떼어도 남아 있어 `test-function` 이 됩니다).

```shell
evidence='<쓰던 증거 디렉터리>'           # 새로 만들지 않습니다
stream='<test>'
round='<회차>'
floor='<F>'                                # deny0 의 floor= 값
seg() { printf '/dvr/%s/%s/seg/%06d.m4s' "${stream}" "${round}" "$1"; }
below="$(seg "$((floor - 1))")"
```

```shell
{
  c_get off-eq "$(seg "${floor}")"
  c_get off-below "${below}"
} | tee -a "${evidence}/dvr-off.txt"
```

통과: `off-eq` 가 `200` · `acao` · `acac` 한 벌(쿠키가 유효함)이고,
`off-below` 의 `xcache` 가 함수 생성이 아님(조각이 있으면 `200`, 없으면 버킷
`403`). 다시 켜는 것은 원인을 고친 뒤 6.5(2단) · 6.6(C′)입니다.

`off-eq` 가 `200` 이 아니면 쿠키 만료와 `floor` 조각 밀림(그 seq 조각을 더
받을 수 없음)을 가를 수 없습니다. 판정 B′ 의 `deny0` 로 `floor` 를 다시
받고 `below` 를 다시 만든 뒤 위 블록을 한 번 더 돌립니다. 그래도 `off-eq`
가 `200` 이 아니면 멈추고 kty 에게 보고합니다.

확인이 끝나면(통과든 멈춤이든) 쿠키 파일을 지웁니다.

```shell
rm -f "${jar}" && test ! -e "${jar}" && echo removed
```

### /dvr 하한 롤백

| 상황 | 할 일 |
|---|---|
| C′ 실패 · 엣지 거절 이상(시청자 404 · `dvr_floor_blocks_list`) · CORS 실패 | 「/dvr 하한 끄기」(1순위) |
| 함수 코드 결함 | 고친 PR → 「붙은 채 함수 코드 고치기」 |
| 경보 오탐 · 무음 | 문턱 · 패턴을 고치는 PR(스위치는 그대로) |
| Writer 문제(`edge_bounds_write_failing` 이 풀리지 않음 등) | [Media 교체 런북](media-swap.md) 「POK-525 롤백」 의 /dvr 하한 갈래 |

어느 갈래에서도 함수 · KVS 그릇 · 로그 그룹은 지우지 않습니다(`O7f` 묶음에
`cloudfront:DeleteFunction` 이 없습니다).

### 붙은 채 함수 코드 고치기

함수는 `publish = true` 입니다. 코드가 바뀐 apply 는 UpdateFunction 뒤 곧바로
PublishFunction 을 부르고, 게시는 그 함수를 쓰는 비헤이비어에 곧바로
실립니다(provider 6.68.0 `function.go` · API PublishFunction — 계획 Ⓓ 리서치
R8, 2026-11-11 확인). 붙은 채 고친 코드를 apply 하면 시험 전 코드가 바로
시청자 요청을 받습니다. 그래서 차례가 이렇습니다.

1.  **고친 PR 을 머지하기 전에** 지금 코드로 「/dvr 하한 끄기」 를 합니다.
    기대 목록은 배포 한 줄입니다. 머지 뒤에 끄면 같은 apply 에서 함수 갱신
    (곧바로 게시)이 배포 변경보다 먼저 돌 수 있습니다 — 배포가 함수 ARN 을
    참조해 함수를 먼저 고칩니다.
1.  고친 PR 을 머지합니다.
1.  1단 apply(`false`) — 기대 목록은
    `update module.media.aws_cloudfront_function.dvr_floor` 한 줄입니다.
1.  판정 B′(새 시험 방송 — 승인 따로).
1.  2단 apply(`true`) → 판정 C′.

### /dvr 하한 실험대 결과

ISB 리스 계정(= prod 계정)의 실험 자원(접두 `pcbench-pok525-isb34`)으로
2026-11-11 에 잰 것입니다. prod CloudFront 는 바뀌지 않았고 잔여는 0
입니다. 원문은 `$HOME/Documents/pokeclip-empirical/runs/` 아래에 있습니다.

*   **(3) 함수 단독**(`test-function`): 갈래 53 개가 모두 node 판정과 같음
    (각 3/3 — 값 골든 25행 · `VALUE INVALID` 포함). 계산 사용률 최대 9
    (exists+get 9 · exists 7 · KVS 안 읽음 7) → 합격.
*   **R6**: 함수 권한만 준 제한 역할로 KVS 연결 함수를 만들고 고칠 수
    있었습니다 — KVS 쪽 권한이 따로 들지 않습니다. 잰 것은 Create ·
    Update 뿐이고 PublishFunction 은 재지 않았습니다(「/dvr 하한 1단 도중
    실패」 의 `AccessDenied` 갈래).
*   **(4) 실제 엣지**: 합격 항목 11/11 — 하한 아래 404 · 머리 한 벌 ·
    `no-store`, 하한 이상 · 옛 꼴 · `/vod` 200 · 머리 한 벌, 다른 출처
    credentials fetch 404 · 200.
*   **R2 — 응답 헤더 정책은 함수가 만든 응답에도 붙습니다.** CORS 머리를
    싣지 않는 함수의 404 에도 정책(`media_cors` 꼴 · `origin_override` 켬)의
    머리가 붙었습니다. dvr-floor 404 의 머리가 한 벌인 것은 정책이 함수
    머리를 덮은 결과입니다(E48 두 벌 없음). 함수 쪽 머리는 정책이 없을 때를
    위한 겹친 방어입니다. 그래서 C′ 는 `/live` 403 의 머리를 보지 않습니다.
*   **R5**: 함수 404 는 캐시되지 않아 재요청 10/10 이 함수를 탔습니다.
*   **R9 — 함수는 원문 경로를 받습니다.** `/dvr/s/x/../r/seg/…` ·
    `/dvr/s/r/seg/./…` · `%2e%2e` 가 정규화본이었다면 통과였을 자리에서 함수
    MALFORMED 404 + 로그가 나왔습니다. `%2F` · `%5C` · `//` · 역슬래시는 두
    경우 모두 404 라 가르지 못했습니다. E14(경로 조작으로 원본 200)는 없었습니다.
*   **DENY 로그**: 요청 19 개가 모두 요청 ID 로 대응했습니다(첫 관측 1분).
*   **못 잰 것**: `KVS ERROR` 갈래(장애를 일으킬 수단 없음 — node 시험의
    가짜 KVS 로만 고정).

## 1차 대응 — 표준 로그 끄기

Budgets 경보가 오면 kty 가 원천을 찾습니다. 표준 로그 급증이면
`enable_standard_logs = false` 로 edge apply 합니다(36차). 실행 직전에
승인하고, 운영 plan 이 「전달 자원 삭제만 · 그 밖 0」 인지 봅니다. 로그
그룹은 남습니다. 끈 동안은 설계 7절의 `/live` 302 · 401 신호를 쓸 수
없습니다([prod/edge](../prod/edge/README.md) 「표준 로그 끄기」).

/dvr 하한을 켠 뒤면 `-var dvr_floor_enabled=true` 를 함께 넘깁니다. 하한
1단이 도중에 실패한 채면 plan 에 남은 생성이 끼어듭니다 — 「/dvr 하한 1단
도중 실패」 의 3 을 따릅니다.

## 결과 기록

시험마다 한 줄을 증거 디렉터리의 `results.tsv` 에 적고, 이 표 꼴로 kty 에게
보고합니다. 시각은 방금 찍은 `date -u` 출력만 씁니다. 결과 원문(IP · ARN ·
계정 ID)은 저장소 · 볼트에 그대로 올리지 않습니다 — 옮길 때는 가린 요약만
옮깁니다.

| 시험 | 시각(UTC) | 판정(통과 · 실패 · 보류 · 하지 않음) | 근거(증거 파일) | 비고(A1 · A2 · 7-R 등) |
|---|---|---|---|---|
| T0 | | | | |
| T6b | | | | |
| KVS 값 입력 | | | | |
| T8e 해석 | | | | |
| T11 404 | | | | |
| T2b | | | | |
| 경로 우회(T2b) | | | | |
| T12 ⓐ 원본 도달 | | | | |
| T1 | | | | |
| T2 | | | | |
| 경로 우회(T2) | | | | |
| T3 | | | | |
| T4 | | | | |
| T5 | | | | |
| T7 | | | | |
| T8a–T8d | | | | |
| T10 | | | | |
| T12 ⓑ 200 JSON | | | | |
| T11 202 | | | | |
| T13 | | | | |
| /dvr 하한 B′ | | | | |
| /dvr 하한 K | | | | |
| /dvr 하한 C′ | | | | |
| T6a · T9b | — | 하지 않음(kty 20차) | 「O7f 순서」 끝 문단 | |

## See also

*   [prod/edge](../prod/edge/README.md) · [prod/records](../prod/records/README.md)
*   [엣지 비밀 런북](edge-secrets.md) — 「O7f」 · 「T6b 대체 절차」
*   [단계 직전 점검 런북](stage-precheck.md) — `O7f` · `O7f-stop2` · 증거
*   [Media 교체 런북](media-swap.md) — 4.10 시험 방송 · 「POK-525 롤백」 의
    /dvr 하한 갈래
*   [런타임 1단-가 런북](runtime-1a.md) — 「POK-525 원장 질의」
*   [prod 철거 런북](prod-teardown.md) — edge · records 를 내릴 때
*   `scripts/kvs-put-cdn-secret.sh` · `scripts/test/kvs_put_test.sh`
*   설계서 5-6 · 5-4 의 9 · 7절 · 8절, 계획 M10 c10 · O7f · 「O7f 실행표」 ·
    3절(SCP 멈춤 규칙 · 부분 진행), POK-525 계획 Ⓓ 3절(걸음 6.0–6.6)
