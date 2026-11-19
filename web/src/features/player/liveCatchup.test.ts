import { describe, expect, it, vi } from 'vitest';
import {
  catchupStep,
  initialCatchupState,
  isWebKitPlayback,
  LIVE_CATCHUP_POLICY,
  liveCatchupPolicy,
  liveCatchupStartMode,
  readHlsCatchup,
  WEBKIT_LIVE_CATCHUP_POLICY,
  watchLiveCatchup,
  type CatchupHls,
  type LiveCatchupAction,
  type LiveCatchupMode,
  type LiveCatchupPolicy,
  type LiveCatchupReading,
  type LiveCatchupSample,
  type LiveCatchupState,
} from './liveCatchup';

// 라이브를 보는 중인 시청자만 앱이 직접 따라잡는다(POK-583). 판정은 순수 함수라 가짜 표본 숫자로 재고, 영상 노드에 거는 결합기만
// 값을 손으로 바꾸는 영상 노드 하나로 잰다. 기대 숫자(0.5 · 1.05 · 1.1 · 0.1 · 1초 · 10초 · 8초 · 2 × TD)는 계획의 규칙
// 그대로다 — 모듈 상수를 가져오지 않아 상수가 바뀌면 시험이 알린다.

/** 목록 목표 조각 길이(초) — 정체 문턱은 이 배수다 */
const TD = 4;
/** 지금 위치(초). 라이브 동기 위치는 여기서 「지연 − 목표」 만큼 앞이다(hls.js 가 정상 갱신 중 그렇게 잡는다) */
const AT = 100;
const TARGET = 1.25;

/** 「지연 − 목표 = excessS」 인 정상 갱신 표본. over 가 나머지를 덮는다 */
function behind(excessS: number, over: Partial<LiveCatchupSample> = {}): LiveCatchupSample {
  return {
    nowMs: 0,
    latencyS: TARGET + excessS,
    targetLatencyS: TARGET,
    liveSyncPositionS: AT + excessS,
    currentTimeS: AT,
    bufferAheadS: 3,
    list: { ageS: 0.5, targetDurationS: TD },
    paused: false,
    seeking: false,
    handoffConfirming: false,
    ...over,
  };
}

/** 목표 0 이라 지연이 곧 「지연 − 목표」 인 표본 — 지연 자체가 초과 문턱 숫자와 같아진다 */
function exactlyBehind(excessS: number): LiveCatchupSample {
  return behind(excessS, { latencyS: excessS, targetLatencyS: 0 });
}

/** 표본들을 차례로 먹여 행동 목록과 마지막 상태를 돌려준다 */
function run(
  start: LiveCatchupState,
  samples: readonly LiveCatchupSample[],
  policy: LiveCatchupPolicy = LIVE_CATCHUP_POLICY,
): { actions: LiveCatchupAction[]; state: LiveCatchupState } {
  let state = start;
  const actions: LiveCatchupAction[] = [];
  for (const sample of samples) {
    const step = catchupStep(state, sample, policy);
    state = step.state;
    actions.push(step.action);
  }
  return { actions, state };
}

/** 배속을 바꾼 횟수 — 점프는 배속을 1 로 되돌리므로 배속 중이던 점프도 센다 */
function rateChanges(start: LiveCatchupState, actions: readonly LiveCatchupAction[]): number {
  let rate = start.rate;
  let changes = 0;
  for (const action of actions) {
    const next = action.kind === 'rate' ? action.rate : action.kind === 'jump' ? 1 : rate;
    if (next !== rate) changes++;
    rate = next;
  }
  return changes;
}

const NONE: LiveCatchupAction = { kind: 'none' };

/** 배속 rate 로 따라잡는 중인 상태 */
function catchingUp(mode: LiveCatchupMode, rate: number): LiveCatchupState {
  return { ...initialCatchupState(mode), rate };
}

describe('liveCatchupStartMode', () => {
  it('라이브 첫 진입은 full · 넘김 소스는 rate · 되감기와 다시보기는 off', () => {
    expect(liveCatchupStartMode({ kind: 'live', startAtMs: null })).toBe('full');
    expect(liveCatchupStartMode({ kind: 'live', startAtMs: 1_000 })).toBe('rate');
    expect(liveCatchupStartMode({ kind: 'rewind', startAtMs: null })).toBe('off');
    expect(liveCatchupStartMode({ kind: 'vod', startAtMs: 1_000 })).toBe('off');
  });
});

