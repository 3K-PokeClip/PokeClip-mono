/**
 * @fileoverview media 배포 /live/* viewer-request 함수(cloudfront-js-2.0).
 * 연결된 KVS 의 cdn-secret 값(MediaMTX hlsCDNSecret 원문)으로 원본 요청의
 * Authorization 을 `Bearer <값>` 으로 항상 덮어쓴다. 시청자가 보낸
 * Authorization 은 원본에 닿지 않는다. 값을 못 얻으면(KVS 예외 · 키 없음 ·
 * 빈 값) 원본으로 넘기지 않고 503 을 낸다(fail-closed). 로그에는 값을 남기지
 * 않고 고정 문자열만 남긴다.
 *
 * 그보다 먼저 경로 조작 표지가 있거나 미디어 파일(`.m3u8` · `.mp4`)이 아닌
 * 요청을 403 으로 거절한다. KVS 를 읽기 전이라 KVS 장애 때도 나쁜 경로는
 * 403 이고, 거절 갈래는 비밀 값에 닿지 않는다. CloudFront 가 함수에 원문
 * 경로를 주는지 정규화본을 주는지는 미확정이다(G1 · POK-548). 그래서 원문이면
 * 모든 표지로 막고, 정규화본이면 정규화 뒤에도 남는 표지(`%` · 역슬래시)로
 * 막는다. 정규화로 사라진 점 구간 · 빈 구간은 함수가 보지 못한다.
 * `event.request.uri` 에는 쿼리가 없어 쿼리는 검사하지 않는다.
 *
 * 미디어 파일만 넘기는 이유(T7): MediaMTX v1.21.1 HLS 서버는 끝 슬래시 없는
 * 경로에 302 를 내는데, ALB 경로 변환 때문에 그 Location 에 `/live` 가 빠진다.
 * `.mp` 에는 원본이 `4` 를 덧붙여 답한다. 끝 슬래시 index.html · hls.min.js ·
 * favicon 도 원본에 닿지 않는다. lowLatency(fMP4) 변형이 전제다 — hlsVariant 를
 * mpegts 로 바꾸면 `.ts` 조각이 막혀 재생이 깨진다.
 */

import cf from 'cloudfront';

/** 이 함수에 연결된 KVS 하나의 핸들. */
const kvs = cf.kvs();

/** CDN 비밀이 든 KVS 키 이름. 값 입력 스크립트와 같은 이름이다. */
const CDN_SECRET_KEY = 'cdn-secret';

/** 값을 못 얻었을 때 남기는 고정 문자열(경보 · 시험 T6b 가 찾는다). */
const UNAVAILABLE_LOG = 'CDN SECRET UNAVAILABLE';

/**
 * 경로를 거절할 때 남기는 고정 문자열. 런북 edge-tests.md 「경로 우회 시험」
 * 의 T2(방송 쿠키로 다른 방송 경로를 노리는 시험) 명령이 함수 로그에서 이
 * 문자열을 센다. 이 문자열을 보는 경보는 아직 없다.
 */
const PATH_DENY_LOG = 'LIVE PATH DENY';

/**
 * 원본으로 넘기는 /live 파일 끝. 원본(strings.HasSuffix)처럼 대소문자를
 * 구분한다.
 */
const MEDIA_SUFFIXES = Object.freeze(['.m3u8', '.mp4']);

/**
 * @param {!Object} event CloudFront Functions viewer-request 이벤트.
 * @return {!Promise<!Object>} Bearer 를 붙인 요청 또는 403 · 503 응답.
 */
async function handler(event) {
  const request = event.request;
  if (hasUnsafePath(request.uri) || !isMediaFile(request.uri)) {
    return forbidden();
  }
  let secret;
  try {
    secret = await kvs.get(CDN_SECRET_KEY);
  } catch (err) {
    return unavailable();
  }
  if (!secret) {
    return unavailable();
  }
  // 중복 헤더의 multiValue 가 남지 않게 객체를 통째로 바꾼다.
  request.headers.authorization = {value: `Bearer ${secret}`};
  return request;
}

/**
 * 고정 문자열을 남기고 원본으로 넘기지 않는 503 응답을 만든다.
 * @return {!Object}
 */
function unavailable() {
  console.log(UNAVAILABLE_LOG);
  return {statusCode: 503, statusDescription: 'Service Unavailable'};
}

/**
 * 경로 조작 표지가 있는지 본다. 정상 /live 경로(스트림 ID [A-Za-z0-9_-] ·
 * gohlslib 파일 이름)에는 `%` 가 없으므로 이스케이프는 종류 · 대소문자를
 * 가리지 않고 모두 표지로 본다.
 * @param {string} uri 쿼리 없는 요청 경로.
 * @return {boolean} `%` · 역슬래시 · 빈 구간(`//`) · 점 구간(`.` · `..`)
 *     가운데 하나라도 있으면 참.
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
 * 원본으로 넘기는 미디어 파일(목록 · 조각)인지 본다.
 * @param {string} uri 쿼리 없는 요청 경로.
 * @return {boolean} MEDIA_SUFFIXES 가운데 하나로 끝나면 참.
 */
function isMediaFile(uri) {
  for (let i = 0; i < MEDIA_SUFFIXES.length; i++) {
    if (uri.endsWith(MEDIA_SUFFIXES[i])) {
      return true;
    }
  }
  return false;
}

/**
 * 고정 문자열을 남기고 원본으로 넘기지 않는 본문 없는 403 응답을 만든다.
 * @return {!Object}
 */
function forbidden() {
  console.log(PATH_DENY_LOG);
  return {statusCode: 403, statusDescription: 'Forbidden'};
}
