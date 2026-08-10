/**
 * 가로형 로고 생성 — Pretendard Variable(wght 600)로 "PokeClip" 워드마크를
 * 아웃라인 패스로 변환해 심볼과 합성한다.
 *   - public/brand/pokeclip-logo-horizontal.svg        (다크 배경용 — 워드마크 화이트)
 *   - public/brand/pokeclip-logo-horizontal-light.svg  (라이트 배경용 — 워드마크 #141517, 재생 마크 다크)
 * 재실행: node scripts/build-wordmark.mjs
 */
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as fontkit from 'fontkit';
import { decompress } from 'wawoff2';

const root = path.dirname(fileURLToPath(import.meta.url));
const brandDir = path.join(root, '..', 'public', 'brand');
const fontPath = path.join(root, '..', '..', '..', 'packages', 'ui', 'src', 'assets', 'fonts', 'PretendardVariable.woff2');

const TEXT = 'PokeClip';
const FONT_SIZE = 120;
const WEIGHT = 600;
const TRACKING_EM = -0.01; // 로고용 미세 자간 조임
const CANVAS_H = 192;
const SYMBOL_SIZE = 168;
const SYMBOL_Y = (CANVAS_H - SYMBOL_SIZE) / 2;
const GAP = 40;
const DARK_CANVAS = '#141517';

// fontkit은 woff2의 variation 테이블 파싱이 깨져서 TTF로 풀어 읽는다
const ttf = Buffer.from(await decompress(await readFile(fontPath)));
const font = fontkit.create(ttf).getVariation({ wght: WEIGHT });
const scale = FONT_SIZE / font.unitsPerEm;
const trackingUnits = TRACKING_EM * font.unitsPerEm;

const run = font.layout(TEXT);
let cursor = 0;
const glyphPaths = [];
for (let i = 0; i < run.glyphs.length; i++) {
  const g = run.glyphs[i];
  const pos = run.positions[i];
  const d = g.path.toSVG();
  if (d) glyphPaths.push(`<path transform="translate(${(cursor + pos.xOffset).toFixed(1)} ${pos.yOffset.toFixed(1)})" d="${d}"/>`);
  cursor += pos.xAdvance + trackingUnits;
}
const textWidth = (cursor - trackingUnits) * scale;
const capHeight = (font.capHeight || font.unitsPerEm * 0.72) * scale;
const baselineY = (CANVAS_H + capHeight) / 2;
const textX = SYMBOL_SIZE + GAP;
const totalW = Math.ceil(textX + textWidth + 4);

const wordmark = (color) =>
  `<g fill="${color}" transform="translate(${textX} ${baselineY.toFixed(1)}) scale(${scale.toFixed(6)} ${-scale.toFixed(6)})">\n    ${glyphPaths.join('\n    ')}\n  </g>`;

// 심볼 원본에서 svg 태그 속성만 교체해 중첩 svg로 배치
const symbolSrc = await readFile(path.join(brandDir, 'pokeclip-symbol.svg'), 'utf8');
const symbolInner = symbolSrc
  .replace(/^[\s\S]*?<svg[^>]*>/, '')
  .replace(/<\/svg>\s*$/, '');
const symbolAt = (playFill) => {
  const inner = playFill === 'white' ? symbolInner : symbolInner.replace('fill="#FFFFFF"', `fill="${DARK_CANVAS}"`);
  return `<svg x="0" y="${SYMBOL_Y}" width="${SYMBOL_SIZE}" height="${SYMBOL_SIZE}" viewBox="0 0 1024 1024">${inner}</svg>`;
};

const doc = (symbol, color) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${totalW} ${CANVAS_H}" width="${totalW}" height="${CANVAS_H}">
  <!-- PokeClip 가로형 로고 — 심볼 + Pretendard SemiBold(600) 워드마크 아웃라인 (생성: scripts/build-wordmark.mjs) -->
  ${symbol}
  ${wordmark(color)}
</svg>
`;

await writeFile(path.join(brandDir, 'pokeclip-logo-horizontal.svg'), doc(symbolAt('white'), '#FFFFFF'));
await writeFile(path.join(brandDir, 'pokeclip-logo-horizontal-light.svg'), doc(symbolAt(DARK_CANVAS), DARK_CANVAS));
console.log(`generated: pokeclip-logo-horizontal(.light).svg — ${totalW}x${CANVAS_H}, text ${Math.round(textWidth)}px`);
