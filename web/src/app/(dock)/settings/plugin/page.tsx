import { PluginSettingsScreen } from '@/features/settings/plugin/PluginSettingsScreen';
import { OwnerOnly } from '@/features/channel/OwnerOnly';

export const metadata = { title: '플러그인 설정 · PokeClip' };

// 설정 > 플러그인 (POK-100 — Stream Key·연결 코드)
export default function PluginSettingsPage() {
  return (
    <OwnerOnly title="플러그인">
      <PluginSettingsScreen />
    </OwnerOnly>
  );
}
