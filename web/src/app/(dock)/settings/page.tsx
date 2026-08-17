import { redirect } from 'next/navigation';

// 설정 진입 시 첫 구현 화면인 플러그인으로 보낸다 (POK-100 — 나머지 탭은 별도 티켓)
export default function SettingsPage() {
  redirect('/settings/plugin');
}
