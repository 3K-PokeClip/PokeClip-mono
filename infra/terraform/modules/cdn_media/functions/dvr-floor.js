/**
 * @fileoverview media 배포 /dvr/* viewer-request 함수(cloudfront-js-2.0).
 * 회차 꼴 조각 /dvr/{스트림}/{회차}/seg/{seq}.m4s 가 그 회차의 하한(연결된
 * KVS 의 키 {스트림}/{회차} 값)보다 앞이면 본문 없는 404 로 막는다. 하한은
 * 되감기 창 정책이지 접근 통제가 아니다 — 1차 통제는 함수보다 먼저 검사되는
 * 서명 쿠키다. 그래서 판단 재료를 못 얻으면(키 없음 · KVS 읽기 실패 · 값
 * 이상) 막지 않고 넘긴다. 읽기 실패 · 값 이상은 고정 문자열 로그로 남겨
 * 경보가 센다. 경로 문법 · 키 · 값 문법은 Go 와 공유 골든으로 맞춘다.
 *
 * 경로 조작 표지(%, 역슬래시, 빈 구간, 점 구간)가 있으면 KVS 를 읽기 전에
 * 404 로 거절한다. 함수는 정규화 전 원문 경로를 받는다 — 실험대 (4) R9
 * (2026-11-11)에서 /../ · /./ · %2e%2e 경로가 MALFORMED 404 였다(G1 ·
 * POK-548). 쿼리는 보지 않는다.
 *
 * 이 파일은 Terraform templatefile 로 렌더되고 자리표시자는 app_origin
 * 하나다. 그래서 백틱을 쓰지 않고, 달러 · 퍼센트 바로 뒤에 여는 중괄호를
 * 두지 않는다(시험이 막는다).
 */

import cf from 'cloudfront';

/** 이 함수에 연결된 KVS(edge-bounds) 하나의 핸들. */
const kvs = cf.kvs();

// 함수 로그의 고정 문자열. 경보의 지표 필터 패턴과 시험이 이 글자를 그대로
// 찾으므로 바꾸면 그쪽도 함께 바꾼다. 로그에 싣는 값은 정규식을 통과한 성분과
// 수뿐이다(요청 원문을 되비추지 않는다). console.log 는 인자 하나만 받는다.

/** 하한 앞 조각을 막을 때. 스트림 · 회차 · seq · floor=하한이 붙는다. */
const DENY_LOG = 'DVR FLOOR DENY';

/** 표지 · 안전 정수 밖 seq 로 막을 때. 아무것도 붙이지 않는다. */
const MALFORMED_LOG = 'DVR FLOOR MALFORMED';

/** 값이 하한 문법 밖이라 넘길 때(경보 대상). 스트림 · 회차가 붙는다. */
const VALUE_INVALID_LOG = 'DVR FLOOR VALUE INVALID';

/** KVS 읽기가 실패해 넘길 때(경보 대상). 스트림 · 회차가 붙는다. */
const KVS_ERROR_LOG = 'DVR FLOOR KVS ERROR';

/**
 * 하한을 보는 회차 꼴 조각 경로. 밖(옛 꼴 · 목록 · init · 129자 성분 · 20자리
 * 이상 seq)은 하한 없이 넘긴다. 설계 · 계획이 이 글자 그대로 가리켜 한 줄에
 * 둔다.
 */
const ROUND_SEGMENT_PATH =
    /^\/dvr\/([A-Za-z0-9._-]{1,128})\/([A-Za-z0-9._-]{1,128})\/seg\/([0-9]{6,19})\.m4s$/;

/** 하한 값 원문의 꼴. Go DecodeValue 와 같다(앞자리 0 없는 십진). */
const FLOOR_TEXT = /^[1-9][0-9]*$/;

/**
 * @param {!Object} event CloudFront Functions viewer-request 이벤트.
 * @return {!Promise<!Object>} 그대로 넘기는 요청 또는 404 응답.
 */
