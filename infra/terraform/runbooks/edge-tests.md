# 엣지 시험 런북

prod 엣지(CloudFront · 공개 ALB · NLB)를 운영 이름 그대로 시험하는
절차입니다. 설계서 5-6(시험 계획과 시험 접속법)과 계획 M10 c10 에 해당하고,
7단계 O7f 의 KVS 값 입력(멈춤 자리 ②)도 여기에 있습니다. 8단계(O8)는
「경로 우회 시험」 을 통과한 뒤에 시작하고, 개통 뒤 관찰은 이 시험표를
`--resolve` 없이 다시 돌립니다.

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
| 6 | 런타임 트랙 2단 | [런타임 트랙 계약](../CONTRACT-runtime.md) |
| 7 | auth · clip · next 정상 기동 확인 | 계획 M10 O7f 7 |
| 8 | 시험 스트림 키 발급 · 입력 | [엣지 비밀 런북](edge-secrets.md) 「O7f」 |
| 9 | 루프백 헬스 · `secret-count` | 계획 M10 O7f 9 |
| 10 | clip 쿠키 발급 1회 200 | 계획 M10 O7f 10 |
| 11 | 7-R(2 에서 403 이었을 때만) | [엣지 비밀 런북](edge-secrets.md) 「T6b 대체 절차」 |
| 12 | 런타임 뒤 시험(T1–T5 · T7 · T8 · T10 · T11 202 · T12 ⓑ) | 「시험표」 · 「런타임 뒤 시험의 추가 항목」 |

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
    T10 · T11 202. `/live` 재생 시험(T1 · T2 의 `/live` 사례 · T3 · T4 · T5 ·
    T7 · T8a–T8d) · 7-R · 8단계는 값 입력 뒤입니다. 대안은 설계하지
    않습니다(34차 Q2 — 막히면 kty 회부).
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

설계 5-6 의 표에서 T6a · T9b 를 뺀 것입니다. 「언제」 의 「엣지만」 은 송출원
· 서명 쿠키 없이 O7f 의 2 · 4 에서 하는 시험이고, 「런타임 뒤」 는 런타임
트랙 2단 · 시험 키 · clip 쿠키 뒤(O7f 의 12)입니다.

