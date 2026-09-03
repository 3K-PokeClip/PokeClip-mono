import { redirect } from 'next/navigation';

// 클립 그룹 진입 시 보관함으로 보낸다 (방송 → 라이브 대시보드 선례).
// 그룹 루트가 화면을 겸하면 승인 대기함(/clips/approvals) 같은 형제 경로에서도 하위 경로
// 매칭으로 보관함이 활성으로 따라붙는다 — 화면들을 형제 경로로 두는 이유다.
export default function ClipsPage() {
  redirect('/clips/library');
}
