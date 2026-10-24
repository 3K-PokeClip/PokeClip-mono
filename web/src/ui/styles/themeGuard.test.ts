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
// 프리미티브는 단계가 붙은 것(--pc-gray-950)과 안 붙은 것(--pc-white) 둘 다
const LITERAL =
  /#[0-9a-fA-F]{3,8}\b|\brgba?\(\s*\d|var\(--pc-(gray|primary|point|red|green|yellow|cyan|teal|white|black)(-|\))/;
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

  it('색을 박은 줄은 모두 시맨틱 토큰이거나 표식이 있다', () => {
    const offenders = cssModules().flatMap(unmarkedLiterals);
    expect(offenders, '시맨틱 토큰(--pc-color-…)을 쓰거나 줄 끝에 표식을 단다').toEqual([]);
  });
});