| # | 시험 | 통과 조건 | 언제 |
|---|---|---|---|
| T0 | 세 이름 TLS 연결 | 인증서 이름 일치 · 정상 응답(앱이 아직 healthy 가 아니면 ALB 503 도 TLS 통과로 봄) | 엣지만 |
| T6b | KVS 키 없음 | `/live` 503 · 함수 로그 고정 문자열 1건 이상 · `/dvr` · 제어면 응답은 503 이 아님(경보 검증 보류) | 엣지만(KVS 값 입력 전) |
| T9a | Media 빈 CDN 비밀 기동 | Media 미기동 · 인스턴스 로그 고정 문자열 · 복구 절차(경보 검증 보류) | O7e([Media 교체 런북](media-swap.md)) |
| T8e | NLB IP · AZ | ingest 해석 IP 하나 · 교체 전후 같음 | 해석은 엣지만, 비교는 T8a 와 함께 |
| T11 | 종료 신호 경로 | media 이름으로 `POST /api/ingest/end-signal` 이 원본(제어면 서버 배포 전에는 nginx 제어면 포트)에 닿음 · 캐시 안 됨 · 시청자 쿠키 없이 통과 · 표준 로그 · 원본 로그에 Authorization 값 0건 · 4D 수신부 배포 전에는 404 그대로 전달 | 404 는 엣지만, 유효 요청 202 는 제어면 서버 반영 뒤 |
| T2b | 기본 거절 · 경로 우회 | 아래 「T2b 기본 거절」 · 「경로 우회 시험」(O8 개통 조건) | 엣지만 |
| T1 | 다른 쿠키 두 시청자 | 목록 종류별 Cache-Control 기록 · 조각 · 파트 두 번째 시청자 적중 · 블로킹 목록은 정상 전달만 · 원본 302 · Set-Cookie · `session` 0건 | 런타임 뒤 |
| T2 | 쿠키 없음 · 다른 방송 쿠키 · 경로 우회 | 403, 원본 도달 0 · 경로 우회는 「경로 우회 시험」(O8 개통 조건) | 런타임 뒤 |
| T3 | 쿠키 만료 | 만료 뒤 403, 재발급 뒤 복구 | 런타임 뒤 |
| T4 | 블로킹 리로드 | 쿼리마다 다른 캐시 키, 504 0건 | 런타임 뒤 |
| T5 | Host · Authorization 전달 | 원본에서 Host = `media.pokeclip.com` · Bearer 있음 · 쿠키 미전달. 시청자 임의 Authorization 은 원본에 안 닿음 | 런타임 뒤 |
| T7 | 경로 변환(ALB transform) | 재생 성공 · 원본 요청 경로에 `/live` 없음 · `_HLS_*` 쿼리 보존 · Location 응답 0건(나오면 viewer-response 보정 착수) | 런타임 뒤 |
| T8a | MVP 정상 교체 | 정상 교체 · 새 방송 재생 · CloudFront 무변경 | 런타임 뒤 |
| T8b | force | 재생 복구 시간 · 연결 종료까지 걸린 시간(30초 안 · A 정지로 줄어드는지) · 재시도 20회 소진 시간 · SRT 가 A 에 남지 않음 · 수동 복구 런북 리허설 | 런타임 뒤 |
| T8c | 교체 창 중 신규 송출 시도 | 한계 관찰 · 결과 기록(차단 기대 없음) | 런타임 뒤 |
| T8d | 부분 실패(B 등록 전 실패 포함) | 회복 · force 였다면 런북으로 기존 방송 복구 확인 | 런타임 뒤 |
| T10 | 재생 API | 아래 「T10」 | 런타임 뒤(2번 · 3번 배포 전이면 보류) |
| T12 | 회차별 되감기 경로 | ⓐ **원본 도달**: media 이름으로 쿠키 없이 `GET /api/streams/<표식>/rounds/<표식>/rewind`. 원본 로그 = nginx 제어면 접근 로그의 같은 경로 원문 줄. `control_upstream` 반영 전 = 원본 로그 1건 · 상류 호출 0 · nginx 404 / 반영 뒤 · 회차 라우트 등록 전 = 원본 로그 1건 · 상류 도달 · 제어면 ServeMux 404 / 둘 다 응답이 CloudFront 기본 동작(dvr 쿠키 · 403)이 아님. ⓑ **200 JSON**(회차 라우트 — 제어면 서버의 이 경로 처리기 — 배포 뒤 · 실재 시험 회차로): 200 · `application/json` · 제어면 응답 계약(계약3 7-2절)의 키 다섯(`ready` · `mode` · `url` · `firstPdt` · `headPdt`). 실패 = 엣지가 404(원본 로그 0건) · 403 · 5xx · JSON 아닌 200 → 엣지 경로 패턴을 맡은 후속 작업에서 고침 | ⓐ 는 엣지만 · ⓑ 는 런타임 뒤(회차 라우트 배포 뒤) |

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
    이면 반영 뒤 · 회차 라우트 등록 전 통과입니다(둘 다 응답 404 · `server`
    가 CloudFront 아님). `COUNT 0` 이면 실패이고 kty 에게 보고합니다 — 엣지
    경로 패턴은 그 패턴을 맡은 후속 작업에서 고칩니다. `COUNT` 가 2 이상이면
    엣지가 같은 요청을 원본에 다시 보낸 것입니다. `UPSTREAM` 이 0 이거나
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

