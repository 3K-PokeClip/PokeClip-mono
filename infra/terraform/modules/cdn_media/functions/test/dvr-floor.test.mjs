// dvr-floor 함수(/dvr/* viewer-request) 단위 시험. 의존성 없이 node 만으로
// 돈다.
//
//   node --test infra/terraform/modules/cdn_media/functions/test/*.test.mjs
//
// 계약: 회차 꼴 조각 /dvr/{스트림}/{회차}/seg/{seq}.m4s 가 KVS 키
// {스트림}/{회차} 의 하한보다 앞(seq < 하한)이면 CORS 머리를 실은 본문 없는
// 404 로 막는다. 경로 조작 표지 · 안전 정수 밖 seq 는 KVS 를 읽지 않고 404
// 다. 판단 재료를 못 얻으면(키 없음 · KVS 읽기 실패 · 값 이상) 통과한다(K5
// (가)). 경로 문법 · KVS 키 · 값 문법은 Go 와 같은 공유 골든 셋으로 맞춘다.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

import {
  findUnsupportedSyntax,
  loadHandlerFromSource,
  readFunctionSource,
  renderTemplate,
} from './cf-function-loader.mjs';

const FUNCTION_PATH =
    fileURLToPath(new URL('../dvr-floor.js', import.meta.url));
// 모듈 기본값(variables.tf 의 app_origin)과 같은 출처다.
const APP_ORIGIN = 'https://app.pokeclip.com';
const SOURCE = readFunctionSource(FUNCTION_PATH);
const RENDERED = renderTemplate(SOURCE, {app_origin: APP_ORIGIN});

// 함수 크기 상한은 10 KB(조정 불가)인데 API · provider 검증은 그보다 커서
// 넘으면 apply 때에야 드러난다. 렌더본의 UTF-8 바이트로 보수적으로 잰다.
const MAX_RENDERED_BYTES = 10000;

const MALFORMED_LOG = 'DVR FLOOR MALFORMED';
const NOT_FOUND_RESPONSE = {
  statusCode: 404,
  statusDescription: 'Not Found',
  headers: {
    'cache-control': {value: 'no-store'},
    'access-control-allow-origin': {value: APP_ORIGIN},
    'access-control-allow-credentials': {value: 'true'},
    'vary': {value: 'origin'},
  },
};

/** 저장소 뿌리 기준 경로의 공유 골든 파일 URL. */
function goldenUrl(path) {
  return new URL('../../../../../../' + path, import.meta.url);
}

const SEG_KEY_GOLDEN = 'media/internal/playback/testdata/seg_key.golden.txt';
const KEY_GOLDEN = 'media/internal/rewind/edgebounds/testdata/key.golden.txt';
const VALUE_GOLDEN =
    'media/internal/rewind/edgebounds/testdata/value.golden.txt';

/**
 * 공유 골든을 읽는다. 줄 규약은 각 파일 머리 주석이 정본이다(`#` 줄 · 빈 줄
 * 건너뜀 · 탭 두 개로 나뉜 세 열).
 * @return {!Array<!Array<string>>}
 */
function readGolden(path) {
  const rows = [];
  const lines = readFileSync(goldenUrl(path), 'utf8').split('\n');
  for (const line of lines) {
    if (line === '' || line.startsWith('#')) {
      continue;
    }
    const columns = line.split('\t');
    assert.equal(columns.length, 3, `${path}: 세 열이 아닌 줄 ${line}`);
    rows.push(columns);
  }
  return rows;
}

/**
 * 가짜 cloudfront 모듈. 실제 KVS 처럼 get() 은 없는 키에 reject 하고
 * exists() 는 참 · 거짓을 낸다.
 * @param {!Object<string, string>} values KVS 에 든 키와 값.
 * @param {{failExists: (boolean|undefined), failGet: (boolean|undefined)}=}
 *     faults 참이면 그 호출이 reject(KVS 장애).
 */
