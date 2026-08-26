'use client';

import { ConfirmDialog } from '@/ui';

// 유튜브 연동 해제 확인 (디자인 1k, ADR-044) — 파괴적 동작은 모달로 확인받고
// 토스트는 결과만 알린다. 골격은 @/ui의 ConfirmDialog가 갖는다 (UnlinkChzzkDialog와
// 같은 모양). 이 파일에는 문구만 남는다.

/**
 * 재확인 문구 3줄. 무엇이 멈추고 무엇이 남는지를 갈라 말한다. 셋째 줄이 구글 권한 안내다 —
 * 우리는 revoke를 보내지 않으므로(ADR-052: 계정 단위라 남의 연동까지 끊는다) 해제해도
 * 구글 계정의 PokeClip 권한은 남는다. 결정하기 **전에** 알아야 할 재료라 모달에 둔다.
 * ConfirmDialog의 consequences는 string이라 링크는 못 걸고, 클릭 가능한 길은 해제 완료
 * 토스트의 「구글 권한 관리」 액션이 잇는다 (useYoutubeLinkState).
 */
const CONSEQUENCES = [
  '해제하면 하이라이트 클립을 유튜브에 업로드할 수 없어요.',
  '이미 업로드된 영상과 보관함 클립은 그대로 남아요.',
  '구글 계정에 준 접근 권한은 자동으로 취소되지 않아요. myaccount.google.com/permissions에서 PokeClip을 삭제하면 완전히 끊겨요.',
];

export function UnlinkYoutubeDialog({
  open,
  busy,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  /** 해제 요청 중 — 확인 버튼이 잠기고 Esc·백드롭으로도 닫히지 않는다. */
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <ConfirmDialog
      open={open}
      busy={busy}
      title="유튜브 연동을 해제할까요?"
      consequences={CONSEQUENCES}
      footnote="해제 후에도 언제든 다시 연동할 수 있어요."
      confirmLabel="연동 해제"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
