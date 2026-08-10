/**
 * 브랜드 래스터 자산 생성 — public/brand/pokeclip-symbol.svg 원본에서
 *   - src/app/favicon.ico   (16/32/48 멀티사이즈, 레거시 폴백 — 재생 마크는 라이트 UI 기준 다크)
 *   - src/app/apple-icon.png (180×180, 불투명 다크 캔버스 #141517 — iOS는 투명 배경을 검게 뭉갠다)
 * 재실행: pnpm --filter @pokeclip/web run build:brand
 */
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';
import pngToIco from 'png-to-ico';

const root = path.dirname(fileURLToPath(import.meta.url));
const appDir = path.join(root, '..', 'src', 'app');
const symbolPath = path.join(root, '..', 'public', 'brand', 'pokeclip-symbol.svg');

const DARK_CANVAS = '#141517';
const svgDarkPlay = await readFile(symbolPath, 'utf8'); // 재생 마크 화이트 (다크 배경용)
// ICO는 테마 대응이 불가 → 라이트 UI에서 보이도록 재생 마크를 다크로 치환
const svgLightUi = svgDarkPlay.replace('fill="#FFFFFF"', `fill="${DARK_CANVAS}"`);

async function renderPng(svg, size) {
  return sharp(Buffer.from(svg), { density: 300 }).resize(size, size).png().toBuffer();
}

// favicon.ico — 16/32/48
const icoPngs = await Promise.all([16, 32, 48].map((s) => renderPng(svgLightUi, s)));
await writeFile(path.join(appDir, 'favicon.ico'), await pngToIco(icoPngs));

// apple-icon.png — 180×180 불투명, 심볼 140px 중앙 배치
const symbol140 = await renderPng(svgDarkPlay, 140);
const appleIcon = await sharp({
  create: { width: 180, height: 180, channels: 4, background: DARK_CANVAS },
})
  .composite([{ input: symbol140, left: 20, top: 20 }])
  .png()
  .toBuffer();
await writeFile(path.join(appDir, 'apple-icon.png'), appleIcon);

console.log('generated: src/app/favicon.ico (16/32/48), src/app/apple-icon.png (180)');
