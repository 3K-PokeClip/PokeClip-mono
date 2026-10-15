// 공유 미리보기 이미지와 구조화 데이터 로고를 만든다 (POK-349). 배포하지 않는다.
//
//   node landing/scripts/og/render.mjs
//     → landing/assets/img/og.jpg            (1200×630 JPG — og.html을 헤드리스 Chrome으로 찍는다)
//     → landing/assets/brand/pokeclip-logo-512.png (512×512 — JSON-LD Organization.logo)
//
// 필요한 것: Google Chrome(기본 경로, CHROME 환경 변수로 바꿀 수 있다), web/node_modules의 sharp(web에서 pnpm install).
// og.html은 file://로 열면 Chrome이 글꼴을 막아(출처 null) 작은 HTTP 서버로 띄워 찍는다.
import { execFile } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const LANDING = fileURLToPath(new URL('../../', import.meta.url));
const sharp = createRequire(join(LANDING, '../web/package.json'))('sharp');
const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const DARK_CANVAS = '#141517';
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
};

const server = createServer((req, res) => {
  const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
  try {
    const body = readFileSync(join(LANDING, path));
    res.writeHead(200, { 'content-type': TYPES[extname(path)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const { port } = server.address();

const work = mkdtempSync(join(tmpdir(), 'pokeclip-og-'));
try {
  const png = join(work, 'og.png');
  // 비동기로 띄운다 — execFileSync는 이벤트 루프를 막아 위 서버가 Chrome 요청에 답하지 못하고 둘 다 멈춘다.
  // 따로 user-data-dir을 줘 사용 중인 Chrome 프로필과 섞이지 않게 한다
  await promisify(execFile)(CHROME, [
    `--user-data-dir=${join(work, 'profile')}`,
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--window-size=1200,630',
    // 글꼴(2MB)이 다 받아진 뒤 찍게 시간을 준다 — 바로 찍으면 대체 글꼴로 나온다
    '--virtual-time-budget=5000',
    `--screenshot=${png}`,
    `http://127.0.0.1:${port}/scripts/og/og.html`,
  ]);
  const og = join(LANDING, 'assets/img/og.jpg');
  await sharp(png)
    .resize(1200, 630, { fit: 'cover' })
    .jpeg({ quality: 85, mozjpeg: true })
    .toFile(og);
  const meta = await sharp(og).metadata();
  console.log(
    `✓ assets/img/og.jpg ${meta.width}×${meta.height} ${Math.round(statSync(og).size / 1024)}KB`,
  );
} finally {
  server.close();
  rmSync(work, { recursive: true, force: true });
}

// JSON-LD 로고 — web의 apple-icon과 같은 꼴(불투명 다크 캔버스 + 심볼). 검색 결과는 흰 바탕에 로고를 얹으므로
// 다크 배경 전용 심볼(재생 마크 화이트)을 투명하게 두면 마크가 사라진다
const symbol = readFileSync(join(LANDING, 'assets/brand/pokeclip-symbol.svg'));
const mark = await sharp(symbol, { density: 600 }).resize(400, 400).png().toBuffer();
const logo = join(LANDING, 'assets/brand/pokeclip-logo-512.png');
writeFileSync(
  logo,
  await sharp({ create: { width: 512, height: 512, channels: 4, background: DARK_CANVAS } })
    .composite([{ input: mark, left: 56, top: 56 }])
    .png()
    .toBuffer(),
);
console.log(`✓ assets/brand/pokeclip-logo-512.png ${Math.round(statSync(logo).size / 1024)}KB`);
