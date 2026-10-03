// 이용약관·개인정보 처리방침 주소 (POK-269).
//
// 두 문서는 랜딩(pokeclip.com)의 정적 페이지다 — 원본은 design/landing-prototype/terms·privacy이고,
// 랜딩을 SSG로 옮길 때 함께 옮긴다. 대시보드(app.pokeclip.com)는 문서를 갖지 않고 그 주소로 잇기만
// 한다. 원본이 둘이면 두 문서가 서로 다른 말을 하게 된다.
//
// 로컬에서 랜딩 시안을 띄워 확인할 때는 NEXT_PUBLIC_LANDING_URL을 그 주소로 준다(.env.example).
const LANDING_URL = (process.env.NEXT_PUBLIC_LANDING_URL || 'https://pokeclip.com').replace(
  /\/+$/,
  '',
);

export const LEGAL_URLS = {
  terms: `${LANDING_URL}/terms`,
  privacy: `${LANDING_URL}/privacy`,
  privacyRetention: `${LANDING_URL}/privacy#retention`,
} as const;

// YouTube API Services Developer Policies III.A가 문구와 주소를 지정한다.
// 다른 주소로 바꾸지 않는다 — 심사는 정책에 적힌 주소 그대로를 찾는다.
export const EXTERNAL_URLS = {
  youtubeTerms: 'https://www.youtube.com/t/terms',
  googlePrivacy: 'http://www.google.com/policies/privacy',
  googlePermissions: 'https://security.google.com/settings/security/permissions',
} as const;
