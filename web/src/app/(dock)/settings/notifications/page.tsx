import { NotificationSettingsScreen } from '@/features/settings/notifications/NotificationSettingsScreen';

export const metadata = { title: '알림 설정 · PokeClip' };

// 설정 > 알림 설정 (POK-209 — 화면만. 스위치는 값을 저장하지 않고 아무것도 발송하지 않는다)
export default function NotificationSettingsPage() {
  return <NotificationSettingsScreen />;
}
