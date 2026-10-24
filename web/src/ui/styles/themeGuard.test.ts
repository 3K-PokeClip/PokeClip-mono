import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// 라이트 모드 가드 — CSS 모듈에 색을 직접 박으면(hex·rgb(숫자)·프리미티브 var(--pc-gray-…))
// 한쪽 테마에서만 맞는 색이 되기 쉽다. 시맨틱 토큰(--pc-color-…)을 쓰고, 일부러 박은 줄에는
// 같은 줄에 표식을 단다(여러 줄 값이면 색이 있는 그 줄에):
//   /* 테마 고정 */  — 두 테마에서 같은 색이 맞다(영상·썸네일 위, 밝은 채움 위 글자 등)
//   /* 라이트 덮음 */ — 다크 값이고, [data-theme='light'] 규칙이 따로 덮는다
//   /* 라이트 값 */  — [data-theme='light'] 규칙 안의 라이트 전용 값
// 영상 위 무대처럼 통째로 어두워야 하는 곳은 요소에 data-theme="dark"를 달아 토큰을 다크로 묶는다.

const SRC = join(__dirname, '..', '..');
// 박힌 색으로 보는 것 — hex · rgb(숫자) · 함수 표기(hsl·oklch …) · 프리미티브 var(단계가 붙은
// --pc-gray-950도, 안 붙은 --pc-white도) · 값 자리의 이름 색 white·black(`white-space` 같은 속성 이름과
// `.white` 같은 셀렉터는 뺀다)
const LITERAL = new RegExp(
  [
    String.raw`#[0-9a-fA-F]{3,8}\b`,
    String.raw`\brgba?\(\s*\d`,
    String.raw`\b(?:hsla?|hwb|lab|lch|oklab|oklch)\(`,
    String.raw`var\(--pc-(?:gray|primary|point|red|green|yellow|cyan|teal|white|black)(?:-|\))`,
    String.raw`:[^;{]*(?<![\w.#-])(?:white|black)(?![\w-])`,
  ].join('|'),
);
const MARKS = ['테마 고정', '라이트 덮음', '라이트 값'];

/** 표식 없이 색을 박은 줄 — `파일:줄  내용` */
function unmarkedLiterals(path: string): string[] {
  const raw = readFileSync(join(SRC, path), 'utf8');
  // 주석 안의 색(설명용 hex)은 세지 않는다 — 줄 번호는 유지한 채 주석만 지운다
  const code = raw.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
  const rawLines = raw.split('\n');
  return code
    .split('\n')
    .flatMap((line, i) =>
      LITERAL.test(line) && !MARKS.some((m) => rawLines[i]?.includes(m))
        ? [`${path}:${i + 1}  ${line.trim()}`]
        : [],
    );
}

function cssModules(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .filter((p) => p.endsWith('.module.css'))
    .map((p) => p.split('\\').join('/'))
    .filter((p) => !p.startsWith('app/dev/')); // 개발용 데모·시안은 제품 화면이 아니다
}

describe('라이트 모드 가드 — CSS 모듈의 박힌 색', () => {
  it('CSS 모듈을 찾는다', () => {
    expect(cssModules().length).toBeGreaterThan(50);
  });

  it('박힌 색 표기는 잡고, 비슷하게 생긴 이름·토큰은 놓아 준다', () => {
    for (const line of [
      'color: #fff;',
      'background: rgb(0 0 0 / 0.5);',
      'color: hsl(0 0% 100%);',
      'color: oklch(0.7 0.1 250);',
      'background: var(--pc-gray-950);',
      'color: var(--pc-white);',
      'color: white;',
      'background: color-mix(in srgb, black 20%, transparent);',
    ]) {
      expect(LITERAL.test(line), line).toBe(true);
    }
    for (const line of [
      'white-space: nowrap;',
      '.white:hover {',
      'color: currentColor;',
      'background: transparent;',
      'color: var(--pc-color-text-primary);',
      'color: var(--pc-color-success-text);',
    ]) {
      expect(LITERAL.test(line), line).toBe(false);
    }
  });

  it('색을 박은 줄은 모두 시맨틱 토큰이거나 표식이 있다', () => {
    const offenders = cssModules().flatMap(unmarkedLiterals);
    expect(offenders, '시맨틱 토큰(--pc-color-…)을 쓰거나 줄 끝에 표식을 단다').toEqual([]);
  });
});
