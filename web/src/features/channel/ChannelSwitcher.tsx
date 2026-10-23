'use client';

import { useState, type ReactElement } from 'react';
import Link from 'next/link';
import { Check } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Avatar, Badge, Button, ConfirmDialog, Popover, useToast } from '@/ui';
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

/** 역할 칩(PRD 「헤더 역할 칩」): 내 채널은 accent, 남의 채널은 point로 「여기는 내 채널이 아니다」를 계속 알린다. */
export function RoleChip({ role }: { role: ChannelRole }) {
  return (
    <Badge size="sm" tone={role === '스트리머' ? 'accent' : 'point'}>
      {role}
    </Badge>
  );
}

export interface ChannelSwitcherProps {
  /** 트리거 하나. Popover.Trigger가 클릭·aria-expanded를 붙인다. */
  children: ReactElement;
  /** 펼친 사이드바는 위로, 접힌 사이드바는 오른쪽으로, 헤더는 아래로(시안 ①②) */
  side: 'top' | 'right' | 'bottom';
  align: 'start' | 'end';
}

export function ChannelSwitcher({ children, side, align }: ChannelSwitcherProps) {
  const [open, setOpen] = useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen} side={side} align={align}>
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
      {invitations.length > 0 && <Invitations invitations={invitations} />}
      <div className={styles.foot}>
        <span className={styles.email}>{me.email}</span>
        <Link href="/settings/account" className={styles.footLink} onClick={onClose}>
          계정 설정
        </Link>
        <button type="button" className={styles.footButton} onClick={() => logout()}>
          로그아웃
        </button>
      </div>
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

/** 받은 초대: 수락·거절 함수와 무효화 키는 설정의 EditorInboxSection과 같다. 거절만 재확인한다(되돌릴 수 없다). */
function Invitations({ invitations }: { invitations: ReceivedInvitation[] }) {
  const { toast } = useToast();
  const queryClient = useQueryClient();
  const [declining, setDeclining] = useState<ReceivedInvitation | null>(null);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['editor-invitations'] });
    void queryClient.invalidateQueries({ queryKey: ['editor-delegations'] });
  };
  const failed = (e: unknown) =>
    toast({
      tone: 'error',
      title: '처리하지 못했어요',
      description: e instanceof Error ? e.message : String(e),
    });
  const accept = useMutation({
    mutationFn: (id: number) => acceptInvitation(id),
    onSuccess: () => {
      toast({ tone: 'success', title: '초대를 수락했어요' });
      refresh();
    },
    onError: failed,
  });
  const decline = useMutation({
    mutationFn: (id: number) => declineInvitation(id),
    onSuccess: () => {
      toast({ tone: 'success', title: '초대를 거절했어요' });
      setDeclining(null);
      refresh();
    },
    onError: failed,
  });
  const busy = accept.isPending || decline.isPending;
  const title = `받은 초대 ${invitations.length}`;

  return (
    <div role="group" aria-label={title} className={styles.invites}>
      <div className={styles.sectionTitleFlat}>{title}</div>
      {invitations.map((inv) => (
        <div key={inv.id} className={styles.invite}>
          <div className={styles.inviteName}>{inv.streamerName} 채널의 편집자 초대</div>
          <div className={styles.inviteMeta}>{expiresLabel(inv.expiresAt)}</div>
          <div className={styles.inviteActions}>
            <Button variant="solid" size="sm" disabled={busy} onClick={() => accept.mutate(inv.id)}>
              수락
            </Button>
            <Button variant="ghost" size="sm" disabled={busy} onClick={() => setDeclining(inv)}>
              거절
            </Button>
          </div>
        </div>
      ))}
      <ConfirmDialog
        open={declining !== null}
        busy={decline.isPending}
        title={`${declining?.streamerName ?? ''}님의 초대를 거절할까요?`}
        consequences={[
          `거절하면 목록에서 사라지고, 다시 참여하려면 ${declining?.streamerName ?? ''}님이 새로 초대해야 해요.`,
          '내 채널과 다른 참여 채널은 그대로예요.',
        ]}
        confirmLabel="거절하기"
        onCancel={() => setDeclining(null)}
        onConfirm={() => declining && decline.mutate(declining.id)}
      />
    </div>
  );
}
