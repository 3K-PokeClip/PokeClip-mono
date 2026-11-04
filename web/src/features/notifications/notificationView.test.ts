import { describe, expect, it } from 'vitest';
import { fromWire, type AppNotification, type NotificationPayload } from '@/api/notifications';
import {
  buildPanelView,
  dayLabel,
  daysUntil,
  describeNotification,
  isKept,
  notificationTime,
  unreadCount,
  visibleNotifications,
} from './notificationView';

// 2026-11-04 (수) 오후 2:10, 서울(vitest env TZ)
const NOW = new Date(2026, 10, 4, 14, 10).getTime();
const MIN = 60_000;

function n(
  id: string,
  minutesAgo: number,
  payload: NotificationPayload,
  extra: Partial<AppNotification> = {},
): AppNotification {
  return {
    id,
    createdAt: new Date(NOW - minutesAgo * MIN).toISOString(),
    readAt: null,
    resolvedAt: null,
    important: false,
    held: false,
    channel: { id: 1, name: '포키왕' },
    payload,
    ...extra,
  };
}

const UPLOADED: NotificationPayload = {
  kind: 'CLIP_UPLOADED',
  clipTitle: '펜타킬 각',
  count: 1,
  privacy: 'PRIVATE',
};
const YOUTUBE: NotificationPayload = {
  kind: 'YOUTUBE_LINK_EXPIRED',
  youtubeChannelName: '포키왕 TV',
};

describe('describeNotification', () => {
  it('묶인 클립 업로드는 개수와 「외 n개」로 쓴다', () => {
    const v = describeNotification(
      n('a', 1, { kind: 'CLIP_UPLOADED', clipTitle: '펜타킬 각', count: 3, privacy: 'PRIVATE' }),
      NOW,
    );
    expect(v.title).toBe('클립 3개를 올렸어요');
    expect(v.detail).toBe('「펜타킬 각」 외 2개 · 비공개');
    expect(v.href).toBe('/clips/library');
  });

  it('연동 만료는 채널 연동 화면으로 간다', () => {
    const v = describeNotification(n('a', 1, YOUTUBE), NOW);
    expect(v).toMatchObject({
      title: '유튜브 연동이 끊겼어요',
      detail: '포키왕 TV · 다시 연결해야 클립을 올릴 수 있어요',
      tone: 'danger',
      href: '/settings/channels',
      goLabel: '다시 연결',
    });
  });

  it('다시보기 삭제 임박은 날짜로 남은 날을 센다(밤늦게 받아도 내일 삭제는 1일)', () => {
    const late = new Date(2026, 10, 4, 23, 50).getTime();
    const deletesAt = new Date(2026, 10, 5, 4, 0).toISOString();
    const v = describeNotification(
      n('a', 1, { kind: 'VOD_EXPIRING', streamId: 's 1', broadcastTitle: '', deletesAt }),
      late,
    );
    expect(v.title).toBe('다시보기가 1일 뒤 삭제돼요');
    expect(v.deadline).toBe('1일 남음');
    expect(v.detail).toBe('「제목 없는 방송」 · 11월 5일 (목) 삭제 · 60일 보관');
    expect(v.href).toBe('/broadcast/vod/s%201');
  });

  it('방송 종료는 길이와 카드 수를 쓴다', () => {
    const v = describeNotification(
      n('a', 1, {
        kind: 'BROADCAST_ENDED',
        streamId: 's1',
        broadcastTitle: '오늘도 롤 랭크',
        durationSec: 15_120,
        cardCount: 14,
      }),
      NOW,
    );
    expect(v.detail).toBe('「오늘도 롤 랭크」 4시간 12분 · 카드 14개');
  });

  it('편집자 초대는 갈 곳이 없다(카드 버튼으로 답한다)', () => {
    const v = describeNotification(
      n(
        'a',
        1,
        { kind: 'EDITOR_INVITED', invitationId: 7 },
        { channel: { id: 9, name: '라면왕' } },
      ),
      NOW,
    );
    expect(v.title).toBe('라면왕 채널이 편집자로 초대했어요');
    expect(v.href).toBeNull();
  });
});

describe('시각 표기', () => {
  it('오늘은 경과, 어제는 시각, 그 전은 날짜', () => {
    expect(notificationTime(new Date(NOW - 20_000).toISOString(), NOW)).toBe('방금');
    expect(notificationTime(new Date(NOW - 12 * MIN).toISOString(), NOW)).toBe('12분 전');
    expect(notificationTime(new Date(NOW - 130 * MIN).toISOString(), NOW)).toBe('2시간 전');
    expect(notificationTime(new Date(2026, 10, 3, 19, 45).toISOString(), NOW)).toBe(
      '어제 오후 7:45',
    );
    expect(notificationTime(new Date(2026, 10, 2, 9, 5).toISOString(), NOW)).toBe('11월 2일 (월)');
  });

  it('날짜 묶음은 자정 기준', () => {
    expect(dayLabel(new Date(2026, 10, 4, 0, 1).toISOString(), NOW)).toBe('오늘');
    expect(dayLabel(new Date(2026, 10, 3, 23, 59).toISOString(), NOW)).toBe('어제');
    expect(dayLabel(new Date(2026, 10, 2, 23, 59).toISOString(), NOW)).toBe('이전');
  });

  it('지난 기한은 0일', () => {
    expect(daysUntil(new Date(2026, 10, 1).toISOString(), NOW)).toBe(0);
  });
});

