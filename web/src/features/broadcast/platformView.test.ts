import { describe, expect, it } from 'vitest';
import {
  ON_AIR_WINDOW_MS,
  onAirLabel,
  onAirPlatforms,
  primaryInfo,
  primaryPlatform,
  withPlatformObservation,
} from './platformView';

// 켜진 플랫폼 · 대표 플랫폼 규칙(POK-397). 홈 · 라이브가 이 함수들로 같은 값을 고른다.

const NOW = Date.parse('2026-10-24T03:00:00Z');
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe('켜진 플랫폼', () => {
  it('관측이 3분 안인 것만 켜짐이다: 꺼진 플랫폼도 서버 묶음에 남는다', () => {
    const list = [
      { platform: 'CHZZK', viewers: 900, observedAt: ago(10 * 60_000) },
      { platform: 'SOOP', viewers: 40, observedAt: ago(30_000) },
    ];
    expect(onAirPlatforms(list, NOW).map((p) => p.platform)).toEqual(['SOOP']);
    expect(onAirLabel(list, NOW)).toBe('SOOP');
  });

  it('3분 경계는 켜짐이고 그 너머는 꺼짐이다', () => {
    const edge = [
      { platform: 'CHZZK', observedAt: ago(ON_AIR_WINDOW_MS) },
      { platform: 'SOOP', observedAt: ago(ON_AIR_WINDOW_MS + 1) },
    ];
    expect(onAirPlatforms(edge, NOW).map((p) => p.platform)).toEqual(['CHZZK']);
  });

  it('지금 켜진 것이 없으면(끝난 방송) 마지막 관측 무렵 켜져 있던 것이다: 서버 목록 제목과 같은 기준이다', () => {
    // 치지직을 잠깐 켰다 끄고 SOOP으로 끝까지 방송했다. 끝난 뒤 보면 둘 다 3분 넘게 지났지만 끝날 때 켜진 것은 SOOP뿐이다
    const list = [
      { platform: 'CHZZK', viewers: 900, observedAt: ago(90 * 60_000) },
      { platform: 'SOOP', viewers: 40, observedAt: ago(60 * 60_000) },
    ];
    expect(onAirLabel(list, NOW)).toBe('SOOP');
    expect(primaryPlatform(list, NOW)?.platform).toBe('SOOP');
  });

  it('관측 시각이 하나도 없으면 받은 것을 그대로 쓴다: 플랫폼이 사라지지 않게', () => {
    const list = [
      { platform: 'CHZZK', observedAt: null },
      { platform: 'SOOP', observedAt: null },
    ];
    expect(onAirLabel(list, NOW)).toBe('치지직 · SOOP');
  });

  it('둘 다 켜졌으면 태그에 둘 다 보인다(서버 순서)', () => {
    const list = [
      { platform: 'CHZZK', observedAt: ago(20_000) },
      { platform: 'SOOP', observedAt: ago(20_000) },
    ];
    expect(onAirLabel(list, NOW)).toBe('치지직 · SOOP');
  });
});

describe('대표 플랫폼', () => {
  it('켜진 것 중 시청자가 가장 많은 쪽이다', () => {
    const list = [
      { platform: 'CHZZK', viewers: 30, observedAt: ago(20_000) },
      { platform: 'SOOP', viewers: 500, observedAt: ago(20_000) },
    ];
    expect(primaryPlatform(list, NOW)?.platform).toBe('SOOP');
  });

  it('꺼진 플랫폼은 시청자가 많아도 대표가 아니다', () => {
    const list = [
      { platform: 'CHZZK', viewers: 9000, observedAt: ago(10 * 60_000) },
      { platform: 'SOOP', viewers: 5, observedAt: ago(20_000) },
    ];
    expect(primaryPlatform(list, NOW)?.platform).toBe('SOOP');
  });

  it('시청자 수가 같거나 모르면 앞(치지직)이다', () => {
    const tie = [
      { platform: 'CHZZK', viewers: 10, observedAt: ago(20_000) },
      { platform: 'SOOP', viewers: 10, observedAt: ago(20_000) },
    ];
    expect(primaryPlatform(tie, NOW)?.platform).toBe('CHZZK');
    const unknown = [
      { platform: 'CHZZK', viewers: null, observedAt: ago(20_000) },
      { platform: 'SOOP', viewers: null, observedAt: ago(20_000) },
    ];
    expect(primaryPlatform(unknown, NOW)?.platform).toBe('CHZZK');
  });

  it('플랫폼 값이 없으면(옛 서버) 없다', () => {
    expect(primaryPlatform(undefined, NOW)).toBeNull();
    expect(primaryPlatform([], NOW)).toBeNull();
  });
});

