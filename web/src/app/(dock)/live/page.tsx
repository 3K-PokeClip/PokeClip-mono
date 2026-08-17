import { ScreenTransition } from '@/components/app-shell/ScreenTransition';
import { LiveScreen } from '@/features/live/LiveScreen';

export const metadata = { title: '라이브 · PokeClip' };

// 독 2 — 라이브 대시보드 (디자인 1b). 전폭 자체 헤더를 가지므로
// ScreenContainer는 쓰지 않고 LiveScreen 내부에서 본문 폭을 잡는다.
export default function LivePage() {
  return (
    <ScreenTransition>
      <LiveScreen />
    </ScreenTransition>
  );
}