describe('catchupStep — 배속', () => {
  it('T1 off 는 많이 밀려도 아무것도 하지 않는다', () => {
    const { actions } = run(initialCatchupState('off'), [behind(5), behind(13)]);
    expect(actions).toEqual([NONE, NONE]);
  });

  it('T2 초과 0.5초 이하면 그대로 · 넘으면 1.05 · 1초 이상이면 1.1', () => {
    const cases: Array<[number, LiveCatchupAction]> = [
      [0.4, NONE],
      [0.5, NONE],
      [0.6, { kind: 'rate', rate: 1.05 }],
      [1, { kind: 'rate', rate: 1.1 }],
      [1.5, { kind: 'rate', rate: 1.1 }],
    ];
    for (const [excess, want] of cases) {
      expect(
        catchupStep(initialCatchupState('full'), behind(excess), LIVE_CATCHUP_POLICY).action,
      ).toEqual(want);
    }
  });

  it('T3 켠 배속은 중간에 바꾸지 않고 0.1초 이하에서만 끈다 — 한 번 따라잡는 데 변경은 정확히 둘', () => {
    const start = initialCatchupState('full');
    const excesses = [1.5, 1.2, 0.8, 0.4, 0.2, 0.15, 0.1, 0.3, 0.45];
    const { actions } = run(
      start,
      excesses.map((e) => exactlyBehind(e)),
    );

    expect(actions[0]).toEqual({ kind: 'rate', rate: 1.1 });
    // 1초 밑 · 0.5초 밑으로 내려와도 1.1 그대로다
    expect(actions.slice(1, 6)).toEqual([NONE, NONE, NONE, NONE, NONE]);
    expect(actions[6]).toEqual({ kind: 'rate', rate: 1 });
    // 끈 뒤 0.5초 밑의 초과로는 다시 켜지 않는다
    expect(actions.slice(7)).toEqual([NONE, NONE]);
    expect(rateChanges(start, actions)).toBe(2);
  });

  it('T3 실제 목표 1.25 · 지연 1.35(초과 0.1)면 그 표본에서 끈다 — 뺄셈 오차로 한 표본 늦지 않는다', () => {
    // 부동소수점으로 1.35 − 1.25 = 0.10000000000000009 다
    expect(
      catchupStep(catchingUp('full', 1.1), behind(0.1, { latencyS: 1.35 }), LIVE_CATCHUP_POLICY)
        .action,
    ).toEqual({ kind: 'rate', rate: 1 });
  });

  it('T3 1.05 로 켠 배속도 초과가 1초를 넘게 자라도 1.1 로 바꾸지 않는다', () => {
    const { actions } = run(initialCatchupState('full'), [behind(0.6), behind(1.4)]);
    expect(actions).toEqual([{ kind: 'rate', rate: 1.05 }, NONE]);
  });

  it('T4 앞 버퍼가 1초 밑이면 배속을 끄고 · 켜지도 않는다', () => {
    expect(
      catchupStep(catchingUp('full', 1.1), behind(1.5, { bufferAheadS: 0.9 }), LIVE_CATCHUP_POLICY)
        .action,
    ).toEqual({ kind: 'rate', rate: 1 });
    expect(
      catchupStep(
        initialCatchupState('full'),
        behind(1.5, { bufferAheadS: 0.9 }),
        LIVE_CATCHUP_POLICY,
      ).action,
    ).toEqual(NONE);
    // 정확히 1초면 켠다
    expect(
      catchupStep(
        initialCatchupState('full'),
        behind(1.5, { bufferAheadS: 1 }),
        LIVE_CATCHUP_POLICY,
      ).action,
    ).toEqual({ kind: 'rate', rate: 1.1 });
  });

  it('T7 멈춤 · 시크 중에는 많이 밀려도 아무것도 하지 않는다', () => {
    for (const over of [{ paused: true }, { seeking: true }]) {
      expect(
        catchupStep(initialCatchupState('full'), behind(13, over), LIVE_CATCHUP_POLICY).action,
      ).toEqual(NONE);
    }
  });

  it('목록 정보(details)가 없는 표본은 무행동이고, 배속 중이었으면 1 로 되돌린다', () => {
    expect(
      catchupStep(initialCatchupState('full'), behind(13, { list: null }), LIVE_CATCHUP_POLICY)
        .action,
    ).toEqual(NONE);
    expect(
      catchupStep(catchingUp('full', 1.1), behind(13, { list: null }), LIVE_CATCHUP_POLICY).action,
    ).toEqual({
      kind: 'rate',
      rate: 1,
    });
  });
});

