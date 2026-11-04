'use client';

// 헤더 · 사이드바의 종과 알림 창(POK-540, 시안 A · A-1 확정).
// 종을 누르면 바로 아래(사이드바면 오른쪽)에 창이 열린다. 창은 열 때마다 「안 읽음」 탭부터, 읽음은 누를 때만.

import { useEffect, useRef, useState, type RefObject } from 'react';
import { useRouter } from 'next/navigation';
import {
  Bell,
  Check,
  ChevronDown,
  CircleCheck,
  CloudUpload,
  Hourglass,
  Link2Off,
  MonitorPlay,
  Moon,
  Radio,
  Settings,
  TriangleAlert,
  UserPlus,
  type LucideIcon,
} from 'lucide-react';
import {
  NOTIFICATION_KEEP_DAYS,
  NOTIFICATION_MAX_COUNT,
  type AppNotification,
  type NotificationKind,
} from '@/api/notifications';
import { Button, IconButton, Popover, Skeleton } from '@/ui';
import {
  buildPanelView,
  describeNotification,
  notificationTime,
  type NotificationTone,
  type PanelTab,
} from './notificationView';
import {
  useArrivalToasts,
  useGoToNotification,
  useNotifications,
  useRespondInvitation,
  type NotificationsState,
} from './useNotifications';
import { useNotificationRequests } from './notificationRequests';
import styles from './NotificationBell.module.css';

const KIND_ICON: Record<NotificationKind, LucideIcon> = {
  CHAT_LINK_BROKEN: Link2Off,
  YOUTUBE_LINK_EXPIRED: Link2Off,
  CLIP_UPLOADED: CircleCheck,
  CLIP_FAILED: TriangleAlert,
  EDITOR_UPLOADED: CloudUpload,
  VOD_EXPIRING: Hourglass,
  BROADCAST_ENDED: MonitorPlay,
  MANAGED_BROADCAST_STARTED: Radio,
  EDITOR_INVITED: UserPlus,
};

export interface NotificationBellProps {
  /** 홈 헤더(아래로 연다) · 사이드바(오른쪽으로 연다) */
  place: 'header' | 'sidebar';
  /**
   * 종 대신 위치를 잡을 블록. 사이드바는 프로필 블록을 줘서 창이 사이드바 오른쪽 경계 밖에 서게 한다 — 종에 붙이면 종이
   * 사이드바 안쪽에 있어 창이 사이드바를 덮는다
   */
  anchorRef?: RefObject<HTMLElement | null>;
  /** 기준과의 간격(px) */
  offset?: number;
}

export function NotificationBell({ place, anchorRef, offset = 8 }: NotificationBellProps) {
  const state = useNotifications();
  const router = useRouter();
  const goTo = useGoToNotification();
  const [open, setOpen] = useState(false);
  // 창을 연 순간 — 시각 표기와 「안 읽음」 snapshot의 기준. 열린 동안은 고정이라 줄이 흔들리지 않는다
  const [openedAt, setOpenedAt] = useState(0);
  const [snapshot, setSnapshot] = useState<ReadonlySet<string>>(() => new Set());
  const [tab, setTab] = useState<PanelTab>('unread');
  // 창이 열린 동안 새로 온(또는 방송이 끝나 풀린) 안 읽은 알림도 snapshot에 더한다: 열 때 한 번만 찍으면 그 줄은 읽는 순간
  // 바로 사라지고, 목록을 읽는 중에 연 창은 snapshot이 비어 모든 줄이 그렇다(PR #481 리뷰)
  if (open) {
    const missing = state.items.filter((n) => !n.held && n.readAt === null && !snapshot.has(n.id));
    if (missing.length > 0) setSnapshot(new Set([...snapshot, ...missing.map((n) => n.id)]));
  }

  function openPanel() {
    setOpenedAt(Date.now());
    setSnapshot(new Set(state.items.filter((n) => !n.held && n.readAt === null).map((n) => n.id)));
    setTab('unread');
    setOpen(true);
  }

  /** 줄 · 카드 · 토스트를 누름: 읽음으로 하고, 갈 곳이 있으면 창을 닫고 간다 */
  function openItem(n: AppNotification) {
    if (n.readAt === null) state.markRead(n.id);
    const href = describeNotification(n, Date.now()).href;
    if (href === null) return;
    setOpen(false);
    goTo(n, href);
  }

  useArrivalToasts(state);

  // 토스트 단추의 부탁을 받는다(notificationRequests). 떠오를 때 이미 있던 부탁은 앞 종 몫이라 건너뛴다. 처리는 최신 함수로:
  // 구독은 한 번만 걸고 함수는 ref로 넘긴다
  const handlers = useRef({ openPanel, openItem });
  useEffect(() => {
    handlers.current = { openPanel, openItem };
  });
  useEffect(() => {
    let seen = useNotificationRequests.getState().seq;
    return useNotificationRequests.subscribe((s) => {
      if (s.seq === seen || s.request === null) return;
      seen = s.seq;
      if (s.request.kind === 'panel') handlers.current.openPanel();
      else handlers.current.openItem(s.request.notification);
    });
  }, []);

  const label = state.unread > 0 ? `알림 · 안 읽음 ${state.unread}개` : '알림';
  return (
    <Popover
      open={open}
      onOpenChange={(next) => (next ? openPanel() : setOpen(false))}
      side={place === 'header' ? 'bottom' : 'right'}
      align="end"
      anchorRef={anchorRef}
      offset={offset}
    >
      <span className={styles.bell} data-place={place}>
        <Popover.Trigger>
          <IconButton variant="ghost" size={place === 'header' ? 'md' : 'sm'} aria-label={label}>
            <Bell aria-hidden />
          </IconButton>
        </Popover.Trigger>
        {state.unread > 0 ? (
          <span className={styles.badge} aria-hidden>
            {state.unread > 9 ? '9+' : state.unread}
          </span>
        ) : null}
      </span>
      <Popover.Content className={styles.panel} aria-label="알림">
        <NotificationPanel
          state={state}
          tab={tab}
          onTab={setTab}
          snapshot={snapshot}
          now={openedAt}
          onOpenItem={openItem}
          onSettings={() => {
            setOpen(false);
            router.push('/settings/notifications');
          }}
        />
      </Popover.Content>
    </Popover>
  );
}

