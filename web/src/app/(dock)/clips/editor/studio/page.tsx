import { StudioScreen } from '@/features/clips/editor/studio/StudioScreen';

export const metadata = { title: '클립 편집 · PokeClip' };

// 클립 › 편집기 스튜디오형 (시안 1d-a). 전폭 자체 헤더를 가지므로
// ScreenContainer는 쓰지 않고 StudioScreen이 직접 화면을 구성한다.
export default function ClipEditorStudioPage() {
  return <StudioScreen />;
}
