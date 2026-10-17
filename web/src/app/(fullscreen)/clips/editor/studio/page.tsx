import { Suspense } from 'react';
import { StudioScreen } from '@/features/clips/editor/studio/StudioScreen';

export const metadata = { title: '클립 편집 · PokeClip' };

// 클립 › 편집기 (POK-251 — 실제 카드·편집본으로 연다). 화면이 useSearchParams 로 ?stream=·?card= 를 읽는다 —
// Next 는 그 훅을 쓰는 트리에 Suspense 경계를 요구한다.
export default function ClipEditorStudioPage() {
  return (
    <Suspense>
      <StudioScreen />
    </Suspense>
  );
}
