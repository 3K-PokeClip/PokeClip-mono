'use client';

import { ConfirmDialog } from '@/ui';
import type { EditorDelegation } from '@/api/editors';

// 편집 권한 회수 확인 (디자인 1l, ADR-044) — 파괴적 동작은 모달로 확인받고 토스트는
// 결과만 알린다. 1l에는 회수 모달 아트웍이 없어 1k ②(채널 해제)의 골격, 즉
// @/ui ConfirmDialog에 1l의 안내 문구를 얹는다. 되돌리기는 붙이지 않는다 — 회수 즉시
// 대기 중이던 승인 요청이 무효가 되므로 되돌려도 그 요청은 안 돌아온다.
export function RevokeEditorDialog({
  target,
  busy,
  onCancel,
  onConfirm,
}: {
  /** 회수 대상 위임. null이면 닫힘 — 이름이 제목·결과 문구에 필요해 boolean 대신 대상이 상태다. */
  target: EditorDelegation | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const name = target?.name ?? '';

  return (
    <ConfirmDialog
      open={target !== null}
      busy={busy}
      eyebrow="편집 권한 회수"
      title={`${name}님의 편집 권한을 회수할까요?`}
      consequences={[
        `회수는 즉시 적용되고, ${name}님은 더 이상 회원님의 클립을 편집할 수 없어요.`,
        `${name}님이 올린 대기 중이던 승인 요청은 무효가 됩니다.`,
        '이미 승인돼 게시된 영상은 그대로 남아요.',
      ]}
      footnote="다시 초대하면 언제든 권한을 줄 수 있어요."
      confirmLabel="권한 회수"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
