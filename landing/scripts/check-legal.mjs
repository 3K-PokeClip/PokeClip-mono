// 이용약관·개인정보 처리방침 정적 페이지 검사 (POK-269). 의존성 없이 node만으로 돈다.
//
//   node landing/scripts/check-legal.mjs            # 내용 검사
//   node landing/scripts/check-legal.mjs --release  # + 자리표시 〈 〉가 남으면 실패 (공개 배포 전)
//
// 랜딩에는 테스트 장치가 없어서, 빠지면 법 위반이 되는 문장만 문자열로 지킨다.
// 랜딩을 SSG로 옮기면 이 검사도 그쪽 테스트로 옮긴다.
import { existsSync, readFileSync } from 'node:fs';
import { changedFrozen, readManifest, syncAll } from './legal-versions.mjs';

const root = new URL('..', import.meta.url);
const raw = (path) => readFileSync(new URL(path, root), 'utf8');
// 내용 검사는 HTML 주석을 빼고 본다 — 주석 처리한 필수 문장이 본문처럼 통과하지 않게.
// 주석 속 내부 메모는 아래에서 원문(raw)으로 따로 막는다
const read = (path) => raw(path).replace(/<!--[\s\S]*?-->/g, '');
const text = (html) =>
  html
    .replace(/<[^>]+>/g, '')
    .replace(/\s+/g, ' ')
    .trim();
const headings = (html) => [...html.matchAll(/<h2[^>]*>([\s\S]*?)<\/h2>/g)].map((m) => text(m[1]));

const failures = [];
const expect = (ok, message) => {
  if (!ok) failures.push(message);
};

const pages = { terms: read('terms/index.html'), privacy: read('privacy/index.html') };

// 개인정보 보호법 제30조·시행령 제31조와 작성지침(2025-04)이 요구하는 기재 항목
const REQUIRED_PRIVACY_SECTIONS = [
  '1. 개인정보의 처리 목적',
  '2. 처리하는 개인정보 항목과 처리 근거',
  '3. 개인정보의 보유 기간',
  '4. 개인정보의 파기 절차와 방법',
  '5. 개인정보의 제3자 제공',
  '6. 개인정보 처리의 위탁',
  '7. 개인정보의 국외 이전',
  '8. 개인정보의 안전성 확보 조치',
  '9. 자동 수집 장치의 설치·운영과 거부',
  '10. 정보주체의 권리와 행사 방법',
  '11. 자동화된 결정',
  '12. 인공지능 관련 처리',
  '13. YouTube API 서비스와 Google 사용자 데이터',
  '14. 치지직·SOOP 연동과 채팅 데이터',
  '15. 개인정보 보호책임자',
  '16. 권익 침해 구제 방법',
  '17. 개인정보 처리방침의 변경',
];
const privacyHeadings = headings(pages.privacy);
for (const title of REQUIRED_PRIVACY_SECTIONS) {
  expect(privacyHeadings.includes(title), `처리방침에 「${title}」 절이 없다`);
}

// YouTube API Services Developer Policies III.A — 주소는 정책에 적힌 그대로여야 한다
const href = (html, url) => html.includes(`href="${url}"`);
expect(
  href(pages.privacy, 'http://www.google.com/policies/privacy'),
  '처리방침에 Google 개인정보처리방침 링크가 없다',
);
expect(
  href(pages.privacy, 'https://security.google.com/settings/security/permissions'),
  '처리방침에 Google 권한 철회 링크가 없다',
);
expect(
  href(pages.privacy, 'https://developers.google.com/terms/api-services-user-data-policy'),
  '처리방침에 Google API 사용자 데이터 정책(Limited Use) 링크가 없다',
);
expect(
  text(pages.privacy).includes('YouTube API 서비스를 사용합니다'),
  '처리방침에 YouTube API 사용 고지가 없다',
);
expect(
  href(pages.terms, 'https://www.youtube.com/t/terms'),
  '약관에 YouTube 서비스 약관 링크가 없다',
);
expect(
  text(pages.terms).includes(
    '회원은 서비스를 이용함으로써 YouTube 서비스 약관에 구속되는 데 동의합니다.',
  ),
  '약관에 YouTube 약관 구속 문장이 없다',
);

