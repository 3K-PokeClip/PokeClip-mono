import { Suspense } from 'react';
import { CALLBACK_PENDING_LABEL, CallbackPending } from '@/features/auth/CallbackPending';
import { SoopCallbackScreen } from '@/features/settings/channels/SoopCallbackScreen';

// SOOP Redirect URL. 백엔드 SOOP_REDIRECT_URI({origin}/oauth/soop/callback)·SOOP 개발자 콘솔 등록값과 짝이고
// 경로 상수는 features/settings/channels/soopOAuth.ts의 SOOP_CALLBACK_PATH다. 치지직 콜백 페이지와 같은 모양.
// 대기 화면은 화면의 교환 대기와 같은 CallbackPending이다(POK-442): 가드 밖이라 null이면 하이드레이션 전까지 빈 화면이다.
export const metadata = {
  title: '채널 연동 중 · PokeClip',
  // code가 실린 주소다. 색인될 자리가 아니다.
  robots: { index: false, follow: false },
};

export default function SoopCallbackPage() {
  return (
    <Suspense fallback={<CallbackPending label={CALLBACK_PENDING_LABEL.soop} />}>
      <SoopCallbackScreen />
    </Suspense>
  );
}
