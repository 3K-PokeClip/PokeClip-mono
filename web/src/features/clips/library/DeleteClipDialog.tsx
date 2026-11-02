'use client';

import { ConfirmDialog } from '@/ui';

// 편집본 삭제 확인. 시안 1g에는 확인 단계가 없지만 삭제는 되돌릴 수 없는 동작이라
// 모달로 한 번 묻는다(ADR-044 — 파괴적 동작은 확인받고, 되돌리기 토스트는 쓰지 않는다).
// 골격은 @/ui ConfirmDialog(UnlinkChzzkDialog 선례) — 여기에는 문구만 남는다.

const CONSEQUENCES = ['편집본과 렌더 결과가 보관함에서 사라져요.'];
const PUBLISHED_CONSEQUENCES = [...CONSEQUENCES, '발행된 유튜브 영상은 그대로 남아요.'];

export function DeleteClipDialog({
  open,
  published,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  /** 발행된 편집본이면 유튜브 영상은 남는다는 것을 함께 말한다 */
  published: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <ConfirmDialog
      open={open}
      busy={false}
      title="이 편집본을 삭제할까요?"
      consequences={published ? PUBLISHED_CONSEQUENCES : CONSEQUENCES}
      confirmLabel="삭제"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
