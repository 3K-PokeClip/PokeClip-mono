import { AccountDeletedScreen } from '@/features/account/AccountDeletedScreen';

export const metadata = { title: '탈퇴 완료 · PokeClip' };

// 탈퇴 완료 안내 (POK-206, 디자인 1p ④).
//
// (dock) 밖에 있는 이유: AuthGuard는 토큰이 비는 순간 /login으로 보낸다. 탈퇴 직후는
// 정확히 그 상태라 가드 안에 두면 이 화면이 뜰 틈이 없다. /login과 같은 층에 둔다.
export default function GoodbyePage() {
  return <AccountDeletedScreen />;
}