describe('대표 플랫폼의 방송 정보', () => {
  const series = [
    { observedAt: ago(120_000), viewers: 900, platform: 'CHZZK' },
    { observedAt: ago(100_000), viewers: 30, platform: 'SOOP' },
    { observedAt: ago(60_000), viewers: 880, platform: 'CHZZK' },
    { observedAt: ago(40_000), viewers: 35, platform: 'SOOP' },
  ];

  it('SOOP로만 켠 방송은 제목 · 카테고리 · 시청자 수 · 기록이 SOOP 값이다: 가장 새 관측(latest)이 치지직이어도', () => {
    const view = primaryInfo(
      {
        latest: {
          title: '치지직 제목',
          tags: ['치지직태그'],
          category: 'LoL',
          viewers: 880,
          observedAt: ago(20_000),
        },
        platforms: [
          {
            platform: 'CHZZK',
            title: '치지직 제목',
            category: 'LoL',
            viewers: 880,
            observedAt: ago(10 * 60_000),
          },
          {
            platform: 'SOOP',
            title: 'SOOP 제목',
            category: '리그 오브 레전드',
            viewers: 35,
            observedAt: ago(40_000),
          },
        ],
        series,
      },
      NOW,
    );
    expect(view.platform).toBe('SOOP');
    expect(view.latest).toEqual({
      title: 'SOOP 제목',
      tags: [],
      category: '리그 오브 레전드',
      viewers: 35,
    });
    expect(view.series.map((p) => p.viewers)).toEqual([30, 35]);
  });

  it('태그는 가장 새 관측이 대표 플랫폼의 그 관측일 때만 쓴다: 태그는 플랫폼마다 따로 오지 않는다', () => {
    const view = primaryInfo(
      {
        latest: {
          title: 'SOOP 제목',
          tags: ['숲태그'],
          category: null,
          viewers: 35,
          observedAt: ago(40_000),
        },
        platforms: [{ platform: 'SOOP', title: 'SOOP 제목', viewers: 35, observedAt: ago(40_000) }],
        series,
      },
      NOW,
    );
    expect(view.latest?.tags).toEqual(['숲태그']);
  });

  it('플랫폼 값이 없는 옛 서버면 받은 그대로다', () => {
    const old = [{ observedAt: ago(40_000), viewers: 12 }];
    const view = primaryInfo(
      {
        latest: { title: '제목', tags: ['t'], category: 'c', viewers: 12 },
        series: old,
      },
      NOW,
    );
    expect(view).toEqual({
      platform: null,
      latest: { title: '제목', tags: ['t'], category: 'c', viewers: 12 },
      series: old,
    });
  });
});

describe('실시간으로 온 관측', () => {
  const soop = {
    platform: 'SOOP',
    title: '숲 제목',
    category: 'LoL',
    viewers: 57,
    observedAt: ago(60_000),
  };
  const chzzk = {
    platform: 'CHZZK',
    title: '치지직 제목',
    viewers: 1842,
    observedAt: ago(20 * 60_000),
  };

  it('그 플랫폼의 값을 새로 얹는다: 꺼졌던 플랫폼이 다시 켜지면 대표가 바뀐다', () => {
    const next = withPlatformObservation([chzzk, soop], {
      time: ago(1_000),
      title: '치지직 새 제목',
      category: '저스트 채팅',
      viewers: 3000,
      platform: 'CHZZK',
    });
    expect(next?.find((p) => p.platform === 'CHZZK')).toEqual({
      platform: 'CHZZK',
      title: '치지직 새 제목',
      category: '저스트 채팅',
      viewers: 3000,
      observedAt: ago(1_000),
    });
    expect(next?.find((p) => p.platform === 'SOOP')).toBe(soop);
    expect(primaryPlatform(next, NOW)?.platform).toBe('CHZZK');
  });

  it('제목이 빈 관측은 시청자 수만 바꾸고 제목 · 카테고리는 앞 값을 둔다: 서버와 같은 규칙이다', () => {
    const next = withPlatformObservation([soop], {
      time: ago(1_000),
      title: ' ',
      category: null,
      viewers: 70,
      platform: 'SOOP',
    });
    expect(next?.[0]).toMatchObject({ title: '숲 제목', category: 'LoL', viewers: 70 });
  });

  it('처음 보는 플랫폼은 덧붙이고, 플랫폼이 없는 관측(옛 서버)은 얹지 않는다', () => {
    expect(
      withPlatformObservation(undefined, {
        time: ago(1_000),
        title: '숲 제목',
        category: null,
        viewers: 1,
        platform: 'SOOP',
      })?.map((p) => p.platform),
    ).toEqual(['SOOP']);
    const list = [soop];
    expect(
      withPlatformObservation(list, { time: ago(1_000), title: 'x', category: null, viewers: 1 }),
    ).toBe(list);
  });
});

describe('codex 1판 지적 (PR #368)', () => {
  it('실시간으로 처음 들어온 플랫폼도 서버 순서(치지직 → SOOP)에 선다: 시청자가 같을 때 치지직이 대표다', () => {
    const soop = { platform: 'SOOP', viewers: 10, observedAt: ago(20_000) };
    const next = withPlatformObservation([soop], {
      time: ago(10_000),
      title: '치지직 제목',
      category: null,
      viewers: 10,
      platform: 'CHZZK',
    });
    expect(next?.map((p) => p.platform)).toEqual(['CHZZK', 'SOOP']);
    expect(primaryPlatform(next, NOW)?.platform).toBe('CHZZK');
  });

  it('두 플랫폼의 관측 시각이 같으면 태그를 쓰지 않는다: 가장 새 관측이 어느 플랫폼 것인지 알 수 없다', () => {
    const at = ago(20_000);
    const view = primaryInfo(
      {
        latest: {
          title: '치지직 제목',
          tags: ['치지직태그'],
          category: null,
          viewers: 5,
          observedAt: at,
        },
        platforms: [
          { platform: 'CHZZK', title: '치지직 제목', viewers: 5, observedAt: at },
          { platform: 'SOOP', title: '숲 제목', viewers: 50, observedAt: at },
        ],
        series: [],
      },
      NOW,
    );
    expect(view.platform).toBe('SOOP');
    expect(view.latest?.tags).toEqual([]);
  });
});
