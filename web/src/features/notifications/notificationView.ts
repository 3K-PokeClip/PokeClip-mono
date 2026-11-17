// 알림 한 건을 화면 문구 · 갈 곳으로, 알림 목록을 창의 묶음으로 바꾼다(POK-540). 화면 상태 없는 순수 함수만 둔다.
//
// 확정 규칙(시안 A · A-1, 2026-11-04):
// - 읽음은 누를 때만 된다(줄 · 확인 필요 카드 · 「모두 읽음」). 창을 열거나 닫는 것으로는 읽음이 되지 않는다.
// - 창은 열 때마다 「안 읽음」 탭부터. 창 안에서 읽은 것도 닫기 전까지는 「안 읽음」 탭에 흐리게 남는다(snapshot).
// - 할 일(TODO_KINDS)은 풀릴 때까지(resolvedAt) 탭과 상관없이 맨 위 「확인 필요」에 있다.
// - 방송 중 쌓아 둔 것(held)은 따로 묶고 종 숫자에 세지 않는다.
// - 최근 14일 · 최대 50개만 보인다. 풀리지 않은 할 일은 기간 · 개수와 상관없이 남는다.

import {
  NOTIFICATION_KEEP_DAYS,
  NOTIFICATION_MAX_COUNT,
  TODO_KINDS,
  type AppNotification,
  type ClipPrivacy,
} from '@/api/notifications';

export type NotificationTone = 'danger' | 'warning' | 'success' | 'info' | 'neutral';

export interface NotificationView {
  title: string;
  detail: string;
  tone: NotificationTone;
  /** 누르면 가는 화면. 없으면 누르기는 읽음만 한다(편집자 초대) */
  href: string | null;
  /** 갈 곳의 이름 — 줄에선 호버 때, 확인 필요 카드에선 버튼 이름 */
  goLabel: string | null;
  /** 기한이 있는 할 일(다시보기 삭제)의 남은 날 */
  deadline: string | null;
}

const DAY_MS = 86_400_000;
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

const PRIVACY_LABEL: Record<ClipPrivacy, string> = {
  PUBLIC: '공개',
  UNLISTED: '일부 공개',
  PRIVATE: '비공개',
};

function quoted(title: string, fallback: string): string {
  return `「${title.trim() || fallback}」`;
}

function durationText(sec: number): string {
  const totalMin = Math.max(0, Math.round(sec / 60));
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m}분`;
  return m === 0 ? `${h}시간` : `${h}시간 ${m}분`;
}

function startOfDay(t: number): number {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function clockText(d: Date): string {
  const h = d.getHours();
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${ampm} ${h12}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function dateText(d: Date): string {
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAYS[d.getDay()]})`;
}

/** 삭제까지 남은 날(오늘 지워지면 0) — 날짜로 센다: 밤 11시에 받은 「내일 삭제」가 시간 차로 0이 되지 않게 */
export function daysUntil(iso: string, now: number): number {
  return Math.max(0, Math.round((startOfDay(Date.parse(iso)) - startOfDay(now)) / DAY_MS));
}

