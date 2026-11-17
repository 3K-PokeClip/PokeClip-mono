/**
 * @fileoverview CloudFront Functions(cloudfront-js-2.0) 원문을 node 시험에서
 * 평가하는 로더. 함수 파일은 `import cf from 'cloudfront';` 로 시작하고
 * `handler` 를 export 하지 않아 node 모듈로 바로 import 되지 않는다. 그래서
 * 원문을 읽어 그 import 줄을 가짜 `cf` 주입으로 바꾸고 `handler` 를 꺼낸다.
 *
 * node 는 CloudFront 런타임이 아니다. 런타임 제약 가운데 원문에서 기계로
 * 찾을 수 있는 것만 findUnsupportedSyntax 와 가짜 console 이 잡는다. 근거는
 * AWS 문서 「JavaScript runtime 2.0 features for CloudFront Functions」
 * (functions-javascript-runtime-20.html, 2026-10-24 확인).
 *
 * cdn_static_site 의 시험도 이 로더를 쓴다.
 */
import {readFileSync} from 'node:fs';

/** 런타임 문서가 KVS 를 쓰는 함수의 첫 줄로 정한 import 문. */
const CLOUDFRONT_IMPORT = /^import cf from 'cloudfront';$/m;

/**
 * 원문에서 찾을 수 있는 런타임 2.0 미지원 문법. 문서의 지원 목록에 없거나
 * 「Restricted features」 에 든 것들이다.
 */
const UNSUPPORTED_SYNTAX = [
  {name: '선택적 연결 ?.', pattern: /\?\.(?!\d)/},
  {name: '널 병합 ??', pattern: /\?\?/},
  {name: '전개 ...', pattern: /\.\.\./},
  {name: 'export', pattern: /^\s*export\b/m},
  {name: 'eval', pattern: /\beval\s*\(/},
  {name: 'Function 생성자', pattern: /\bnew\s+Function\b/},
  {name: '타이머', pattern: /\b(setTimeout|setImmediate|clearTimeout)\b/},
  {name: 'process', pattern: /\bprocess\./},
];

/**
 * 함수 원문을 읽는다.
 * @param {string} path 함수 파일의 절대 경로.
 * @return {string}
 */
export function readFunctionSource(path) {
  return readFileSync(path, 'utf8');
}

/**
 * 원문에서 런타임 2.0 이 지원하지 않는 문법의 이름을 모은다. 주석은 빼고
 * 본다.
 * @param {string} source 함수 원문.
 * @return {!Array<string>} 찾은 문법 이름. 없으면 빈 배열.
 */
export function findUnsupportedSyntax(source) {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
  return UNSUPPORTED_SYNTAX.filter(({pattern}) => pattern.test(code))
      .map(({name}) => name);
}

/**
 * Terraform templatefile 처럼 원문의 자리표시자(달러 · 여는 중괄호 · 이름 ·
 * 닫는 중괄호)를 vars 의 값으로 바꾼다. 넣은 값은 다시 해석하지 않는다.
 * 흉내 내는 것은 이름 하나짜리 자리표시자뿐이다 — 식 · 지시문(퍼센트 · 여는
 * 중괄호) · 탈출(달러 둘) · 넘기지 않은 이름 · 닫히지 않은 자리표시자는 실제
 * templatefile 과 결과가 갈릴 수 있어 예외로 막는다.
 * @param {string} source 템플릿 원문.
 * @param {!Object<string, string>} vars 자리표시자 이름과 값.
 * @return {string} 렌더본.
 */
export function renderTemplate(source, vars) {
  if (source.includes('%{') || source.includes('$${')) {
    throw new Error('templatefile 지시문 · 탈출 문법은 흉내 내지 않는다');
  }
  const opened = source.split('${').length - 1;
  let replaced = 0;
  const rendered = source.replace(/\$\{([^}]*)\}/g, (match, name) => {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || !Object.hasOwn(vars, name)) {
      throw new Error(`templatefile 자리표시자 ${match} 에 넘긴 값이 없다`);
    }
    replaced++;
    return vars[name];
  });
  if (replaced !== opened) {
    throw new Error('templatefile 자리표시자가 닫히지 않았다');
  }
  return rendered;
}

/**
 * 함수 파일을 읽어 평가하고 handler 를 돌려준다(loadHandlerFromSource 참고).
 * @param {string} path 함수 파일의 절대 경로.
 * @param {{cf: (?Object|undefined), logs: (!Array<string>|undefined)}=}
 *     options cf = 가짜 cloudfront 모듈, logs = console.log 가 쌓이는 곳.
 * @return {function(!Object): !Promise<!Object>}
 */
export function loadHandler(path, options = {}) {
  return loadHandlerFromSource(readFunctionSource(path), path, options);
}

/**
 * 함수 원문을 평가해 handler 를 돌려준다. 런타임처럼 strict 모드로 돈다.
 * console.log 는 인자 하나만 받는다(런타임이 쉼표 문법을 지원하지 않는다).
 * templatefile 로 렌더하는 함수는 renderTemplate 의 결과를 넘긴다.
 * @param {string} source 함수 원문(또는 렌더본).
 * @param {string} label 오류 문구에 싣는 이름(보통 함수 파일 경로).
 * @param {{cf: (?Object|undefined), logs: (!Array<string>|undefined)}=}
 *     options cf = 가짜 cloudfront 모듈, logs = console.log 가 쌓이는 곳.
 * @return {function(!Object): !Promise<!Object>}
 */
export function loadHandlerFromSource(
    source, label, {cf = undefined, logs = []} = {}) {
  const body = source.replace(CLOUDFRONT_IMPORT, 'const cf = injectedCf;');
  if (/^\s*(import|export)\b/m.test(body)) {
    throw new Error(`${label}: cloudfront 밖의 import · export 는 쓸 수 없다`);
  }
  const fakeConsole = {
    log(...args) {
      if (args.length !== 1) {
        throw new Error('console.log 는 인자 하나만 받는다(쉼표 문법 미지원)');
      }
      logs.push(String(args[0]));
    },
  };
  const factory = new Function(
      'injectedCf', 'console', `'use strict';\n${body}\nreturn handler;`);
  return factory(cf, fakeConsole);
}
