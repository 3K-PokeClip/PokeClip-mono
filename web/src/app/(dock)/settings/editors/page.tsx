import { EditorSettingsScreen } from '@/features/settings/editors/EditorSettingsScreen';
import { OwnerOnly } from '@/features/channel/OwnerOnly';

export const metadata = { title: '편집자 관리 · PokeClip' };

// 설정 > 편집자 관리 (POK-208 — 디자인 1l)
export default function EditorSettingsPage() {
  return (
    <OwnerOnly title="편집자 관리">
      <EditorSettingsScreen />
    </OwnerOnly>
  );
}
