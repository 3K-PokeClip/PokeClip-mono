// live-auth 함수(/live/* viewer-request) 단위 시험. 의존성 없이 node 만으로
// 돈다.
//
//   node --test infra/terraform/modules/cdn_media/functions/test/
//
// 계약: KVS 키 cdn-secret 의 값(MediaMTX hlsCDNSecret 원문)으로 원본 요청의
// Authorization 을 `Bearer <값>` 으로 항상 덮어쓴다. 값을 못 얻으면(KVS 예외 ·
// 빈 값) 원본으로 넘기지 않고 503 을 내며 고정 문자열을 남긴다(fail-closed).
import assert from 'node:assert/strict';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

import {
  findUnsupportedSyntax,
  loadHandler,
  readFunctionSource,
} from './cf-function-loader.mjs';

const FUNCTION_PATH =
    fileURLToPath(new URL('../live-auth.js', import.meta.url));
const SECRET_KEY = 'cdn-secret';
// 시험 전용 가짜 값이다. 실제 비밀이 아니다.
const FAKE_SECRET = 'edgebench-fake-cdn-secret';
const UNAVAILABLE_LOG = 'CDN SECRET UNAVAILABLE';
const UNAVAILABLE_RESPONSE = {
  statusCode: 503,
  statusDescription: 'Service Unavailable',
};

/**
 * 가짜 cloudfront 모듈. 실제 KVS 처럼 없는 키를 읽으면 예외를 던진다.
 * @param {!Object<string, string>} values KVS 에 든 키와 값.
 * @param {boolean=} failing 참이면 어떤 키든 예외(KVS 장애).
 */
function fakeCloudfront(values, failing = false) {
  const calls = {kvsArgs: [], keys: []};
  const handle = {
    async get(key) {
      calls.keys.push(key);
      if (failing || !Object.hasOwn(values, key)) {
        throw new Error(`fake kvs: ${key} not found`);
      }
      return values[key];
    },
  };
  const cf = {
    kvs(...args) {
      calls.kvsArgs.push(args);
      return handle;
    },
  };
  return {cf, calls};
}

/** 시청자 요청 이벤트. 헤더를 더 줄 수 있다. */
function viewerRequestEvent(extraHeaders = {}) {
  return {
    version: '1.0',
    context: {eventType: 'viewer-request'},
    viewer: {ip: '198.51.100.11'},
    request: {
      method: 'GET',
      uri: '/live/abc123/index.m3u8',
      querystring: {_HLS_msn: {value: '42'}, _HLS_part: {value: '1'}},
      headers: {host: {value: 'media.pokeclip.com'}, ...extraHeaders},
      cookies: {'CloudFront-Key-Pair-Id': {value: 'KFAKEKEYPAIR'}},
    },
  };
}

/** 함수를 실행하고 결과와 로그 · KVS 호출을 돌려준다. */
async function run(event, values, failing = false) {
  const {cf, calls} = fakeCloudfront(values, failing);
  const logs = [];
  const handler = loadHandler(FUNCTION_PATH, {cf, logs});
  const result = await handler(event);
  return {result, logs, calls};
}

test('KVS 키 이름은 cdn-secret 이고 KVS 핸들은 인자 없이 얻는다', async () => {
  const {calls} = await run(viewerRequestEvent(), {[SECRET_KEY]: FAKE_SECRET});

  assert.deepEqual(calls.keys, [SECRET_KEY]);
  assert.deepEqual(calls.kvsArgs, [[]]);
});

test('KVS 값으로 Authorization 을 Bearer 로 설정해 원본으로 넘긴다', async () => {
  const event = viewerRequestEvent();
  const before = structuredClone(event.request);

  const {result, logs} =
      await run(event, {[SECRET_KEY]: FAKE_SECRET});

  assert.equal(result.uri, before.uri);
  assert.equal(result.method, before.method);
  assert.deepEqual(result.querystring, before.querystring);
  assert.deepEqual(result.cookies, before.cookies);
  assert.deepEqual(result.headers, {
    ...before.headers,
    authorization: {value: `Bearer ${FAKE_SECRET}`},
  });
  assert.deepEqual(logs, []);
});

test('시청자 Authorization 이 있어도 KVS 값으로 덮어쓴다', async () => {
  const event = viewerRequestEvent({
    authorization: {value: 'Bearer viewer-supplied'},
  });

  const {result} = await run(event, {[SECRET_KEY]: FAKE_SECRET});

  assert.deepEqual(
      result.headers.authorization, {value: `Bearer ${FAKE_SECRET}`});
});

test('시청자가 Authorization 을 여럿 보내도 하나로 덮어쓴다', async () => {
  // 중복 헤더는 multiValue 로 온다. multiValue 가 남아 있고 바뀌었으면 런타임은
  // value 대신 multiValue 를 적용하므로(이벤트 구조 문서) 통째로 바꿔야 한다.
  const event = viewerRequestEvent({
    authorization: {
      value: 'Bearer viewer-1',
      multiValue: [{value: 'Bearer viewer-1'}, {value: 'Bearer viewer-2'}],
    },
  });

  const {result} = await run(event, {[SECRET_KEY]: FAKE_SECRET});

  assert.deepEqual(
      result.headers.authorization, {value: `Bearer ${FAKE_SECRET}`});
});

test('KVS 조회가 예외면 503 을 내고 원본으로 넘기지 않는다', async () => {
  const {result, logs} = await run(viewerRequestEvent(), {}, true);

  assert.deepEqual(result, UNAVAILABLE_RESPONSE);
  assert.deepEqual(logs, [UNAVAILABLE_LOG]);
});

test('KVS 에 키가 없으면 503 이다', async () => {
  const {result, logs} = await run(viewerRequestEvent(), {});

  assert.deepEqual(result, UNAVAILABLE_RESPONSE);
  assert.deepEqual(logs, [UNAVAILABLE_LOG]);
});

test('KVS 값이 빈 문자열이면 503 과 고정 로그 문자열이다', async () => {
  const {result, logs} = await run(viewerRequestEvent(), {[SECRET_KEY]: ''});

  assert.deepEqual(result, UNAVAILABLE_RESPONSE);
  assert.deepEqual(logs, [UNAVAILABLE_LOG]);
});

test('cloudfront-js-2.0 이 지원하지 않는 문법을 쓰지 않는다', () => {
  const source = readFunctionSource(FUNCTION_PATH);

  assert.deepEqual(findUnsupportedSyntax(source), []);
  assert.match(source, /^import cf from 'cloudfront';$/m);
  assert.match(source, /^async function handler\(event\) \{$/m);
});
