'use client';

import { ConfirmDialog } from '@/ui';

// 치지직 연동 해제 확인 (디자인 1k ②, ADR-044) — 파괴적 동작은 모달로 확인받고
// 토스트는 결과만 알린다.
//
// 골격은 @/ui의 ConfirmDialog가 갖는다 — 원래 여기 로컬이던 것을 두 번째 파괴적
// 확인(편집자 권한 회수, POK-208)이 생기면서 승격했다. 이 파일에는 1k ②의 문구만 남는다.

/** 1k ②의 재확인 문구 3줄. 무엇이 멈추고 무엇이 남는지를 갈라 말한다. */
const CONSEQUENCES = [
  '해제하면 방송을 켜도 하이라이트를 감지하지 않아요.',
  '이미 저장된 지난 방송과 보관함 클립은 그대로 남아요.',
  '진행 중인 감지·클립 작업은 즉시 중단됩니다.',
];

export function UnlinkChzzkDialog({
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
      title="치지직 연동을 해제할까요?"
      consequences={CONSEQUENCES}
      footnote="해제 후에도 언제든 다시 연동할 수 있어요."
      confirmLabel="연동 해제"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