function fakeCloudfront(values, {failExists = false, failGet = false} = {}) {
  const calls = {kvsArgs: [], reads: []};
  const handle = {
    async exists(key) {
      calls.reads.push('exists ' + key);
      if (failExists) {
        throw new Error('fake kvs: exists failed');
      }
      return Object.hasOwn(values, key);
    },
    async get(key) {
      calls.reads.push('get ' + key);
      if (failGet || !Object.hasOwn(values, key)) {
        throw new Error('fake kvs: get failed');
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

/** uri(와 메서드 · 쿼리)만 정한 시청자 요청 이벤트. */
function viewerRequestEvent(uri, {method = 'GET', querystring = {}} = {}) {
  return {
    version: '1.0',
    context: {eventType: 'viewer-request'},
    viewer: {ip: '198.51.100.11'},
    request: {
      method,
      uri,
      querystring,
      headers: {
        host: {value: 'media.pokeclip.com'},
        origin: {value: APP_ORIGIN},
      },
      cookies: {'CloudFront-Key-Pair-Id': {value: 'KFAKEKEYPAIR'}},
    },
  };
}

/** 렌더본을 실행하고 결과 · 로그 · KVS 호출 · 실행 전 요청을 돌려준다. */
async function run(uri, values = {}, options = {}) {
  const {cf, calls} = fakeCloudfront(values, options);
  const logs = [];
  const handler = loadHandlerFromSource(RENDERED, FUNCTION_PATH, {cf, logs});
  const event = viewerRequestEvent(uri, options);
  const before = structuredClone(event.request);
  const result = await handler(event);
  return {result, logs, calls, before};
}

/** 요청을 바꾸지 않고 원본으로 넘겼는지 본다. */
function assertPassed(outcome, logs = []) {
  assert.deepEqual(outcome.result, outcome.before);
  assert.deepEqual(outcome.logs, logs);
}

/** 본문 없는 404 와 로그 한 줄로 막았는지 본다. */
function assertNotFound(outcome, log) {
  assert.deepEqual(outcome.result, NOT_FOUND_RESPONSE);
  assert.deepEqual(outcome.logs, [log]);
}

/** KVS 를 읽지 않았는지 본다. */
function assertNoKvsRead(outcome) {
  assert.deepEqual(outcome.calls.reads, []);
}

/** 키를 exists() 다음 get() 으로 읽었는지 본다. */
function assertFloorRead(outcome, key) {
  assert.deepEqual(outcome.calls.reads, ['exists ' + key, 'get ' + key]);
}

/** seq 를 엣지 경로 꼴(6자리 이상 십진)로 쓴다. */
function seqText(seq) {
  return String(seq).padStart(6, '0');
}

/** 회차 s/r 의 조각 경로. */
function segmentUri(seq, stream = 's', round = 'r') {
  return '/dvr/' + stream + '/' + round + '/seg/' + seqText(seq) + '.m4s';
}

// --- 공유 골든: 조각 키 문법(Go ParseSegKey 와 같은 판정) ---

// 골든의 「엣지 정규식과 견주는 규칙 둘」 을 시험 쪽에서 판정한다. Go 가 받는
// 회차 꼴이고 seq 가 19자리 이하면 그 키로 하한을 본다. 그 밖(Go 가 받는 옛
// 꼴 · 20자리 이상 seq, Go 가 거절하는 모든 행)은 KVS 를 읽지 않는다.
const SEG_KEY_ROWS = readGolden(SEG_KEY_GOLDEN);

// err 행이 엣지에서 받는 결과를 함수와 따로 시험 쪽에서 정한다. 경로 조작
// 표지(%, 역슬래시, //, 점 구간)가 있거나, 엣지 정규식 안인데 seq 가 안전
// 정수 밖이면 MALFORMED 404 다. 나머지는 정규식 밖이라 통과다. 정규식은
// 함수의 ROUND_SEGMENT_PATH 와 같은 글자라 한 줄에 둔다.
const EDGE_SEGMENT_SHAPE =
    /^\/dvr\/[A-Za-z0-9._-]{1,128}\/[A-Za-z0-9._-]{1,128}\/seg\/([0-9]{6,19})\.m4s$/;
const PATH_MARKER = /[%\\]|\/\/|\/\.\.?(?=\/|$)/;

/**
 * @param {string} uri 골든 err 행의 엣지 경로.
 * @return {string} 'malformed' 또는 'pass'.
 */
function expectedErrOutcome(uri) {
  if (PATH_MARKER.test(uri)) {
    return 'malformed';
  }
  const shape = EDGE_SEGMENT_SHAPE.exec(uri);
  if (shape === null) {
    return 'pass';
  }
  // 정규식 안 · 안전 정수 seq 인 err 행은 KVS 를 읽게 되어 규칙 둘과 어긋난다.
  assert.ok(!Number.isSafeInteger(Number(shape[1])),
      `${SEG_KEY_GOLDEN}: 정규식 안 안전 정수 seq 인 err 행 ${uri}`);
  return 'malformed';
}

test('조각 키 골든이 비지 않았다(행 수 하한)', () => {
  assert.ok(SEG_KEY_ROWS.length >= 43, `행 ${SEG_KEY_ROWS.length}개`);
});

for (const [key, expected, verdict] of SEG_KEY_ROWS) {
  const uri = '/' + key;
  if (verdict === 'err') {
    if (expectedErrOutcome(uri) === 'malformed') {
      test(`골든 err ${uri} 는 KVS 없이 MALFORMED 404 다`, async () => {
        const outcome = await run(uri, {'s/r': '9007199254740991'});

        assertNoKvsRead(outcome);
        assertNotFound(outcome, MALFORMED_LOG);
      });
    } else {
      test(`골든 err ${uri} 는 정규식 밖이라 KVS 없이 통과한다`, async () => {
        const outcome = await run(uri, {'s/r': '9007199254740991'});

        assertNoKvsRead(outcome);
        assertPassed(outcome);
      });
    }
    continue;
  }
  assert.equal(verdict, 'ok', `${SEG_KEY_GOLDEN}: 판정 열 ${verdict}`);
  const [stream, round, seq] = expected.split(',');
  const digits = /\/seg\/([0-9]+)\.m4s$/.exec(key)[1];
  if (round === '' || digits.length > 19) {
    test(`골든 ok ${uri} 는 정규식 밖이라 KVS 없이 통과한다`, async () => {
      const outcome = await run(uri, {[stream + '/' + round]: '1'});

      assertNoKvsRead(outcome);
      assertPassed(outcome);
    });
    continue;
  }
  test(`골든 ok ${uri} 는 키 ${stream}/${round} 의 하한으로 막는다`,
      async () => {
        const floor = Number(seq) + 1;
        const floorKey = stream + '/' + round;

        const outcome = await run(uri, {[floorKey]: String(floor)});

        assertFloorRead(outcome, floorKey);
        assertNotFound(outcome, 'DVR FLOOR DENY ' + stream + ' ' + round +
            ' ' + seq + ' floor=' + floor);
      });
}

// --- 공유 골든: KVS 키(Go FloorKey 와 같은 키) ---

const KEY_ROWS = readGolden(KEY_GOLDEN);

test('KVS 키 골든이 비지 않았다(행 수 하한)', () => {
  assert.ok(KEY_ROWS.length >= 8, `행 ${KEY_ROWS.length}개`);
});

for (const [stream, round, key] of KEY_ROWS) {
  test(`스트림 ${stream} · 회차 ${round} 는 키 ${key} 를 묻는다`, async () => {
    const outcome = await run(segmentUri(7, stream, round));

    assert.deepEqual(outcome.calls.reads, ['exists ' + key]);
    assertPassed(outcome);
  });
}

// --- 공유 골든: KVS 값 문법(Go DecodeValue 와 같은 판정) ---

const VALUE_ROWS = readGolden(VALUE_GOLDEN);
const VALUE_INVALID_LOG = 'DVR FLOOR VALUE INVALID s r';

test('KVS 값 골든이 비지 않았다(행 수 하한)', () => {
  assert.ok(VALUE_ROWS.length >= 25, `행 ${VALUE_ROWS.length}개`);
});

for (const [raw, expected, verdict] of VALUE_ROWS) {
  if (verdict === 'err') {
    // seq 0 으로 묻는다 — 값을 1 이상으로 잘못 읽으면 DENY 가 나서 드러난다.
    test(`골든 err 값 ${raw} 는 VALUE INVALID 로 남기고 통과한다`,
        async () => {
          const outcome = await run(segmentUri(0), {'s/r': raw});

          assertFloorRead(outcome, 's/r');
          assertPassed(outcome, [VALUE_INVALID_LOG]);
        });
    continue;
  }
  assert.equal(verdict, 'ok', `${VALUE_GOLDEN}: 판정 열 ${verdict}`);
  const floor = Number(expected);
  test(`골든 ok 값 ${raw} 는 하한 ${expected} 로 읽는다`, async () => {
    const at = await run(segmentUri(floor), {'s/r': raw});
    const below = await run(segmentUri(floor - 1), {'s/r': raw});

    assertFloorRead(at, 's/r');
    assertPassed(at);
    assertNotFound(below, 'DVR FLOOR DENY s r ' + (floor - 1) + ' floor=' +
        expected);
  });
}

// 골든 줄 문법이 실을 수 없는 값(빈 값 · 공백 · 개행 · 탭 · CR · NUL)은 여기서
// 직접 본다(value.golden.txt 머리 주석). Go DecodeValue 는 다듬지 않고
// 거절한다.
const UNFRAMEABLE_VALUES = [
  '', ' 7', '7 ', '7\n', '\n7', '7\t', '\t7', '7\r', '7\r\n', '7\u0000',
];

for (const raw of UNFRAMEABLE_VALUES) {
  test(`값 ${JSON.stringify(raw)} 는 VALUE INVALID 로 남기고 통과한다`,
      async () => {
        const outcome = await run(segmentUri(0), {'s/r': raw});

        assertFloorRead(outcome, 's/r');
        assertPassed(outcome, [VALUE_INVALID_LOG]);
      });
}

// --- 하한 경계(웹 계약 불변식: 하한 그 조각은 막지 않는다) ---

test('seq = 하한은 통과 · 하한 - 1 은 404 · 하한 + 1 은 통과다', async () => {
  const values = {'s/r': '100'};

  const at = await run(segmentUri(100), values);
  const below = await run(segmentUri(99), values);
  const above = await run(segmentUri(101), values);

  assertPassed(at);
  assertNotFound(below, 'DVR FLOOR DENY s r 99 floor=100');
  assertPassed(above);
});

test('DENY 로그의 seq 는 앞자리 0 을 뗀 십진이다', async () => {
  const outcome = await run('/dvr/s/r/seg/0000099.m4s', {'s/r': '100'});

  assertNotFound(outcome, 'DVR FLOOR DENY s r 99 floor=100');
});

test('안전 정수 상한 seq 는 하한과 정확히 견준다', async () => {
  const values = {'s/r': '9007199254740991'};

  const at = await run('/dvr/s/r/seg/9007199254740991.m4s', values);
  const below = await run('/dvr/s/r/seg/9007199254740990.m4s', values);

  assertPassed(at);
  assertNotFound(below,
      'DVR FLOOR DENY s r 9007199254740990 floor=9007199254740991');
});

// Go ParseSegKey 는 int64 까지 받지만 엣지는 seq 를 배정밀도 수로 견주므로
// 2^53 이상은 MALFORMED 로 막는다. 실제 조각은 MSN(4초마다 1)만 쓰여 그
// 값에 닿지 않아 영향이 없다.
test('안전 정수를 넘는 seq 는 KVS 없이 MALFORMED 404 다', async () => {
  const outcome =
      await run('/dvr/s/r/seg/9007199254740992.m4s', {'s/r': '1'});

  assertNoKvsRead(outcome);
  assertNotFound(outcome, MALFORMED_LOG);
});

// --- KVS 읽기: 키 없음과 장애 가르기 ---

test('KVS 핸들은 인자 없이 얻는다', async () => {
  const outcome = await run(segmentUri(7));

  assert.deepEqual(outcome.calls.kvsArgs, [[]]);
});

test('키가 없으면 exists() 한 번만 묻고 로그 없이 통과한다', async () => {
  const outcome = await run(segmentUri(7), {});

  assert.deepEqual(outcome.calls.reads, ['exists s/r']);
  assertPassed(outcome);
});

test('exists() 가 실패하면 KVS ERROR 로 남기고 통과한다', async () => {
  const outcome =
      await run(segmentUri(7), {'s/r': '100'}, {failExists: true});

  assert.deepEqual(outcome.calls.reads, ['exists s/r']);
  assertPassed(outcome, ['DVR FLOOR KVS ERROR s r']);
});

// exists() 참 뒤 get() 사이에 키가 지워져도 KVS ERROR 로 센다 — 드문 경우라
// 한계로 둔다. 실제 장애는 실험대에서 일으킬 수 없어 이 가짜로만 본다.
test('exists() 참 뒤 get() 이 실패하면 KVS ERROR 로 남기고 통과한다',
    async () => {
      const outcome =
          await run(segmentUri(7), {'s/r': '100'}, {failGet: true});

      assertFloorRead(outcome, 's/r');
      assertPassed(outcome, ['DVR FLOOR KVS ERROR s r']);
    });

// 통과로 바꾸는 것은 KVS 읽기 실패뿐이다(K-D5 (a)). 최상위 catch 가 없어
// KVS 밖 예외는 그대로 던져져 런타임이 함수 오류로 센다 — 삼켜 통과시키면
// 그 지표에 드러나지 않는다.
test('KVS 밖에서 난 예외는 삼키지 않고 그대로 던진다', async () => {
  const {cf, calls} = fakeCloudfront({'s/r': '100'});
  const logs = [];
  const handler = loadHandlerFromSource(RENDERED, FUNCTION_PATH, {cf, logs});
  const event = viewerRequestEvent(segmentUri(7));
  const thrown = new Error('uri 를 읽을 수 없다');
  Object.defineProperty(event.request, 'uri', {
    get() {
      throw thrown;
    },
  });

  await assert.rejects(handler(event), (err) => err === thrown);
  assertNoKvsRead({calls});
  assert.deepEqual(logs, []);
});

// --- 경로 조작 표지(KVS 읽기 전 MALFORMED) ---

// 정규식 밖 꼴(목록 · init)에도 표지가 있으면 MALFORMED 다 — 표지 검사가
// 정규식보다 먼저여야 이 사례들이 404 가 된다.
const MALFORMED_URIS = [
  '/dvr/s/r/seg/%30%30%30%30%30%37.m4s',
  '/dvr/s/%41/index.m3u8',
  '/dvr/s/r/%2e%2e/index.m3u8',
  '/dvr/s/r/seg/..\\000007.m4s',
  '/dvr/s//r/seg/000007.m4s',
  '/dvr//init/S-1.mp4',
  '/dvr/s/./seg/000007.m4s',
  '/dvr/s/../r/seg/000007.m4s',
  '/dvr/s/r/seg/000007.m4s/..',
  '/dvr/./s/index.m3u8',
];

for (const uri of MALFORMED_URIS) {
  test(`표지가 든 ${uri} 는 KVS 없이 MALFORMED 404 다`, async () => {
    const outcome = await run(uri, {'s/r': '1'});

    assertNoKvsRead(outcome);
    assertNotFound(outcome, MALFORMED_LOG);
  });
}

test('KVS 장애여도 표지가 든 경로는 MALFORMED 404 다', async () => {
  const outcome = await run('/dvr/s/r/seg/%2e%2e/000007.m4s', {},
      {failExists: true, failGet: true});

  assertNoKvsRead(outcome);
  assertNotFound(outcome, MALFORMED_LOG);
});

// 원문 경로와 RFC 3986 정규화본(이스케이프 대문자화 · 비예약 문자 디코드 · 점
// 구간 제거)에 CloudFront 의 // 접기를 거친 꼴의 쌍이다. 함수는 원문을
// 받는다(실험대 (4) R9 · 2026-11-11 — G1 · POK-548). 정규화본 결과도 함수의
// 거동으로 함께 고정한다. 하한은 3 이다. 정규화본이 표지 없이 하한 판정을
// 받는 쌍은 한계 고정이다 — 원본(S3)은 원문 경로를 받는다.
const PATH_PAIRS = [
  {
    raw: '/dvr/s/x/../r/seg/000004.m4s',
    normalized: '/dvr/s/r/seg/000004.m4s',
    normalizedVerdict: 'pass',
  },
  {
    raw: '/dvr/s/x/../r/seg/000002.m4s',
    normalized: '/dvr/s/r/seg/000002.m4s',
    normalizedVerdict: 'deny',
  },
  {
    raw: '/dvr/s/r/seg/./000002.m4s',
    normalized: '/dvr/s/r/seg/000002.m4s',
    normalizedVerdict: 'deny',
  },
  {
    raw: '/dvr/s/r//seg/000002.m4s',
    normalized: '/dvr/s/r/seg/000002.m4s',
    normalizedVerdict: 'deny',
  },
  {
    raw: '/dvr/s/r/seg/%30%30%30%30%30%32.m4s',
    normalized: '/dvr/s/r/seg/000002.m4s',
    normalizedVerdict: 'deny',
  },
  // 정규화로 seg 구간이 사라져 회차 꼴이 아니게 된다 — 하한 판정 없이 통과.
  {
    raw: '/dvr/s/r/seg/%2e%2e/000002.m4s',
    normalized: '/dvr/s/r/000002.m4s',
    normalizedVerdict: 'pass',
  },
  {
    raw: '/dvr/s/r/seg/..%2f000002.m4s',
    normalized: '/dvr/s/r/seg/..%2F000002.m4s',
    normalizedVerdict: 'malformed',
  },
  {
    raw: '/dvr/s/r/seg/..%5c000002.m4s',
    normalized: '/dvr/s/r/seg/..%5C000002.m4s',
    normalizedVerdict: 'malformed',
  },
  {
    raw: '/dvr/s/r/seg/..\\000002.m4s',
    normalized: '/dvr/s/r/seg/..\\000002.m4s',
    normalizedVerdict: 'malformed',
  },
  // 이중 인코딩. %25 는 비예약 문자가 아니라 정규화 뒤에도 남는다.
  {
    raw: '/dvr/s/r/seg/%252e%252e/000002.m4s',
    normalized: '/dvr/s/r/seg/%252e%252e/000002.m4s',
    normalizedVerdict: 'malformed',
  },
];

for (const {raw, normalized, normalizedVerdict} of PATH_PAIRS) {
  test(`원문 ${raw} 는 KVS 없이 MALFORMED 404 다`, async () => {
    const outcome = await run(raw, {'s/r': '3'});

    assertNoKvsRead(outcome);
    assertNotFound(outcome, MALFORMED_LOG);
  });

  test(`정규화본 ${normalized}(원문 ${raw}) 는 ${normalizedVerdict}`,
      async () => {
        const outcome = await run(normalized, {'s/r': '3'});

        if (normalizedVerdict === 'malformed') {
          assertNoKvsRead(outcome);
          assertNotFound(outcome, MALFORMED_LOG);
        } else if (normalizedVerdict === 'deny') {
          assertNotFound(outcome, 'DVR FLOOR DENY s r 2 floor=3');
        } else {
          assertPassed(outcome);
        }
      });
}

// --- 하한을 보지 않는 꼴 ---

const PASS_THROUGH_URIS = [
  '/dvr/s/seg/000001.m4s',
  '/dvr/s/r/index.m3u8',
  '/dvr/s/index.m3u8',
  '/dvr/s/init/S-1.mp4',
  '/dvr/s/r/seg/00001.m4s',
  '/dvr/s/r/seg/000001.mp4',
  '/dvr/s/r/seg/000001.m4s;p',
  '/dvr/s/a/b/seg/000001.m4s',
  '/dvr/s/r/seg/00000000000000000001.m4s',
  // 정규식 앞 고정(^)이 빠지면 이 경로가 하한 판정을 받는다.
  '/x/dvr/s/r/seg/000001.m4s',
];

for (const uri of PASS_THROUGH_URIS) {
  test(`회차 꼴 조각이 아닌 ${uri} 는 KVS 없이 통과한다`, async () => {
    const outcome = await run(uri, {'s/r': '100'});

    assertNoKvsRead(outcome);
    assertPassed(outcome);
  });
}

// --- 메서드 · 쿼리 ---

test('OPTIONS 는 경로를 보지 않고 KVS 없이 통과한다', async () => {
  const below = await run(segmentUri(1), {'s/r': '100'}, {method: 'OPTIONS'});
  const malformed =
      await run('/dvr/s/%2e%2e/index.m3u8', {}, {method: 'OPTIONS'});

  assertNoKvsRead(below);
  assertPassed(below);
  assertNoKvsRead(malformed);
  assertPassed(malformed);
});

test('HEAD 도 GET 과 같은 하한 규칙을 탄다', async () => {
  const below = await run(segmentUri(1), {'s/r': '100'}, {method: 'HEAD'});
  const at = await run(segmentUri(100), {'s/r': '100'}, {method: 'HEAD'});

  assertNotFound(below, 'DVR FLOOR DENY s r 1 floor=100');
  assertPassed(at);
});

test('쿼리는 검사하지 않는다(값에 %2e · .. 가 있어도 경로로만 판정)',
    async () => {
      const querystring = {x: {value: '%2e%2e/..%5c'}, y: {value: '1'}};

      const at = await run(segmentUri(100), {'s/r': '100'}, {querystring});
      const below = await run(segmentUri(99), {'s/r': '100'}, {querystring});

      assertPassed(at);
      assertNotFound(below, 'DVR FLOOR DENY s r 99 floor=100');
    });

// --- 원문 · 렌더본 규칙 ---

test('cloudfront-js-2.0 이 지원하지 않는 문법을 쓰지 않는다', () => {
  assert.deepEqual(findUnsupportedSyntax(SOURCE), []);
  assert.match(SOURCE, /^import cf from 'cloudfront';$/m);
  assert.match(SOURCE, /^async function handler\(event\) \{$/m);
});

// templatefile 이 백틱 안의 자리표시자도 해석하므로 원문에 백틱을 두지 않는다.
test('원문에 백틱이 없다', () => {
  assert.equal(SOURCE.indexOf('`'), -1);
});

test('원문의 templatefile 문법은 app_origin 자리표시자 하나뿐이다', () => {
  assert.deepEqual(SOURCE.match(/[$%]\{/g), ['${']);
  assert.deepEqual(SOURCE.match(/\$\{[^}]*\}/g), ['${app_origin}']);
});

test('렌더본에 templatefile 문법이 남지 않고 출처가 들어간다', () => {
  assert.equal(RENDERED.match(/[$%]\{/g), null);
  assert.equal(RENDERED.split('\'' + APP_ORIGIN + '\'').length - 1, 1);
});

test(`렌더본 UTF-8 바이트가 ${MAX_RENDERED_BYTES} 보다 작다`, () => {
  const bytes = Buffer.byteLength(RENDERED, 'utf8');

  assert.ok(bytes < MAX_RENDERED_BYTES, `렌더본 ${bytes} 바이트`);
});
