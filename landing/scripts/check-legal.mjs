// 이용약관·개인정보 처리방침 정적 페이지 검사 (POK-269). 의존성 없이 node만으로 돈다.
//
//   node landing/scripts/check-legal.mjs            # 내용 검사
//   node landing/scripts/check-legal.mjs --release  # + 자리표시 〔 〕가 남으면 실패 (공개 배포 전)
//
// 랜딩에는 테스트 장치가 없어서, 빠지면 법 위반이 되는 문장만 문자열로 지킨다.
// 랜딩을 SSG로 옮기면 이 검사도 그쪽 테스트로 옮긴다.
import { readFileSync } from 'node:fs';
import { syncAll } from './legal-versions.mjs';

const root = new URL('..', import.meta.url);
const read = (path) => readFileSync(new URL(path, root), 'utf8');
const text = (html) => html.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
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
expect(href(pages.privacy, 'http://www.google.com/policies/privacy'), '처리방침에 Google 개인정보처리방침 링크가 없다');
expect(
  href(pages.privacy, 'https://security.google.com/settings/security/permissions'),
  '처리방침에 Google 권한 철회 링크가 없다',
);
expect(
  href(pages.privacy, 'https://developers.google.com/terms/api-services-user-data-policy'),
  '처리방침에 Google API 사용자 데이터 정책(Limited Use) 링크가 없다',
);
expect(text(pages.privacy).includes('YouTube API 서비스를 사용합니다'), '처리방침에 YouTube API 사용 고지가 없다');
expect(href(pages.terms, 'https://www.youtube.com/t/terms'), '약관에 YouTube 서비스 약관 링크가 없다');
expect(
  text(pages.terms).includes('회원은 서비스를 이용함으로써 YouTube 서비스 약관에 구속되는 데 동의합니다.'),
  '약관에 YouTube 약관 구속 문장이 없다',
);

// 치지직·SOOP은 같은 기준이다 — 더 엄격한 SOOP 개발자 이용약관(제11조 ⑤⑭⑰, 제16조 ⑦)을 두 플랫폼에 함께 쓴다.
// 제11조 ⑰: 동의 철회를 주기적으로 확인하고 지체 없이 파기한다. 해제는 처리 목적이 끝난 것이다(개인정보 보호법 21조)
expect(
  text(pages.privacy).includes('해제하면 바로 토큰을 폐기하고 그 채널의 수집을 멈춥니다. 진행 중이던 방송의 채팅 연결도 닫습니다.'),
  '처리방침에 치지직·SOOP 연동 해제 시 수집 중단이 없다',
);
expect(
  text(pages.privacy).includes('거기서 뽑은 집계 지표는 지체 없이 지웁니다'),
  '처리방침에 치지직·SOOP 데이터 지체 없는 삭제가 없다',
);
expect(
  text(pages.privacy).includes('토큰 갱신과 방송 시작 때마다 인증 결과를 확인합니다'),
  '처리방침에 치지직·SOOP 동의 철회 확인이 없다',
);
expect(
  text(pages.terms).includes('치지직과 SOOP 각각의 이용약관과 운영정책을 지키는 데 동의합니다'),
  '약관에 치지직·SOOP 약관 준수 문장이 없다',
);

// 처리방침은 회원이 아닌 시청자 정보와 그 권리 행사를 밝혀야 한다
expect(text(pages.privacy).includes('시청자 정보(회원 아님)'), '처리방침에 시청자 정보 항목이 없다');
expect(text(pages.privacy).includes('회원이 아닌 시청자도 같은 권리를 행사할 수 있습니다.'), '처리방침에 시청자 권리 행사가 없다');

// 약관 — 14세 제한, 저작권법 제103조 수령인, 약관규제법 제7조(고의·중과실 면책 무효)
expect(text(pages.terms).includes('만 14세 미만은 가입할 수 없습니다.'), '약관에 만 14세 제한이 없다');
expect(text(pages.terms).includes('복제·전송 중단 요청 담당자'), '약관에 복제·전송 중단 요청 담당자가 없다');
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

// 시행일 드롭다운이 legal-versions.json과 같아야 한다 — 지난 판을 고를 수 없으면 판을 보관한 의미가 없다
try {
  const stale = syncAll({ write: false });
  expect(stale.length === 0, `시행일 드롭다운이 목록과 다르다: ${stale.join(', ')} — scripts/legal-versions.mjs를 돌린다`);
} catch (error) {
  failures.push(error.message);
}

// 자리표시 — 평소에는 알리기만 하고, --release에서는 실패시킨다.
// HTML 주석은 뺀다 — 머리 주석이 「〔 〕는 자리표시다」라고 설명하느라 같은 괄호를 쓴다.
const placeholders = Object.entries(pages).flatMap(([name, html]) =>
  [...html.replace(/<!--[\s\S]*?-->/g, '').matchAll(/〔[^〕]*〕/g)].map((m) => `${name}: ${m[0]}`),
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