describe('catchupStep — 점프', () => {
  it('T5 지연 10초 넘게 밀리면 full 은 라이브 동기 위치로 건너뛰고 rate 는 1.1 배속만', () => {
    const sample = behind(10.5 - TARGET);
    expect(catchupStep(initialCatchupState('full'), sample, LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'jump',
      toS: sample.liveSyncPositionS,
    });
    expect(
      catchupStep(
        initialCatchupState('rate'),
        { ...sample, handoffConfirming: true },
        LIVE_CATCHUP_POLICY,
      ).action,
    ).toEqual({ kind: 'rate', rate: 1.1 });
  });

  it('T5 지연이 정확히 10초면 건너뛰지 않고 배속으로 좁힌다', () => {
    expect(
      catchupStep(initialCatchupState('full'), behind(10 - TARGET), LIVE_CATCHUP_POLICY).action,
    ).toEqual({
      kind: 'rate',
      rate: 1.1,
    });
  });

  it('T5 점프 뒤 상태의 배속은 1 이다(배속 중이던 점프는 배속도 되돌린다)', () => {
    const step = catchupStep(catchingUp('full', 1.1), behind(12), LIVE_CATCHUP_POLICY);
    expect(step.action.kind).toBe('jump');
    expect(step.state.rate).toBe(1);
  });

  it('점프는 앞으로 1초 넘게 갈 때만 — 제자리 · 뒤로는 건너뛰지 않는다', () => {
    const at = (distance: number) => behind(12, { liveSyncPositionS: AT + distance });
    expect(catchupStep(initialCatchupState('full'), at(0.5), LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'rate',
      rate: 1.1,
    });
    expect(catchupStep(initialCatchupState('full'), at(1), LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'rate',
      rate: 1.1,
    });
    expect(catchupStep(initialCatchupState('full'), at(-2), LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'rate',
      rate: 1.1,
    });
    expect(catchupStep(initialCatchupState('full'), at(1.5), LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'jump',
      toS: AT + 1.5,
    });
  });

  it('라이브 동기 위치를 모르면 건너뛰지 않는다', () => {
    expect(
      catchupStep(
        initialCatchupState('full'),
        behind(12, { liveSyncPositionS: null }),
        LIVE_CATCHUP_POLICY,
      ).action,
    ).toEqual({ kind: 'rate', rate: 1.1 });
  });

  it('T5c 목표 7.25 · 지연 11 · 정상 갱신 — full 은 건너뛰고, 앞으로 1초 안이면 1.1 배속', () => {
    const sample = behind(11 - 7.25, { latencyS: 11, targetLatencyS: 7.25 });
    expect(catchupStep(initialCatchupState('full'), sample, LIVE_CATCHUP_POLICY).action).toEqual({
      kind: 'jump',
      toS: AT + 3.75,
    });
    expect(
      catchupStep(
        initialCatchupState('full'),
        { ...sample, liveSyncPositionS: AT + 0.5 },
        LIVE_CATCHUP_POLICY,
      ).action,
    ).toEqual({ kind: 'rate', rate: 1.1 });
  });

  it('T6 점프 뒤 8초 안에는 다시 건너뛰지 않는다', () => {
    const { actions } = run(initialCatchupState('full'), [
      behind(12, { nowMs: 0 }),
      behind(12, { nowMs: 7_999 }),
      behind(12, { nowMs: 8_000 }),
    ]);
    expect(actions.map((a) => a.kind)).toEqual(['jump', 'rate', 'jump']);
  });
});

