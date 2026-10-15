// legal-versions.mjs archive 회귀 테스트 (POK-349). 의존성 없이 node만으로 돈다.
//
//   node --test 'landing/scripts/*.test.mjs'
//
// archive는 지난 판을 한 번 얼리면 되돌릴 수 없는 일이라(본문 지문이 고정된다) 실제 저장소에서 돌려 볼 수 없다.
// 임시 디렉터리에 스크립트와 두 문서·판 목록을 복사해 그 안에서만 돌린다 — 스크립트는 자기 위치 기준(../)으로
// 문서를 찾으므로 복사본은 복사본만 고친다.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, test } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

const LANDING = fileURLToPath(new URL('..', import.meta.url));
const temps = [];

// 판 목록의 지금 판 시행일을 label로 채운 임시 랜딩. label이 없으면 자리표시 그대로 둔다
function makeLanding(label) {
  const dir = mkdtempSync(join(tmpdir(), 'legal-versions-'));
  temps.push(dir);
  for (const path of ['scripts/legal-versions.mjs', 'terms', 'privacy', 'legal-versions.json']) {
    cpSync(join(LANDING, path), join(dir, path), { recursive: true });
  }
  if (label) {
    const manifest = JSON.parse(readFileSync(join(dir, 'legal-versions.json'), 'utf8'));
    for (const versions of Object.values(manifest)) versions[0].label = label;
    writeFileSync(join(dir, 'legal-versions.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    run(dir); // 드롭다운을 바뀐 목록대로 다시 쓴다
  }
  return dir;
}

const run = (dir, ...args) =>
  execFileSync(process.execPath, [join(dir, 'scripts/legal-versions.mjs'), ...args], {
    encoding: 'utf8',
    stdio: 'pipe',
  });
const read = (dir, path) => readFileSync(join(dir, path), 'utf8');
const manifestOf = (dir) => JSON.parse(read(dir, 'legal-versions.json'));

afterEach(() => {
  while (temps.length > 0) rmSync(temps.pop(), { recursive: true, force: true });
});

test('지난 판은 자기 주소를 대표 주소로 내걸고 noindex를 단다', () => {
  const dir = makeLanding('2026.10.01');
  run(dir, 'archive', 'terms', '2026.11.01');

  const frozen = read(dir, 'terms/2026-10-01/index.html');
  assert.match(
    frozen,
    /<link rel="canonical" href="https:\/\/pokeclip\.com\/terms\/2026-10-01\/" \/>/,
  );
  assert.match(frozen, /<meta name="robots" content="noindex" \/>/);
  assert.doesNotMatch(frozen, /href="https:\/\/pokeclip\.com\/terms\/" \/>/);

  // 현재 판은 그대로 색인된다
  const current = read(dir, 'terms/index.html');
  assert.match(current, /<link rel="canonical" href="https:\/\/pokeclip\.com\/terms\/" \/>/);
  assert.doesNotMatch(current, /noindex/);
});

test('목록에 새 판이 맨 앞에 서고, 얼린 판의 지문은 noindex를 넣은 본문 기준이다', async () => {
  const dir = makeLanding('2026.10.01');
  run(dir, 'archive', 'terms', '2026.11.01');

  const [next, frozen] = manifestOf(dir).terms;
  assert.deepEqual(next, { label: '2026.11.01', path: '/terms/', file: 'terms/index.html' });
  assert.equal(frozen.path, '/terms/2026-10-01/');
  assert.equal(frozen.file, 'terms/2026-10-01/index.html');

  const { bodyHash, changedFrozen } = await import(
    pathToFileURL(join(dir, 'scripts/legal-versions.mjs')).href
  );
  assert.equal(frozen.sha256, bodyHash(read(dir, frozen.file)));
  assert.deepEqual(changedFrozen(), []);
  // 드롭다운까지 목록과 같다
  run(dir, '--check');

  // 얼린 판 본문을 고치면 잡힌다
  const path = join(dir, frozen.file);
  writeFileSync(path, readFileSync(path, 'utf8').replace('noindex', 'index'));
  assert.deepEqual(changedFrozen(), [frozen.file]);
});

test('두 번 얼려도 드롭다운이 모든 판을 잇는다', () => {
  const dir = makeLanding('2026.10.01');
  run(dir, 'archive', 'terms', '2026.11.01');
  run(dir, 'archive', 'terms', '2026.12.01');

  assert.deepEqual(
    manifestOf(dir).terms.map((v) => v.path),
    ['/terms/', '/terms/2026-11-01/', '/terms/2026-10-01/'],
  );
  for (const file of [
    'terms/index.html',
    'terms/2026-11-01/index.html',
    'terms/2026-10-01/index.html',
  ]) {
    const html = read(dir, file);
    for (const path of ['/terms/', '/terms/2026-11-01/', '/terms/2026-10-01/']) {
      assert.ok(html.includes(`href="${path}"`), `${file} 드롭다운에 ${path}가 없다`);
    }
  }
  run(dir, '--check');
});

test('시행일이 자리표시인 판은 얼리지 않는다', () => {
  const dir = makeLanding();
  assert.throws(() => run(dir, 'archive', 'terms', '2026.11.01'), /자리표시인 판은 보관할 수 없다/);
  assert.equal(manifestOf(dir).terms.length, 1);
});

test('현재 판 canonical을 못 찾으면 아무것도 만들지 않고 멈춘다', () => {
  const dir = makeLanding('2026.10.01');
  const path = join(dir, 'terms/index.html');
  // 옛 꼴(끝 슬래시 없음)로 되돌린 문서 — 이대로 얼리면 지난 판이 현재 판 주소를 대표 주소로 내건다
  writeFileSync(
    path,
    readFileSync(path, 'utf8').replace('pokeclip.com/terms/" />', 'pokeclip.com/terms" />'),
  );
  assert.throws(() => run(dir, 'archive', 'terms', '2026.11.01'), /canonical/);
  assert.equal(existsSync(join(dir, 'terms/2026-10-01')), false);
  assert.equal(manifestOf(dir).terms.length, 1);
});
