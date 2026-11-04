// 이용약관·개인정보 처리방침 주소 (POK-269).
//
// 두 문서는 랜딩(pokeclip.com)의 정적 페이지다 — 원본은 landing/terms·privacy이고,
// 랜딩을 SSG로 옮길 때 함께 옮긴다. 대시보드(app.pokeclip.com)는 문서를 갖지 않고 그 주소로 잇기만
// 한다. 원본이 둘이면 두 문서가 서로 다른 말을 하게 된다.
//
// 로컬에서 랜딩 시안을 띄워 확인할 때는 NEXT_PUBLIC_LANDING_URL을 그 주소로 준다(.env.example).
const LANDING_URL = (process.env.NEXT_PUBLIC_LANDING_URL || 'https://pokeclip.com').replace(
  /\/+$/,
  '',
);

// 정본은 끝 슬래시 꼴이다 — 문서가 디렉터리 index(terms/index.html)라 canonical·sitemap·판 목록이 모두 이 꼴이고,
// 슬래시 없는 주소는 호스팅이 301로 보낸다(POK-349). 리다이렉트를 한 번 더 타지 않게 여기서도 같은 꼴로 잇는다
export const LEGAL_URLS = {
  terms: `${LANDING_URL}/terms/`,
  privacy: `${LANDING_URL}/privacy/`,
  privacyRetention: `${LANDING_URL}/privacy/#retention`,
} as const;

// 문의 메일 — 랜딩 하단·처리방침·약관(landing/)에 적힌 주소와 같다. 대시보드에서는 도움말 문의 카드와
// 탈퇴 창이 이 상수 하나를 쓴다. 주소를 바꾸면 landing/의 같은 주소도 함께 고친다.
export const SUPPORT_EMAIL = 'support@pokeclip.com';

// YouTube API Services Developer Policies III.A가 문구와 주소를 지정한다.
// 다른 주소로 바꾸지 않는다 — 심사는 정책에 적힌 주소 그대로를 찾는다.
export const EXTERNAL_URLS = {
  youtubeTerms: 'https://www.youtube.com/t/terms',
  googlePrivacy: 'http://www.google.com/policies/privacy',
  googlePermissions: 'https://security.google.com/settings/security/permissions',
} as const;
