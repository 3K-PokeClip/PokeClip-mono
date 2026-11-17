'use client';

// 알림 서버가 생기기 전 화면을 보려고 쓰는 가짜 알림(POK-540) — 개발 서버에서만 켜진다.
//
//   ?mock=notifications       평소: 알림 10개(편집자 초대 · 맡은 채널 방송 포함), 12초 뒤 새 알림 하나
//   ?mock=notifications-live  방송 중: 「중요」가 아닌 것이 쌓여 있다가 40초 뒤 방송이 끝나 한꺼번에 풀린다
//   ?mock=off                 끈다
//
// 한 번 켜면 같은 탭에선 다른 화면으로 가도 유지된다(sessionStorage) — 알림을 누르면 화면을 옮기기 때문이다.
// 프로덕션 번들에서는 첫 줄의 NODE_ENV 검사로 늘 null이라 켤 수 없다.

import { useSyncExternalStore } from 'react';
import type { AppNotification, NotificationPayload } from '@/api/notifications';

export type NotificationsMockMode = 'normal' | 'live';

const STORAGE_KEY = 'pc-mock-notifications';
const ARRIVE_AFTER_MS = 12_000;
const LIVE_ENDS_AFTER_MS = 40_000;

const mockAllowed = () => process.env.NODE_ENV !== 'production' && typeof window !== 'undefined';

/** 주소의 ?mock= 값. 없으면 undefined(주소가 말하지 않음), ?mock=off면 null */
function modeFromUrl(): NotificationsMockMode | null | undefined {
  const param = new URLSearchParams(window.location.search).get('mock');
  if (param === 'notifications') return 'normal';
  if (param === 'notifications-live') return 'live';
  if (param === 'off') return null;
  return undefined;
}

/**
 * 지금 가짜 모드. 읽기만 한다 — 렌더 중에(useSyncExternalStore의 getSnapshot) 여러 번 불리므로 저장소에 쓰지 않는다.
 * 주소가 말하면 주소, 아니면 이 탭에 기억해 둔 값.
 */
export function readNotificationsMockMode(): NotificationsMockMode | null {
  if (!mockAllowed()) return null;
  try {
    const fromUrl = modeFromUrl();
    if (fromUrl !== undefined) return fromUrl;
    const saved = window.sessionStorage.getItem(STORAGE_KEY);
    return saved === 'normal' || saved === 'live' ? saved : null;
  } catch {
    return null;
  }
}

/** 주소의 값을 이 탭에 기억한다(알림을 눌러 다른 화면으로 가도 유지되게). 렌더 밖 — 구독할 때 — 한다 */
function rememberUrlMode() {
  if (!mockAllowed()) return;
  try {
    const fromUrl = modeFromUrl();
    if (fromUrl === undefined) return;
    if (fromUrl === null) window.sessionStorage.removeItem(STORAGE_KEY);
    else window.sessionStorage.setItem(STORAGE_KEY, fromUrl);
  } catch {
    /* 저장이 막혀도 이번 화면에선 주소 값으로 돈다 */
  }
}

const subscribe = () => {
  rememberUrlMode();
  return () => {};
};

/** 가짜 알림 모드 — 서버 렌더와 첫 수화에선 늘 null(꺼짐)이라 숫자 배지가 어긋나지 않는다 */
export function useNotificationsMockMode(): NotificationsMockMode | null {
  return useSyncExternalStore(subscribe, readNotificationsMockMode, () => null);
}

interface Owner {
  id: number;
  name: string;
}

interface MockStore {
  mode: NotificationsMockMode;
  ownerId: number;
  startedAt: number;
  items: AppNotification[];
  arrived: boolean;
  liveEnded: boolean;
}

let store: MockStore | null = null;

const MIN = 60_000;
/**
 * 편집을 맡은 남의 채널(가짜). 참여 채널 목록에 없어서, 누르면 목록을 새로 받아 보고 그래도 없으니 「라면왕 채널의 편집 권한이
 * 없어요」만 알리고 이동하지 않는다(useGoToNotification). 그 채널로 바꿔 가는 흐름은 NotificationBell 시험이 본다
 */
const OTHER_CHANNEL = { id: -540, name: '라면왕' };

function make(
  id: string,
  minutesAgo: number,
  payload: NotificationPayload,
  channel: Owner,
  extra: Partial<AppNotification> = {},
): AppNotification {
  return {
    id,
    createdAt: new Date(Date.now() - minutesAgo * MIN).toISOString(),
    readAt: null,
    resolvedAt: null,
    important: false,
    held: false,
    channel: { id: channel.id, name: channel.name },
    payload,
    ...extra,
  };
}

function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  d.setHours(4, 0, 0, 0);
  return d.toISOString();
}

function minutesSinceMidnight(): number {
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
}