describe('catchupStep — 목록 정체', () => {
  it('T5b 목록이 2 × TD 넘게 안 자라면 지연이 커도 배속 · 점프 0 이고, 배속 중이었으면 1 로', () => {
    const stale = { list: { ageS: 2 * TD + 0.5, targetDurationS: TD } };
    expect(
      catchupStep(initialCatchupState('full'), behind(13, stale), LIVE_CATCHUP_POLICY).action,
    ).toEqual(NONE);
    expect(
      catchupStep(catchingUp('full', 1.1), behind(13, stale), LIVE_CATCHUP_POLICY).action,
    ).toEqual({
      kind: 'rate',
      rate: 1,
    });
  });

  it('T5b 정확히 2 × TD 면 정체가 아니다', () => {
    const edge = behind(13, { list: { ageS: 2 * TD, targetDurationS: TD } });
    expect(catchupStep(initialCatchupState('full'), edge, LIVE_CATCHUP_POLICY).action.kind).toBe(
      'jump',
    );
  });

  it('정체에서 풀리려면 age < TD 표본이 둘 연속이어야 한다 — 하나로는 무행동', () => {
    const ages = [9, 3, 3, 3];
    const { actions } = run(
      initialCatchupState('full'),
      ages.map((ageS) => behind(1.5, { list: { ageS, targetDurationS: TD } })),
    );
    expect(actions).toEqual([NONE, NONE, { kind: 'rate', rate: 1.1 }, NONE]);
  });

  it('TD 이상 · 2 × TD 이하의 age 는 회복으로 치지 않고 연속을 끊는다', () => {
    const ages = [9, 3, 5, 3, 6, 3, 3];
    const { actions } = run(
      initialCatchupState('full'),
      ages.map((ageS) => behind(1.5, { list: { ageS, targetDurationS: TD } })),
    );
    expect(actions.slice(0, 6)).toEqual([NONE, NONE, NONE, NONE, NONE, NONE]);
    expect(actions[6]).toEqual({ kind: 'rate', rate: 1.1 });
  });

  it('정체 뒤 전진 한 번에 이어 재생이 멎어 다음 표본이 TD 넘게 늦으면 풀리지 않고, 다음 전진의 두 표본째에 풀린다', () => {
    // hls.js 의 age 는 전진 때 0 에서 시계대로 오른다 — 표본마다 (지금 − 마지막 전진) 초
    const at = (nowMs: number, advancedAtMs: number) =>
      behind(1.5, { nowMs, list: { ageS: (nowMs - advancedAtMs) / 1000, targetDurationS: TD } });
    const { actions } = run(initialCatchupState('full'), [
      at(0, -9_000),
      at(250, -250),
      // 재생이 4초 넘게 멎어 timeupdate 가 끊겼다 — age 4.75 ≥ TD 라 연속이 끊긴다
      at(4_500, -250),
      at(4_750, 4_750),
      at(5_000, 4_750),
    ]);
    expect(actions).toEqual([NONE, NONE, NONE, NONE, { kind: 'rate', rate: 1.1 }]);
  });

  it('띄엄띄엄 자라는 목록(9초마다 한 번)에서 배속 변경은 한 주기에 켬 · 끔 둘을 넘지 않는다', () => {
    const start = initialCatchupState('full');
    // 4Hz 표본 · 목록이 9초마다 한 번 자란다 → age 가 0 에서 9 까지 오르다 0 으로
    const samples = Array.from({ length: 4 * 27 }, (_, i) =>
      behind(1.5, { nowMs: i * 250, list: { ageS: (i * 0.25) % 9, targetDurationS: TD } }),
    );
    const { actions } = run(start, samples);
    expect(rateChanges(start, actions)).toBeLessThanOrEqual(2 * 3);
  });
});

describe('catchupStep — 넘김 확인', () => {
  it('확인 중에는 full 로 켜 있어도 건너뛰지 않는다(게이트가 모드보다 먼저)', () => {
    for (const mode of ['rate', 'full'] as const) {
      const step = catchupStep(
        initialCatchupState(mode),
        behind(13, { handoffConfirming: true }),
        LIVE_CATCHUP_POLICY,
      );
      expect(step.action).toEqual({ kind: 'rate', rate: 1.1 });
    }
  });

  it('확인 중 넘긴 위치 근처(지연 7)에서 목표에 닿아도 full 로 오르지 않아, 이어 10초 넘게 밀려도 점프 0', () => {
    // 넘긴 위치 24 · 되감기 끝 28 · 현재 25 · 라이브 끝 32 · 목표 7.25 — 지연 7 은 목표 안쪽이다(계획 R3 반례)
    const near = behind(0, {
      latencyS: 7,
      targetLatencyS: 7.25,
      currentTimeS: 25,
      liveSyncPositionS: 32 - 7.25,
      handoffConfirming: true,
    });
    // 확인이 끝나지 않은 채 4초 멎어 라이브 끝이 36 으로 자랐다 — 지연 11 · 동기 위치는 3.75초 앞
    const stalled = {
      ...near,
      nowMs: 4_000,
      latencyS: 11,
      liveSyncPositionS: 36 - 7.25,
    };
    const { actions, state } = run(initialCatchupState('rate'), [near, stalled]);
    expect(actions).toEqual([NONE, { kind: 'rate', rate: 1.1 }]);
    expect(state.mode).toBe('rate');
  });

  it('확인이 끝난 표본에서 rate 는 full 로 오르고, 그 표본부터 건너뛴다', () => {
    const confirming = catchupStep(
      initialCatchupState('rate'),
      behind(13, { handoffConfirming: true }),
      LIVE_CATCHUP_POLICY,
    );
    expect(confirming.state.mode).toBe('rate');

    const confirmed = catchupStep(
      confirming.state,
      behind(13, { nowMs: 250 }),
      LIVE_CATCHUP_POLICY,
    );
    expect(confirmed.state.mode).toBe('full');
    expect(confirmed.action.kind).toBe('jump');
  });
});