// 치지직·SOOP은 받는 정보·목적·권한은 같고, 연동 해제 뒤 처리만 각 플랫폼 약관을 따른다.
// SOOP 개발자 이용약관 제11조 ⑰: 동의 철회를 주기적으로 확인하고 지체 없이 파기한다
expect(
  text(pages.privacy).includes(
    '해제하거나 동의를 철회하면 토큰을 폐기하고 그 채널의 정보를 더 받지 않으며, 그 채널에서 받은 개인정보는 지체 없이 파기합니다.',
  ),
  '처리방침에 SOOP 연동 해제·동의 철회 시 수집 중단과 지체 없는 파기가 없다',
);
// 치지직 개발자 센터 이용약관에는 해제 시 파기 의무가 없다 — 해제해도 보관 기간(받은 지 60일)까지 둔다(ADR-090 결정 3·4)
expect(
  text(pages.privacy).includes(
    '이미 받은 정보는 보관 기간(받은 지 60일)이 끝나거나 회원이 탈퇴하면 지체 없이 파기합니다.',
  ),
  '처리방침에 치지직 연동 해제 뒤 보관 기간과 파기 시점이 없다',
);
expect(
  text(pages.privacy).includes('연동 상태를 주기적으로 확인해'),
  '처리방침에 치지직·SOOP 동의 철회 확인이 없다',
);
expect(
  text(pages.privacy).includes('받은 정보를 팔거나 제3자에게 주지 않습니다.'),
  '처리방침에 치지직·SOOP 제3자 제공 금지가 없다',
);
expect(
  text(pages.terms).includes('치지직과 SOOP 각각의 이용약관과 운영정책을 지키는 데 동의합니다'),
  '약관에 치지직·SOOP 약관 준수 문장이 없다',
);

// YouTube API Services Developer Policies III.D.2.c — 서비스 안에서 연동을 해제하면 토큰을 Google에 바로 revoke하고,
// API 데이터는 철회 뒤 7일 안에 지운다. III.E.4.c — 토큰 밖 API 데이터는 30일마다 갱신하거나 지운다
expect(
  text(pages.privacy).includes(
    '서비스에서 연동을 해제하면 Google에도 권한 취소를 바로 요청합니다.',
  ),
  '처리방침에 YouTube 연동 해제 시 Google 권한 취소 요청이 없다',
);
expect(
  text(pages.privacy).includes(
    '연동 중에는 30일마다 YouTube에서 다시 확인해 갱신하고, 서비스에서 연동을 해제하거나 탈퇴하면 7일 안에',
  ),
  '처리방침 13절에 YouTube 데이터의 30일 갱신과 해제·탈퇴 7일 삭제가 없다',
);

// Google OAuth 브랜드 확인이 Google API 이용약관 위반 의심(AI NCII)으로 반려됐다(2026-10-17). 기준은 공개되지 않았고,
// 같은 사유로 반려된 앱들은 Google API를 AI NCII에 쓰지 않는다는 문장과 권한별 용도를 적어 대응했다
expect(
  text(pages.privacy).includes(
    '서비스는 Google API와 Google 사용자 데이터를 당사자 동의 없이 인공지능으로 만든 성적 이미지·영상(AI-generated Non-Consensual Intimate Imagery, AI NCII)',
  ),
  '처리방침 12절에 Google API를 AI NCII에 쓰지 않는다는 문장이 없다',
);
for (const scope of [
  'Google 로그인(openid·email·profile):',
  '동영상 업로드 권한(youtube.upload):',
  '채널 조회 권한(youtube.readonly):',
]) {
  expect(text(pages.privacy).includes(scope), `처리방침 13절 권한별 용도에 「${scope}」가 없다`);
}

