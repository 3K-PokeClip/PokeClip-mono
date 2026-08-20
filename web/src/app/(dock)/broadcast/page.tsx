import { redirect } from 'next/navigation';

// 방송 그룹 진입 시 라이브 대시보드로 보낸다 (설정 → 플러그인 선례).
// 그룹 루트가 화면을 겸하면 지난 방송(/broadcast/vod)에서도 하위 경로 매칭으로
// 라이브 대시보드가 활성으로 따라붙는다 — 두 화면을 형제 경로로 두는 이유다.
export default function BroadcastPage() {
  redirect('/broadcast/livenow');
}
