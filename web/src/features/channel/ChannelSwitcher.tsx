'use client';

import { useEffect, useId, useRef, useState, type ReactElement, type RefObject } from 'react';
import Link from 'next/link';
import { Check } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Avatar, Button, Popover, useToast } from '@/ui';
import {
  acceptInvitation,
  declineInvitation,
  delegationsAsEditorQueryOptions,
  receivedInvitationsQueryOptions,
  type ReceivedInvitation,
} from '@/api/editors';
import { useLogout, useMe } from '@/features/auth/useSession';
import { expiresLabel } from '@/features/settings/editors/editorDates';
import { useChannelStore } from '@/stores/channel';
import { useActiveChannel, useSwitchChannel, type SwitchTarget } from './useActiveChannel';
import styles from './ChannelSwitcher.module.css';

// 채널 스위처(POK-390, 시안 1u). 로그인 계정이 아니라 「지금 일하는 채널」을 바꾼다: 내 채널 · 편집 참여 중인 채널 ·
// 받은 초대가 한 목록에 나온다. 트리거는 부르는 쪽이 준다(사이드바 프로필 · 홈 헤더 아바타).
// 시안에서 뺀 것: 요금제(Pro) · 편집자 등급(기본·신뢰) · 채널별 알림 숫자 · 플랫폼 이름: 데이터가 없거나 결정으로 빠졌다.

export type ChannelRole = '스트리머' | '편집자';

/** 트리거가 그릴 지금 채널: 아직 안 섰으면 내 이름으로(사이드바가 빈칸으로 깜빡이지 않게). 남의 채널은 사진이 없다. */
export function useChannelIdentity(): {
  name: string | null;
  photo: string | null;
  role: ChannelRole | null;
} {
  const me = useMe().data;
  const active = useActiveChannel();
  if (active.ready) {
    const own = active.relation === 'OWNER';
    return {
      name: active.name,
      photo: own ? (me?.profileImageUrl ?? null) : null,
      role: own ? '스트리머' : '편집자',
    };
  }
  return { name: me?.name ?? null, photo: me?.profileImageUrl ?? null, role: null };
}

export interface ChannelSwitcherProps {
  /** 트리거 하나. Popover.Trigger가 클릭·aria-expanded를 붙인다. */
  children: ReactElement;
  /** 펼친 사이드바는 위로, 접힌 사이드바는 오른쪽으로, 헤더는 아래로(시안 ①②) */
  side: 'top' | 'right' | 'bottom';
  align: 'start' | 'end';
  /** 트리거 대신 위치를 잡을 블록(사이드바는 구분선 위로 띄우려고 프로필 블록을 준다) */
  anchorRef?: RefObject<HTMLElement | null>;
  offset?: number;
}

export function ChannelSwitcher({
  children,
  side,
  align,
  anchorRef,
  offset,
}: ChannelSwitcherProps) {
  const [open, setOpen] = useState(false);
  return (
    <Popover
      open={open}
      onOpenChange={setOpen}
      side={side}
      align={align}
      anchorRef={anchorRef}
      offset={offset}
    >
      <Popover.Trigger>{children}</Popover.Trigger>
      <Popover.Content className={styles.panel} aria-label="계정 전환">
        {/* 본문은 열렸을 때만 선다: 쿼리·로그아웃 훅이 닫힌 트리거마다 돌지 않게 */}
        <SwitcherPanel onClose={() => setOpen(false)} />
      </Popover.Content>
    </Popover>
  );
}

