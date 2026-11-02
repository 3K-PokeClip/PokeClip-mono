import { Suspense } from 'react';
import { AccountSettingsScreen } from '@/features/settings/account/AccountSettingsScreen';

export const metadata = { title: '계정 · PokeClip' };

// 설정 > 계정 (POK-206 — 화면만. 이름·사진·탈퇴 어느 것도 서버에 가지 않는다)
//
// 화면이 useSearchParams로 차단 상태 목업 토글(?mock=blocked)을 읽는다 —
// Next는 그 훅을 쓰는 트리에 Suspense 경계를 요구한다.
export default function AccountSettingsPage() {
  return (
    <Suspense>
      <AccountSettingsScreen />
    </Suspense>
  );
}
