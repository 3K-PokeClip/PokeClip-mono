/**
 * @fileoverview apex 배포 viewer-request 함수(cloudfront-js-2.0, ADR-089 ·
 * 설계 4-1).
 * - www.pokeclip.com 요청은 경로 · 쿼리를 보존해 https://pokeclip.com 으로
 *   301 한다(www 는 쓰지 않고 리다이렉트만 둔다).
 * - S3 OAC 는 디렉터리 색인을 모르므로 확장자 없는 경로에 /index.html 을
 *   붙인다(/terms · /terms/ → /terms/index.html). / 는 배포의
 *   default_root_object 가 맡으므로 그대로 둔다.
 */

/** 리다이렉트할 호스트. Host 값은 소문자로 바꿔 비교한다. */
const WWW_HOST = 'www.pokeclip.com';

/** 리다이렉트 목적지의 출처. */
const APEX_ORIGIN = 'https://pokeclip.com';

/**
 * @param {!Object} event CloudFront Functions viewer-request 이벤트.
 * @return {!Promise<!Object>} 301 응답 또는 uri 를 고친 요청.
 */
async function handler(event) {
  const request = event.request;
  const host = request.headers.host;
  if (host && host.value.toLowerCase() === WWW_HOST) {
    return redirectToApex(request);
  }
  request.uri = withIndexDocument(request.uri);
  return request;
}

/**
 * 경로 · 쿼리를 보존한 apex 301 응답을 만든다.
 * @param {!Object} request 시청자 요청.
 * @return {!Object}
 */
function redirectToApex(request) {
  const query = serializeQuery(request.querystring);
  const location = `${APEX_ORIGIN}${request.uri}${query ? `?${query}` : ''}`;
  return {
    statusCode: 301,
    statusDescription: 'Moved Permanently',
    headers: {location: {value: location}},
  };
}

/**
 * 이벤트의 querystring 객체를 받은 순서대로 다시 문자열로 만든다. 값은 받은
 * 그대로 쓴다. 같은 이름이 여럿이면 multiValue 에 모두 들어 있다.
 * @param {!Object<string, !Object>} querystring
 * @return {string} `a=1&b=2` 꼴. 쿼리가 없으면 빈 문자열.
 */
function serializeQuery(querystring) {
  // 런타임 2.0 문장 목록에 for-of 가 없어 forEach 로 돈다.
  const pairs = [];
  Object.keys(querystring).forEach((name) => {
    const field = querystring[name];
    const values = field.multiValue || [field];
    values.forEach((item) => pairs.push(`${name}=${item.value}`));
  });
  return pairs.join('&');
}

/**
 * 확장자 없는 경로에 index.html 을 붙인다. 마지막 경로 조각에 `.` 이 있으면
 * 파일로 보고 그대로 둔다.
 * @param {string} uri `/` 로 시작하는 요청 경로.
 * @return {string}
 */
function withIndexDocument(uri) {
  if (uri === '/') {
    return uri;
  }
  if (uri.endsWith('/')) {
    return `${uri}index.html`;
  }
  const lastSegment = uri.slice(uri.lastIndexOf('/') + 1);
  if (lastSegment.includes('.')) {
    return uri;
  }
  return `${uri}/index.html`;
}
