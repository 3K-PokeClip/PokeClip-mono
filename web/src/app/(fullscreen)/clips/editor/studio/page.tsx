import { StudioScreen } from '@/features/clips/editor/studio/StudioScreen';

export const metadata = { title: '클립 편집 · PokeClip' };

// 클립 › 편집기 스튜디오형 (시안 1d-a). 독 없는 (fullscreen) 그룹에 있어
// 뷰포트를 끝까지 쓴다 — 시안도 하단 독 없이 타임라인이 바닥에 붙는다.
// 전폭 자체 헤더를 가지므로 ScreenContainer는 쓰지 않는다.
export default function ClipEditorStudioPage() {
  return <StudioScreen />;
}
