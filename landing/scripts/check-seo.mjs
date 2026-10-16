// 랜딩 검색 노출 검사 (POK-349 · POK-350 · POK-353). 의존성 없이 node만으로 돈다.
//
//   node landing/scripts/check-seo.mjs
//
// 검색 결과·공유 미리보기는 배포 뒤에야 눈으로 확인할 수 있어서, 배포 전에 깨지면 모르고 지나가는 것만 지킨다:
// 필수 메타와 절대 주소, 네이버 소유 확인 메타, canonical·sitemap·링크의 주소 꼴, 루트 정적 파일, 구조화 데이터가 보이는 본문과 같은지,
// AI 크롤러 정책(학습용 차단·답변용 허용), 사실 요약이 FAQ·JSON-LD·llms.txt에서 같은지, 공개 파일에 내부 티켓 번호가 없는지.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';

const root = new URL('..', import.meta.url);
const raw = (path) => readFileSync(new URL(path, root), 'utf8');
const SITE = 'https://pokeclip.com';
const OG_MAX_BYTES = 300 * 1024;

const failures = [];
const expect = (ok, message) => {
  if (!ok) failures.push(message);
};

// 태그 하나의 속성들. 값에 따옴표가 없는 꼴은 이 저장소에서 쓰지 않는다
const attrs = (tag) =>
  Object.fromEntries([...tag.matchAll(/([\w:-]+)="([^"]*)"/g)].map(([, k, v]) => [k, v]));
const tags = (html, name) =>
  [...html.matchAll(new RegExp(`<${name}\\b[^>]*>`, 'g'))].map((m) => attrs(m[0]));
const metaOf = (html, key) =>
  tags(html, 'meta').find((a) => a.property === key || a.name === key)?.content;
const linksOf = (html, rel) => tags(html, 'link').filter((a) => a.rel === rel);
const text = (html) =>
  html
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style)\b[\s\S]*?<\/\1>/g, '')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
// 사이트 절대 주소 → 랜딩 안의 파일
const fileOf = (url) => url.slice(SITE.length + 1);

// JPEG 크기(SOF 표지에서 읽는다) — 메신저 미리보기는 1200×630이 기준이다
function jpegSize(buf) {
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xff) return null;
    const marker = buf[i + 1];
    if (marker === 0xff) {
      i += 1;
      continue;
    }
    if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
      return { width: buf.readUInt16BE(i + 7), height: buf.readUInt16BE(i + 5) };
    }
    i += 2 + buf.readUInt16BE(i + 2);
  }
  return null;
}

const pages = {
  '/': raw('index.html'),
  '/terms/': raw('terms/index.html'),
  '/privacy/': raw('privacy/index.html'),
};
const home = pages['/'];