describe('isWebKitPlayback', () => {
  const ua = {
    safariMac:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
    safariIphone:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
    chromeIos:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/131.0.6778.73 Mobile/15E148 Safari/604.1',
    firefoxIos:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/132.0 Mobile/15E148 Safari/605.1.15',
    edgeIos:
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 EdgiOS/131.0.2903.68 Mobile/15E148 Safari/605.1.15',
    chromeMac:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    edgeWindows:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0',
    chromeAndroid:
      'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    samsungAndroid:
      'Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/27.0 Chrome/125.0.0.0 Mobile Safari/537.36',
    firefoxMac:
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0',
  };

  it('T8 Safari 와 iOS 의 모든 브라우저는 WebKit · Chromium 계열과 Firefox 데스크톱은 아니다', () => {
    const cases: Array<[string, boolean]> = [
      [ua.safariMac, true],
      [ua.safariIphone, true],
      [ua.chromeIos, true],
      [ua.firefoxIos, true],
      [ua.edgeIos, true],
      [ua.chromeMac, false],
      [ua.edgeWindows, false],
      [ua.chromeAndroid, false],
      [ua.samsungAndroid, false],
      [ua.firefoxMac, false],
    ];
    for (const [userAgent, want] of cases) {
      expect(isWebKitPlayback({ userAgent }), userAgent).toBe(want);
    }
  });

  it('T8 판별할 수 없으면(빈 UA) WebKit 이 아니다 — Chromium 정책으로 간다', () => {
    expect(isWebKitPlayback({ userAgent: '' })).toBe(false);
  });
});

describe('WebKit 정책(Q1 (다) — 점프만)', () => {
  it('T9 지연 10초 이하면 얼마나 밀려도 배속을 켜지 않는다 — 같은 표본에서 Chromium 정책은 켠다', () => {
    // 목표 0 이라 지연 = 초과 — 9초 초과도 지연 10초 밑이라 점프 자리가 아니다
    for (const excess of [3, 5, 9]) {
      expect(
        catchupStep(initialCatchupState('full'), exactlyBehind(excess), WEBKIT_LIVE_CATCHUP_POLICY)
          .action,
        `초과 ${excess}`,
      ).toEqual(NONE);
      expect(
        catchupStep(initialCatchupState('full'), exactlyBehind(excess), LIVE_CATCHUP_POLICY).action,
        `초과 ${excess}`,
      ).toEqual({ kind: 'rate', rate: 1.1 });
    }
    const { actions, state } = run(
      initialCatchupState('full'),
      [3, 5, 9].map((excess) => exactlyBehind(excess)),
      WEBKIT_LIVE_CATCHUP_POLICY,
    );
    expect(rateChanges(initialCatchupState('full'), actions)).toBe(0);
    expect(state.rate).toBe(1);
  });

  it('T9 full 에서 지연 10초를 넘으면 Chromium 과 같은 조건 · 쿨다운으로 건너뛰고, 쿨다운 동안에도 배속은 켜지 않는다', () => {
    const late = (nowMs: number) => behind(10.5 - TARGET, { nowMs });
    const { actions } = run(
      initialCatchupState('full'),
      [late(0), late(1_000), late(8_000)],
      WEBKIT_LIVE_CATCHUP_POLICY,
    );
    expect(actions).toEqual([
      { kind: 'jump', toS: AT + 10.5 - TARGET },
      NONE,
      { kind: 'jump', toS: AT + 10.5 - TARGET },
    ]);
    // 같은 표본에서 Chromium 은 쿨다운 동안 배속으로 좁힌다
    expect(run(initialCatchupState('full'), [late(0), late(1_000)]).actions[1]).toEqual({
      kind: 'rate',
      rate: 1.1,
    });
  });

  it('T9 rate(넘김 확인 중)에서는 지연 10.5초도 아무것도 하지 않고, 확인이 끝나면 full 로 올라 건너뛴다', () => {
    const confirming = catchupStep(
      initialCatchupState('rate'),
      behind(10.5 - TARGET, { handoffConfirming: true }),
      WEBKIT_LIVE_CATCHUP_POLICY,
    );
    expect(confirming.action).toEqual(NONE);
    expect(confirming.state.mode).toBe('rate');

    const confirmed = catchupStep(
      confirming.state,
      behind(10.5 - TARGET, { nowMs: 250 }),
      WEBKIT_LIVE_CATCHUP_POLICY,
    );
    expect(confirmed.state.mode).toBe('full');
    expect(confirmed.action.kind).toBe('jump');
  });

  it('T9 배속이 걸린 채 이어진 상태면 1 로 되돌린다', () => {
    expect(
      catchupStep(catchingUp('full', 1.1), exactlyBehind(3), WEBKIT_LIVE_CATCHUP_POLICY).action,
    ).toEqual({ kind: 'rate', rate: 1 });
  });
});

