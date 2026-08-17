import { redirect } from 'next/navigation';

// 서버 리다이렉트는 /home 고정이다 — 세션이 localStorage에 있어 서버는 분기 재료가
// 없고, /home의 AuthGuard가 비로그인을 /login으로 보내 분기가 완성된다 (POK-101).
export default function RootPage() {
  redirect('/home');
}
