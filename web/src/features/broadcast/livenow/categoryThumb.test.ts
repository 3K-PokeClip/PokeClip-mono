import { describe, expect, it } from 'vitest';
import { categoryAt, categorySpansOf, focusAtMs, type CategorySpan } from './categoryThumb';

const T0 = Date.parse('2026-10-25T10:00:00Z');
const min = (n: number) => T0 + n * 60_000;
const iso = (ms: number) => new Date(ms).toISOString();

const JUST = 'https://img.test/just.jpg';
const LOL = 'https://img.test/lol.jpg';

const span = (
  platform: string,
  fromMs: number,
  untilMs: number | null,
  name: string | null,
  imageUrl: string | null,
): CategorySpan => ({ platform, fromMs, untilMs, name, imageUrl });

describe('categoryAt — 그 시각의 카테고리 구간', () => {
  const spans = [
    span('CHZZK', min(1), min(10), '저스트 채팅', JUST),
    span('CHZZK', min(10), min(20), '리그 오브 레전드', LOL),
    span('CHZZK', min(20), null, null, null),
  ];

  it('시각을 모르면(null) 그 플랫폼의 마지막 구간이다', () => {
    expect(categoryAt(spans.slice(0, 2), null, 'CHZZK')?.name).toBe('리그 오브 레전드');
  });

  it('from ≤ t < until인 구간이다: 바꾼 순간부터 새 카테고리', () => {
    expect(categoryAt(spans, min(10), 'CHZZK')?.name).toBe('리그 오브 레전드');
    expect(categoryAt(spans, min(10) - 1, 'CHZZK')?.name).toBe('저스트 채팅');
  });

  it('끝(until)이 없는 구간은 지금도 그 값이다', () => {
    expect(categoryAt(spans, min(500), 'CHZZK')).toEqual(spans[2]);
  });

  it('첫 구간 앞 · 구간 사이 빈 곳 · 마지막 구간 뒤는 모른다(null)', () => {
    expect(categoryAt(spans, min(1) - 1, 'CHZZK')).toBeNull();
    const gapped = [
      span('CHZZK', min(1), min(5), '저스트 채팅', JUST),
      span('CHZZK', min(8), min(9), '리그 오브 레전드', LOL),
    ];
    expect(categoryAt(gapped, min(6), 'CHZZK')).toBeNull();
    expect(categoryAt(gapped, min(9), 'CHZZK')).toBeNull();
  });

  it('동시 방송이면 그 플랫폼의 구간만 본다', () => {
    const both = [
      span('CHZZK', min(1), null, '저스트 채팅', JUST),
      span('SOOP', min(1), null, '리그 오브 레전드', LOL),
    ];
    expect(categoryAt(both, min(5), 'CHZZK')?.imageUrl).toBe(JUST);
    expect(categoryAt(both, min(5), 'SOOP')?.imageUrl).toBe(LOL);
    expect(categoryAt(both, null, 'CHZZK')?.imageUrl).toBe(JUST);
  });

  it('대표 플랫폼을 모르면(옛 서버) 플랫폼을 가리지 않고 그 시각의 마지막 구간이다', () => {
    const both = [
      span('CHZZK', min(1), null, '저스트 채팅', JUST),
      span('SOOP', min(3), null, '리그 오브 레전드', LOL),
    ];
    expect(categoryAt(both, min(5), null)?.imageUrl).toBe(LOL);
    expect(categoryAt(both, min(2), null)?.imageUrl).toBe(JUST);
  });

  it('구간이 없으면 null이다', () => {
    expect(categoryAt([], null, 'CHZZK')).toBeNull();
    expect(categoryAt([], min(3), 'CHZZK')).toBeNull();
  });
});

describe('categorySpansOf — 시점별 기록 창구(POK-462)를 화면 자료형으로', () => {
  const segment = {
    platform: 'CHZZK',
    from: iso(min(1)),
    until: iso(min(10)) as string | null,
    title: '랭크',
    category: '리그 오브 레전드' as string | null,
    categoryImageUrl: LOL as string | null,
    tags: ['롤'],
  };

  it('답이 없으면 빈 목록이다', () => {
    expect(categorySpansOf(null)).toEqual([]);
    expect(categorySpansOf(undefined)).toEqual([]);
  });

  it('구간마다 플랫폼 · 시각 · 카테고리 이름 · 사진 주소를 옮기고 from 순으로 늘어놓는다', () => {
    expect(
      categorySpansOf({
        segments: [
          {
            ...segment,
            from: iso(min(10)),
            until: null,
            category: 'Just Chatting',
            categoryImageUrl: null,
          },
          segment,
        ],
        truncated: false,
      }),
    ).toEqual([
      span('CHZZK', min(1), min(10), '리그 오브 레전드', LOL),
      span('CHZZK', min(10), null, 'Just Chatting', null),
    ]);
  });

  it('시각을 못 읽는 구간은 버린다', () => {
    expect(
      categorySpansOf({
        segments: [
          { ...segment, from: 'not-a-time' },
          { ...segment, until: 'bad' },
        ],
        truncated: false,
      }),
    ).toEqual([]);
  });
});

describe('focusAtMs — 화면이 보고 있는 시각(채팅과 같은 축)', () => {
  const base = {
    status: 'live' as const,
    playheadMs: null,
    liveRewind: null,
    timeBaseMs: null,
    originMs: null,
    startedAt: min(0),
  };

  it('라이브 실시간이면 null(마지막 구간)이다', () => {
    expect(focusAtMs(base)).toBeNull();
  });

  it('라이브를 되감았으면 되감은 자리의 실제 시각이다', () => {
    expect(
      focusAtMs({
        ...base,
        liveRewind: { atMs: min(5), edgeAtMs: min(12), behindSeconds: 420 },
      }),
    ).toBe(min(5));
  });

  it('라이브에는 지난 방송 재생 위치를 쓰지 않는다', () => {
    expect(focusAtMs({ ...base, playheadMs: 60_000 })).toBeNull();
  });

  it('지난 방송은 기준점 + 재생 위치다: 녹화 시작이 기준점보다 먼저다', () => {
    const ended = { ...base, status: 'ended' as const, originMs: min(0) + 2_000 };
    expect(focusAtMs({ ...ended, playheadMs: 60_000 })).toBe(min(1) + 2_000);
    expect(focusAtMs({ ...ended, playheadMs: 60_000, timeBaseMs: min(0) + 5_000 })).toBe(
      min(1) + 5_000,
    );
  });

  it('지난 방송에서 재생 위치를 모르면 null(마지막 구간 = 방송 끝)이다', () => {
    expect(focusAtMs({ ...base, status: 'ended' })).toBeNull();
  });

  it('상태를 모르면 null이다', () => {
    expect(focusAtMs({ ...base, status: 'unknown', playheadMs: 60_000 })).toBeNull();
  });
});
