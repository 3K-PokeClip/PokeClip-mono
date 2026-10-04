// 이용약관·개인정보 처리방침의 판(版) 보관 (POK-269). 토스 약관처럼 제목 아래 시행일 드롭다운으로 지난 판을 고른다.
//
//   node landing/scripts/legal-versions.mjs                        # 모든 판의 드롭다운을 목록대로 다시 쓴다
//   node landing/scripts/legal-versions.mjs --check                # 다시 쓸 게 남았으면 실패 (check-legal이 부른다)
//   node landing/scripts/legal-versions.mjs archive terms 2026.11.01
//       # 지금 판을 /terms/<지금 시행일>/에 얼려 두고 새 시행일을 맨 앞에 올린다. 그다음 terms/index.html 본문을 고친다.
//
// 목록의 정본은 legal-versions.json이다(새 판이 맨 앞). 지난 판의 본문은 고치지 않는다 — 가입 시각으로 그 회원에게
// 적용된 판을 찾는 근거라서다. 이 스크립트가 다시 쓰는 것은 각 판의 legal-versions 표시 사이 드롭다운 블록뿐이다.
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  writeFileSync,
} from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = new URL('..', import.meta.url);
const MANIFEST = new URL('legal-versions.json', ROOT);
const BLOCK = /^([ \t]*)<!-- legal-versions:start[\s\S]*?<!-- legal-versions:end -->/m;
const DATE = /^\d{4}\.\d{2}\.\d{2}$/;
const DOC_NAME = { terms: '약관', privacy: '처리방침' };

export function readManifest() {
  return JSON.parse(readFileSync(MANIFEST, 'utf8'));
}

// index번째 판에 들어갈 드롭다운. 맨 앞(0)이 현재 판이고, 나머지는 지난 판 안내를 단다.
function renderBlock(doc, versions, index, indent) {
  const shown = versions[index];
  const lines = [
    '<!-- legal-versions:start — scripts/legal-versions.mjs가 legal-versions.json으로 다시 쓴다. 손으로 고치지 않는다 -->',
    '<div class="legal-version">',
    '  <details class="legal-version__menu">',
    `    <summary aria-label="시행일 ${shown.label} — 다른 판 보기">${shown.label}</summary>`,
    '    <ul>',
    ...versions.map((v, i) => {
      const current = i === index ? ' aria-current="page"' : '';
      const badge = i === 0 ? ' <span class="legal-version__badge">현재</span>' : '';
      return `      <li><a href="${v.path}"${current}>${v.label}${badge}</a></li>`;
    }),
    '    </ul>',
    '  </details>',
    '</div>',
  ];
  if (index > 0) {
    lines.push(
      `<p class="legal-version__notice">이 ${DOC_NAME[doc]}은 ${shown.label}부터 ${versions[index - 1].label} 전까지 적용된 지난 판입니다. <a href="${versions[0].path}">현재 판 보기</a></p>`,
    );
  }
  lines.push('<!-- legal-versions:end -->');
  return lines.map((line) => indent + line).join('\n');
}

// 목록과 어긋난 판 파일을 돌려준다. write면 그 자리에서 고친다.
export function syncAll({ write }) {
  const manifest = readManifest();
  const stale = [];
  for (const [doc, versions] of Object.entries(manifest)) {
    versions.forEach((version, index) => {
      const file = new URL(version.file, ROOT);
      if (!existsSync(file))
        throw new Error(`${version.file}가 없다 (legal-versions.json의 ${doc} ${version.label})`);
      const html = readFileSync(file, 'utf8');
      const match = html.match(BLOCK);
      if (!match) throw new Error(`${version.file}에 legal-versions 표시가 없다`);
      const next = html.replace(BLOCK, renderBlock(doc, versions, index, match[1]));
      if (next === html) return;
      stale.push(version.file);
      if (write) writeFileSync(file, next);
    });
  }
  return stale;
}

// 지금 판을 시행일 폴더에 얼려 두고, 새 시행일을 현재 판으로 올린다.
function archive(doc, nextLabel) {
  const manifest = readManifest();
  const versions = manifest[doc];
  if (!versions) throw new Error(`문서는 ${Object.keys(manifest).join(' · ')} 중 하나다`);
  if (!DATE.test(nextLabel ?? '')) throw new Error('새 시행일은 YYYY.MM.DD로 준다');
  const current = versions[0];
  if (!DATE.test(current.label)) {
    throw new Error(
      `지금 판의 시행일(${current.label})이 확정되지 않았다 — 자리표시인 판은 보관할 수 없다`,
    );
  }
  if (nextLabel <= current.label)
    throw new Error(`새 시행일은 지금 판(${current.label})보다 뒤여야 한다`);

  const slug = current.label.replaceAll('.', '-');
  const archived = `${doc}/${slug}/index.html`;
  if (existsSync(new URL(archived, ROOT))) throw new Error(`${archived}가 이미 있다`);
  mkdirSync(new URL(`${doc}/${slug}/`, ROOT), { recursive: true });
  copyFileSync(new URL(current.file, ROOT), new URL(archived, ROOT));
  // 얼린 판의 대표 주소는 그 판 자신이다
  const frozen = readFileSync(new URL(archived, ROOT), 'utf8').replace(
    `href="https://pokeclip.com/${doc}"`,
    `href="https://pokeclip.com/${doc}/${slug}/"`,
  );
  writeFileSync(new URL(archived, ROOT), frozen);

  versions[0] = { ...current, path: `/${doc}/${slug}/`, file: archived };
  versions.unshift({ label: nextLabel, path: `/${doc}/`, file: `${doc}/index.html` });
  writeFileSync(MANIFEST, `${JSON.stringify(manifest, null, 2)}\n`);
  syncAll({ write: true });

  console.log(`✓ ${current.label} 판을 ${archived}에 보관했다`);
  console.log(
    `  다음: ${doc}/index.html 본문과 본문 속 시행일 문장을 ${nextLabel} 판으로 고친 뒤 check-legal을 돌린다`,
  );
}

// 직접 실행했는지는 실제 경로끼리 견준다 — import.meta.url은 심볼릭 링크를 푼 경로라, argv[1]을 그대로 견주면
// /tmp(→/private/tmp) 클론이나 심링크 체크아웃에서 CLI가 통째로 건너뛰어져 --check가 아무 말 없이 통과한다
const invokedDirectly =
  process.argv[1] !== undefined &&
  realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const [command, ...args] = process.argv.slice(2);
  try {
    if (command === 'archive') {
      archive(args[0], args[1]);
    } else if (command === '--check') {
      const stale = syncAll({ write: false });
      if (stale.length > 0) {
        console.error(
          `✗ 드롭다운이 목록과 다르다: ${stale.join(', ')} — legal-versions.mjs를 돌린다`,
        );
        process.exit(1);
      }
      console.log('✓ 판 목록과 드롭다운이 같다');
    } else {
      const stale = syncAll({ write: true });
      console.log(stale.length > 0 ? `✓ 다시 썼다: ${stale.join(', ')}` : '✓ 바꿀 것이 없다');
    }
  } catch (error) {
    console.error(`✗ ${error.message}`);
    process.exit(1);
  }
}
