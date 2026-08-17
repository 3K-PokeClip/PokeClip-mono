import { LoginScreen } from '@/features/auth/LoginScreen';

export const metadata = { title: '로그인 · PokeClip' };

// 로그인 진입 라우트 — 셸 없음. 구글 OAuth·인증 가드는 POK-101 범위.
export default function LoginPage() {
  return <LoginScreen />;
}
