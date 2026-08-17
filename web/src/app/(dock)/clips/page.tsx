import { ScreenContainer } from '@/components/app-shell/ScreenContainer';
import { ScreenTransition } from '@/components/app-shell/ScreenTransition';

export const metadata = { title: '클립 · PokeClip' };

// 독 3 — 빈 화면 (보관함·에디터는 별도 티켓, 디자인상 클립 사이드바가 붙는다)
export default function ClipsPage() {
  return (
    <ScreenTransition>
      <ScreenContainer>
        <h1>클립</h1>
      </ScreenContainer>
    </ScreenTransition>
  );
}