export function describeNotification(n: AppNotification, now: number): NotificationView {
  const p = n.payload;
  switch (p.kind) {
    case 'CHAT_LINK_BROKEN':
      return {
        title: `${p.platform === 'SOOP' ? 'SOOP' : '치지직'} 채팅을 못 모으고 있어요`,
        detail: '연동이 만료됐어요 · 다시 연동하면 바로 모아요',
        tone: 'danger',
        href: '/settings/channels',
        goLabel: '다시 연동',
        deadline: null,
      };
    case 'YOUTUBE_LINK_EXPIRED':
      return {
        title: '유튜브 연동이 끊겼어요',
        detail: [p.youtubeChannelName.trim(), '다시 연결해야 클립을 올릴 수 있어요']
          .filter(Boolean)
          .join(' · '),
        tone: 'danger',
        href: '/settings/channels',
        goLabel: '다시 연결',
        deadline: null,
      };
    case 'CLIP_UPLOADED':
      return {
        title: p.count > 1 ? `클립 ${p.count}개를 올렸어요` : '클립을 올렸어요',
        detail: `${quoted(p.clipTitle, '제목 없는 클립')}${p.count > 1 ? ` 외 ${p.count - 1}개` : ''} · ${PRIVACY_LABEL[p.privacy]}`,
        tone: 'success',
        href: '/clips/library',
        goLabel: '보관함 보기',
        deadline: null,
      };
    case 'CLIP_FAILED':
      return {
        title: p.stage === 'UPLOAD' ? '클립을 올리지 못했어요' : '클립을 만들지 못했어요',
        detail: `${quoted(p.clipTitle, '제목 없는 클립')} · 보관함에서 다시 시도할 수 있어요`,
        tone: 'danger',
        href: '/clips/library',
        goLabel: '보관함에서 보기',
        deadline: null,
      };
    case 'EDITOR_UPLOADED':
      return {
        title: `${p.editorName.trim() || '편집자'} 님이 내 채널에 올렸어요`,
        detail: `${quoted(p.clipTitle, '제목 없는 클립')} · ${PRIVACY_LABEL[p.privacy]}`,
        tone: 'info',
        href: '/clips/library',
        goLabel: '보관함 보기',
        deadline: null,
      };
    case 'VOD_EXPIRING': {
      const broadcast = quoted(p.broadcastTitle, '제목 없는 방송');
      const where = {
        tone: 'warning',
        href: `/broadcast/vod/${encodeURIComponent(p.streamId)}`,
        goLabel: '카드 보기',
      } as const;
      // 날짜를 못 읽으면(서버가 deletesAt을 빼먹어 ''로 옴) 날짜 없이 쓴다 — 「NaN일 뒤」 · 「NaN월 NaN일」이 그려지지 않게(PR #481 3차)
      if (Number.isNaN(Date.parse(p.deletesAt))) {
        return {
          ...where,
          title: '다시보기가 곧 삭제돼요',
          detail: `${broadcast} · 60일 보관`,
          deadline: null,
        };
      }
      const days = daysUntil(p.deletesAt, now);
      return {
        ...where,
        title: days === 0 ? '다시보기가 오늘 삭제돼요' : `다시보기가 ${days}일 뒤 삭제돼요`,
        detail: `${broadcast} · ${dateText(new Date(p.deletesAt))} 삭제 · 60일 보관`,
        deadline: days === 0 ? '오늘' : `${days}일 남음`,
      };
    }
    case 'BROADCAST_ENDED':
      return {
        title: '방송이 끝났어요 · 다시보기 준비됨',
        detail: `${quoted(p.broadcastTitle, '제목 없는 방송')} ${durationText(p.durationSec)} · 카드 ${p.cardCount}개`,
        tone: 'neutral',
        href: `/broadcast/vod/${encodeURIComponent(p.streamId)}`,
        goLabel: '카드 보기',
        deadline: null,
      };
    case 'MANAGED_BROADCAST_STARTED':
      return {
        title: `${n.channel.name} 채널이 방송을 시작했어요`,
        detail: `${quoted(p.broadcastTitle, '제목 없는 방송')} · 하이라이트 카드가 쌓이기 시작해요`,
        tone: 'info',
        href: '/broadcast/livenow',
        goLabel: '라이브 보기',
        deadline: null,
      };
    case 'EDITOR_INVITED':
      return {
        title: `${n.channel.name} 채널이 편집자로 초대했어요`,
        detail: `수락하면 ${n.channel.name} 채널의 카드로 클립을 만들 수 있어요`,
        tone: 'info',
        href: null,
        goLabel: null,
        deadline: null,
      };
  }
}