interface PanelProps {
  state: NotificationsState;
  tab: PanelTab;
  onTab: (tab: PanelTab) => void;
  snapshot: ReadonlySet<string>;
  now: number;
  onOpenItem: (n: AppNotification) => void;
  onSettings: () => void;
}

function NotificationPanel({
  state,
  tab,
  onTab,
  snapshot,
  now,
  onOpenItem,
  onSettings,
}: PanelProps) {
  const view = buildPanelView(state.items, tab, snapshot, now);
  return (
    <>
      <div className={styles.head}>
        <h2 className={styles.title}>알림</h2>
        {/* disabled 대신 aria-disabled: 누른 뒤 비활성이 돼도 포커스가 이 단추에 남는다(disabled면 body로 떨어진다) */}
        <button
          type="button"
          className={styles.textButton}
          onClick={() => {
            if (state.unread > 0) state.markAllRead();
          }}
          aria-disabled={state.unread === 0}
        >
          모두 읽음
        </button>
        <IconButton variant="ghost" size="sm" aria-label="알림 설정" onClick={onSettings}>
          <Settings aria-hidden />
        </IconButton>
      </div>
      <div className={styles.tabs} role="group" aria-label="보기">
        <button
          type="button"
          aria-pressed={tab === 'unread'}
          aria-label={state.unread > 0 ? `안 읽음 ${state.unread}개` : '안 읽음'}
          onClick={() => onTab('unread')}
        >
          안 읽음{state.unread > 0 ? <b aria-hidden>{state.unread}</b> : null}
        </button>
        <button type="button" aria-pressed={tab === 'all'} onClick={() => onTab('all')}>
          전체
        </button>
      </div>
      <div className={styles.body}>
        <PanelBody
          state={state}
          view={view}
          tab={tab}
          onTab={onTab}
          now={now}
          onOpenItem={onOpenItem}
        />
      </div>
    </>
  );
}

