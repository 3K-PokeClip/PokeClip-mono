import { Suspense } from 'react';
import { LiveScreen } from '@/features/broadcast/livenow/LiveScreen';

export const metadata = { title: '라이브 · PokeClip' };

// 방송 › 라이브 대시보드 (디자인 1b). 전폭 자체 헤더를 가지므로
// ScreenContainer는 쓰지 않고 LiveScreen 내부에서 본문 폭을 잡는다.
//
// 화면이 맨 위에서 useSearchParams로 오프라인 목업 토글(?mock=offline, POK-227)을 읽는다 —
// Next는 그 훅을 쓰는 트리에 Suspense 경계를 요구하므로 화면 전체를 감싼다(settings/account 선례).
// 플레이어 쪽(?stream=) 경계는 LiveScreen 안에 그대로 있다 — 토글이 실제 조회로 바뀌면 다시 그쪽이 가장 가깝다.
export default function LivePage() {
  return (
    <Suspense>
      <LiveScreen />
    </Suspense>
  );
}
