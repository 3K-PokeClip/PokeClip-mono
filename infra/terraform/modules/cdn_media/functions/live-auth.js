/**
 * @fileoverview media 배포 /live/* viewer-request 함수(cloudfront-js-2.0).
 * 연결된 KVS 의 cdn-secret 값(MediaMTX hlsCDNSecret 원문)으로 원본 요청의
 * Authorization 을 `Bearer <값>` 으로 항상 덮어쓴다. 시청자가 보낸
 * Authorization 은 원본에 닿지 않는다. 값을 못 얻으면(KVS 예외 · 키 없음 ·
 * 빈 값) 원본으로 넘기지 않고 503 을 낸다(fail-closed). 로그에는 값을 남기지
 * 않고 고정 문자열만 남긴다.
 */

import cf from 'cloudfront';

/** 이 함수에 연결된 KVS 하나의 핸들. */
const kvs = cf.kvs();

/** CDN 비밀이 든 KVS 키 이름. 값 입력 스크립트와 같은 이름이다. */
const CDN_SECRET_KEY = 'cdn-secret';

/** 값을 못 얻었을 때 남기는 고정 문자열(경보 · 시험 T6b 가 찾는다). */
const UNAVAILABLE_LOG = 'CDN SECRET UNAVAILABLE';

/**
 * @param {!Object} event CloudFront Functions viewer-request 이벤트.
 * @return {!Promise<!Object>} Bearer 를 붙인 요청 또는 503 응답.
 */
async function handler(event) {
  let secret;
  try {
    secret = await kvs.get(CDN_SECRET_KEY);
  } catch (err) {
    return unavailable();
  }
  if (!secret) {
    return unavailable();
  }
  const request = event.request;
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
