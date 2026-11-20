import { EditingSettingsScreen } from '@/features/settings/editing/EditingSettingsScreen';

export const metadata = { title: '편집 환경 · PokeClip' };

// 설정 > 편집 환경 (POK-614 — 도구 패널 위치 · 오디오 트랙 이름). 채널 주인 전용이 아니다:
// 패널 위치는 편집자도 쓴다. 오디오 트랙 이름만 화면이 남의 채널에서 막는다(EditingSettingsScreen)
export default function EditingSettingsPage() {
  return <EditingSettingsScreen />;
}
