import { redirect } from 'next/navigation';

// 인증 도입 전 임시 진입점 — 로그인 티켓에서 세션 분기로 교체한다
export default function RootPage() {
  redirect('/home');
}
