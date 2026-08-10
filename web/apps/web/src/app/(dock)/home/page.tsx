export const metadata = { title: '홈 · PokeClip' };

// 독 1 — 빈 화면 (홈 대시보드는 별도 티켓)
// DS dist 배럴은 서버 컴포넌트에서 import 불가(클라이언트 경계 규칙) — 제목은 순수 h1로.
export default function HomePage() {
  return <h1>홈</h1>;
}
