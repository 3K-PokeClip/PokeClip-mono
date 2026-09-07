import { ScreenContainer } from '@/components/app-shell/ScreenContainer';

export const metadata = { title: '승인 대기함 · PokeClip' };

// 클립 › 승인 대기함 — 빈 화면 (심사 큐 본체는 시안 1j 별도 티켓 POK-236).
// 보관함(1g)의 승인 대기 배너와 상세 패널 주 동작이 갈 자리라 함께 만든다.
// 링크가 404로 끝나면 이동이 아니라 고장이다 (VOD 뷰어 자리 페이지와 같은 이유).
//
// 사이드바 「승인 대기함」 항목은 아직 href가 없다 — 화면이 서는 티켓이 배지 수와 함께
// 붙이기로 한 자리다. 여기서는 보관함이 이미 내보내는 두 링크가 갈 곳만 세운다.
export default function ClipApprovalsPage() {
  return (
    <ScreenContainer>
      <h1>승인 대기함</h1>
    </ScreenContainer>
  );
}