function SwitcherPanel({ onClose }: { onClose: () => void }) {
  const me = useMe().data;
  const active = useActiveChannel();
  const switchTo = useSwitchChannel();
  const logout = useLogout();
  const asEditor = useQuery(delegationsAsEditorQueryOptions);
  const received = useQuery(receivedInvitationsQueryOptions);
  const select = useChannelStore((s) => s.select);
  // 저장된 남의 채널이 있다(지금은 as-editor를 못 읽어 내 채널로 보일 수 있다)
  const storedOther = useChannelStore((s) => s.selected !== null && s.selected !== s.userId);
  // 거절 확인은 창 안 덮개로 묻는다(시안 1u ③). 덮개가 스크롤 밖에 있어야 해서 상태를 목록이 아니라 여기 둔다
  const [declining, setDeclining] = useState<Declining | null>(null);

  if (!me) return <div className={styles.head}>계정 전환</div>;
  const currentId = active.ready ? active.streamerId : me.id;
  const pick = (target: SwitchTarget) => {
    // 지금 채널을 다시 누르면 닫기만 한다: 같은 채널로 「전환했어요」를 띄우지 않는다
    if (target.streamerId !== currentId) switchTo(target);
    // 다만 내 채널로 보이는 것이 as-editor를 못 읽어서라면 저장된 남의 채널이 남아 있다. 「내 채널」을 눌렀으면 그것을 지운다:
    // 안 지우면 나중에 목록이 읽히는 순간 그 채널로 되돌아간다(PR #323 codex 2차). 같은 채널로 보이니 토스트는 없다
    else if (target.relation === 'OWNER' && storedOther) select(me.id, null);
    onClose();
  };
  // 실패는 그 목록을 한 번도 못 받았을 때만이다. react-query는 다시 읽기가 실패해도 받아 둔 목록을 남기므로, 그것을 실패로
  // 보면 보이던 초대가 사라진다. 두 목록은 따로 본다: 참여 채널을 못 읽어도 받은 초대는 보인다(로컬 리뷰 1라운드)
  const channelsFailed = asEditor.data === undefined && asEditor.isError;
  const invitesFailed = received.data === undefined && received.isError;
  const channels = asEditor.data ?? [];
  const invitations = received.data ?? [];

  return (
    <>
      {/* 패널이 아니라 이 칸이 스크롤한다: 덮개가 스크롤과 상관없이 보이는 칸을 덮게. 덮개가 떠 있는 동안 뒤는
          누를 수도 Tab으로 갈 수도 없다 */}
      <div className={styles.scroll} inert={declining !== null}>
        <div className={styles.head}>계정 전환</div>
        <div className={styles.section}>
          <div className={styles.sectionTitle}>내 채널</div>
          <ChannelRow
            name={me.name}
            photo={me.profileImageUrl}
            role="스트리머"
            current={currentId === me.id}
            onClick={() => pick({ streamerId: me.id, name: me.name, relation: 'OWNER' })}
          />
          {channels.length > 0 && (
            <>
              <div className={styles.sectionTitle}>편집 참여 중 · {channels.length}</div>
              {channels.map((d) => (
                <ChannelRow
                  key={d.id}
                  name={d.streamerName}
                  photo={null}
                  role="편집자"
                  current={currentId === d.streamerId}
                  onClick={() =>
                    pick({ streamerId: d.streamerId, name: d.streamerName, relation: 'EDITOR' })
                  }
                />
              ))}
            </>
          )}
        </div>
        {(channelsFailed || invitesFailed) && (
          // 못 읽은 것을 「없음」으로 숨기지 않는다: 기한 있는 초대를 놓친다(EditorInboxSection과 같은 이유)
          <div className={styles.fallback}>
            <span>
              {channelsFailed && invitesFailed
                ? '참여 채널과 받은 초대를 불러오지 못했어요'
                : channelsFailed
                  ? '참여 채널을 불러오지 못했어요'
                  : '받은 초대를 불러오지 못했어요'}
            </span>
            <Button
              variant="soft"
              size="sm"
              loading={asEditor.isFetching || received.isFetching}
              onClick={() => {
                if (channelsFailed) void asEditor.refetch();
                if (invitesFailed) void received.refetch();
              }}
            >
              다시 시도
            </Button>
          </div>
        )}
        {invitations.length > 0 && (
          <Invitations
            invitations={invitations}
            onDecline={(invitation, opener) => setDeclining({ invitation, opener })}
          />
        )}
        <div className={styles.foot}>
          <span className={styles.email}>{me.email}</span>
          <Link href="/settings/account" className={styles.footLink} onClick={onClose}>
            계정 설정
          </Link>
          <button type="button" className={styles.footButton} onClick={() => logout()}>
            로그아웃
          </button>
        </div>
      </div>
      {declining && <DeclineConfirm {...declining} onClose={() => setDeclining(null)} />}
    </>
  );
}

interface ChannelRowProps {
  name: string;
  photo: string | null;
  role: '스트리머' | '편집자';
  current: boolean;
  onClick: () => void;
}

function ChannelRow({ name, photo, role, current, onClick }: ChannelRowProps) {
  return (
    <button
      type="button"
      className={styles.row}
      aria-current={current ? 'true' : undefined}
      // 남의 채널이 지금 채널이면 그 줄을 point로 칠한다: 내 채널(accent)과 색으로 갈린다
      data-others-channel={role === '편집자' || undefined}
      onClick={onClick}
    >
      {/* 남의 채널은 서버가 사진을 안 준다: 이름 글자 아바타 */}
      <Avatar size="sm" src={photo ?? undefined} name={name} className={styles.rowAvatar} />
      <span className={styles.rowText}>
        <span className={styles.rowName}>{name} 채널</span>
        <span className={styles.rowMeta}>{role}</span>
      </span>
      {current && <Check aria-hidden className={styles.rowCheck} />}
    </button>
  );
}

/** 실패는 토스트로만 알린다: 서버 문구를 그대로 보인다(EditorInboxSection과 같다) */
function failedToast(toast: ReturnType<typeof useToast>['toast'], e: unknown) {
  toast({
    tone: 'error',
    title: '처리하지 못했어요',
    description: e instanceof Error ? e.message : String(e),
  });
}

