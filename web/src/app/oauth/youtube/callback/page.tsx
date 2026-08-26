import { Suspense } from 'react';
import { YoutubeCallbackScreen } from '@/features/settings/channels/YoutubeCallbackScreen';

// 유튜브(구글) redirect URI — 백엔드 YOUTUBE_REDIRECT_URI({origin}/oauth/youtube/callback)와
// 짝이고 경로 상수는 features/settings/channels/youtubeOAuth.ts의 YOUTUBE_CALLBACK_PATH다.
// (dock) 밖이라 AuthGuard가 없다 — 세션 판정은 화면이 직접 한다.
// useSearchParams를 쓰는 클라 화면이라 프리렌더 경계로 Suspense가 필요하다.
export const metadata = {
  title: '채널 연동 중 · PokeClip',
  // code·state가 실린 주소다. 색인될 자리가 아니다.
  robots: { index: false, follow: false },
};

export default function YoutubeCallbackPage() {
  return (
    <Suspense fallback={null}>
      <YoutubeCallbackScreen />
    </Suspense>
  );
}