// --- 결합기 ---

interface MediaState {
  currentTime: number;
  paused: boolean;
  seeking: boolean;
  playbackRate: number;
  /** [시작, 끝] 초 구간들 — video.buffered */
  buffered: Array<[number, number]>;
}

/** 값을 손으로 바꾸는 영상 노드. currentTime · playbackRate 쓰기는 횟수와 함께 남긴다 */
function fakeVideo(initial: Partial<MediaState> = {}) {
  const state: MediaState = {
    currentTime: AT,
    paused: false,
    seeking: false,
    playbackRate: 1,
    buffered: [[0, AT + 3]],
    ...initial,
  };
  const writes = { currentTime: 0, playbackRate: 0 };
  const video = document.createElement('video');
  for (const key of ['paused', 'seeking'] as const) {
    Object.defineProperty(video, key, { get: () => state[key], configurable: true });
  }
  for (const key of ['currentTime', 'playbackRate'] as const) {
    Object.defineProperty(video, key, {
      get: () => state[key],
      set: (value: number) => {
        state[key] = value;
        writes[key]++;
      },
      configurable: true,
    });
  }
  const range = (i: number) => {
    const found = state.buffered[i];
    if (found === undefined) throw new RangeError(`buffered 구간 ${i} 없음`);
    return found;
  };
  Object.defineProperty(video, 'buffered', {
    get: () => ({
      length: state.buffered.length,
      start: (i: number) => range(i)[0],
      end: (i: number) => range(i)[1],
    }),
    configurable: true,
  });
  return { video, state, writes };
}

/** 재생 위치 AT 에서 「지연 − 목표 = excessS」 인 재생기 쪽 값 */
function reading(excessS: number, over: Partial<LiveCatchupReading> = {}): LiveCatchupReading {
  return {
    liveEdgeS: AT + TARGET + excessS,
    targetLatencyS: TARGET,
    liveSyncPositionS: AT + excessS,
    list: { ageS: 0.5, targetDurationS: TD },
    handoffConfirming: false,
    ...over,
  };
}

function setup(initial: Partial<MediaState> = {}) {
  const media = fakeVideo(initial);
  const current = { reading: reading(1.5) };
  const clock = { ms: 0 };
  const read = vi.fn(() => current.reading);
  const watch = watchLiveCatchup(media.video, {
    read,
    policy: LIVE_CATCHUP_POLICY,
    now: () => clock.ms,
  });
  const tick = () => media.video.dispatchEvent(new Event('timeupdate'));
  return { ...media, current, clock, read, watch, tick };
}