describe('buildPanelView', () => {
  const items = [
    n('new', 5, UPLOADED),
    n('read', 30, UPLOADED, { readAt: new Date(NOW).toISOString() }),
    n('todo-read', 40, YOUTUBE, { readAt: new Date(NOW).toISOString() }),
    n('todo-done', 50, YOUTUBE, { resolvedAt: new Date(NOW).toISOString() }),
    n('held', 3, UPLOADED, { held: true }),
    n('yesterday', 24 * 60, UPLOADED),
  ];

  it('「안 읽음」 탭은 안 읽은 것만, 풀리지 않은 할 일은 읽었어도 맨 위에 둔다', () => {
    const view = buildPanelView(items, 'unread', new Set(), NOW);
    expect(view.todos.map((x) => x.id)).toEqual(['todo-read']);
    expect(view.groups.map((g) => [g.day, g.items.map((x) => x.id)])).toEqual([
      ['오늘', ['new', 'todo-done']],
      ['어제', ['yesterday']],
    ]);
    expect(view.held.map((x) => x.id)).toEqual(['held']);
  });

  it('창을 열 때 안 읽음이던 것은 창 안에서 읽어도 닫기 전까지 「안 읽음」 탭에 남는다', () => {
    const readNow = items.map((x) =>
      x.id === 'new' ? { ...x, readAt: new Date(NOW).toISOString() } : x,
    );
    const kept = buildPanelView(readNow, 'unread', new Set(['new']), NOW);
    expect(kept.groups[0]?.items.map((x) => x.id)).toContain('new');
    const reopened = buildPanelView(readNow, 'unread', new Set(), NOW);
    expect(reopened.groups[0]?.items.map((x) => x.id)).not.toContain('new');
  });

  it('「전체」 탭은 읽은 것까지 보인다', () => {
    const view = buildPanelView(items, 'all', new Set(), NOW);
    expect(view.groups[0]?.items.map((x) => x.id)).toEqual(['new', 'read', 'todo-done']);
  });

  it('종 숫자는 받은 것 중 안 읽은 것만 센다(쌓아 둔 것 · 읽은 것 제외)', () => {
    expect(unreadCount(items)).toBe(3);
  });
});

describe('보여 주는 범위 — 최근 14일 · 최대 50개', () => {
  it('14일 안은 보이고 그 뒤는 안 보인다', () => {
    expect(isKept(n('a', 14 * 24 * 60, UPLOADED), NOW)).toBe(true);
    expect(isKept(n('a', 14 * 24 * 60 + 1, UPLOADED), NOW)).toBe(false);
  });

  it('최신 50개만 남기되, 풀리지 않은 할 일은 개수와 상관없이 모두 남기고 나머지 자리를 최신부터 채운다', () => {
    const todos = [n('todo-1', 1, YOUTUBE), n('todo-2', 13 * 24 * 60, YOUTUBE)];
    const others = Array.from({ length: 60 }, (_, i) => n(`u-${i}`, 2 + i, UPLOADED));
    const got = visibleNotifications([todos[0]!, ...others, todos[1]!], NOW);
    expect(got).toHaveLength(50);
    expect(got.map((x) => x.id)).toContain('todo-1');
    expect(got.map((x) => x.id)).toContain('todo-2');
    expect(got.filter((x) => x.id.startsWith('u-')).map((x) => x.id)).toEqual(
      others.slice(0, 48).map((x) => x.id),
    );
  });

  it('풀리지 않은 할 일은 기간이 지나도 남고, 풀린 할 일은 기간을 따른다', () => {
    expect(isKept(n('a', 60 * 24 * 60, YOUTUBE), NOW)).toBe(true);
    expect(
      isKept(n('a', 60 * 24 * 60, YOUTUBE, { resolvedAt: new Date(NOW).toISOString() }), NOW),
    ).toBe(false);
  });
});

describe('fromWire', () => {
  const base = {
    id: 'w1',
    createdAt: new Date(NOW).toISOString(),
    readAt: null,
    resolvedAt: null,
    important: true,
    held: false,
    channelId: 1,
    channelName: '포키왕',
  };

  it('모르는 종류는 버린다', () => {
    expect(fromWire({ ...base, kind: 'SOMETHING_NEW', data: {} })).toBeNull();
  });

  it('data가 비었거나(null) 빠져도 던지지 않고 빈 칸으로 옮긴다 — 한 건 때문에 목록 전체가 깨지지 않게', () => {
    expect(fromWire({ ...base, kind: 'CHAT_LINK_BROKEN', data: null })?.payload).toEqual({
      kind: 'CHAT_LINK_BROKEN',
      platform: 'CHZZK',
    });
    expect(fromWire({ ...base, kind: 'BROADCAST_ENDED' })?.payload).toEqual({
      kind: 'BROADCAST_ENDED',
      streamId: '',
      broadcastTitle: '',
      durationSec: 0,
      cardCount: 0,
    });
  });

  it('종류가 정한 칸만 옮기고 모르는 칸은 버린다', () => {
    const got = fromWire({
      ...base,
      kind: 'EDITOR_UPLOADED',
      data: { editorName: '민트초코', clipTitle: '한타', privacy: 'PUBLIC', editorEmail: 'x@y' },
    });
    expect(got?.payload).toEqual({
      kind: 'EDITOR_UPLOADED',
      editorName: '민트초코',
      clipTitle: '한타',
      privacy: 'PUBLIC',
    });
    expect(got?.channel).toEqual({ id: 1, name: '포키왕' });
  });
});
