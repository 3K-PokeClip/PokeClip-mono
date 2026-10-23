import { HelpSettingsScreen } from '@/features/settings/help/HelpSettingsScreen';

export const metadata = { title: '도움말 · 문의 · PokeClip' };

// 설정 > 도움말 · 문의 (디자인 1q, POK-386). 1:1 문의는 서버가 없어 메일로 받는다
export default function HelpSettingsPage() {
  return <HelpSettingsScreen />;
}
