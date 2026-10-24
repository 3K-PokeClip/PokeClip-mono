/**
 * @fileoverview media 배포 기본 비헤이비어 viewer-request 함수
 * (cloudfront-js-2.0). 어떤 경로 · 메서드 · 쿼리든 원본으로 넘기지 않고 본문
 * 없는 404 응답을 반환한다 — `/live` 없는 경로로 서명 쿠키를 우회해 원본에
 * 닿는 갈래를 막는다(설계 3-3 「기본 동작은 전부 거절」).
 */

/** 거절할 때 남기는 고정 문자열. */
const DENY_LOG = 'DENY DEFAULT';

/**
 * @param {!Object} event CloudFront Functions viewer-request 이벤트(쓰지
 *     않는다).
 * @return {!Promise<!Object>} 본문 없는 404 응답.
 */
async function handler(event) {
  console.log(DENY_LOG);
  return {statusCode: 404, statusDescription: 'Not Found'};
}
