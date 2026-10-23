import { ChannelSettingsScreen } from '@/features/settings/channels/ChannelSettingsScreen';
import { OwnerOnly } from '@/features/channel/OwnerOnly';

export const metadata = { title: '채널 연동 · PokeClip' };

// 설정 > 채널 연동 (POK-112 — 목업 스텁: 방송 채널 섹션만. 본 구현에서 실화면으로 확장)
export default function ChannelSettingsPage() {
  return (
    <OwnerOnly>
      <ChannelSettingsScreen />
    </OwnerOnly>
  );
}
