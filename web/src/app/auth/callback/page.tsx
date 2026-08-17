import { Suspense } from 'react';
import { OAuthCallbackScreen } from '@/features/auth/OAuthCallbackScreen';

export const metadata = { title: '로그인 중 · PokeClip' };

// 구글 OAuth redirect_uri — 백엔드 GoogleAuthProperties 기본값({origin}/auth/callback)과
// 짝이다. useSearchParams를 쓰는 클라 화면이라 프리렌더 경계로 Suspense가 필요하다.
export default function AuthCallbackPage() {
  return (
    <Suspense fallback={null}>
      <OAuthCallbackScreen />
    </Suspense>
  );
}