describe('watchLiveCatchup', () => {
  it('켜기 전에는 재생기 값을 읽지도 않고 배속도 건드리지 않는다', () => {
    const { tick, read, writes } = setup();
    tick();
    expect(read).not.toHaveBeenCalled();
    expect(writes.playbackRate).toBe(0);
  });

  it('켜면 timeupdate 마다 판정해 배속을 걸고, 앞 버퍼는 지금 자리를 담은 구간에서 잰다', () => {
    const { tick, watch, state, current } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);

    current.reading = reading(0.05);
    tick();
    expect(state.playbackRate).toBe(1);

    // 앞 버퍼 0.5초 — 켜지 않는다
    state.buffered = [[0, AT + 0.5]];
    current.reading = reading(1.5);
    tick();
    expect(state.playbackRate).toBe(1);

    // 지금 자리가 받아 둔 구간 밖이면 앞 버퍼 0 — 뒤쪽 구간은 더하지 않는다
    state.buffered = [
      [0, AT - 1],
      [AT + 1, AT + 10],
    ];
    tick();
    expect(state.playbackRate).toBe(1);

    // 지금 자리를 담은 구간이 둘째여도 그 구간에서 잰다
    state.buffered = [
      [0, AT - 5],
      [AT - 1, AT + 3],
    ];
    tick();
    expect(state.playbackRate).toBe(1.1);
  });

  it('지연 10초를 넘으면 라이브 동기 위치로 옮기고 배속을 1 로 둔다', () => {
    const { tick, watch, state, writes, current } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);

    current.reading = reading(12);
    tick();
    expect(state.currentTime).toBe(AT + 12);
    expect(state.playbackRate).toBe(1);
    expect(writes.currentTime).toBe(1);
  });

  it('건 배속이 영상에서 사건 없이 1 로 풀리면(recoverMediaError 의 미디어 load) 다음 표본에서 다시 건다', () => {
    const { tick, watch, state } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);

    // 쓰기 기록을 남기지 않고 바꾼다 — 미디어 load 는 ratechange 없이 기본 배속으로 되돌린다(실측 (다))
    state.playbackRate = 1;
    tick();
    expect(state.playbackRate).toBe(1.1);
  });

  it('건 배속을 밖(확장 · 브라우저 메뉴)에서 1 이 아닌 배속으로 바꾸면 다음 표본에서 덮어쓰지 않는다', () => {
    const { tick, watch, state, writes } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);
    expect(writes.playbackRate).toBe(1);

    // 쓰기 기록을 남기지 않고 바꾼다 — 사람이 고른 배속이다
    state.playbackRate = 1.25;
    tick();
    expect(state.playbackRate).toBe(1.25);
    expect(writes.playbackRate).toBe(1);
  });

  it('재생 위치나 잰 지연이 유한한 수가 아니면 그 표본은 배속 · 점프를 하지 않는다', () => {
    const { tick, watch, state, writes, current } = setup();
    watch.arm('full');

    // 지연 12초 표본이지만 재생 위치가 NaN
    state.currentTime = Number.NaN;
    current.reading = reading(12);
    tick();
    expect(writes.currentTime).toBe(0);
    expect(writes.playbackRate).toBe(0);

    // 재생 위치는 수지만 라이브 끝 추정 위치가 NaN 이라 지연이 NaN
    state.currentTime = AT;
    current.reading = reading(12, { liveEdgeS: Number.NaN });
    tick();
    expect(writes.currentTime).toBe(0);
    expect(writes.playbackRate).toBe(0);
  });

  it('멈춘 영상 · 시크 중인 영상에서는 판정하지 않는다', () => {
    const { tick, watch, state } = setup({ paused: true });
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1);
    state.paused = false;
    state.seeking = true;
    tick();
    expect(state.playbackRate).toBe(1);
  });

  it('disarm 은 배속을 1 로 되돌리고, 그 뒤 timeupdate 로 다시 켜지 않는다', () => {
    const { tick, watch, state, read } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);

    watch.disarm();
    expect(state.playbackRate).toBe(1);
    const reads = read.mock.calls.length;
    tick();
    expect(state.playbackRate).toBe(1);
    expect(read.mock.calls.length).toBe(reads);
  });

  it('disarm 뒤 다시 arm 하면 같은 지연에서 다시 켠다', () => {
    const { tick, watch, state } = setup();
    watch.arm('full');
    tick();
    watch.disarm();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);
  });

  it('켜진 중 다시 arm 하면 걸어 둔 배속을 잇는다 — 새로 잡았다면 초과 0.8 에서 1.05 로 다시 건다', () => {
    const { tick, watch, state, writes, current } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);
    const rateWrites = writes.playbackRate;

    watch.arm('rate');
    current.reading = reading(0.8);
    tick();
    expect(state.playbackRate).toBe(1.1);
    expect(writes.playbackRate).toBe(rateWrites);
  });

  it('켜진 중 다시 arm 하면 점프 시각을 잇는다 — 점프 1초 뒤 다시 켜도 쿨다운 8초 안이라 건너뛰지 않는다', () => {
    const { tick, watch, state, writes, current, clock } = setup();
    watch.arm('full');
    current.reading = reading(12);
    tick();
    expect(writes.currentTime).toBe(1);

    clock.ms = 1_000;
    watch.arm('full');
    current.reading = reading(12, {
      liveEdgeS: state.currentTime + TARGET + 12,
      liveSyncPositionS: state.currentTime + 12,
    });
    tick();
    expect(writes.currentTime).toBe(1);
  });

  it('켜진 중 다시 arm 하면 정체 판정을 잇는다 — 회복 표본 하나로는 풀리지 않는다', () => {
    const { tick, watch, state, current } = setup();
    watch.arm('full');
    current.reading = reading(1.5, { list: { ageS: 9, targetDurationS: TD } });
    tick();
    expect(state.playbackRate).toBe(1);

    watch.arm('full');
    current.reading = reading(1.5, { list: { ageS: 0.25, targetDurationS: TD } });
    tick();
    expect(state.playbackRate).toBe(1);
  });

  it('arm(off) 은 disarm 과 같다', () => {
    const { tick, watch, state } = setup();
    watch.arm('full');
    tick();
    watch.arm('off');
    expect(state.playbackRate).toBe(1);
    tick();
    expect(state.playbackRate).toBe(1);
  });

  it('dispose 는 배속을 1 로 되돌리고, 그 뒤의 사건 · arm 은 아무것도 하지 않는다', () => {
    const { tick, watch, state, writes } = setup();
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1.1);

    watch.dispose();
    expect(state.playbackRate).toBe(1);
    const rateWrites = writes.playbackRate;
    watch.arm('full');
    tick();
    expect(state.playbackRate).toBe(1);
    expect(writes.playbackRate).toBe(rateWrites);
  });

  it('스스로 건너뛰어도(currentTime 쓰기 · seeking 사건) 꺼지지 않는다 — 자동 점프는 사람 시크가 아니다', () => {
    const { tick, watch, state, current, clock, video } = setup();
    watch.arm('full');
    current.reading = reading(12);
    tick();
    expect(state.currentTime).toBe(AT + 12);
    video.dispatchEvent(new Event('seeking'));
    video.dispatchEvent(new Event('seeked'));

    clock.ms = 1_000;
    state.buffered = [[0, state.currentTime + 3]];
    current.reading = reading(1.5, {
      liveEdgeS: state.currentTime + TARGET + 1.5,
      liveSyncPositionS: state.currentTime + 1.5,
    });
    tick();
    expect(state.playbackRate).toBe(1.1);
  });
});

