'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, useToast } from '@/ui';
import {
  acceptInvitation,
  declineInvitation,
  delegationsAsEditorQueryOptions,
  receivedInvitationsQueryOptions,
  revokeDelegation,
} from '@/api/editors';
import styles from './EditorSettingsScreen.module.css';

// 편집자 시점(초대함) — 받은 초대에 답하고, 내가 편집자로 들어가 있는 채널을 본다.
// 시안 1l은 스트리머 시점만 그렸다. 백엔드(POK-57)는 양쪽을 다 가지므로 같은 화면의 행 모양을 빌려 아래에 잇는다.
// 없을 때는 아무것도 그리지 않는다 — 스트리머 혼자 쓰는 계정에는 이 구역이 보이지 않는다.
export function EditorInboxSection() {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const received = useQuery(receivedInvitationsQueryOptions);
  const asEditor = useQuery(delegationsAsEditorQueryOptions);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['editor-invitations'] });
    void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
  };
  const answer = useMutation({
    mutationFn: ({ id, accept }: { id: number; accept: boolean }) =>
      accept ? acceptInvitation(id) : declineInvitation(id),
    onSuccess: (_, { accept }) => {
      toast({ tone: 'success', title: accept ? '초대를 수락했어요' : '초대를 거절했어요' });
      refresh();
    },
    onError: (e: unknown) =>
      toast({
        tone: 'error',
        title: '처리하지 못했어요',
        description: e instanceof Error ? e.message : String(e),
      }),
  });
  const leave = useMutation({
    mutationFn: (id: number) => revokeDelegation(id),
    onSuccess: () => {
      toast({ tone: 'success', title: '편집자에서 나왔어요' });
      refresh();
    },
    onError: (e: unknown) =>
      toast({
        tone: 'error',
        title: '처리하지 못했어요',
        description: e instanceof Error ? e.message : String(e),
      }),
  });

  // 못 읽은 것을 「초대 없음」으로 숨기지 않는다 — 기한 있는 초대를 놓친다(PR #200 codex).
  // 편집자 목록과 같은 실패 카드(행 개수를 모르니 한 덩어리)에 다시 시도를 둔다
  if (received.isError || asEditor.isError) {
    const retrying = received.isFetching || asEditor.isFetching;
    return (
      <section aria-label="내가 편집자인 채널" className={styles.rows}>
        <div className={styles.fallbackCard}>
          <span className={styles.fallbackText}>받은 초대를 불러오지 못했어요</span>
          <Button variant="soft" size="sm" loading={retrying} onClick={refresh}>
            다시 시도
          </Button>
        </div>
      </section>
    );
  }
  const invitations = received.data ?? [];
  const channels = asEditor.data ?? [];
  if (invitations.length === 0 && channels.length === 0) return null;

  return (
    <section aria-label="내가 편집자인 채널" className={styles.rows}>
      {invitations.map((invitation) => (
        <div key={`inv-${invitation.id}`} className={styles.row}>
          <div className={styles.rowBody}>
            <div className={styles.rowName}>{invitation.streamerName} 님의 초대</div>
            <div className={styles.rowMeta}>
              {new Date(invitation.expiresAt).toLocaleDateString('ko-KR')}까지 답할 수 있어요
            </div>
          </div>
          <Button
            variant="ghost"
            size="sm"
            disabled={answer.isPending}
            onClick={() => answer.mutate({ id: invitation.id, accept: false })}
          >
            거절
          </Button>
          <Button
            variant="solid"
            size="sm"
            disabled={answer.isPending}
            onClick={() => answer.mutate({ id: invitation.id, accept: true })}
          >
            수락
          </Button>
        </div>
      ))}
      {channels.map((channel) => (
        <div key={`ch-${channel.id}`} className={styles.row}>
          <div className={styles.rowBody}>
            <div className={styles.rowName}>{channel.streamerName} 채널의 편집자</div>
            <div className={styles.rowMeta}>
              {new Date(channel.grantedAt).toLocaleDateString('ko-KR')}부터
            </div>
          </div>
          <Button
            variant="ghost"
            size="sm"
            disabled={leave.isPending}
            onClick={() => leave.mutate(channel.id)}
          >
            나가기
          </Button>
        </div>
      ))}
    </section>
  );
}