// 분석 도구(PostHog) — 수탁자·국외 이전(개인정보 보호법 제26조·제28조의8 ②)과 거부 방법, YouTube 정책 III.A.2.g
// (기기에 쿠키를 두고 정보를 모으면 알린다). 도구를 빼거나 바꾸면 이 문장들이 먼저 걸린다
expect(text(pages.privacy).includes('PostHog Inc.서비스 이용 분석'), '처리방침 6절에 PostHog 위탁이 없다');
expect(
  text(pages.privacy).includes('이전받는 자PostHog Inc.'),
  '처리방침 7절에 PostHog 국외 이전이 없다',
);
expect(href(pages.privacy, 'https://posthog.com/privacy'), '처리방침에 PostHog 개인정보처리방침 링크가 없다');
expect(
  text(pages.privacy).includes('이용 분석만 거부하려면 브라우저의 추적 금지(Do Not Track) 설정을 켜거나'),
  '처리방침 9절에 이용 분석 거부 방법이 없다',
);
expect(
  text(pages.privacy).includes(
    'Google에서 받은 사용자 데이터(YouTube 채널 ID·채널 이름, 영상 정보)는 분석 도구에 보내지 않습니다.',
  ),
  '처리방침 13절에 Google 사용자 데이터를 분석 도구에 보내지 않는다는 문장이 없다',
);
// 대시보드·랜딩의 분석 설정(web/docs/ANALYTICS.md 「결정」 표)을 적은 2·3·4절 — IP 저장 끔, 녹화 가림, 보존 기간,
// 탈퇴 뒤 삭제. 설정을 바꾸면 이 문장들이 먼저 걸린다
for (const [section, phrase] of [
  ['2절', '접속 국가(접속 IP로 추정하고 IP는 저장하지 않음)'],
  ['2절', '화면의 글자와 입력 내용을 가린 채'],
  ['3절', '수집한 날부터 1년. 회원이 탈퇴하면 분석 도구에 있는 그 회원의 기록을 7일 안에 삭제'],
  ['3절', '녹화한 날부터 30일. 회원이 탈퇴하면 7일 안에 함께 삭제'],
  [
    '4절',
    '분석 도구(PostHog)에 있는 탈퇴한 회원의 이용 분석 기록과 화면 조작 기록은 운영자가 탈퇴 뒤 7일 안에',
  ],
]) {
  expect(text(pages.privacy).includes(phrase), `처리방침 ${section}에 분석 설정 문장 「${phrase}」이 없다`);
}

// 바뀐 약속의 옛 문장이 남지 않는다 — 탈퇴 유예(ADR-090), 확인 뒤 업로드(ADR-091), 해제해도 남는 Google 권한(III.D.2.c),
// 분석 도구를 쓰지 않는다던 9절(PostHog 도입)
for (const [doc, phrase] of [
  ['privacy', '탈퇴 후 5일 이내'],
  ['privacy', '확인하고 업로드를 요청하면'],
  ['privacy', 'Google 쪽 권한은 남을 수 있으니'],
  ['privacy', '쿠키와 브라우저 저장소를 쓰지 않습니다'],
  ['privacy', '방문 분석, 광고, 행태정보 수집을 위한 쿠키나 도구는 쓰지 않습니다'],
  ['terms', '서비스에서 확인하고 요청한 클립'],
  ['terms', 'Google 쪽에 남는 접근 권한은'],
]) {
  expect(
    !text(pages[doc]).includes(phrase),
    `${doc}에 바뀐 약속의 옛 문장 「${phrase}」이 남아 있다`,
  );
}

// 처리방침은 회원이 아닌 시청자 정보와 그 권리 행사를 밝혀야 한다
expect(
  text(pages.privacy).includes('시청자 정보(회원 아님)'),
  '처리방침에 시청자 정보 항목이 없다',
);
expect(
  text(pages.privacy).includes('회원이 아닌 시청자도 같은 권리를 행사할 수 있습니다.'),
  '처리방침에 시청자 권리 행사가 없다',
);

// 약관 — 14세 제한, 저작권법 제103조 수령인, 약관규제법 제7조(고의·중과실 면책 무효)
expect(
  text(pages.terms).includes('만 14세 미만은 가입할 수 없습니다.'),
  '약관에 만 14세 제한이 없다',
);
expect(
  text(pages.terms).includes('복제·전송 중단 요청 담당자'),
  '약관에 복제·전송 중단 요청 담당자가 없다',
);
expect(!text(pages.terms).includes('일체 책임'), '약관에 「일체 책임」 같은 전부 면책 문장이 있다');
expect(
  text(pages.terms).includes('고의나 중대한 과실로 생긴 손해에는 이 항을 적용하지 않습니다'),
  '약관의 책임 제한에 고의·중과실 예외가 없다',
);

// 목차의 모든 링크가 실제 절로 이어진다
for (const [name, html] of Object.entries(pages)) {
  const toc = html.match(/<nav class="legal-toc"[\s\S]*?<\/nav>/);
  expect(toc !== null, `${name}에 목차가 없다`);
  for (const [, id] of toc?.[0].matchAll(/href="#([^"]+)"/g) ?? []) {
    expect(html.includes(`id="${id}"`), `${name} 목차의 #${id}가 가리키는 절이 없다`);
  }
}

