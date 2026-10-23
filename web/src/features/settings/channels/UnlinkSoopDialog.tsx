'use client';

import { ConfirmDialog } from '@/ui';

// SOOP 연동 해제 확인 (POK-396). UnlinkChzzkDialog의 쌍둥이로 문구만 다르다.
// 둘째 줄은 SOOP 약관의 「연동 해제 시 바로 파기」를 사용자에게 그대로 알리는 것이다.
const CONSEQUENCES = [
  '해제하면 SOOP 방송에서 하이라이트를 감지하지 않아요.',
  'SOOP에서 받은 채널 정보는 바로 지워져요.',
];

export function UnlinkSoopDialog({
  open,
  busy,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <ConfirmDialog
      open={open}
      busy={busy}
      title="SOOP 연동을 해제할까요?"
      consequences={CONSEQUENCES}
      footnote="해제 후에도 언제든 다시 연동할 수 있어요."
      confirmLabel="연동 해제"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