| 언제 | 요청(쿠키) | 통과 조건 |
|---|---|---|
| T2b | `/live/../api/ingest/<표식>` · `/playback/../api/ingest/<표식>` · `/live/../api/streams/<표식>/rewind`(쿠키 없음) | 차단 계층 · 응답 코드 기록 · 엣지 차단이면 nginx 도달 0 · nginx 차단이면 거절 응답 + 보호 대상 상류 전달 0 |
| T2 | `/live/<A>/../<B>/index.m3u8`(방송 A 쿠키) | 앞뒤 양성 대조가 둘 다 유효(HTTP 응답이 왔고 `server` 가 CloudFront 가 아님 — 아니면 그 회차 무효)하고, 모든 사례가 CloudFront 가 낸 403(`server: CloudFront`)이며, 그 시각대 WAF 차단 로그의 시험 IP 건수가 0 — 사례가 그 밖(nginx 응답 · `Miss` · 2xx · 5xx · 통신 실패)이면 A 쿠키가 엣지를 통과한 것이라 실패 · WAF 건수가 0 이 아니면 403 이 서명 거절인지 가를 수 없어 실패 |

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
*   **T2 는 엣지 서명 거절만 통과입니다**(kty 2026-10-25). 모든 사례의
    응답이 CloudFront 가 직접 낸 403(`server: CloudFront` 헤더 — nginx 가 낸
    응답이면 `server` 가 nginx)이어야 합니다. 그 밖의 응답은 모두
    실패입니다 — nginx 까지 간 응답(400 · 403 · 404 · `Miss`)은 A 쿠키가
    엣지 서명 검사를 통과했다는 뜻이고, 그러면 캐시가 차 있는 실제 운영에서
    A 쿠키로 B 목록 · 조각(조각은 최대 1시간 캐시)이 나갈 수 있습니다. 2xx
    는 노출 그 자체입니다. 판정이 캐시 상태에 기대지 않으므로 예열은 하지
    않습니다.
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
    ```

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
        (위 「T2 는 엣지 서명 거절만 통과」) — 앞뒤 양성 대조가 둘 다
        유효할 때(HTTP 응답이 왔고 `server` 가 CloudFront 가 아님)만
        판정합니다. `results.tsv` 비고에는 대조 결과 · 응답 코드 · `server` ·
        `x-cache` 와, 그 시각대 WAF 차단 로그의 시험 IP 건수(0 이어야 「서명
        거절」 — WAF 403 도 `server: CloudFront`)를 적습니다. 그 건수가 0 이
        아니면 실패입니다(회차 무효로 다시 돌리지 않습니다). nginx 건수는 T2
        판정에 쓰지 않습니다.
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

## 1차 대응 — 표준 로그 끄기

Budgets 경보가 오면 kty 가 원천을 찾습니다. 표준 로그 급증이면
`enable_standard_logs = false` 로 edge apply 합니다(36차). 실행 직전에
승인하고, 운영 plan 이 「전달 자원 삭제만 · 그 밖 0」 인지 봅니다. 로그
그룹은 남습니다. 끈 동안은 설계 7절의 `/live` 302 · 401 신호를 쓸 수
없습니다([prod/edge](../prod/edge/README.md) 「표준 로그 끄기」).

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
| T6a · T9b | — | 하지 않음(kty 20차) | 「O7f 순서」 끝 문단 | |

## See also

*   [prod/edge](../prod/edge/README.md) · [prod/records](../prod/records/README.md)
*   [엣지 비밀 런북](edge-secrets.md) — 「O7f」 · 「T6b 대체 절차」
*   [단계 직전 점검 런북](stage-precheck.md) — `O7f` · `O7f-stop2` · 증거
*   [prod 철거 런북](prod-teardown.md) — edge · records 를 내릴 때
*   `scripts/kvs-put-cdn-secret.sh` · `scripts/test/kvs_put_test.sh`
*   설계서 5-6 · 5-4 의 9 · 7절 · 8절, 계획 M10 c10 · O7f · 「O7f 실행표」 ·
    3절(SCP 멈춤 규칙 · 부분 진행)
