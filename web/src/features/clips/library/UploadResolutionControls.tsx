'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Button, ConfirmDialog, Dialog, Field, Input } from '@/ui';
import type { UploadResolution } from '@/api/clipEditor';
import styles from './UploadResolution.module.css';

// 「확인 필요」 업로드를 사람이 푼다(POK-333). 일꾼이 올라갔는지 모를 때 멈춘 업로드라, 스트리머 채널을 보고 결과를 적어야
// 그 판이 풀린다. 단추 둘: 「채널에 올라갔어요」(영상 주소를 받아 발행됨으로) · 「안 올라갔어요」(경고를 거쳐 실패로 닫아 다시
// 올릴 수 있게). 2번 시안이 없어 기존 확인 창(ConfirmDialog)과 입력 창(편집자 초대 창 모양)을 쓴다.

const NOT_UPLOADED_CONSEQUENCES = [
  '이 업로드를 실패로 닫아요. 그 뒤 「다시 시도」나 영상 만들기로 다시 올릴 수 있어요.',
  '채널에 영상이 실제로 있는데 다시 올리면 같은 영상이 두 개 떠요.',
];

export function UploadResolutionControls({
  busy,
  onResolve,
}: {
  /** 보내는 중: 단추를 잠근다 */
  busy: boolean;
  /** 결과를 보낸다. 받아들여졌으면 true(창을 닫는다). 거절 사유는 부른 쪽이 토스트로 알린다 */
  onResolve: (resolution: UploadResolution) => Promise<boolean>;
}) {
  const [asking, setAsking] = useState<'uploaded' | 'not_uploaded' | null>(null);

  const send = (resolution: UploadResolution) => {
    void onResolve(resolution).then((ok) => {
      if (ok) setAsking(null);
    });
  };

  return (
    <>
      <div className={styles.actions}>
        <Button
          variant="outline"
          size="sm"
          fullWidth
          disabled={busy}
          onClick={() => setAsking('uploaded')}
        >
          채널에 올라갔어요
        </Button>
        <Button
          variant="ghost"
          size="sm"
          fullWidth
          disabled={busy}
          onClick={() => setAsking('not_uploaded')}
        >
          안 올라갔어요
        </Button>
      </div>
      <UploadedDialog
        open={asking === 'uploaded'}
        busy={busy}
        onCancel={() => setAsking(null)}
        onSubmit={(videoId) => send({ result: 'uploaded', videoId })}
      />
      <ConfirmDialog
        open={asking === 'not_uploaded'}
        busy={busy}
        title="유튜브에 안 올라간 것이 맞나요?"
        consequences={NOT_UPLOADED_CONSEQUENCES}
        footnote="스트리머 채널의 유튜브 스튜디오 「콘텐츠」에서 비공개 · 일부 공개 영상까지 먼저 확인해 주세요."
        confirmLabel="안 올라갔어요"
        onCancel={() => setAsking(null)}
        onConfirm={() => send({ result: 'not_uploaded' })}
      />
    </>
  );
}

/** 채널에서 찾은 영상의 주소(또는 영상 번호)를 받는다. 모양 검사는 서버가 한다: 틀리면 토스트로 알리고 창은 남는다 */
function UploadedDialog({
  open,
  busy,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (videoId: string) => void;
}) {
  const [value, setValue] = useState('');

  // 닫힐 때 입력을 지운다: 다음에 열면 다른 업로드일 수 있다.
  useEffect(() => {
    if (!open) setValue('');
  }, [open]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (busy || value.trim() === '') return;
    onSubmit(value.trim());
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !busy) onCancel();
      }}
    >
      <Dialog.Content className={styles.dialog}>
        <Dialog.Title className={styles.title}>채널에 올라간 영상</Dialog.Title>
        <Dialog.Description className={styles.lead}>
          스트리머 채널에서 찾은 영상의 주소를 붙여 넣어 주세요. 이 업로드가 발행됨으로 바뀌어요.
        </Dialog.Description>
        <form onSubmit={submit}>
          <Field>
            <Field.Label>영상 주소</Field.Label>
            <Input
              size="md"
              placeholder="https://youtu.be/…"
              autoComplete="off"
              value={value}
              disabled={busy}
              onChange={(e) => setValue(e.target.value)}
            />
            <Field.Description>
              youtu.be · watch · shorts · 스튜디오 주소나 영상 번호를 받아요
            </Field.Description>
          </Field>
          <div className={styles.dialogActions}>
            <Button variant="ghost" size="md" type="button" onClick={onCancel} disabled={busy}>
              취소
            </Button>
            <Button
              variant="solid"
              size="md"
              type="submit"
              loading={busy}
              disabled={value.trim() === ''}
            >
              발행됨으로 바꾸기
            </Button>
          </div>
        </form>
      </Dialog.Content>
    </Dialog>
  );
}
