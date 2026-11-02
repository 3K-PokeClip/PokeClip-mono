'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button, Dialog, Field, Input } from '@/ui';
import type { EditorInviteMessage } from '@/api/editors';
import styles from './EditorSettingsScreen.module.css';

// 편집자 초대 모달 (디자인 1l ③, ADR-039) — 이메일 정확 일치 방식이다. 시안의
// 「초대 링크」·「24시간 만료」 문구를 따르지 않는다: 링크는 「가진 사람이 곧 자격」이라
// 기각된 방식이고, 만료는 7일이다. 권한 Select도 넣지 않는다 — 등급은 결정 대기다.
//
// 파괴적 확인이 아니라 ConfirmDialog를 쓰지 않는다. 실패는 모달 안에 그린다 —
// 입력을 고쳐 재시도하는 자리가 모달이다 (토스트는 결과만 알린다는 ADR-044와 어긋나지
// 않는다: 폼 오류는 폼이 갖는다).
export function InviteEditorDialog({
  open,
  busy,
  error,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  /** 초대 요청 중 — 보내기 버튼이 잠기고 Esc·백드롭으로도 닫히지 않는다. */
  busy: boolean;
  /** 서버가 거절한 사유 — null이면 정상 입력 안내를 그린다. */
  error: EditorInviteMessage | null;
  onCancel: () => void;
  onSubmit: (email: string) => void;
}) {
  const [email, setEmail] = useState('');

  // 닫힐 때 입력을 지운다 — 다음에 열면 새 초대다.
  useEffect(() => {
    if (!open) setEmail('');
  }, [open]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    onSubmit(email.trim());
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !busy) onCancel();
      }}
    >
      <Dialog.Content className={styles.inviteDialog}>
        <Dialog.Title className={styles.inviteTitle}>편집자 초대</Dialog.Title>
        <Dialog.Description className={styles.inviteLead}>
          초대받은 사람이 로그인해 초대함에서 수락하면 편집자로 합류해요.
        </Dialog.Description>
        <form onSubmit={submit}>
          <Field invalid={error !== null}>
            <Field.Label>이메일</Field.Label>
            <Input
              type="email"
              required
              size="md"
              placeholder="editor@example.com"
              autoComplete="off"
              value={email}
              disabled={busy}
              onChange={(e) => setEmail(e.target.value)}
            />
            {error !== null ? (
              <Field.Error>
                {error.title}
                {error.description !== undefined && (
                  <span className={styles.inviteErrorDetail}>{error.description}</span>
                )}
              </Field.Error>
            ) : (
              <Field.Description>
                가입한 이메일과 정확히 일치해야 해요 · 초대는 7일 후 만료돼요
              </Field.Description>
            )}
          </Field>
          <div className={styles.inviteActions}>
            <Button variant="ghost" size="md" type="button" onClick={onCancel} disabled={busy}>
              취소
            </Button>
            <Button variant="solid" size="md" type="submit" loading={busy}>
              초대 보내기
            </Button>
          </div>
        </form>
      </Dialog.Content>
    </Dialog>
  );
}
