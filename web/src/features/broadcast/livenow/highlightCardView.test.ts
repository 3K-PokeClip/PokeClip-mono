import { describe, expect, it } from 'vitest';
import { CARD_THUMBNAIL_PASS_MS } from '@/features/thumbnail/thumbnailUrl';
import {
  cardPositionUptime,
  cardViewFor,
  cardVisualFor,
  reuseHighlights,
} from './highlightCardView';
import type { CardVisual } from './useLiveDetailsMockState';
import type { HighlightStatus, LiveHighlight, LiveStream } from './useLiveMockState';

const STREAM: LiveStream = {
  title: '새벽 랭크 올리기',
  platform: '치지직',
  channelName: '게임하는너구리',
  startedNote: '오후 7:12 시작',
  uptimeSeconds: 5043,
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  editorName: '박편집',
};

function highlight(overrides: Partial<LiveHighlight> = {}): LiveHighlight {
  return {
    id: 'hl-1',
    timestamp: '1:07:50',
    title: '시청자 참여 미션 성공',
    meta: '채팅 ×4.2 급증 · 42초 · 방금',
    status: 'scored',
    source: 'auto',
    ...overrides,
  };
}

describe('cardViewFor: 계약 status 5종 → 시안 카드 상태', () => {
  // 상태 배지는 카드에서 뺐다(시안 B2-1) — 상태 · 흐림 · 버튼줄만 정한다
  const cases: Array<[HighlightStatus, string]> = [
    ['scored', 'ready'],
    ['manual', 'manual'],
    ['clipped', 'ready'],
    ['unprocessed', 'ready'],
    ['expired', 'ready'],
  ];

  it.each(cases)('%s → %s 상태', (status, state) => {
    expect(cardViewFor(highlight({ status })).state).toBe(state);
  });

  it('만료 카드만 흐리고 버튼줄이 없다', () => {
    expect(cardViewFor(highlight({ status: 'expired' })).dimmed).toBe(true);
    expect(cardViewFor(highlight({ status: 'expired' })).showActions).toBe(false);
    expect(cardViewFor(highlight({ status: 'scored' })).dimmed).toBe(false);
    expect(cardViewFor(highlight({ status: 'scored' })).showActions).toBe(true);
  });
});

describe('cardVisualFor — 아는 카드는 표기값 그대로', () => {
  const visuals: Record<string, CardVisual> = {
    'hl-1': {
      duration: '0:42',
      reason: '채팅 급증',
      posPercent: 94,
      spark: [0.2, 0.8],
      timeAgo: '방금',
      progress: 62,
    },
  };

  it('맵에 있으면 그 값을 쓴다', () => {
    expect(cardVisualFor(highlight(), STREAM, visuals)).toEqual({
      duration: '0:42',
      reason: '채팅 급증',
      posPercent: 94,
      spark: [0.2, 0.8],
      timeAgo: '방금',
      progress: 62,
    });
  });
});

describe('cardVisualFor — 모르는 카드는 계약 필드에서 세운다', () => {
  // 이 폴백이 POK-180(실 SSE 카드는 id가 전부 낯설다)과 수동 마킹이 함께 서는 자리다

  it('감지 사유는 source에서 만든다 — meta 문자열은 파싱하지 않는다', () => {
    expect(cardVisualFor(highlight({ id: 'unknown' }), STREAM, {}).reason).toBe('자동 감지');
    expect(cardVisualFor(highlight({ id: 'unknown', source: 'manual' }), STREAM, {}).reason).toBe(
      '수동 마킹',
    );
  });

  it('타임라인 위치는 방송 경과 대비 카드 시각이다 — 시안 표기값과 같은 수가 나온다', () => {
    expect(cardVisualFor(highlight({ id: 'unknown' }), STREAM, {}).posPercent).toBe(81);
    expect(
      cardVisualFor(highlight({ id: 'unknown', timestamp: '0:58:41' }), STREAM, {}).posPercent,
    ).toBe(70);
    expect(
      cardVisualFor(highlight({ id: 'unknown', timestamp: '0:47:22' }), STREAM, {}).posPercent,
    ).toBe(56);
  });

  it('시각을 못 읽거나 방송 길이가 0이면 오른쪽 끝이다', () => {
    expect(
      cardVisualFor(highlight({ id: 'unknown', timestamp: '방금' }), STREAM, {}).posPercent,
    ).toBe(100);
    expect(
      cardVisualFor(highlight({ id: 'unknown' }), { ...STREAM, uptimeSeconds: 0 }, {}).posPercent,
    ).toBe(100);
  });

  it('길이 · 경과는 카드 목록이 실어 준 값을 쓴다(사진 위 「1:00」 · 「3분 전」)', () => {
    const resolved = cardVisualFor(
      highlight({ id: 'unknown', lengthSec: 60, ago: '3분 전' }),
      STREAM,
      {},
    );
    expect(resolved.duration).toBe('1:00');
    expect(resolved.timeAgo).toBe('3분 전');
    expect(cardVisualFor(highlight({ id: 'unknown', lengthSec: 45 }), STREAM, {}).duration).toBe(
      '0:45',
    );
  });

  it('알 수 없는 값은 지어내지 않고 감춘다 — 실연동 뒤 거짓 표기로 남는다', () => {
    const resolved = cardVisualFor(highlight({ id: 'unknown' }), STREAM, {});
    expect(resolved.duration).toBeNull();
    expect(resolved.spark).toBeNull();
    expect(resolved.timeAgo).toBeNull();
    expect(resolved.progress).toBeNull();
  });
});