function seed(mode: NotificationsMockMode, owner: Owner): AppNotification[] {
  const today = minutesSinceMidnight();
  const read = { readAt: new Date().toISOString() };
  const items: AppNotification[] = [
    make(
      'mock-managed-live',
      5,
      { kind: 'MANAGED_BROADCAST_STARTED', broadcastTitle: '점심 발로란트' },
      OTHER_CHANNEL,
    ),
    make(
      'mock-youtube',
      12,
      { kind: 'YOUTUBE_LINK_EXPIRED', youtubeChannelName: `${owner.name} TV` },
      owner,
      { important: true },
    ),
    make(
      'mock-editor-upload',
      18,
      {
        kind: 'EDITOR_UPLOADED',
        editorName: '민트초코',
        clipTitle: '한타 대참사 모음',
        privacy: 'PUBLIC',
      },
      owner,
    ),
    make(
      'mock-clip-failed',
      25,
      { kind: 'CLIP_FAILED', clipTitle: '바론 스틸 역전각', stage: 'RENDER' },
      owner,
    ),
    make('mock-invite', 40, { kind: 'EDITOR_INVITED', invitationId: -1 }, OTHER_CHANNEL, {
      important: true,
    }),
    make(
      'mock-clip-done',
      70,
      { kind: 'CLIP_UPLOADED', clipTitle: '펜타킬 각', count: 3, privacy: 'PRIVATE' },
      owner,
    ),
    make(
      'mock-vod-expiring',
      Math.max(0, Math.min(today - 1, 310)),
      {
        kind: 'VOD_EXPIRING',
        streamId: 'mock-stream-old',
        broadcastTitle: '솔랭 300판 도전',
        deletesAt: inDays(3),
      },
      owner,
      read,
    ),
    make(
      'mock-broadcast-ended',
      today + 128,
      {
        kind: 'BROADCAST_ENDED',
        streamId: 'mock-stream-yesterday',
        broadcastTitle: '오늘도 롤 랭크 🔥',
        durationSec: 15_120,
        cardCount: 14,
      },
      owner,
      read,
    ),
    make('mock-chat-broken', today + 255, { kind: 'CHAT_LINK_BROKEN', platform: 'CHZZK' }, owner, {
      ...read,
      important: true,
      resolvedAt: new Date().toISOString(),
    }),
    make(
      'mock-clip-old',
      today + 1440 + 100,
      { kind: 'CLIP_UPLOADED', clipTitle: '아리 5인 궁', count: 1, privacy: 'PUBLIC' },
      owner,
      read,
    ),
  ];
  if (mode === 'normal') return items;
  // 방송 중: 방송(40분 전 시작) 동안 생긴 「중요」가 아닌 것은 쌓여 있고, 중요(채팅 끊김)는 바로 왔다
  return [
    make('mock-live-chat', 3, { kind: 'CHAT_LINK_BROKEN', platform: 'CHZZK' }, owner, {
      important: true,
    }),
    ...items.map((n) =>
      Date.now() - Date.parse(n.createdAt) < 40 * MIN && !n.important ? { ...n, held: true } : n,
    ),
  ];
}

function storeFor(mode: NotificationsMockMode, owner: Owner): MockStore {
  if (store === null || store.mode !== mode || store.ownerId !== owner.id) {
    store = {
      mode,
      ownerId: owner.id,
      startedAt: Date.now(),
      items: seed(mode, owner),
      arrived: false,
      liveEnded: false,
    };
  }
  return store;
}

/** 서버 GET 흉내 — 때가 되면 새 알림을 얹고(평소) 쌓아 둔 것을 푼다(방송 중) */
export async function fetchMockNotifications(
  mode: NotificationsMockMode,
  owner: Owner,
): Promise<{ items: AppNotification[] }> {
  const s = storeFor(mode, owner);
  const elapsed = Date.now() - s.startedAt;
  if (mode === 'normal' && !s.arrived && elapsed >= ARRIVE_AFTER_MS) {
    s.arrived = true;
    s.items = [
      make(
        'mock-arrival',
        0,
        { kind: 'CLIP_UPLOADED', clipTitle: '드래곤 스틸', count: 1, privacy: 'PRIVATE' },
        owner,
      ),
      ...s.items,
    ];
  }
  if (mode === 'live' && !s.liveEnded && elapsed >= LIVE_ENDS_AFTER_MS) {
    s.liveEnded = true;
    s.items = s.items.map((n) => (n.held ? { ...n, held: false } : n));
  }
  return { items: s.items.map((n) => ({ ...n, channel: { ...n.channel } })) };
}

function patchOne(id: string, change: Partial<AppNotification>) {
  if (store === null) return;
  store.items = store.items.map((n) => (n.id === id ? { ...n, ...change } : n));
}

export async function markMockRead(id: string): Promise<void> {
  patchOne(id, { readAt: new Date().toISOString() });
}

export async function markAllMockRead(): Promise<void> {
  if (store === null) return;
  const at = new Date().toISOString();
  store.items = store.items.map((n) => (n.held || n.readAt !== null ? n : { ...n, readAt: at }));
}

/** 초대에 답한 것 흉내 — 서버라면 답을 받은 뒤 resolvedAt을 채운다 */
export async function resolveMock(id: string): Promise<void> {
  const at = new Date().toISOString();
  patchOne(id, { readAt: at, resolvedAt: at });
}

/** 시험용: 가짜 저장소를 비운다 */
export function resetMockNotifications() {
  store = null;
}