// --- 대표 주소: 현재 판 세 쪽은 정본(끝 슬래시) 주소를 대표로 내걸고 색인을 막지 않는다
for (const [path, html] of Object.entries(pages)) {
  const canonical = linksOf(html, 'canonical');
  expect(canonical.length === 1, `${path}에 canonical이 하나가 아니다(${canonical.length}개)`);
  expect(canonical[0]?.href === `${SITE}${path}`, `${path}의 canonical이 ${SITE}${path}가 아니다`);
  expect(
    !/noindex/.test(metaOf(html, 'robots') ?? ''),
    `${path}가 noindex다 — 현재 판은 색인돼야 한다`,
  );
  for (const rel of ['icon', 'apple-touch-icon']) {
    for (const { href } of linksOf(html, rel)) {
      expect(
        existsSync(new URL(href.replace(/^\//, ''), root)),
        `${path}의 ${rel} ${href} 파일이 없다`,
      );
    }
  }
  expect(linksOf(html, 'icon').length > 0, `${path}에 파비콘 링크가 없다`);
  // 정본이 아닌 꼴로 잇으면 301을 한 번 더 탄다
  expect(
    !/href="\/(terms|privacy)(#[^"]*)?"/.test(html),
    `${path}에 끝 슬래시 없는 약관·처리방침 링크가 있다`,
  );
}

// --- 홈 메타: 공유 미리보기는 상대 주소 이미지를 못 읽는다
const title = home.match(/<title>([^<]*)<\/title>/)?.[1] ?? '';
expect(
  title.length > 0 && !title.includes('시안'),
  `홈 title이 비었거나 시안 제목이다: 「${title}」`,
);
const REQUIRED_META = [
  'description',
  'og:type',
  'og:site_name',
  'og:locale',
  'og:url',
  'og:title',
  'og:description',
  'og:image',
  'og:image:width',
  'og:image:height',
  'og:image:alt',
  'twitter:card',
  'twitter:title',
  'twitter:description',
  'twitter:image',
];
for (const key of REQUIRED_META) expect(metaOf(home, key), `홈에 ${key}가 없다`);
expect(metaOf(home, 'og:url') === `${SITE}/`, '홈 og:url이 canonical과 다르다');
expect(
  metaOf(home, 'twitter:card') === 'summary_large_image',
  '홈 twitter:card가 summary_large_image가 아니다',
);
// 네이버 서치어드바이저 소유 확인(POK-353) — 메타를 지우면 확인이 풀린다. 값은 발급받은 것만(16진수 40자)
expect(
  /^[0-9a-f]{40}$/.test(metaOf(home, 'naver-site-verification') ?? ''),
  '홈에 네이버 소유 확인(naver-site-verification) 메타가 없거나 발급 꼴(16진수 40자)이 아니다',
);
for (const [path, html] of Object.entries(pages)) {
  for (const key of ['og:image', 'twitter:image']) {
    const url = metaOf(html, key);
    if (url === undefined) continue;
    expect(url.startsWith(`${SITE}/`), `${path}의 ${key}가 절대 주소가 아니다: ${url}`);
    if (!url.startsWith(`${SITE}/`)) continue;
    const file = new URL(fileOf(url), root);
    expect(existsSync(file), `${path}의 ${key} 파일이 없다: ${fileOf(url)}`);
    if (!existsSync(file) || !/\.jpe?g$/.test(url)) continue;
    const size = jpegSize(readFileSync(file));
    // 크기는 그 쪽이 적은 og:image:width·height와 견준다. 약관·처리방침처럼 홈 이미지를 같이 쓰면서 크기를 안 적은 쪽만
    // 홈 값과 견준다 — 다른 이미지를 걸고 크기를 안 적으면 실패한다
    const sized =
      metaOf(html, 'og:image:width') !== undefined || url !== metaOf(home, key) ? html : home;
    const width = Number(metaOf(sized, 'og:image:width'));
    const height = Number(metaOf(sized, 'og:image:height'));
    expect(
      size?.width === width && size?.height === height,
      `${path}의 ${fileOf(url)} 크기(${size?.width}×${size?.height})가 og:image:width·height(${width}×${height})와 다르다`,
    );
    expect(
      statSync(file).size <= OG_MAX_BYTES,
      `${fileOf(url)}가 ${OG_MAX_BYTES / 1024}KB를 넘는다`,
    );
  }
}

// --- robots.txt·sitemap.xml: 사이트맵은 현재 판 canonical과 같은 주소만
expect(existsSync(new URL('robots.txt', root)), 'robots.txt가 없다');
if (existsSync(new URL('robots.txt', root))) {
  const robots = raw('robots.txt');
  expect(robots.includes(`Sitemap: ${SITE}/sitemap.xml`), 'robots.txt에 사이트맵 주소가 없다');
  // 묶음은 크롤러처럼(RFC 9309) 읽는다 — 이어진 User-agent 줄이 한 묶음의 대상이고, 규칙 뒤에 다시 User-agent가 오면
  // 새 묶음이다. 빈 줄·주석은 묶음을 끊지 않는다(빈 줄로 나누면 「User-agent 뒤 빈 줄, 그다음 Disallow」를 못 읽는다)
  const groups = [];
  for (const line of robots.split('\n')) {
    const field = line
      .replace(/#.*/, '')
      .trim()
      .match(/^([\w-]+)\s*:\s*(.*)$/);
    if (!field) continue;
    const key = field[1].toLowerCase();
    const value = field[2].trim();
    if (key === 'user-agent') {
      const last = groups.at(-1);
      if (!last || last.rules.length > 0) groups.push({ agents: [], rules: [] });
      groups.at(-1).agents.push(value.toLowerCase());
    } else if ((key === 'allow' || key === 'disallow') && groups.length > 0) {
      groups.at(-1).rules.push({ key, path: value });
    }
  }
  // 사이트 전체 차단: `/`·`/*`를 막고 같은 길이로 다시 열지 않았다(길이가 같으면 Allow가 이긴다)
  const blocksAll = (group) => {
    const whole = group.rules.filter((rule) => rule.path === '/' || rule.path === '/*');
    return whole.some((r) => r.key === 'disallow') && !whole.some((r) => r.key === 'allow');
  };

  // User-agent: * 묶음이 사이트 전체를 막으면 검색에서 통째로 빠진다
  expect(
    !groups.some((group) => group.agents.includes('*') && blocksAll(group)),
    'robots.txt가 모든 크롤러(*)에 사이트 전체를 막는다',
  );

  // AI 크롤러 정책(POK-350): 학습용은 막고 검색·답변용은 연다 — 답변용을 막으면 AI 답변이 랜딩을 인용하지 못한다
  const blocked = new Set(groups.filter(blocksAll).flatMap((group) => group.agents));
  for (const agent of ['GPTBot', 'ClaudeBot', 'CCBot', 'Applebot-Extended']) {
    expect(blocked.has(agent.toLowerCase()), `robots.txt가 학습용 크롤러 ${agent}를 막지 않는다`);
  }
  const OPEN = [
    'Googlebot',
    'bingbot',
    'Yeti',
    'Applebot',
    'Google-Extended',
    'OAI-SearchBot',
    'ChatGPT-User',
    'PerplexityBot',
    'Perplexity-User',
    'Claude-SearchBot',
    'Claude-User',
  ];
  for (const agent of OPEN) {
    expect(!blocked.has(agent.toLowerCase()), `robots.txt가 검색·답변용 크롤러 ${agent}를 막는다`);
  }
}
expect(existsSync(new URL('sitemap.xml', root)), 'sitemap.xml이 없다');
if (existsSync(new URL('sitemap.xml', root))) {
  const locs = [...raw('sitemap.xml').matchAll(/<loc>([^<]*)<\/loc>/g)].map((m) => m[1]).sort();
  const canonicals = Object.keys(pages)
    .map((path) => `${SITE}${path}`)
    .sort();
  expect(
    JSON.stringify(locs) === JSON.stringify(canonicals),
    `sitemap.xml 주소(${locs.join(', ')})가 현재 판 canonical(${canonicals.join(', ')})과 다르다`,
  );
}

// --- 시작 버튼: 랜딩 호스트에는 /login이 없다 — 시안 때의 상대 주소·가로채기가 남으면 버튼이 죽는다
expect(!home.includes('data-proto-link'), '홈에 시안용 data-proto-link가 남았다');
expect(!/href="\/login"/.test(home), '홈에 상대 주소 /login 링크가 남았다(랜딩 호스트에는 없다)');

// --- 구조화 데이터: 보이는 본문에 있는 것만
const ldBlocks = [...home.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)];
expect(ldBlocks.length === 1, `홈 JSON-LD가 하나가 아니다(${ldBlocks.length}개)`);
let graph = [];
try {
  graph = JSON.parse(ldBlocks[0]?.[1] ?? '{}')['@graph'] ?? [];
} catch (error) {
  failures.push(`홈 JSON-LD를 읽지 못했다: ${error.message}`);
}
const node = (type) => graph.find((n) => n['@type'] === type);
for (const type of ['Organization', 'WebSite', 'SoftwareApplication', 'FAQPage']) {
  expect(node(type), `JSON-LD에 ${type}이 없다`);
}
const logo = node('Organization')?.logo;
if (logo) {
  expect(
    logo.startsWith(`${SITE}/`) && existsSync(new URL(fileOf(logo), root)),
    `Organization.logo 파일이 없다: ${logo}`,
  );
}
const visible = text(home);
const offer = node('SoftwareApplication')?.offers;
if (offer?.price === '0' || offer?.price === 0) {
  expect(
    visible.includes('무료 베타'),
    'JSON-LD 가격이 0원인데 본문에 「무료 베타」가 보이지 않는다',
  );
}
expect(
  !node('SoftwareApplication')?.aggregateRating && !node('SoftwareApplication')?.review,
  'SoftwareApplication에 평점·리뷰가 있다 — 실제 평점이 없으면 넣지 않는다',
);
// FAQPage는 화면 FAQ(S10 <details>)와 순서·글자까지 같아야 한다
const faqSection = home.match(/<section[^>]*id="faq"[\s\S]*?<\/section>/)?.[0] ?? '';
const shown = [...faqSection.matchAll(/<summary>([\s\S]*?)<\/summary>\s*<p>([\s\S]*?)<\/p>/g)].map(
  ([, q, a]) => ({ q: text(q), a: text(a) }),
);
const listed = (node('FAQPage')?.mainEntity ?? []).map((item) => ({
  q: item.name,
  a: item.acceptedAnswer?.text,
}));
expect(shown.length > 0, '화면 FAQ를 찾지 못했다');
expect(
  JSON.stringify(shown) === JSON.stringify(listed),
  `FAQPage가 화면 FAQ와 다르다 —\n  화면: ${JSON.stringify(shown)}\n  JSON-LD: ${JSON.stringify(listed)}`,
);

// --- 사실 요약(POK-350): FAQ 첫 문항의 답이 AI 답변 엔진이 옮겨 갈 한 문단이다.
// SoftwareApplication.description과 llms.txt가 같은 문장을 쓴다 — 셋이 다르면 엔진마다 다른 설명을 퍼뜨린다
const summary = shown[0]?.a ?? '';
expect(
  summary.startsWith('PokeClip(포케클립)은'),
  'FAQ 첫 문항이 사실 요약(「PokeClip(포케클립)은 …」)이 아니다',
);
expect(
  node('SoftwareApplication')?.description === summary,
  'SoftwareApplication.description이 FAQ 첫 문항의 사실 요약과 다르다',
);
expect(existsSync(new URL('llms.txt', root)), 'llms.txt가 없다');
if (existsSync(new URL('llms.txt', root))) {
  const llms = raw('llms.txt');
  expect(llms.includes(summary), 'llms.txt에 FAQ 첫 문항의 사실 요약이 없다');
  for (const path of Object.keys(pages)) {
    expect(llms.includes(`(${SITE}${path})`), `llms.txt에 ${SITE}${path} 링크가 없다`);
  }
}

// --- 공개 파일에는 내부 티켓 번호를 두지 않는다 — 주석도 소스 보기·robots.txt로 그대로 보인다
// README·scripts/·legal-versions.json은 배포하지 않는 파일이다(README 「이용약관 · 개인정보 처리방침」)
const PRIVATE = new Set(['README.md', 'scripts', 'legal-versions.json']);
const publicTexts = (dir) =>
  readdirSync(new URL(dir || '.', root), { withFileTypes: true }).flatMap((entry) => {
    const path = `${dir}${entry.name}`;
    if (PRIVATE.has(path)) return [];
    if (entry.isDirectory()) return publicTexts(`${path}/`);
    return /\.(html|css|js|txt|xml|svg)$/.test(entry.name) ? [path] : [];
  });
for (const path of publicTexts('')) {
  const tickets = [...new Set(raw(path).match(/\bPOK-\d+\b/g) ?? [])];
  expect(tickets.length === 0, `공개 파일 ${path}에 내부 티켓 번호가 있다: ${tickets.join(', ')}`);
}

if (failures.length > 0) {
  console.error(failures.map((f) => `✗ ${f}`).join('\n'));
  process.exit(1);
}
console.log('✓ 검색 노출 검사 통과');
