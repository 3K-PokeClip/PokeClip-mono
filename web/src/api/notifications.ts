'use client';

import { apiFetch } from './client';

// 알림(종) 호출 (POK-540). 알림 서버는 아직 없다 — 아래 계약은 웹이 백엔드에 제안하는 모양이고, 백엔드가 열면
// NOTIFICATIONS_SERVER_READY를 true로 바꾸고 next.config.ts 프록시 목록에 맡은 서비스로 `/api/notifications/:path*`
// 한 줄을 더한다. 그 전에는 요청을 보내지 않는다(종은 빈 창).
//
// ── 제안 계약 ──
// GET  /api/notifications            → 200 { items: NotificationWire[] } — 최신순. 최근 14일(NOTIFICATION_KEEP_DAYS) 안의 것을
//                                       최대 50개(NOTIFICATION_MAX_COUNT). 단, 아직 풀리지 않은 할 일(resolvedAt=null)은
//                                       기간 · 개수와 상관없이 함께 준다(해야 할 일이 오래됐다고 사라지면 안 된다)
// POST /api/notifications/{id}/read  → 204 — 하나 읽음(이미 읽었어도 204)
// POST /api/notifications/read-all   → 204 — 받은(held가 아닌) 알림을 모두 읽음
//
// 서버가 맡는 규칙(웹은 받은 그대로 그린다):
// - 무엇을 언제 만들지 · 설정 › 알림의 끔 · 받기 · 중요(important)를 거르는 일
// - 방송 중 방해 금지: 방송 중에 생긴 「중요」가 아닌 알림은 held=true로 두었다가 방송이 끝나면 false로 푼다
// - 할 일 종류(TODO_KINDS)는 그 일이 풀리면 resolvedAt을 채운다(연동을 다시 함 · 클립을 다시 만듦 · 초대에 답함 · 삭제됨)
// - 문구는 웹이 만든다: 서버는 종류와 data만 준다

/** 알림 서버가 위 계약을 열었는가. false면 목록을 읽지 않는다 */
export const NOTIFICATIONS_SERVER_READY = false;

/** 알림을 보여 주는 기간(일)과 개수. 넘는 것은 서버가 안 주고, 웹도 한 번 더 거른다 — 풀리지 않은 할 일은 예외 */
export const NOTIFICATION_KEEP_DAYS = 14;
export const NOTIFICATION_MAX_COUNT = 50;

export type NotificationKind =
  | 'CHAT_LINK_BROKEN'
  | 'YOUTUBE_LINK_EXPIRED'
  | 'CLIP_UPLOADED'
  | 'CLIP_FAILED'
  | 'EDITOR_UPLOADED'
  | 'VOD_EXPIRING'
  | 'BROADCAST_ENDED'
  | 'MANAGED_BROADCAST_STARTED'
  | 'EDITOR_INVITED';

export type ClipPrivacy = 'PUBLIC' | 'UNLISTED' | 'PRIVATE';

/** 종류별 data. 설정 › 알림의 항목(notificationItems.ts)과 짝: 채팅 끊김 · 유튜브 만료 · 클립 결과 · 편집자 업로드 · VOD 삭제 임박 · 방송 종료 · 맡은 채널 방송 시작 + 끌 수 없는 편집자 초대 */
export type NotificationPayload =
  /** 채팅 수집이 재시도로 안 풀려 다시 연동해야 한다 */
  | { kind: 'CHAT_LINK_BROKEN'; platform: 'CHZZK' | 'SOOP' }
  /** 구글이 토큰 갱신을 거부(invalid_grant)했다 */
  | { kind: 'YOUTUBE_LINK_EXPIRED'; youtubeChannelName: string }
  /** 클립을 유튜브에 올렸다 — 짧은 시간에 여럿이면 서버가 하나로 묶어 count를 늘린다 */
  | { kind: 'CLIP_UPLOADED'; clipTitle: string; count: number; privacy: ClipPrivacy }
  | { kind: 'CLIP_FAILED'; clipTitle: string; stage: 'RENDER' | 'UPLOAD' }
  /** 편집자가 내 유튜브 채널에 올렸다(승인 단계가 없어 스트리머가 알아야 한다) */
  | { kind: 'EDITOR_UPLOADED'; editorName: string; clipTitle: string; privacy: ClipPrivacy }
  /** 다시보기 60일 보관이 끝나기 전 — D-7 · D-3 · D-1에 하나씩 */
  | { kind: 'VOD_EXPIRING'; streamId: string; broadcastTitle: string; deletesAt: string }
  | {
      kind: 'BROADCAST_ENDED';
      streamId: string;
      broadcastTitle: string;
      durationSec: number;
      cardCount: number;
    }
  /** 편집을 맡은 채널이 방송을 시작했다(편집자에게만) */
  | { kind: 'MANAGED_BROADCAST_STARTED'; broadcastTitle: string }
  /** 편집자로 초대받았다 — 끌 수 없다. 답하면 resolvedAt */
  | { kind: 'EDITOR_INVITED'; invitationId: number };

/** 읽어도 남고, 그 일이 풀려야(resolvedAt) 「확인 필요」에서 빠지는 종류 */
export const TODO_KINDS: ReadonlySet<NotificationKind> = new Set<NotificationKind>([
  'CHAT_LINK_BROKEN',
  'YOUTUBE_LINK_EXPIRED',
  'CLIP_FAILED',
  'VOD_EXPIRING',
  'EDITOR_INVITED',
]);

