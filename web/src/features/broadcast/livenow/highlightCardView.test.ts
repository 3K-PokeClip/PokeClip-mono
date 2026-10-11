import { describe, expect, it } from 'vitest';
import { cardViewFor, cardVisualFor } from './highlightCardView';
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

describe('cardViewFor — 계약 status 6종 → 시안 카드 상태', () => {
  const cases: Array<[HighlightStatus, string, string]> = [
    ['scored', 'ready', '검토 대기'],
    ['manual', 'manual', '수동 마킹'],
    ['editing', 'processing', '편집 중'],
    ['clipped', 'ready', '클립 완료'],
    ['unprocessed', 'ready', '미처리'],
    ['expired', 'ready', '만료'],
  ];

  it.each(cases)('%s → %s 상태에 "%s" 배지', (status, state, label) => {
    const view = cardViewFor(highlight({ status }));
    expect(view.state).toBe(state);
    expect(view.badgeLabel).toBe(label);
  });

  it('편집 중 카드는 누가 잡고 있는지까지 배지에 적는다', () => {
    expect(cardViewFor(highlight({ status: 'editing', editorName: '박편집' })).badgeLabel).toBe(
      '편집 중 · 박편집',
    );
  });

  it('이름을 모르면 상태만 말한다 — "편집 중 · undefined"가 되면 안 된다', () => {
    expect(cardViewFor(highlight({ status: 'editing' })).badgeLabel).toBe('편집 중');
  });

  it('만료 카드만 흐리고, 만료·편집 중에는 버튼줄이 없다', () => {
    expect(cardViewFor(highlight({ status: 'expired' })).dimmed).toBe(true);
    expect(cardViewFor(highlight({ status: 'expired' })).showActions).toBe(false);
    expect(cardViewFor(highlight({ status: 'editing' })).showActions).toBe(false);
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

  it('알 수 없는 값은 지어내지 않고 감춘다 — 실연동 뒤 거짓 표기로 남는다', () => {
    const resolved = cardVisualFor(highlight({ id: 'unknown' }), STREAM, {});
    expect(resolved.duration).toBeNull();
    expect(resolved.spark).toBeNull();
    expect(resolved.timeAgo).toBeNull();
    expect(resolved.progress).toBeNull();
  });
});