/** 수락·거절 공통: 함수와 무효화 키는 설정의 EditorInboxSection과 같다. */
function useInvitationAction(
  send: (id: number) => Promise<void>,
  doneTitle: string,
  onDone?: () => void,
) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSuccess: () => {
      toast({ tone: 'success', title: doneTitle });
      onDone?.();
      void queryClient.invalidateQueries({ queryKey: ['editor-invitations'] });
      void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
    },
    onError: (e: unknown) => failedToast(toast, e),
  });
}

/** 거절을 누른 초대와 그 버튼(확인을 닫으면 포커스가 돌아갈 곳) */
interface Declining {
  invitation: ReceivedInvitation;
  opener: HTMLElement;
}

/** 받은 초대: 수락은 바로 보내고, 거절만 덮개로 한 번 더 묻는다(되돌릴 수 없다). */
function Invitations({
  invitations,
  onDecline,
}: {
  invitations: ReceivedInvitation[];
  onDecline: (invitation: ReceivedInvitation, opener: HTMLElement) => void;
}) {
  const accept = useInvitationAction(acceptInvitation, '초대를 수락했어요');
  const title = `받은 초대 ${invitations.length}`;

  return (
    <div role="group" aria-label={title} className={styles.invites}>
      <div className={styles.sectionTitleFlat}>{title}</div>
      {invitations.map((inv) => (
        <div key={inv.id} className={styles.invite}>
          <div className={styles.inviteName}>{inv.streamerName} 채널의 편집자 초대</div>
          <div className={styles.inviteMeta}>{expiresLabel(inv.expiresAt)}</div>
          <div className={styles.inviteActions}>
            <Button
              variant="solid"
              size="sm"
              disabled={accept.isPending}
              onClick={() => accept.mutate(inv.id)}
            >
              수락
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={accept.isPending}
              onClick={(e) => onDecline(inv, e.currentTarget)}
            >
              거절
            </Button>
          </div>
        </div>
      ))}
    </div>
  );
}

/** 받은 초대 거절 확인 */
function DeclineConfirm({
  invitation,
  opener,
  onClose,
}: {
  invitation: ReceivedInvitation;
  opener: HTMLElement;
  onClose: () => void;
}) {
  const decline = useInvitationAction(declineInvitation, '초대를 거절했어요', onClose);
  return (
    <PanelConfirm
      title={`${invitation.streamerName}님의 초대를 거절할까요?`}
      text="거절하면 목록에서 사라지고, 다시 참여하려면 새로 초대받아야 해요."
      confirmLabel="거절하기"
      busy={decline.isPending}
      opener={opener}
      onConfirm={() => decline.mutate(invitation.id)}
      onClose={onClose}
    />
  );
}

/**
 * 창 안 확인 덮개(시안 1u ③, 시안 비교 B안). 화면 가운데 모달 대신 창 안에서 묻되, 덮개는 패널(position: fixed)을
 * 기준으로 보이는 칸 전체를 덮는다: 초대가 많아 창을 내려 둔 채로 눌러도 카드가 눈앞 가운데에 뜬다.
 */
function PanelConfirm({
  title,
  text,
  confirmLabel,
  busy,
  opener,
  onConfirm,
  onClose,
}: {
  title: string;
  text: string;
  confirmLabel: string;
  busy: boolean;
  opener: HTMLElement;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const titleId = useId();
  const textId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  // 열리면 취소에 포커스(Enter 한 번에 거절되지 않게). 닫히면 누른 거절 버튼으로 돌아간다: passive effect의
  // 정리는 뒤 칸의 inert가 풀린 뒤에 돌아 포커스가 들어간다
  useEffect(() => {
    cancelRef.current?.focus();
    return () => {
      if (opener.isConnected) opener.focus();
    };
  }, [opener]);

  // 요청이 나간 뒤에는 닫지 않는다: 결과가 오기 전에 사라지면 취소된 줄 안다(ConfirmDialog와 같다)
  const cancel = () => {
    if (!busy) onClose();
  };

  return (
    <div
      className={styles.confirm}
      onClick={(e) => {
        if (e.target === e.currentTarget) cancel();
      }}
      onKeyDown={(e) => {
        // 창 전체가 아니라 덮개만 닫는다: 여기서 멈추면 창의 Esc 닫기(document 리스너)까지 가지 않는다
        if (e.key !== 'Escape') return;
        e.stopPropagation();
        cancel();
      }}
    >
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={textId}
        className={styles.confirmCard}
      >
        <div id={titleId} className={styles.confirmTitle}>
          {title}
        </div>
        <div id={textId} className={styles.confirmText}>
          {text}
        </div>
        <div className={styles.confirmActions}>
          <Button
            ref={cancelRef}
            variant="outline"
            size="sm"
            fullWidth
            disabled={busy}
            onClick={cancel}
          >
            취소
          </Button>
          <Button variant="danger" size="sm" fullWidth loading={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
