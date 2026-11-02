// apex-request 함수(apex 배포 viewer-request) 단위 시험. 의존성 없이 node
// 만으로 돈다.
//
//   node --test infra/terraform/modules/cdn_static_site/functions/test/
//
// 계약(ADR-089 · 설계 4-1):
// - www.pokeclip.com 요청은 경로 · 쿼리를 보존해 https://pokeclip.com 으로
//   301 한다.
// - S3 OAC 는 디렉터리 색인을 모르므로 확장자 없는 경로에 /index.html 을
//   붙인다(/terms · /terms/ 모두). / 와 확장자 있는 경로는 그대로 둔다.
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

// 원문 로더는 cdn_media 시험의 것을 같이 쓴다.
import {
  findUnsupportedSyntax,
  loadHandler,
  readFunctionSource,
} from '../../../cdn_media/functions/test/cf-function-loader.mjs';

const FUNCTION_PATH =
    fileURLToPath(new URL('../apex-request.js', import.meta.url));

/** 시청자 요청 이벤트. */
function viewerRequestEvent(host, uri, querystring = {}) {
  return {
    version: '1.0',
    context: {eventType: 'viewer-request'},
    viewer: {ip: '198.51.100.11'},
    request: {
      method: 'GET',
      uri,
      querystring,
      headers: {host: {value: host}},
      cookies: {},
    },
  };
}

/** 301 응답 객체. */
function movedTo(location) {
  return {
    statusCode: 301,
    statusDescription: 'Moved Permanently',
    headers: {location: {value: location}},
  };
}

async function run(event) {
  const handler = loadHandler(FUNCTION_PATH);
  return handler(event);
}

const REDIRECT_CASES = [
  {
    name: '경로와 쿼리를 보존한다',
    host: 'www.pokeclip.com',
    uri: '/terms',
    query: {a: {value: '1'}, b: {value: 'x%20y'}},
    location: 'https://pokeclip.com/terms?a=1&b=x%20y',
  },
  {
    name: '루트는 쿼리 없이 / 로 간다',
    host: 'www.pokeclip.com',
    uri: '/',
    query: {},
    location: 'https://pokeclip.com/',
  },
  {
    name: '값 없는 쿼리도 보존한다',
    host: 'www.pokeclip.com',
    uri: '/privacy/',
    query: {ref: {value: ''}},
    location: 'https://pokeclip.com/privacy/?ref=',
  },
  {
    name: '같은 이름 쿼리 여럿(multiValue)을 모두 보존한다',
    host: 'www.pokeclip.com',
    uri: '/a',
    query: {
      q: {value: '1', multiValue: [{value: '1'}, {value: '2'}]},
      z: {value: '9'},
    },
    location: 'https://pokeclip.com/a?q=1&q=2&z=9',
  },
  {
    name: '확장자 있는 경로도 다시 쓰지 않고 보낸다',
    host: 'www.pokeclip.com',
    uri: '/og/cover.png',
    query: {},
    location: 'https://pokeclip.com/og/cover.png',
  },
  {
    name: 'Host 의 대소문자는 가리지 않는다',
    host: 'WWW.PokeClip.com',
    uri: '/terms',
    query: {},
    location: 'https://pokeclip.com/terms',
  },
];

for (const {name, host, uri, query, location} of REDIRECT_CASES) {
  test(`www → apex 301: ${name}`, async () => {
    const result = await run(viewerRequestEvent(host, uri, query));

    assert.deepEqual(result, movedTo(location));
  });
}

const REWRITE_CASES = [
  {uri: '/terms', rewritten: '/terms/index.html'},
  {uri: '/terms/', rewritten: '/terms/index.html'},
  {uri: '/privacy', rewritten: '/privacy/index.html'},
  {uri: '/privacy/', rewritten: '/privacy/index.html'},
  // 확장자 없는 경로 일반 규칙(설계 4-1) — 약관 지난 판 주소.
  {uri: '/terms/2026-10-01', rewritten: '/terms/2026-10-01/index.html'},
  {uri: '/', rewritten: '/'},
  {uri: '/index.html', rewritten: '/index.html'},
  {uri: '/styles.css', rewritten: '/styles.css'},
  {uri: '/og/cover.png', rewritten: '/og/cover.png'},
];

for (const {uri, rewritten} of REWRITE_CASES) {
  test(`apex ${uri} → ${rewritten}, 쿼리는 보존한다`, async () => {
    const query = {utm_source: {value: 'mail'}};
    const event = viewerRequestEvent('pokeclip.com', uri, query);

    const result = await run(event);

    assert.equal(result.uri, rewritten);
    assert.equal(result.method, 'GET');
    assert.deepEqual(result.querystring, {utm_source: {value: 'mail'}});
    assert.deepEqual(result.headers, {host: {value: 'pokeclip.com'}});
  });
}

test('cloudfront-js-2.0 이 지원하지 않는 문법을 쓰지 않는다', () => {
  const source = readFunctionSource(FUNCTION_PATH);

  assert.deepEqual(findUnsupportedSyntax(source), []);
  assert.match(source, /^async function handler\(event\) \{$/m);
});
