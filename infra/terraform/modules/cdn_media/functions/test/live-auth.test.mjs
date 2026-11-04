// live-auth 함수(/live/* viewer-request) 단위 시험. 의존성 없이 node 만으로
// 돈다.
//
//   node --test infra/terraform/modules/cdn_media/functions/test/
//
// 계약: KVS 키 cdn-secret 의 값(MediaMTX hlsCDNSecret 원문)으로 원본 요청의
// Authorization 을 `Bearer <값>` 으로 항상 덮어쓴다. 값을 못 얻으면(KVS 예외 ·
// 빈 값) 원본으로 넘기지 않고 503 을 내며 고정 문자열을 남긴다(fail-closed).
// 그보다 먼저, 경로 조작 표지(`%` · 역슬래시 · 빈 구간 · 점 구간)가 있는
// 경로는 KVS 를 읽지 않고 본문 없는 403 과 고정 문자열로 거절한다.
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
const PATH_DENY_LOG = 'LIVE PATH DENY';
const FORBIDDEN_RESPONSE = {statusCode: 403, statusDescription: 'Forbidden'};

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

/** uri(와 쿼리)만 바꾼 시청자 요청 이벤트. */
function eventWithUri(uri, querystring = undefined) {
  const event = viewerRequestEvent();
  event.request.uri = uri;
  if (querystring) {
    event.request.querystring = querystring;
  }
  return event;
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

/**
 * 원본에 넘기는 요청인지 본다. Bearer 를 덮어쓰고 uri · 쿼리 · 쿠키는 그대로,
 * 로그는 없다.
 */
function assertForwarded({result, logs}, before) {
  assert.equal(result.uri, before.uri);
  assert.deepEqual(result.querystring, before.querystring);
  assert.deepEqual(result.cookies, before.cookies);
  assert.deepEqual(
      result.headers.authorization, {value: `Bearer ${FAKE_SECRET}`});
  assert.deepEqual(logs, []);
}

/** KVS 를 읽지 않고 403 과 고정 문자열로 거절했는지 본다. */
function assertPathDenied({result, logs, calls}) {
  assert.deepEqual(result, FORBIDDEN_RESPONSE);
  assert.deepEqual(logs, [PATH_DENY_LOG]);
  assert.deepEqual(calls.keys, []);
}

// 원문 경로와, RFC 3986 정규화(6.2.2 이스케이프 대문자화 · 비예약 문자 디코드 ·
// 5.2.4 점 구간 제거)와 CloudFront 의 `//` 접기를 거친 정규화본의 쌍이다.
// CloudFront 가 함수에 어느 쪽을 주는지(G1)는 미확정이라(POK-548) 두 쪽 결과를
// 모두 고정한다. normalizedDenied 가 거짓인 쌍은 한계 고정이다 — 정규화로
// 사라진 점 구간 · 빈 구간은 함수가 보지 못하고 서명 · nginx 몫이다.
const PATH_PAIRS = [
  // POK-458 T2 에서 엣지를 지나 nginx 400 까지 간 갈래다.
  {
    raw: '/live/abc123/..%5cdef456/index.m3u8',
    normalized: '/live/abc123/..%5Cdef456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live/abc123/..%5Cdef456/index.m3u8',
    normalized: '/live/abc123/..%5Cdef456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live/abc123/..\\def456/index.m3u8',
    normalized: '/live/abc123/..\\def456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live/abc123/%2e%2e/def456/index.m3u8',
    normalized: '/live/def456/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123/%2E%2E/def456/index.m3u8',
    normalized: '/live/def456/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123/%2e/index.m3u8',
    normalized: '/live/abc123/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123/..%2fdef456/index.m3u8',
    normalized: '/live/abc123/..%2Fdef456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live/abc123/..%2Fdef456/index.m3u8',
    normalized: '/live/abc123/..%2Fdef456/index.m3u8',
    normalizedDenied: true,
  },
  // 이중 인코딩. `%25` 는 비예약 문자가 아니라 정규화 뒤에도 남는다.
  {
    raw: '/live/abc123/%252e%252e/def456/index.m3u8',
    normalized: '/live/abc123/%252e%252e/def456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live/abc123/%252E%252E/def456/index.m3u8',
    normalized: '/live/abc123/%252E%252E/def456/index.m3u8',
    normalizedDenied: true,
  },
  {
    raw: '/live//abc123/index.m3u8',
    normalized: '/live/abc123/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123//index.m3u8',
    normalized: '/live/abc123/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123//../def456/index.m3u8',
    normalized: '/live/def456/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/abc123/../def456/index.m3u8',
    normalized: '/live/def456/index.m3u8',
    normalizedDenied: false,
  },
  {
    raw: '/live/./abc123/index.m3u8',
    normalized: '/live/abc123/index.m3u8',
    normalizedDenied: false,
  },
  // 정규화본 `/live/` 는 경로 위생만으로는 거절할 표지가 없다.
  {
    raw: '/live/abc123/..',
    normalized: '/live/',
    normalizedDenied: false,
  },
];

for (const {raw, normalized, normalizedDenied} of PATH_PAIRS) {
  test(`원문 ${raw} 는 KVS 를 읽지 않고 403 이다`, async () => {
    assertPathDenied(await run(eventWithUri(raw), {[SECRET_KEY]: FAKE_SECRET}));
  });

  const verdict = normalizedDenied ? '403' : '원본으로 넘긴다(한계 고정)';
  test(`정규화본 ${normalized}(원문 ${raw}) 는 ${verdict}`, async () => {
    const event = eventWithUri(normalized);
    const before = structuredClone(event.request);

    const outcome = await run(event, {[SECRET_KEY]: FAKE_SECRET});

    if (normalizedDenied) {
      assertPathDenied(outcome);
    } else {
      assertForwarded(outcome, before);
    }
  });
}

// gohlslib 가 내는 실제 이름 꼴이다(접두 = 무작위 12자리 16진, 스트림 = main ·
// video<N> · audio<N>). 스트림 ID 는 web STREAM_ID_RE([A-Za-z0-9_-]) 를 따른다.
const NORMAL_URIS = [
  '/live/abc123/index.m3u8',
  '/live/abc_12-3/index.m3u8',
  '/live/abc123/main_stream.m3u8',
  '/live/abc123/audio2_stream.m3u8',
  '/live/abc123/0123456789ab_main_init.mp4',
  '/live/abc123/0123456789ab_video1_seg42.mp4',
  '/live/abc123/0123456789ab_audio2_part107.mp4',
  '/live/abc123/gap.mp4',
];

for (const uri of NORMAL_URIS) {
  test(`정상 경로 ${uri} 는 Bearer 를 붙여 원본으로 넘긴다`, async () => {
    const event = eventWithUri(uri);
    const before = structuredClone(event.request);

    assertForwarded(await run(event, {[SECRET_KEY]: FAKE_SECRET}), before);
  });
}

test('쿼리는 검사하지 않는다(값에 %2e · .. 가 있어도 넘긴다)', async () => {
  const event = eventWithUri('/live/abc123/main_stream.m3u8', {
    _HLS_msn: {value: '42'},
    _HLS_part: {value: '1'},
    _HLS_skip: {value: 'YES'},
    cookieCheck: {value: '1'},
    x: {value: '%2e%2e/..%5c'},
  });
  const before = structuredClone(event.request);

  assertForwarded(await run(event, {[SECRET_KEY]: FAKE_SECRET}), before);
});

test('KVS 장애여도 나쁜 경로는 503 이 아니라 403 이다', async () => {
  const event = eventWithUri('/live/abc123/..%5cdef456/index.m3u8');

  assertPathDenied(await run(event, {}, true));
});

test('cloudfront-js-2.0 이 지원하지 않는 문법을 쓰지 않는다', () => {
  const source = readFunctionSource(FUNCTION_PATH);

  assert.deepEqual(findUnsupportedSyntax(source), []);
  assert.match(source, /^import cf from 'cloudfront';$/m);
  assert.match(source, /^async function handler\(event\) \{$/m);
});