async function handler(event) {
  const request = event.request;
  if (request.method === 'OPTIONS') {
    return request;
  }
  if (hasUnsafePath(request.uri)) {
    return notFound(MALFORMED_LOG);
  }
  const match = ROUND_SEGMENT_PATH.exec(request.uri);
  if (match === null) {
    return request;
  }
  // 하한과 배정밀도 수로 견주므로 2^53 이상은 정확하지 않다.
  const seq = Number(match[3]);
  if (!Number.isSafeInteger(seq)) {
    return notFound(MALFORMED_LOG);
  }
  const label = match[1] + ' ' + match[2];
  const raw = await readFloor(match[1] + '/' + match[2], label);
  if (raw === null) {
    return request;
  }
  const floor = parseFloor(raw);
  if (floor === null) {
    console.log(VALUE_INVALID_LOG + ' ' + label);
    return request;
  }
  if (seq < floor) {
    return notFound(DENY_LOG + ' ' + label + ' ' + seq + ' floor=' + floor);
  }
  return request;
}

/**
 * 하한 값 원문을 읽는다. get() 은 키가 없어도 reject 해 장애와 가를 수 없어서
 * exists() 를 먼저 묻는다. exists() · get() 의 reject 는 모두 KVS 장애로
 * 센다 — exists() 참 뒤 get() 전에 키가 지워진 드문 경우도 장애로 세는 것은
 * 한계다.
 * @param {string} key KVS 키.
 * @param {string} label 로그에 붙이는 스트림 · 회차.
 * @return {!Promise<?string>} 값 원문. 키가 없거나 읽기가 실패하면 null.
 */
async function readFloor(key, label) {
  try {
    if (!(await kvs.exists(key))) {
      return null;
    }
    return await kvs.get(key);
  } catch (err) {
    console.log(KVS_ERROR_LOG + ' ' + label);
    return null;
  }
}

/**
 * @param {string} raw KVS 값 원문.
 * @return {?number} 1 이상 2^53-1 이하의 하한. 꼴 · 범위 밖이면 null.
 */
function parseFloor(raw) {
  if (!FLOOR_TEXT.test(raw)) {
    return null;
  }
  const floor = Number(raw);
  return floor <= Number.MAX_SAFE_INTEGER ? floor : null;
}

/**
 * 경로 조작 표지가 있는지 본다. live-auth.js 와 같은 규칙이다(함수끼리
 * 모듈을 나눌 수 없어 따로 둔다). 정상 /dvr 경로에는 % 가 없다.
 * @param {string} uri 쿼리 없는 요청 경로.
 * @return {boolean} %, 역슬래시, 빈 구간, 점 구간 가운데 하나라도 있으면 참.
 */
function hasUnsafePath(uri) {
  if (uri.indexOf('%') !== -1 || uri.indexOf('\\') !== -1 ||
      uri.indexOf('//') !== -1) {
    return true;
  }
  // 런타임 2.0 문장 목록에 for-of 가 없어 인덱스로 돈다.
  const segments = uri.split('/');
  for (let i = 0; i < segments.length; i++) {
    if (segments[i] === '.' || segments[i] === '..') {
      return true;
    }
  }
  return false;
}

/**
 * 로그 한 줄을 남기고 원본으로 넘기지 않는 본문 없는 404 를 만든다. 웹이
 * credentials 요청으로 받으므로 CORS 머리를 직접 싣고, 캐시하지 않게 한다.
 * @param {string} log 남길 로그 한 줄.
 * @return {!Object}
 */
function notFound(log) {
  console.log(log);
  return {
    statusCode: 404,
    statusDescription: 'Not Found',
    headers: {
      'cache-control': {value: 'no-store'},
      'access-control-allow-origin': {value: '${app_origin}'},
      'access-control-allow-credentials': {value: 'true'},
      'vary': {value: 'origin'},
    },
  };
}