/** 오늘이면 「n분 전」, 어제면 「어제 오후 7:45」, 그 전이면 「11월 2일 (월)」 */
export function notificationTime(iso: string, now: number): string {
  const t = Date.parse(iso);
  // 못 읽으면(서버가 createdAt을 빼먹음) 비운다 — 「NaN월 NaN일 (undefined)」가 그려지지 않게. 날짜 묶음은 「이전」으로 간다(PR #481 5차)
  if (Number.isNaN(t)) return '';
  const today = startOfDay(now);
  if (t >= today) {
    const min = Math.max(0, Math.floor((now - t) / 60_000));
    if (min < 1) return '방금';
    if (min < 60) return `${min}분 전`;
    return `${Math.floor(min / 60)}시간 전`;
  }
  const d = new Date(t);
  if (t >= today - DAY_MS) return `어제 ${clockText(d)}`;
  return dateText(d);
}

export type DayLabel = '오늘' | '어제' | '이전';

export function dayLabel(iso: string, now: number): DayLabel {
  const t = Date.parse(iso);
  const today = startOfDay(now);
  if (t >= today) return '오늘';
  if (t >= today - DAY_MS) return '어제';
  return '이전';
}

/** 아직 풀리지 않은 할 일 — 「확인 필요」에 둔다 */
export function isOpenTodo(n: AppNotification): boolean {
  return TODO_KINDS.has(n.payload.kind) && n.resolvedAt === null;
}

/**
 * 보여 줄 기간 안인가(최근 14일). 풀리지 않은 할 일은 기간과 상관없이 남긴다 — 해야 할 일이 오래됐다고 사라지면 안 된다.
 * 서버도 같은 규칙으로 주지만(api/notifications.ts 계약) 창을 오래 켜 둔 사이 지난 것까지 한 번 더 거른다
 */
export function isKept(n: AppNotification, now: number): boolean {
  return isOpenTodo(n) || now - Date.parse(n.createdAt) <= NOTIFICATION_KEEP_DAYS * DAY_MS;
}

/**
 * 창에 올릴 알림: 기간(14일) 안의 것을 최신순 그대로 최대 50개. 풀리지 않은 할 일은 기간 · 개수와 상관없이 모두 남기고,
 * 나머지 자리를 최신 것부터 채운다
 */
export function visibleNotifications(
  items: readonly AppNotification[],
  now: number,
): AppNotification[] {
  const kept = items.filter((n) => isKept(n, now));
  let room = Math.max(0, NOTIFICATION_MAX_COUNT - kept.filter(isOpenTodo).length);
  return kept.filter((n) => {
    if (isOpenTodo(n)) return true;
    if (room === 0) return false;
    room -= 1;
    return true;
  });
}

/** 종 숫자에 세는 것: 받은(held가 아닌) 것 중 안 읽은 것 */
export function unreadCount(items: readonly AppNotification[]): number {
  return items.filter((n) => !n.held && n.readAt === null).length;
}

export type PanelTab = 'unread' | 'all';

export interface PanelView {
  todos: AppNotification[];
  groups: { day: DayLabel; items: AppNotification[] }[];
  held: AppNotification[];
}

const DAYS: DayLabel[] = ['오늘', '어제', '이전'];

/**
 * 창에 그릴 묶음. snapshot = 창을 연 순간 안 읽음이던 번호들: 창 안에서 읽어도 닫기 전까지는 「안 읽음」 탭에서 빼지 않는다
 * (누르자마자 줄이 사라지면 놀란다). 목록은 서버가 준 최신순을 그대로 쓴다.
 */
export function buildPanelView(
  items: readonly AppNotification[],
  tab: PanelTab,
  snapshot: ReadonlySet<string>,
  now: number,
): PanelView {
  const delivered = items.filter((n) => !n.held);
  const todos = delivered.filter(isOpenTodo);
  const rest = delivered.filter(
    (n) => !isOpenTodo(n) && (tab === 'all' || n.readAt === null || snapshot.has(n.id)),
  );
  const groups = DAYS.map((day) => ({
    day,
    items: rest.filter((n) => dayLabel(n.createdAt, now) === day),
  })).filter((g) => g.items.length > 0);
  return { todos, groups, held: items.filter((n) => n.held) };
}
