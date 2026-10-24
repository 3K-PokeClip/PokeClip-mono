import { Suspense } from 'react';
import { CALLBACK_PENDING_LABEL, CallbackPending } from '@/features/auth/CallbackPending';
import { OAuthCallbackScreen } from '@/features/auth/OAuthCallbackScreen';

export const metadata = { title: '로그인 중 · PokeClip' };

// 구글 OAuth redirect_uri — 백엔드 GoogleAuthProperties 기본값({origin}/auth/callback)과
// 짝이다. useSearchParams를 쓰는 클라 화면이라 프리렌더 경계로 Suspense가 필요하다.
// 대기 화면은 화면의 교환 대기와 같은 CallbackPending이다(POK-442): 가드 밖이라 null이면 하이드레이션 전까지 빈 화면이다.
export default function AuthCallbackPage() {
  return (
    <Suspense fallback={<CallbackPending label={CALLBACK_PENDING_LABEL.login} />}>
      <OAuthCallbackScreen />
    </Suspense>
  );
}
