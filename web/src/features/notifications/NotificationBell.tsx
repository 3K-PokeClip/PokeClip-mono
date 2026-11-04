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
import { isInvitationFinished } from '@/features/channel/useInvitationResponse';
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
import { useNotificationRequests, type NotificationRequest } from './notificationRequests';
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
    void goTo(n, href);
  }

  useArrivalToasts(state);

  // 토스트(「알림 보기」 · 「모아 보기」)로 열면 FocusScope가 닫을 때 돌아갈 자리로 그 토스트 단추를 기억하는데, 토스트는 누르는
  // 즉시 사라져 닫으면 포커스가 body로 떨어진다. 종 단추에 먼저 포커스를 두고 연다 — Toast는 모달이 포커스를 잡았으면 빼앗지
  // 않는다(PR #481 4차)
  const bellRef = useRef<HTMLSpanElement>(null);
  function openFromOutside() {
    bellRef.current?.querySelector<HTMLButtonElement>('button')?.focus();
    openPanel();
  }

  // 토스트 단추의 부탁을 받는다(notificationRequests). 떠오를 때 이미 있던 부탁은 앞 종 몫이라 건너뛴다. 처리는 최신 함수로:
  // 구독은 한 번만 걸고 함수는 ref로 넘긴다
  const handlers = useRef({ openPanel: openFromOutside, openItem });
  useEffect(() => {
    handlers.current = { openPanel: openFromOutside, openItem };
  });
  useEffect(() => {
    const requests = useNotificationRequests.getState();
    const handle = (r: NotificationRequest) =>
      r.kind === 'panel' ? handlers.current.openPanel() : handlers.current.openItem(r.notification);
    // 종이 없던 화면에서 눌러 남은 부탁이 있으면 이 종이 한 번 받는다
    const detach = requests.attach();
    const waiting = requests.take();
    if (waiting) handle(waiting);
    let seen = useNotificationRequests.getState().seq;
    const unsubscribe = useNotificationRequests.subscribe((s) => {
      if (s.seq === seen || s.request === null) return;
      seen = s.seq;
      handle(s.request);
    });
    return () => {
      unsubscribe();
      detach();
    };
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
      <span ref={bellRef} className={styles.bell} data-place={place}>
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

  // 누른 단추가 다시 그려지며 사라지면(초대에 답해 「확인 필요」 카드가 일반 줄로 바뀜) 포커스가 body로 떨어져 다음 Tab이 창
  // 맨 앞에서 시작한다. 마지막으로 포커스가 있던 알림을 적어 두었다가, 잃었으면 같은 알림의 새 줄로 — 없으면 지금 탭 단추로
  // 옮긴다(PR #481 4차)
  const bodyRef = useRef<HTMLDivElement>(null);
  const tabsRef = useRef<HTMLDivElement>(null);
  const lastFocusedId = useRef<string | null>(null);
  useEffect(() => {
    const id = lastFocusedId.current;
    if (id === null || document.activeElement !== document.body) return;
    const row = [
      ...(bodyRef.current?.querySelectorAll<HTMLElement>('[data-notification-id]') ?? []),
    ].find((el) => el.dataset.notificationId === id);
    const target =
      row?.querySelector<HTMLButtonElement>('button') ??
      tabsRef.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]');
    target?.focus();
  });

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
      <div ref={tabsRef} className={styles.tabs} role="group" aria-label="보기">
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
      <div
        ref={bodyRef}
        className={styles.body}
        onFocus={(e) => {
          const at = e.target.closest<HTMLElement>('[data-notification-id]');
          lastFocusedId.current = at?.dataset.notificationId ?? null;
        }}
      >
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
      {view.held.length > 0 ? <DndBar count={pendingHeld(view.held)} /> : null}
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
    <div
      className={styles.rowWrap}
      data-notification-id={n.id}
      data-unread={unread || undefined}
      data-dim={dim || undefined}
    >
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
    <div
      className={styles.todo}
      data-notification-id={n.id}
      data-unread={unread || undefined}
      data-dim={dim || undefined}
    >
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
  const cancelRef = useRef<HTMLButtonElement>(null);
  const declineRef = useRef<HTMLButtonElement>(null);
  const asked = useRef(false);
  // 누른 단추가 확인 칸으로 바뀌며 사라지면 포커스가 body로 떨어진다(다음 Tab이 창 맨 앞, 확인 문구도 안 읽힘) — 채널 스위처의
  // 거절 확인과 같은 규칙: 열리면 「취소」(Enter 한 번에 거절되지 않게), 닫히면 「거절」로 돌린다. 그 사이 다른 곳으로 옮겼으면
  // 빼앗지 않는다(PR #481 3차)
  useEffect(() => {
    if (asking) {
      asked.current = true;
      cancelRef.current?.focus();
      return;
    }
    if (!asked.current) return;
    asked.current = false;
    const lost = document.activeElement === null || document.activeElement === document.body;
    if (lost) declineRef.current?.focus();
  }, [asking]);
  // 수락 · 거절 모두 읽음을 같이 보낸다: 서버는 답한 초대에 resolvedAt만 채우므로, 안 보내면 거절한 알림이 「확인 필요」에서
  // 빠진 뒤 안 읽은 「처리됨」 줄로 남고 종 숫자도 그대로다
  function answer(
    kind: 'accept' | 'decline',
    options?: { onSettled?: (data: unknown, error: unknown) => void },
  ) {
    if (n.readAt === null) state.markRead(n.id);
    respond.mutate({ n, answer: kind }, options);
  }
  if (asking) {
    return (
      <div className={styles.confirm}>
        <span>거절하면 {n.channel.name} 채널이 다시 초대해야 참여할 수 있어요</span>
        <div className={styles.actions} data-count="2">
          <Button
            ref={cancelRef}
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={() => setAsking(false)}
          >
            취소
          </Button>
          <Button
            size="sm"
            variant="danger"
            disabled={busy}
            // 모르는 실패(일시 장애)엔 확인을 남겨 「거절하기」만 다시 누르게 한다 — 채널 스위처와 같은 규칙(PR #481 4차)
            onClick={() =>
              answer('decline', {
                onSettled: (_data, error) => {
                  if (isInvitationFinished(error)) setAsking(false);
                },
              })
            }
          >
            거절하기
          </Button>
        </div>
      </div>
    );
  }
  return (
    <div className={styles.actions} data-count="2">
      <Button
        ref={declineRef}
        size="sm"
        variant="outline"
        disabled={busy}
        onClick={() => setAsking(true)}
      >
        거절
      </Button>
      <Button size="sm" variant="solid" disabled={busy} onClick={() => answer('accept')}>
        수락
      </Button>
    </div>
  );
}

/**
 * 쌓아 둔 것 중 방송이 끝나면 알려 줄 것 — 안 읽은 것만. 묶음을 펴 읽은 것은 끝날 때 토스트(「방송 중 알림 n개」)에도 안 세므로
 * 띠 · 묶음 머리도 같이 안 센다: 다 세면 띠는 3개, 끝나면 「2개」로 어긋난다(PR #481 4차)
 */
function pendingHeld(items: AppNotification[]): number {
  return items.filter((n) => n.readAt === null).length;
}

function DndBar({ count }: { count: number }) {
  return (
    <div className={styles.dnd}>
      <Moon aria-hidden />
      <span>
        <b>방송 중이라 「중요」 알림만 띄워요</b>
        {count > 0 ? <span>나머지 {count}개는 방송이 끝나면 한 번에 알려 드려요</span> : null}
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
  const pending = pendingHeld(items);
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
          {pending > 0 ? (
            <>
              방송이 끝나면 알려 드릴 알림 <b>{pending}개</b>
            </>
          ) : (
            <>
              방송 중 쌓아 둔 알림 <b>{items.length}개</b>
            </>
          )}
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
