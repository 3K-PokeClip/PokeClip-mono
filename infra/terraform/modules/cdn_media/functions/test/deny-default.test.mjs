// deny-default 함수(media 배포 기본 비헤이비어 viewer-request) 단위 시험.
// 의존성 없이 node 만으로 돈다.
//
//   node --test infra/terraform/modules/cdn_media/functions/test/
//
// 계약: 어떤 경로 · 메서드 · 쿼리든 원본으로 넘기지 않고 본문 없는 404 응답을
// 반환하며 고정 문자열을 남긴다(설계 3-3 「기본 동작은 전부 거절」).
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

import {
  findUnsupportedSyntax,
  loadHandler,
  readFunctionSource,
} from './cf-function-loader.mjs';

const FUNCTION_PATH =
    fileURLToPath(new URL('../deny-default.js', import.meta.url));
const DENY_LOG = 'DENY DEFAULT';
const NOT_FOUND_RESPONSE = {statusCode: 404, statusDescription: 'Not Found'};

/** 시청자 요청 이벤트. */
function viewerRequestEvent(method, uri, querystring = {}) {
  return {
    version: '1.0',
    context: {eventType: 'viewer-request'},
    viewer: {ip: '198.51.100.11'},
    request: {
      method,
      uri,
      querystring,
      headers: {host: {value: 'media.pokeclip.com'}},
      cookies: {},
    },
  };
}

const CASES = [
  {method: 'GET', uri: '/'},
  {method: 'GET', uri: '/x'},
  // /live 없는 경로로 서명 쿠키를 우회해 원본에 닿는 갈래(설계 3-3).
  {method: 'GET', uri: '/abc123/index.m3u8'},
  {method: 'GET', uri: '/abc123/index.m3u8', query: {_HLS_msn: {value: '1'}}},
  {method: 'HEAD', uri: '/robots.txt'},
  {method: 'OPTIONS', uri: '/anything'},
  {method: 'POST', uri: '/api/other', query: {a: {value: '1'}}},
  {method: 'DELETE', uri: '/api/streams/1'},
];

for (const {method, uri, query} of CASES) {
  const suffix = query ? ' (쿼리 있음)' : '';
  test(`${method} ${uri}${suffix} 는 본문 없는 404 를 반환한다`, async () => {
    const event = viewerRequestEvent(method, uri, query);
    const logs = [];
    const handler = loadHandler(FUNCTION_PATH, {logs});

    const result = await handler(event);

    assert.deepEqual(result, NOT_FOUND_RESPONSE);
    assert.deepEqual(logs, [DENY_LOG]);
  });
}

test('반환 객체는 요청이 아니라 응답이다(원본으로 넘기지 않는다)', async () => {
  const event = viewerRequestEvent('GET', '/live/abc123/index.m3u8');
  const handler = loadHandler(FUNCTION_PATH);

  const result = await handler(event);

  assert.notEqual(result, event.request);
  assert.equal('uri' in result, false);
  assert.equal('method' in result, false);
  assert.equal('body' in result, false);
});

test('cloudfront-js-2.0 이 지원하지 않는 문법을 쓰지 않는다', () => {
  const source = readFunctionSource(FUNCTION_PATH);

  assert.deepEqual(findUnsupportedSyntax(source), []);
  assert.match(source, /^async function handler\(event\) \{$/m);
});
