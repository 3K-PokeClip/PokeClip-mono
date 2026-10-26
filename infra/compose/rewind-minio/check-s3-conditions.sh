#!/bin/sh
#
# 로컬 S3 대체물의 공통 수용 시험 (POK-200 · 설계 M5 9.4.1 · R34 · R48).
#
# 발행 층은 조건부 PUT(If-None-Match: * · If-Match)으로 fence 를 지킨다. 대체물이 이를 무시하면 로컬 실측이
# prod 와 다른 동작을 잰다. 이미지 · 판을 고르거나 올릴 때마다 돌리고, 하나라도 어긋나면 그 판은 쓰지 않는다.
#
#   새 키 If-None-Match: *            → 200
#   같은 키 If-None-Match: *          → 412
#   같은 키 틀린 ETag If-Match        → 412
#   같은 키 맞는 ETag If-Match        → 200
#   익명 목록 · 익명 streams/ · 익명 dvr/ → 403 · 403 · 200
#
# 입력(env): S3_URL(https://호스트:포트) · S3_BUCKET · S3_ACCESS_KEY · S3_SECRET_KEY · S3_CA_FILE(로컬 CA) ·
# S3_REGION(기본 us-east-1). 자격 증명 값은 출력하지 않는다. 시험 객체는 어떻게 끝나든 종료 때 지운다.
# 종료 코드: 0 = 전부 기대대로 · 1 = 어긋남 · 2 = 입력 · 도구 · 전송(연결 · TLS) 오류.
#
# #!/bin/sh 인 까닭(Google Shell Style 의 bash 예외 — 실행 환경이 강제할 때): minio-check 컨테이너
# (alpine)에는 bash 가 없고 busybox sh 만 있다. 그래서 POSIX sh 로만 쓴다.

set -eu

readonly REGION="${S3_REGION:-us-east-1}"

# check_inputs 는 필수 env 와 curl 을 확인한다. 빠지면 종료 코드 2 다.
check_inputs() {
  for v in S3_URL S3_BUCKET S3_ACCESS_KEY S3_SECRET_KEY S3_CA_FILE; do
    eval "val=\${$v:-}"
    if [ -z "${val}" ]; then
      echo "입력 없음: ${v}" >&2
      exit 2
    fi
  done
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl 이 없다" >&2
    exit 2
  fi
}

# signed 는 서명한 요청을 보내고 상태 코드를 출력한다. 응답 머리는 ${HEADERS_FILE} 에 남는다. 전송이 실패하면
# (상태 코드를 받지 못하면) 종료 코드 2 다.
signed() {
  curl -sS -o /dev/null -D "${HEADERS_FILE}" -w '%{http_code}' --cacert "${S3_CA_FILE}" \
    --aws-sigv4 "aws:amz:${REGION}:s3" --user "${S3_ACCESS_KEY}:${S3_SECRET_KEY}" "$@" || exit 2
}

# anonymous 는 서명 없는 요청을 보내고 상태 코드를 출력한다. 전송이 실패하면 종료 코드 2 다.
anonymous() {
  curl -sS -o /dev/null -w '%{http_code}' --cacert "${S3_CA_FILE}" "$@" || exit 2
}

# expect 는 이름 · 기대 · 실제를 한 줄로 적고 어긋남을 센다.
expect() {
  if [ "$2" = "$3" ]; then
    echo "ok   $1 — $3"
  else
    echo "FAIL $1 — 기대 $2 · 실제 $3"
    failures=$((failures + 1))
  fi
}

etag() {
  tr -d '\r' < "${HEADERS_FILE}" | awk 'tolower($1) == "etag:" { print $2 }'
}

# cleanup 은 종료 때(성공 · 어긋남 · 전송 실패 모두) 시험 객체를 지우고 머리 파일을 치운다. 지우기 실패는 경고만
# 하고 종료 코드를 바꾸지 않는다.
cleanup() {
  for key in "${COND_KEY}" "${DVR_KEY}" "${STREAMS_KEY}"; do
    if ! (signed -X DELETE "${BASE}/${key}" >/dev/null) 2>/dev/null; then
      echo "경고: 시험 객체를 지우지 못했다 — ${key}" >&2
    fi
  done
  rm -f "${HEADERS_FILE}"
}

main() {
  check_inputs
  readonly BASE="${S3_URL}/${S3_BUCKET}"
  RUN_ID="$(date +%s)-$$"
  readonly RUN_ID
  readonly COND_KEY="dvr/_s3check/${RUN_ID}/cond.txt"
  readonly DVR_KEY="dvr/_s3check/${RUN_ID}/anon.txt"
  readonly STREAMS_KEY="streams/_s3check/${RUN_ID}/anon.txt"
  HEADERS_FILE="$(mktemp)"
  readonly HEADERS_FILE
  trap cleanup EXIT
  failures=0

  code="$(signed -X PUT -H 'If-None-Match: *' --data-binary 'v1' "${BASE}/${COND_KEY}")"
  expect "새 키 If-None-Match: *" 200 "${code}"
  first_etag="$(etag)"

  code="$(signed -X PUT -H 'If-None-Match: *' --data-binary 'v2' "${BASE}/${COND_KEY}")"
  expect "같은 키 If-None-Match: *" 412 "${code}"

  code="$(signed -X PUT -H 'If-Match: "00000000000000000000000000000000"' --data-binary 'v2' \
    "${BASE}/${COND_KEY}")"
  expect "틀린 ETag If-Match" 412 "${code}"

  code="$(signed -X PUT -H "If-Match: ${first_etag}" --data-binary 'v2' "${BASE}/${COND_KEY}")"
  expect "맞는 ETag If-Match" 200 "${code}"

  signed -X PUT --data-binary 'anon' "${BASE}/${DVR_KEY}" >/dev/null
  signed -X PUT --data-binary 'anon' "${BASE}/${STREAMS_KEY}" >/dev/null
  # 코드는 변수에 먼저 받는다 — 함수 인자 안의 $(…) 는 종료 코드가 버려져 전송 실패가 어긋남(1)으로 둔갑한다.
  code="$(anonymous "${BASE}/?list-type=2")"
  expect "익명 목록 GET /?list-type=2" 403 "${code}"
  code="$(anonymous "${BASE}/${STREAMS_KEY}")"
  expect "익명 GET streams/…" 403 "${code}"
  code="$(anonymous "${BASE}/${DVR_KEY}")"
  expect "익명 GET dvr/…" 200 "${code}"

  if [ "${failures}" -ne 0 ]; then
    echo "수용 시험 어긋남 ${failures}건 — 이 이미지 · 판은 쓰지 않는다"
    exit 1
  fi
  echo "수용 시험 통과(조건부 PUT 넷 · 익명 탐침 셋)"
}

main "$@"