describe('cardPositionUptime: 카드 눈금에 넘기는 라이브 경과', () => {
  const positionOfNewestCard = (uptime: number) =>
    Math.round((uptime / cardPositionUptime(uptime)) * 100);

  it.each([1, 30, 61, 74, 101, 301, 899, 1501, 5043, 43_199])(
    '%d초에 방금 생긴 카드는 끝에서 1% 안에 선다',
    (uptime) => {
      expect(positionOfNewestCard(uptime)).toBeGreaterThanOrEqual(99);
    },
  );

  it('방송 초반에도 오차가 작다: 15초 단위로 올리면 61초의 카드가 80% 자리에 섰다', () => {
    expect(positionOfNewestCard(61)).toBe(100);
  });

  it('한 단위 안에서는 같은 값이다: 값이 그대로면 카드가 다시 그려지지 않는다', () => {
    expect(cardPositionUptime(3_601)).toBe(cardPositionUptime(3_620));
  });

  it('경과가 0이면 0이다', () => {
    expect(cardPositionUptime(0)).toBe(0);
  });
});

describe('reuseHighlights', () => {
  const NOW = Date.parse('2026-10-31T01:00:00Z');
  /** 카드 사진 주소. 서명 시각만 달라도 글자가 다르다(서버가 목록마다 새로 서명한다) */
  const photo = (signed: string) =>
    `https://bucket.s3.amazonaws.com/cards/23.jpg?X-Amz-Date=${signed}&X-Amz-Signature=sig-${signed}`;

  it('내용이 그대로면 앞 배열을 그대로 돌려준다: 매초 새 배열이면 카드 패널의 memo가 무력해진다', () => {
    const prev = [highlight({ id: 'a' }), highlight({ id: 'b' })];

    const next = reuseHighlights(prev, [highlight({ id: 'a' }), highlight({ id: 'b' })], NOW);

    expect(next).toBe(prev);
  });

  it('표기가 바뀐 카드만 새 객체다: 나머지 카드는 다시 그리지 않는다', () => {
    const prev = [highlight({ id: 'a', meta: '5초 전' }), highlight({ id: 'b', meta: '3분 전' })];

    const next = reuseHighlights(
      prev,
      [highlight({ id: 'a', meta: '6초 전' }), highlight({ id: 'b', meta: '3분 전' })],
      NOW,
    );

    expect(next).not.toBe(prev);
    expect(next[0]?.meta).toBe('6초 전');
    expect(next[1]).toBe(prev[1]);
  });

  it('카드가 늘거나 순서가 바뀌면 새 배열이다', () => {
    const a = highlight({ id: 'a' });
    const b = highlight({ id: 'b' });

    expect(reuseHighlights([a], [a, b], NOW)).toEqual([a, b]);
    const swapped = reuseHighlights([a, b], [highlight({ id: 'b' }), highlight({ id: 'a' })], NOW);
    expect(swapped[0]).toBe(b);
    expect(swapped[1]).toBe(a);
  });

  it('같은 사진을 새로 서명한 주소는 바뀐 것이 아니다: 3초마다 온 카드가 전부 다시 그려진다', () => {
    const prev = [highlight({ id: 'a', thumbnailUrl: photo('20261031T005930Z') })];

    const next = reuseHighlights(
      prev,
      [highlight({ id: 'a', thumbnailUrl: photo('20261031T010000Z') })],
      NOW,
    );

    expect(next).toBe(prev);
  });

  it('1분이 지나면 새 주소를 넘긴다: 못 받은 사진은 새 주소가 와야 다시 시도한다', () => {
    const oldUrl = photo('20261031T000000Z');
    const newUrl = photo('20261031T000100Z');
    const prev = [highlight({ id: 'a', thumbnailUrl: oldUrl })];

    const next = reuseHighlights(
      prev,
      [highlight({ id: 'a', thumbnailUrl: newUrl })],
      Date.parse('2026-10-31T00:00:00Z') + CARD_THUMBNAIL_PASS_MS,
    );

    expect(next[0]?.thumbnailUrl).toBe(newUrl);
  });

  it('사진이 생기거나 사라지면 바뀐 것이다', () => {
    const none = highlight({ id: 'a', thumbnailUrl: null });
    const shown = highlight({ id: 'a', thumbnailUrl: photo('20261031T010000Z') });

    expect(reuseHighlights([none], [shown], NOW)[0]).toBe(shown);
    expect(reuseHighlights([shown], [none], NOW)[0]).toBe(none);
  });
});
