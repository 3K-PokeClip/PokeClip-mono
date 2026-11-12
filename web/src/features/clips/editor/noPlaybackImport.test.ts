import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

// 편집기는 녹화 재생 서버(/playback)를 부르지 않는다(POK-473) — 영상은 ③ 되감기 목록 창, 카드 바로 저장은 clip 이 준 창 두
// 끝(playerWindow)이다. /playback 을 부르는 함수 셋을 편집기 제품 파일이 가져오면 이 시험이 막는다. 자격(mediaCredentials 등)은
// 그대로 가져와도 된다. eslint 설정 대신 시험으로 둔다(kty 2026-11-13 — 설정 파일은 보호 훅이 지킨다). useMutation 금지는 기존
// eslint 규칙이 이미 막는다.
// 훑는 법은 themeGuard 처럼 소스 글을 정규식으로 읽는다(AST 의존을 더하지 않는다). 주석은 지우지 않는다 — 주석 안에 가져오기 꼴을
// 그대로 적으면 막혀 고쳐 쓰게 될 뿐(안전한 쪽으로 틀린다).
// 타입 전용 가져오기(`import type { … }` · `{ type x }`)는 놓아 준다: 컴파일 때 지워져 /playback 을 부를 수 없다.
// 모듈을 통째로 가져오기(`import * as` · `import('…')` · `export * from`)는 막는다: 그 셋을 이름 없이 꺼내 쓸 수 있다.

const EDITOR = __dirname;
const MODULE = String.raw`['"](?:@/|(?:\.\./)+)api/mediaPlayback['"]`;
const BANNED = ['lookupRecordingSpans', 'fetchRecordingSpans', 'recordingClipUrl'];

/** `import { … } from` · `export { … } from` — 중괄호 안 이름들과 문장 머리의 type 표지 */
const NAMED = new RegExp(
  String.raw`\b(?:import|export)\s+(type\s+)?\{([^}]*)\}\s*from\s*${MODULE}`,
  'g',
);
/** 모듈 통째 — 이름 없이 셋을 꺼낼 수 있다(타입 전용 통째는 놓아 준다) */
const WHOLE = new RegExp(
  String.raw`\bimport\s+\*\s+as\s+\w+\s+from\s*${MODULE}|\bimport\s*\(\s*${MODULE}\s*\)|\bexport\s+\*(?:\s+as\s+\w+)?\s+from\s*${MODULE}`,
);

/** 소스 글에서 막힌 가져오기 — 막힌 이름 또는 「통째」 */
function bannedImports(source: string): string[] {
  const found = [...source.matchAll(NAMED)].flatMap(([, typeOnly, names = '']) => {
    if (typeOnly) return [];
    return names
      .split(',')
      .map((part) => part.trim())
      .filter((part) => !part.startsWith('type '))
      .map((part) => part.split(/\s+as\s+/)[0]!.trim())
      .filter((name) => BANNED.includes(name));
  });
  return WHOLE.test(source) ? [...found, '통째'] : found;
}

/** 편집기 제품 파일(시험 · 시험 자료 제외) — 이 폴더 기준 경로 */
function productFiles(): string[] {
  return readdirSync(EDITOR, { recursive: true, encoding: 'utf8' })
    .map((p) => p.split('\\').join('/'))
    .filter((p) => /\.tsx?$/.test(p) && !/\.test\.tsx?$/.test(p) && !p.includes('testdata/'));
}

describe('편집기는 녹화 재생 서버(/playback) 함수를 가져오지 않는다(POK-473)', () => {
  it('편집기 제품 파일을 찾는다', () => {
    expect(productFiles().length).toBeGreaterThan(50);
    expect(productFiles()).toContain('saveCardDraft.ts');
    expect(productFiles()).not.toContain('saveCardDraft.test.ts');
  });

  it('막힌 이름 · 통째 가져오기는 잡고, 자격 · 타입 전용 · 다른 모듈은 놓아 준다', () => {
    const caught: [string, string[]][] = [
      [`import { recordingClipUrl } from '@/api/mediaPlayback';`, ['recordingClipUrl']],
      [
        `import {\n  mediaCredentials,\n  lookupRecordingSpans as spans,\n} from "@/api/mediaPlayback";`,
        ['lookupRecordingSpans'],
      ],
      [
        `import { fetchRecordingSpans } from '../../../api/mediaPlayback';`,
        ['fetchRecordingSpans'],
      ],
      [`export { recordingClipUrl } from '@/api/mediaPlayback';`, ['recordingClipUrl']],
      [`import * as media from '@/api/mediaPlayback';`, ['통째']],
      [`const m = await import('@/api/mediaPlayback');`, ['통째']],
    ];
    for (const [source, expected] of caught)
      expect(bannedImports(source), source).toEqual(expected);
    for (const source of [
      `import { mediaCredentials } from '@/api/mediaPlayback';`,
      `import type { recordingClipUrl } from '@/api/mediaPlayback';`,
      `import { type recordingClipUrl, mediaCredentials } from '@/api/mediaPlayback';`,
      `import { recordingClipUrl } from '@/api/otherPlayback';`,
      `import { ensurePlaybackAccess } from '@/api/playbackAccess';`,
    ])
      expect(bannedImports(source), source).toEqual([]);
  });

  it('편집기 제품 파일은 /playback 을 부르는 함수를 가져오지 않는다', () => {
    const offenders = productFiles().flatMap((path) =>
      bannedImports(readFileSync(join(EDITOR, path), 'utf8')).map((name) => `${path}: ${name}`),
    );
    expect(offenders).toEqual([]);
  });
});
