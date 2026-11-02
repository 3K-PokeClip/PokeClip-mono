import { LibraryScreen } from '@/features/clips/library/LibraryScreen';

export const metadata = { title: '보관함 · PokeClip' };

// 클립 › 보관함 (시안 1g) — 화면 본체는 features/clips/library에 있고 여기서는 조립만 한다.
// 슬라이드 래퍼는 clips/layout이 갖는다.
export default function ClipLibraryPage() {
  return <LibraryScreen />;
}
