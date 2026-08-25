'use client';

import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/api/client';
import {
  cancelInvitation as requestCancelInvitation,
  editorDelegationsQueryOptions,
  inviteEditor,
  inviteFailureMessage,
  revokeDelegation,
  sentInvitationsQueryOptions,
  type EditorDelegation,
  type EditorInviteMessage,
  type SentInvitation,
} from '@/api/editors';
import { useToast } from '@/ui';

// 편집자 관리 실상태 훅 (POK-208) — useChzzkLinkState와 같은 규약이다: 상태 판단은
// 전부 여기서 하고 화면은 결과만 읽는다.
//
// 정원 배지(「2 / 3」)·정원 초과 사전 모달은 아직 없다 — 정원 API가 POK-207 몫이라
// 상한 도달은 초대 409(TOO_MANY_PENDING) 문구가 알린다. POK-207이 머지되면 여기에
// 정원 쿼리가 붙는다.

export type EditorsView = 'loading' | 'unavailable' | 'ready';

export interface EditorSettingsViewState {
  view: EditorsView;
  /** `ready`가 아니면 빈 배열 — 로딩·오류 화면에 낡은 목록이 남지 않게. */
  editors: EditorDelegation[];
  /** sent 이력에서 PENDING만 — 거절·취소·만료 이력은 이 화면이 그리지 않는다. */
  pendingInvitations: SentInvitation[];
  /** `ready`이고 편집자·대기 초대가 모두 0 — 빈 상태 카드를 그린다. */
  empty: boolean;
  retry: () => void;
  /** 조회 실패 후 다시 시도가 도는 중 — 버튼에 진행 표시를 준다. */
  retrying: boolean;

  /** 초대 모달. 실패는 모달 안에 그린다 — 입력을 고쳐 재시도하는 자리가 모달이다. */
  inviteOpen: boolean;
  openInvite: () => void;
  closeInvite: () => void;
  invite: (email: string) => void;
  inviting: boolean;
  inviteError: EditorInviteMessage | null;

  /** 내보내기(위임 해제) 확인 모달 — 파괴적 동작이라 모달로 확인받고 토스트는 결과만
   *  알린다 (ADR-044). boolean 대신 대상 자체가 상태다 — 이름이 모달·토스트 문구에 필요하다. */
  revokeTarget: EditorDelegation | null;
  openRevoke: (editor: EditorDelegation) => void;
  closeRevoke: () => void;
  /** 내보내기 실행. 요청 중에는 모달이 닫히지 않는다. */
  confirmRevoke: () => void;
  revoking: boolean;

  /** 초대 취소 — 확인 모달이 없다. 같은 이메일로 다시 초대하면 되돌아가는 동작이라
   *  파괴적이지 않다 (시안 1l도 확인 없이 취소한다). */
  cancelInvitation: (invitation: SentInvitation) => void;
  /** 취소가 도는 초대 id들 — 해당 행의 버튼만 잠근다. mutation.variables는 마지막 호출만
   *  기억해 동시 취소에서 앞 행의 잠금이 풀리므로, 진행 중 집합을 직접 든다. */
  cancelingIds: ReadonlySet<number>;
}

