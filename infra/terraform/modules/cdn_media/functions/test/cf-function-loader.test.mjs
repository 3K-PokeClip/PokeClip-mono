// 시험 로더 자체의 시험. 문법 검사가 아무것도 못 잡으면 함수 시험의 「미지원
// 문법 없음」 단언이 헛돈다.
//
//   node --test infra/terraform/modules/cdn_media/functions/test/
import assert from 'node:assert/strict';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {after, test} from 'node:test';

import {findUnsupportedSyntax, loadHandler} from './cf-function-loader.mjs';

const dir = mkdtempSync(join(tmpdir(), 'cf-function-loader-'));
after(() => rmSync(dir, {recursive: true, force: true}));

/** 임시 함수 파일을 쓰고 경로를 돌려준다. */
function writeFunction(name, source) {
  const path = join(dir, name);
  writeFileSync(path, source);
  return path;
}

const UNSUPPORTED_CASES = [
  {name: '선택적 연결 ?.', source: 'const a = b?.c;'},
  {name: '널 병합 ??', source: 'const a = b ?? c;'},
  {name: '전개 ...', source: 'const a = {...b};'},
  {name: 'export', source: 'export function handler() {}'},
  {name: 'eval', source: 'eval("1");'},
  {name: 'Function 생성자', source: 'const f = new Function("return 1");'},
  {name: '타이머', source: 'setTimeout(f, 1);'},
  {name: 'process', source: 'const e = process.env;'},
];

for (const {name, source} of UNSUPPORTED_CASES) {
  test(`미지원 문법 ${name} 을 찾는다`, () => {
    assert.deepEqual(findUnsupportedSyntax(source), [name]);
  });
}

test('주석과 삼항 연산자의 소수는 미지원 문법으로 치지 않는다', () => {
  const source = '// a?.b\n/* c ?? d */\nconst x = y ? .5 : 1;';

  assert.deepEqual(findUnsupportedSyntax(source), []);
});

test('import 줄을 가짜 cf 로 바꿔 handler 를 꺼낸다', async () => {
  const path = writeFunction('uses-cf.js', [
    'import cf from \'cloudfront\';',
    'const kvs = cf.kvs();',
    'async function handler(event) {',
    '  return {value: await kvs.get(event.key)};',
    '}',
  ].join('\n'));
  const cf = {kvs: () => ({get: async (key) => `got ${key}`})};

  const handler = loadHandler(path, {cf});

  assert.deepEqual(await handler({key: 'k'}), {value: 'got k'});
});

test('console.log 를 인자 둘로 부르면 실패한다(런타임 미지원)', async () => {
  const path = writeFunction('comma-log.js', [
    'async function handler(event) {',
    '  console.log(\'a\', \'b\');',
    '  return event;',
    '}',
  ].join('\n'));

  const handler = loadHandler(path);

  await assert.rejects(handler({}), /인자 하나만/);
});

test('cloudfront 밖의 import 는 거부한다', () => {
  const path = writeFunction('other-import.js', [
    'import fs from \'fs\';',
    'async function handler(event) { return event; }',
  ].join('\n'));

  assert.throws(() => loadHandler(path), /import · export/);
});