// 비어 있는 칸(readAt · resolvedAt = null, important · held = false)은 빼고 보내도 같게 읽는다: auth 서버 DTO가
// `@JsonInclude(NON_NULL)` 관례라 같은 꼴로 오면 undefined가 남아 `=== null` 판정(안 읽음 · 확인 필요)이 모두 갈린다(PR #481 3차)
interface NotificationWire {
  id: string;
  kind: string;
  createdAt: string;
  readAt?: string | null;
  resolvedAt?: string | null;
  /** 받는 사람이 이 종류를 「중요」로 둔 채 생겼다(방해 금지를 뚫는다 · 할 일이면 토스트를 닫을 때까지 둔다) */
  important?: boolean;
  /** 방송 중 방해 금지로 쌓아 둔 것 — 방송이 끝나면 서버가 false로 푼다 */
  held?: boolean;
  /** 이 일이 일어난 채널. 남의 채널(편집을 맡은 곳)이면 눌렀을 때 그 채널로 바꾼 뒤 간다 */
  channelId: number;
  channelName: string;
  /** 종류별 칸. 서버가 비워(null) 보내거나 빼먹어도 그 한 건 때문에 목록 전체가 깨지지 않게 받는다 */
  data?: Record<string, unknown> | null;
}

export interface AppNotification {
  id: string;
  createdAt: string;
  readAt: string | null;
  resolvedAt: string | null;
  important: boolean;
  held: boolean;
  channel: { id: number; name: string };
  payload: NotificationPayload;
}

export interface NotificationsResponse {
  items: AppNotification[];
}

const KINDS: ReadonlySet<string> = new Set<NotificationKind>([
  'CHAT_LINK_BROKEN',
  'YOUTUBE_LINK_EXPIRED',
  'CLIP_UPLOADED',
  'CLIP_FAILED',
  'EDITOR_UPLOADED',
  'VOD_EXPIRING',
  'BROADCAST_ENDED',
  'MANAGED_BROADCAST_STARTED',
  'EDITOR_INVITED',
]);

/**
 * 서버 한 건을 화면 꼴로 옮긴다. 모르는 종류는 버린다(서버가 종류를 먼저 늘려도 창이 깨지지 않게). data는 종류가 정한 칸만
 * 화이트리스트로 옮긴다 — 스프레드로 받으면 서버가 칸을 늘릴 때 조용히 딸려 들어온다.
 */
export function fromWire(wire: NotificationWire): AppNotification | null {
  if (!KINDS.has(wire.kind)) return null;
  const d: Record<string, unknown> =
    wire.data !== null && typeof wire.data === 'object' ? wire.data : {};
  const str = (k: string) => (typeof d[k] === 'string' ? (d[k] as string) : '');
  const num = (k: string) => (typeof d[k] === 'number' ? (d[k] as number) : 0);
  const privacy = (): ClipPrivacy =>
    d.privacy === 'PUBLIC' || d.privacy === 'UNLISTED' ? d.privacy : 'PRIVATE';
  let payload: NotificationPayload;
  switch (wire.kind as NotificationKind) {
    case 'CHAT_LINK_BROKEN':
      payload = { kind: 'CHAT_LINK_BROKEN', platform: d.platform === 'SOOP' ? 'SOOP' : 'CHZZK' };
      break;
    case 'YOUTUBE_LINK_EXPIRED':
      payload = { kind: 'YOUTUBE_LINK_EXPIRED', youtubeChannelName: str('youtubeChannelName') };
      break;
    case 'CLIP_UPLOADED':
      payload = {
        kind: 'CLIP_UPLOADED',
        clipTitle: str('clipTitle'),
        count: Math.max(1, num('count')),
        privacy: privacy(),
      };
      break;
    case 'CLIP_FAILED':
      payload = {
        kind: 'CLIP_FAILED',
        clipTitle: str('clipTitle'),
        stage: d.stage === 'UPLOAD' ? 'UPLOAD' : 'RENDER',
      };
      break;
    case 'EDITOR_UPLOADED':
      payload = {
        kind: 'EDITOR_UPLOADED',
        editorName: str('editorName'),
        clipTitle: str('clipTitle'),
        privacy: privacy(),
      };
      break;
    case 'VOD_EXPIRING':
      payload = {
        kind: 'VOD_EXPIRING',
        streamId: str('streamId'),
        broadcastTitle: str('broadcastTitle'),
        deletesAt: str('deletesAt'),
      };
      break;
    case 'BROADCAST_ENDED':
      payload = {
        kind: 'BROADCAST_ENDED',
        streamId: str('streamId'),
        broadcastTitle: str('broadcastTitle'),
        durationSec: num('durationSec'),
        cardCount: num('cardCount'),
      };
      break;
    case 'MANAGED_BROADCAST_STARTED':
      payload = { kind: 'MANAGED_BROADCAST_STARTED', broadcastTitle: str('broadcastTitle') };
      break;
    case 'EDITOR_INVITED':
      payload = { kind: 'EDITOR_INVITED', invitationId: num('invitationId') };
      break;
  }
  return {
    id: wire.id,
    createdAt: wire.createdAt,
    readAt: wire.readAt ?? null,
    resolvedAt: wire.resolvedAt ?? null,
    important: wire.important === true,
    held: wire.held === true,
    channel: { id: wire.channelId, name: wire.channelName },
    payload,
  };
}

export async function fetchNotifications(): Promise<NotificationsResponse> {
  const res = await apiFetch('/api/notifications');
  const body = (await res.json()) as { items: NotificationWire[] };
  return {
    items: body.items.map(fromWire).filter((n): n is AppNotification => n !== null),
  };
}

export async function markNotificationRead(id: string): Promise<void> {
  await apiFetch(`/api/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' });
}

export async function markAllNotificationsRead(): Promise<void> {
  await apiFetch('/api/notifications/read-all', { method: 'POST' });
}

export const NOTIFICATIONS_QUERY_KEY = ['notifications'] as const;