export function useEditorSettingsState(): EditorSettingsViewState {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const delegations = useQuery(editorDelegationsQueryOptions);
  const invitations = useQuery(sentInvitationsQueryOptions);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<EditorDelegation | null>(null);
  const [cancelingIds, setCancelingIds] = useState<ReadonlySet<number>>(new Set());

  // 초대 수락·내보내기·취소가 두 리소스에 걸쳐 상태를 옮기므로 어느 뮤테이션이든 둘 다 갱신한다.
  // 실패 경로에도 부른다 — ALREADY_EDITOR·404는 내 목록이 낡았다는 뜻이라 실패가 곧 갱신 사유다.
  const invalidateLists = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: editorDelegationsQueryOptions.queryKey });
    void queryClient.invalidateQueries({ queryKey: sentInvitationsQueryOptions.queryKey });
  }, [queryClient]);

  const invite = useMutation({
    mutationFn: inviteEditor,
    onSuccess: () => {
      setInviteOpen(false);
      // 기한 연장(재초대)도 같은 201·같은 문구다 — 사용자 입장에서 결과는 "초대가 살아 있다"로 같다.
      toast({
        tone: 'success',
        title: '초대를 보냈어요',
        description: '상대가 수락하면 편집자 목록에 추가돼요. 초대는 7일 후 만료돼요.',
      });
    },
    // onError 없음 — 모달을 닫지 않고 아래 inviteError 파생이 폼 안에 사유를 그린다.
    // ADR-044와 어긋나지 않는다: 토스트는 결과만 알리고, 폼 오류는 폼이 갖는다.
    onSettled: invalidateLists,
  });

  const revoke = useMutation({
    // 대상을 클로저가 아니라 variables로 받는다 — onSettled가 revokeTarget을 먼저 지워도
    // 토스트 문구가 이름을 잃지 않는다.
    mutationFn: (target: EditorDelegation) => revokeDelegation(target.id),
    onSuccess: (_data, target) => {
      // destructive 표식이 undo를 컴파일 단계에서 막는다 (ADR-044). 실제로 되돌릴 수 없다 —
      // 내보내는 즉시 대기 중이던 승인 요청이 무효가 되므로 되돌려도 그 요청은 안 돌아온다.
      // 시안 ⑥의 부제는 「편집자 1 / 3 · 승인 요청 1건 무효」지만 정원·건수가 POK-207 몫이라
      // 무효 고지만 남긴다. 호칭은 시안 제목처럼 「님」을 붙인다 — 조사(을/를)도 갈리지 않는다.
      toast({
        tone: 'success',
        destructive: true,
        title: `${target.name} 님을 편집자에서 내보냈어요`,
        description: '대기 중이던 승인 요청이 무효가 됐어요.',
      });
    },
    onError: (e) => {
      // 없는 것과 같은 404 — 다른 탭·기기에서 이미 내보냈거나 편집자가 스스로 나갔다.
      if (e instanceof ApiError && e.status === 404 && e.message === 'DELEGATION_NOT_FOUND') {
        toast({
          tone: 'error',
          title: '이미 내보낸 편집자예요',
          description: '다른 곳에서 먼저 처리됐어요. 목록을 최신으로 갱신했어요.',
        });
        return;
      }
      toast({
        tone: 'error',
        title: '편집자 내보내기에 실패했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
    onSettled: () => {
      // 성공·실패 모두 모달을 닫는다 — 결과는 토스트가 알린다 (ADR-044).
      setRevokeTarget(null);
      invalidateLists();
    },
  });

  const cancel = useMutation({
    mutationFn: (invitation: SentInvitation) => requestCancelInvitation(invitation.id),
    onMutate: (invitation) => {
      setCancelingIds((prev) => new Set(prev).add(invitation.id));
    },
    onSuccess: () => {
      toast({
        tone: 'success',
        title: '초대를 취소했어요',
        description: '같은 이메일로 언제든 다시 초대할 수 있어요.',
      });
    },
    onError: (e) => {
      // 상대가 먼저 응답한 경합은 상태에 따라 둘로 갈라져 온다 — 목록 조회 뒤 수락·거절이
      // 끼어들면 409 INVITATION_NOT_PENDING(「이미 처리된 초대다」), 행 자체가 사라졌으면
      // 404 INVITATION_NOT_FOUND. 사용자에게는 같은 상황이라 한 문구로 합친다.
      const alreadySettled =
        e instanceof ApiError &&
        ((e.status === 409 && e.message === 'INVITATION_NOT_PENDING') ||
          (e.status === 404 && e.message === 'INVITATION_NOT_FOUND'));
      if (alreadySettled) {
        toast({
          tone: 'error',
          title: '이미 처리된 초대예요',
          description: '상대가 먼저 응답했어요. 목록을 최신으로 갱신했어요.',
        });
        return;
      }
      toast({
        tone: 'error',
        title: '초대 취소에 실패했어요',
        description: '잠시 후 다시 시도해 주세요.',
      });
    },
    onSettled: (_data, _error, invitation) => {
      setCancelingIds((prev) => {
        const next = new Set(prev);
        next.delete(invitation.id);
        return next;
      });
      invalidateLists();
    },
  });

  const { reset: resetInvite } = invite;
  const openInvite = useCallback(() => {
    // 이전 실패 문구가 새로 연 모달에 남지 않게 연 시점에 지운다.
    resetInvite();
    setInviteOpen(true);
  }, [resetInvite]);
  const inviting = invite.isPending;
  const closeInvite = useCallback(() => {
    // 요청이 나간 뒤에는 결과(onSuccess의 닫기 또는 실패 문구)가 돌아올 때까지 닫지 않는다.
    if (inviting) return;
    setInviteOpen(false);
  }, [inviting]);
  const { mutate: mutateInvite } = invite;
  const submitInvite = useCallback((email: string) => mutateInvite(email), [mutateInvite]);

  const openRevoke = useCallback((editor: EditorDelegation) => setRevokeTarget(editor), []);
  const closeRevoke = useCallback(() => setRevokeTarget(null), []);
  const { mutate: mutateRevoke } = revoke;
  const confirmRevoke = useCallback(() => {
    if (revokeTarget !== null) mutateRevoke(revokeTarget);
  }, [mutateRevoke, revokeTarget]);

  const { mutate: mutateCancel } = cancel;
  const cancelInvitation = useCallback(
    (invitation: SentInvitation) => {
      // 진행 중인 행의 재클릭 방어 — 버튼 loading이 막지만 상태로도 한 번 더 막는다.
      if (cancelingIds.has(invitation.id)) return;
      mutateCancel(invitation);
    },
    [cancelingIds, mutateCancel],
  );

  const { refetch: refetchDelegations } = delegations;
  const { refetch: refetchInvitations } = invitations;
  const retry = useCallback(() => {
    void refetchDelegations();
    void refetchInvitations();
  }, [refetchDelegations, refetchInvitations]);

  const view = toView(delegations, invitations);
  const editors = view === 'ready' ? (delegations.data ?? []) : [];
  const pendingInvitations =
    view === 'ready' ? (invitations.data ?? []).filter((s) => s.status === 'PENDING') : [];

  return {
    view,
    editors,
    pendingInvitations,
    empty: view === 'ready' && editors.length === 0 && pendingInvitations.length === 0,
    retry,
    // isPending이 아닌 isFetching이다 — 최초 조회는 view가 'loading'이라 겹치지 않고,
    // 여기서 잡아야 할 것은 실패 후 재조회다 (useChzzkLinkState와 같은 판정).
    retrying: delegations.isFetching || invitations.isFetching,
    inviteOpen,
    openInvite,
    closeInvite,
    invite: submitInvite,
    inviting,
    inviteError: inviteOpen && invite.isError ? inviteFailureMessage(invite.error) : null,
    revokeTarget,
    openRevoke,
    closeRevoke,
    confirmRevoke,
    revoking: revoke.isPending,
    cancelInvitation,
    cancelingIds,
  };
}

function toView(
  delegations: { isPending: boolean; isError: boolean; data: unknown },
  invitations: { isPending: boolean; isError: boolean; data: unknown },
): EditorsView {
  if (delegations.isPending || invitations.isPending) return 'loading';
  // 한 번이라도 읽었으면 재조회 실패에 오류 화면으로 갈아끼우지 않는다 — "아무것도 모르는"
  // 최초 실패만 오류로 그린다. 빈 목록으로 오인시키는 쪽이 훨씬 나쁘다. 쿼리가 둘이라
  // 어느 한쪽이라도 처음부터 실패면 화면 전체를 오류로 둔다 — 반쪽 목록은 "편집자가
  // 없다"와 구분이 안 된다. (useChzzkLinkState의 data-유무 판정을 2쿼리로 확장)
  if (
    (delegations.isError && delegations.data === undefined) ||
    (invitations.isError && invitations.data === undefined)
  )
    return 'unavailable';
  return 'ready';
}