// 문서 밖에서 들어오는 앵커도 살아 있어야 한다 — 약관이 처리방침 절을, 대시보드 탈퇴 모달이 #retention을 가리킨다.
// id를 바꾸면서 목차만 같이 고치면 위 검사는 통과하고, 바깥 링크는 조용히 문서 맨 위로 떨어진다
const webLegal = new URL('../web/src/features/legal/legalInfo.ts', root);
for (const [name, html] of Object.entries(pages)) {
  for (const [, doc, id] of html.matchAll(
    /href="(?:\.\.)?\/(terms|privacy)\/?(?:index\.html)?#([^"]+)"/g,
  )) {
    expect(pages[doc].includes(`id="${id}"`), `${name}이 가리키는 ${doc}#${id} 절이 없다`);
  }
}
if (existsSync(webLegal)) {
  // 정본 주소는 끝 슬래시 꼴(/privacy/#retention)이다. 주소 꼴이 바뀌어 정규식이 하나도 못 잡으면 검사가 조용히
  // 꺼지므로, 앵커가 적어도 하나(탈퇴 모달의 #retention)는 잡혀야 한다
  const anchors = [...readFileSync(webLegal, 'utf8').matchAll(/\/(terms|privacy)\/?#([\w-]+)/g)];
  expect(anchors.length > 0, '대시보드(legalInfo.ts)에서 약관·처리방침 앵커를 하나도 못 찾았다');
  for (const [, doc, id] of anchors) {
    expect(
      pages[doc].includes(`id="${id}"`),
      `대시보드(legalInfo.ts)가 가리키는 ${doc}#${id} 절이 없다`,
    );
  }
}

// 시행일 드롭다운이 legal-versions.json과 같아야 한다 — 지난 판을 고를 수 없으면 판을 보관한 의미가 없다
try {
  const stale = syncAll({ write: false });
  expect(
    stale.length === 0,
    `시행일 드롭다운이 목록과 다르다: ${stale.join(', ')} — scripts/legal-versions.mjs를 돌린다`,
  );
} catch (error) {
  failures.push(error.message);
}

try {
  const manifest = readManifest();

  // 얼린 지난 판은 고치지 않는다 — 얼릴 때 적은 본문 지문과 지금 본문이 같아야 한다
  const changed = changedFrozen();
  expect(changed.length === 0, `얼린 지난 판의 본문이 바뀌었다: ${changed.join(', ')}`);

  // 현재 판 시행일이 본문 시행 문장(약관 부칙·처리방침 17절)과 같아야 한다 — archive 뒤 본문을 안 고치면 여기서 걸린다
  // 시행일이 아직 자리표시(〈시행일 확정 후 기재〉)면 본문에도 같은 자리표시가 있어야 한다
  const koDate = (label) => {
    if (!/^\d{4}\.\d{2}\.\d{2}$/.test(label)) return label;
    const [y, m, d] = label.split('.');
    return `${y}년 ${Number(m)}월 ${Number(d)}일`;
  };
  const EFFECTIVE = { terms: '부터 시행합니다', privacy: '부터 적용합니다' };
  for (const [doc, tail] of Object.entries(EFFECTIVE)) {
    const { label } = manifest[doc][0];
    expect(
      text(pages[doc]).includes(`${koDate(label)}${tail}`),
      `${doc} 본문의 시행일 문장이 목록의 현재 판(${label})과 다르다`,
    );
  }

  // 공개 HTML(지난 판 포함)에는 내부 메모를 두지 않는다 — 주석도 소스 보기로 그대로 보인다. 메모는 README에 둔다
  const memos = Object.values(manifest)
    .flat()
    .map((version) => version.file)
    .filter((file) => /🔴|목표값/.test(raw(file)));
  expect(
    memos.length === 0,
    `${memos.join(', ')}에 내부 메모(🔴·목표값)가 있다 — HTML 주석도 공개 소스에 보인다`,
  );
} catch (error) {
  failures.push(error.message);
}

// 자리표시 —평소에는 알리기만 하고, --release에서는 실패시킨다. (머리 주석은 read가 이미 뺐다)
// 지금 꼴은 〈… 확정 후 기재〉·〈… 지정 후 기재〉이고, 옛 꼴 〔 〕가 남아도 잡는다
const placeholders = Object.entries(pages).flatMap(([name, html]) =>
  [...html.matchAll(/〈[^〉]*〉|〔[^〕]*〕/g)].map((m) => `${name}: ${m[0]}`),
);
if (placeholders.length > 0) {
  const message = `자리표시 ${placeholders.length}곳이 남았다 — ${[...new Set(placeholders)].join(', ')}`;
  if (process.argv.includes('--release')) failures.push(message);
  else console.warn(`⚠ ${message}`);
}

if (failures.length > 0) {
  console.error(failures.map((f) => `✗ ${f}`).join('\n'));
  process.exit(1);
}
console.log('✓ 약관·처리방침 검사 통과');