function PanelBody({
  state,
  view,
  tab,
  onTab,
  now,
  onOpenItem,
}: {
  state: NotificationsState;
  view: ReturnType<typeof buildPanelView>;
  tab: PanelTab;
  onTab: (tab: PanelTab) => void;
  now: number;
  onOpenItem: (n: AppNotification) => void;
}) {
  if (state.isPending) {
    return (
      <div className={styles.loading} role="status" aria-label="알림을 불러오는 중">
        {[0, 1, 2].map((i) => (
          <div key={i} className={styles.loadingRow}>
            <Skeleton circle height="calc(32 * var(--pc-u))" />
            <span className={styles.text}>
              <Skeleton height="calc(13 * var(--pc-u))" width="70%" />
              <Skeleton height="calc(11 * var(--pc-u))" width="90%" />
            </span>
          </div>
        ))}
      </div>
    );
  }
  if (state.isError) {
    return (
      <div className={styles.empty}>
        <span>알림을 불러오지 못했어요</span>
        <Button size="sm" variant="outline" onClick={state.refetch}>
          다시 시도
        </Button>
      </div>
    );
  }
  if (state.items.length === 0) {
    return (
      <div className={styles.empty}>
        <Bell aria-hidden />
        <span>알림이 없어요</span>
        <span className={styles.emptySub}>연동이 끊기거나 클립이 올라가면 여기서 알려 드려요</span>
      </div>
    );
  }
  return (
    <>
      {view.held.length > 0 ? <DndBar count={view.held.length} /> : null}
      {view.todos.length > 0 ? (
        <section className={styles.group} aria-label="확인 필요">
          <h3 className={styles.groupLabel}>
            확인 필요 <span className={styles.groupCount}>{view.todos.length}</span>
          </h3>
          <div className={styles.todoList}>
            {view.todos.map((n) => (
              <TodoCard key={n.id} n={n} now={now} state={state} onOpen={() => onOpenItem(n)} />
            ))}
          </div>
        </section>
      ) : null}
      {view.groups.map((g) => (
        <section key={g.day} className={styles.group} aria-label={g.day}>
          <h3 className={styles.groupLabel}>{g.day}</h3>
          {g.items.map((n) => (
            <NotificationRow
              key={n.id}
              n={n}
              now={now}
              onOpen={() => onOpenItem(n)}
              onRead={() => state.markRead(n.id)}
            />
          ))}
        </section>
      ))}
      {view.groups.length === 0 && tab === 'unread' ? (
        <div className={styles.emptyLine}>
          새 알림이 없어요
          <button type="button" className={styles.textButton} onClick={() => onTab('all')}>
            전체 보기
          </button>
        </div>
      ) : null}
      {view.held.length > 0 ? (
        <HeldGroup items={view.held} now={now} onOpenItem={onOpenItem} onRead={state.markRead} />
      ) : null}
      {tab === 'all' ? (
        <p className={styles.keepNote}>
          최근 {NOTIFICATION_KEEP_DAYS}일 · 최대 {NOTIFICATION_MAX_COUNT}개까지 보여요
        </p>
      ) : null}
    </>
  );
}

function KindIcon({ kind, tone }: { kind: NotificationKind; tone: NotificationTone }) {
  const Icon = KIND_ICON[kind];
  return (
    <span className={styles.kindIcon} data-tone={tone} aria-hidden>
      <Icon />
    </span>
  );
}

function NotificationRow({
  n,
  now,
  onOpen,
  onRead,
  dim,
}: {
  n: AppNotification;
  now: number;
  onOpen: () => void;
  onRead: () => void;
  dim?: boolean;
}) {
  const view = describeNotification(n, now);
  const unread = n.readAt === null;
  return (
    <div className={styles.rowWrap} data-unread={unread || undefined} data-dim={dim || undefined}>
      <button
        type="button"
        className={styles.row}
        onClick={onOpen}
        aria-label={`${unread ? '안 읽음 · ' : ''}${view.title} · ${view.detail} · ${notificationTime(n.createdAt, now)}`}
      >
        <KindIcon kind={n.payload.kind} tone={view.tone} />
        <span className={styles.text}>
          <span className={styles.rowTitle}>{view.title}</span>
          <span className={styles.rowDetail}>{view.detail}</span>
          <span className={styles.meta}>
            {notificationTime(n.createdAt, now)}
            {n.resolvedAt !== null ? (
              <span className={styles.doneTag}>
                <Check aria-hidden />
                처리됨
              </span>
            ) : view.goLabel ? (
              <span className={styles.go}>{view.goLabel} ›</span>
            ) : null}
          </span>
        </span>
      </button>
      {unread ? <MarkReadButton title={view.title} onRead={onRead} /> : null}
    </div>
  );
}

/**
 * 안 읽음 점이 곧 「읽음으로 표시」 단추다 — 누르면 그 화면으로 가지 않고 읽음만 한다(알림마다 화면을 오가지 않게).
 * 줄에 마우스를 올리거나 단추에 포커스가 오면 점이 체크로 바뀐다. 터치 화면은 늘 체크로 보인다.
 */
function MarkReadButton({ title, onRead }: { title: string; onRead: () => void }) {
  return (
    <button
      type="button"
      className={styles.markRead}
      onClick={(e) => {
        // 누르면 이 단추가 사라진다: 포커스가 body로 떨어지지 않게 바로 앞의 그 알림 줄(형제 단추)로 옮긴다
        const row = e.currentTarget.previousElementSibling;
        onRead();
        if (row instanceof HTMLElement) row.focus();
      }}
      aria-label={`읽음으로 표시: ${title}`}
      title="읽음으로 표시"
    >
      <span className={styles.markDot} aria-hidden />
      <Check aria-hidden className={styles.markCheck} />
    </button>
  );
}

