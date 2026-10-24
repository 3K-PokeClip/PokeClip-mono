import { describe, expect, it } from 'vitest';
import { segmentAt, viewedSegment, type TimelineSegment } from './broadcastInfoAt';

const T = Date.parse('2026-10-25T10:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString();

function seg(
  platform: string,
  fromMin: number,
  untilMin: number | null,
  title: string,
  category: string | null = 'LoL',
): TimelineSegment {
  return {
    platform,
    from: iso(T + fromMin * 60_000),
    until: untilMin === null ? null : iso(T + untilMin * 60_000),
    title,
    category,
    categoryImageUrl: null,
    tags: ['롤'],
  };
}

describe('segmentAt', () => {
  const segments = [
    seg('CHZZK', 0, 10, '앞 제목'),
    seg('SOOP', 1, null, '숲 제목', '토크'),
    seg('CHZZK', 10, null, '뒤 제목', 'Just Chatting'),
  ];

  it('같은 플랫폼에서 from ≤ t < until인 구간을 고른다 — until은 다음 구간의 첫 순간이라 그 순간은 다음 구간이다', () => {
    expect(segmentAt(segments, 'CHZZK', T)?.title).toBe('앞 제목');
    expect(segmentAt(segments, 'CHZZK', T + 10 * 60_000 - 1)?.title).toBe('앞 제목');
    expect(segmentAt(segments, 'CHZZK', T + 10 * 60_000)?.title).toBe('뒤 제목');
  });

  it('until이 없으면 지금도 그 값이다', () => {
    expect(segmentAt(segments, 'CHZZK', T + 999 * 60_000)?.category).toBe('Just Chatting');
  });

  it('플랫폼으로 고른다 — 두 플랫폼 구간이 같은 시각을 덮으면 대표 플랫폼 것이다', () => {
    expect(segmentAt(segments, 'SOOP', T + 5 * 60_000)?.title).toBe('숲 제목');
    expect(segmentAt(segments, 'CHZZK', T + 5 * 60_000)?.title).toBe('앞 제목');
  });

  it('첫 관측 전이면 가장 이른 구간이다 — 화면이 최신 관측으로 떨어지면 나중 제목이 방송 시작 자리에 뜬다', () => {
    expect(segmentAt(segments, 'CHZZK', T - 1)?.title).toBe('앞 제목');
    expect(segmentAt(segments, 'SOOP', T - 1)?.title).toBe('숲 제목');
    expect(segmentAt([], 'CHZZK', T)).toBeNull();
  });

  it('구간 목록이 잘렸으면(오래된 쪽을 버림) 첫 구간 앞은 모른다 — 가장 이른 구간은 첫 관측이 아니다', () => {
    expect(segmentAt(segments, 'CHZZK', T - 1, true)).toBeNull();
    expect(segmentAt(segments, 'CHZZK', T, true)?.title).toBe('앞 제목');
  });

  it('플랫폼을 모르면(옛 서버 · 정보 없음) 그 시각의 아무 플랫폼 구간이라도 쓴다', () => {
    expect(segmentAt(segments, null, T + 5 * 60_000)?.title).toBe('앞 제목');
    expect(segmentAt([seg('SOOP', 0, null, '숲')], null, T)?.title).toBe('숲');
  });

  it('대표 플랫폼이 그 시각에 없으면 다른 플랫폼 구간을 쓴다 — 치지직을 늦게 켠 방송의 앞부분', () => {
    expect(
      segmentAt([seg('SOOP', 0, null, '숲'), seg('CHZZK', 30, null, '치지직')], 'CHZZK', T + 60_000)
        ?.title,
    ).toBe('숲');
  });
});

describe('viewedSegment', () => {
  const timeline = { segments: [seg('CHZZK', 0, null, '제목')], truncated: false };

  it('실시간(보는 시각 없음)이면 null — 화면은 최신 관측을 그린다', () => {
    expect(viewedSegment(timeline, 'CHZZK', null)).toBeNull();
  });

  it('기록을 아직 못 읽었으면 null', () => {
    expect(viewedSegment(undefined, 'CHZZK', T)).toBeNull();
  });

  it('보는 시각이 있으면 그 시점 구간이고, 잘림 표시를 넘긴다', () => {
    expect(viewedSegment(timeline, 'CHZZK', T)?.title).toBe('제목');
    expect(viewedSegment({ ...timeline, truncated: true }, 'CHZZK', T - 1)).toBeNull();
  });
});