describe('liveCatchupPolicy', () => {
  it('WebKit 재생이면 WebKit 정책 · 아니면 Chromium 정책', () => {
    const safari =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15';
    const chrome =
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
    expect(liveCatchupPolicy({ userAgent: safari })).toBe(WEBKIT_LIVE_CATCHUP_POLICY);
    expect(liveCatchupPolicy({ userAgent: chrome })).toBe(LIVE_CATCHUP_POLICY);
  });
});

describe('readHlsCatchup — 재생기 값을 판정할 수 없으면 목록을 null 로', () => {
  /** hls.js 재생기에서 읽히는 몫만 든 흉내 — over 가 덮는다 */
  function hlsLike(
    over: Partial<Record<keyof CatchupHls, unknown>> = {},
    details: Record<string, unknown> = {},
  ): CatchupHls {
    return {
      targetLatency: 1.25,
      liveSyncPosition: 30,
      latestLevelDetails: { live: true, edge: 30.5, age: 0.5, targetduration: TD, ...details },
      ...over,
    } as unknown as CatchupHls;
  }

  it('정상 값은 그대로(라이브 끝은 목록 끝 + age) · 넘김 확인 여부를 싣는다', () => {
    expect(readHlsCatchup(hlsLike(), true)).toEqual({
      liveEdgeS: 31,
      targetLatencyS: 1.25,
      liveSyncPositionS: 30,
      list: { ageS: 0.5, targetDurationS: TD },
      handoffConfirming: true,
    });
  });

  it.each([
    ['재생기가 없다(내렸다)', null],
    ['목록이 없다', hlsLike({ latestLevelDetails: null })],
    ['목록이 끝났다(ENDLIST)', hlsLike({}, { live: false })],
    ['목표를 아직 모른다(null)', hlsLike({ targetLatency: null })],
    ['목록 끝이 수가 아니다', hlsLike({}, { edge: undefined })],
    ['age 가 수가 아니다', hlsLike({}, { age: Number.NaN })],
    ['TD 가 수가 아니다', hlsLike({}, { targetduration: undefined })],
  ] as const)('%s', (...[, hls]) => {
    expect(readHlsCatchup(hls, false).list).toBeNull();
  });

  it('라이브 동기 위치를 모르면 null — 건너뛰지 않는다', () => {
    expect(readHlsCatchup(hlsLike({ liveSyncPosition: null }), false).liveSyncPositionS).toBeNull();
  });
});