/** 처리할 때까지 남는 알림. 윗부분을 누르면 그 화면으로 가며 읽음, 버튼은 카드 폭을 같은 크기로 나눈다 */
function TodoCard({
  n,
  now,
  state,
  onOpen,
  dim,
}: {
  n: AppNotification;
  now: number;
  state: NotificationsState;
  onOpen: () => void;
  dim?: boolean;
}) {
  const view = describeNotification(n, now);
  const unread = n.readAt === null;
  return (
    <div className={styles.todo} data-unread={unread || undefined} data-dim={dim || undefined}>
      <button
        type="button"
        className={styles.todoTop}
        onClick={onOpen}
        aria-label={`${unread ? '안 읽음 · ' : ''}${view.title} · ${view.detail}`}
      >
        <KindIcon kind={n.payload.kind} tone={view.tone} />
        <span className={styles.text}>
          <span className={styles.rowTitle}>{view.title}</span>
          <span className={styles.rowDetail} data-wrap>
            {view.detail}
          </span>
          <span className={styles.meta}>
            {notificationTime(n.createdAt, now)}
            {view.deadline ? <span className={styles.deadline}>{view.deadline}</span> : null}
          </span>
        </span>
      </button>
      {unread ? <MarkReadButton title={view.title} onRead={() => state.markRead(n.id)} /> : null}
      {n.payload.kind === 'EDITOR_INVITED' ? (
        <InviteActions n={n} state={state} />
      ) : view.goLabel ? (
        <div className={styles.actions} data-count="1">
          <Button size="sm" variant="outline" onClick={onOpen}>
            {view.goLabel}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

/**
 * 초대 수락은 바로, 거절은 카드 안에서 한 번 더 묻는다(되돌릴 수 없다 — 채널 스위처와 같은 규칙). 창 위에 확인 창을 또 띄우면
 * 바깥 누름으로 보고 알림 창이 닫혀 카드 안에서 묻는다.
 */
function InviteActions({ n, state }: { n: AppNotification; state: NotificationsState }) {
  const respond = useRespondInvitation(state);
  const [asking, setAsking] = useState(false);
  const busy = respond.isPending;
  // 수락 · 거절 모두 읽음을 같이 보낸다: 서버는 답한 초대에 resolvedAt만 채우므로, 안 보내면 거절한 알림이 「확인 필요」에서
  // 빠진 뒤 안 읽은 「처리됨」 줄로 남고 종 숫자도 그대로다
  function answer(kind: 'accept' | 'decline', options?: { onSettled?: () => void }) {
    if (n.readAt === null) state.markRead(n.id);
    respond.mutate({ n, answer: kind }, options);
  }
  if (asking) {
    return (
      <div className={styles.confirm}>
        <span>거절하면 {n.channel.name} 채널이 다시 초대해야 참여할 수 있어요</span>
        <div className={styles.actions} data-count="2">
          <Button size="sm" variant="outline" disabled={busy} onClick={() => setAsking(false)}>
            취소
          </Button>
          <Button
            size="sm"
            variant="danger"
            disabled={busy}
            onClick={() => answer('decline', { onSettled: () => setAsking(false) })}
          >
            거절하기
          </Button>
        </div>
      </div>
    );
  }
  return (
    <div className={styles.actions} data-count="2">
      <Button size="sm" variant="outline" disabled={busy} onClick={() => setAsking(true)}>
        거절
      </Button>
      <Button size="sm" variant="solid" disabled={busy} onClick={() => answer('accept')}>
        수락
      </Button>
    </div>
  );
}

function DndBar({ count }: { count: number }) {
  return (
    <div className={styles.dnd}>
      <Moon aria-hidden />
      <span>
        <b>방송 중이라 「중요」 알림만 띄워요</b>
        <span>나머지 {count}개는 방송이 끝나면 한 번에 알려 드려요</span>
      </span>
    </div>
  );
}

function HeldGroup({
  items,
  now,
  onOpenItem,
  onRead,
}: {
  items: AppNotification[];
  now: number;
  onOpenItem: (n: AppNotification) => void;
  onRead: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className={styles.held}>
      <button
        type="button"
        className={styles.heldHead}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Moon aria-hidden />
        <span>
          방송이 끝나면 알려 드릴 알림 <b>{items.length}개</b>
        </span>
        <ChevronDown aria-hidden className={styles.chevron} data-open={open || undefined} />
      </button>
      {open ? (
        <div className={styles.heldBody}>
          {items.map((n) => (
            <NotificationRow
              key={n.id}
              n={n}
              now={now}
              dim
              onOpen={() => onOpenItem(n)}
              onRead={() => onRead(n.id)}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}
