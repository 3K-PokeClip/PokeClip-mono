import { describe, expect, it } from 'vitest';
import type { RewindMode, RewindStatusResult } from '@/api/rewindStatus';
import { RESUME_WAIT_MS } from '@/features/player/sessionShared';
import {
  cardSceneLabelOf,
  editorSourceView,
  nextEditorSourceState,
  type EditorSourceEvent,
  type EditorSourceState,
} from './editorSourceState';

// D3 원천 상태 판정 순서(설계 prw2 14.2 표 — 위에서 처음 맞는 줄)를 시험 표로 둔다. 4 · 5행(카드 자리 대 목록 범위)은 상태에서
// 파생하는 보기(editorSourceView)가 맡고, 나머지는 답 · 실패 사건을 쌓는 nextEditorSourceState 가 맡는다. R58 은 플레이어
// onLiveStreamPoll(rewindSwitch.ts:403-415)과 같은 규칙이다.

const ROUND_URL = 'https://cdn.test/dvr/s/rounds/r1/index.m3u8';
const STREAM_URL = 'https://cdn.test/dvr/s/index.m3u8';
const CHECKING: EditorSourceState = { kind: 'checking' };
const LIVE_READY: EditorSourceState = { kind: 'ready', mode: 'live', url: STREAM_URL };
/** 스트림 경로가 다른 방송(R58)이라 선 3행 */
const R58_FINALIZING: EditorSourceState = { kind: 'finalizing', cause: 'stream' };
const LIVE_FAILURE = { mode: 'live', listUrl: STREAM_URL } as const;

/** 회차 경로(ending · vod ∧ ¬ready)가 under 를 덮어 선 3행 */
function roundFinalizing(under: EditorSourceState): EditorSourceState {
  return { kind: 'finalizing', cause: 'round', under };
}

function ok(mode: RewindMode | null, ready: boolean, url: string | null): RewindStatusResult {
  return {
    kind: 'ok',
    status: { ready, mode, url, firstPdtMs: url ? 1000 : null, headPdtMs: url ? 9000 : null },
  };
}

function round(result: RewindStatusResult, waitedMs = 0): EditorSourceEvent {
  return { type: 'round', result, clock: { nowMs: 10_000 + waitedMs, unknownSinceMs: 10_000 } };
}

function stream(result: RewindStatusResult, id: string | null): EditorSourceEvent {
  return {
    type: 'stream',
    result,
    currentX: id === null ? { kind: 'unknown' } : { kind: 'known', id },
    roundId: 'r1',
  };
}

/** 조각 실패 사건. listUrl = 그 조각을 받은 목록 주소(기본 스트림 목록) */
function segment(
  failure: 'forbiddenAfterRefresh' | 'gone',
  facts: { listed: boolean; live: boolean; roundGone?: boolean; listUrl?: string },
): EditorSourceEvent {
  return { type: 'segment', failure, roundGone: false, listUrl: STREAM_URL, ...facts };
}

describe('nextEditorSourceState — D3 판정 순서', () => {
  it.each<[string, EditorSourceState, EditorSourceEvent, EditorSourceState['kind']]>([
    ['1 출입증 410 → expired', LIVE_READY, { type: 'accessExpired' }, 'expired'],
    [
      '2 회차 경로 404(유예 뒤) → goneOrNone',
      CHECKING,
      round({ kind: 'none' }, RESUME_WAIT_MS),
      'goneOrNone',
    ],
    ['2 회차 경로 ended → goneOrNone', LIVE_READY, round(ok('ended', false, null)), 'goneOrNone'],
    ['3 회차 경로 ending → finalizing', LIVE_READY, round(ok('ending', false, null)), 'finalizing'],
    [
      '3 회차 경로 vod ∧ ¬ready → finalizing',
      CHECKING,
      round(ok('vod', false, null)),
      'finalizing',
    ],
    [
      '3 스트림 경로가 다른 방송(R58) → finalizing',
      LIVE_READY,
      stream(ok('live', true, STREAM_URL), 'r2'),
      'finalizing',
    ],
    [
      '6 조각 403 → 갱신 → 또 403 · seq 빠짐 · 방송 중 → outsideDvr',
      LIVE_READY,
      segment('forbiddenAfterRefresh', { listed: false, live: true }),
      'outsideDvr',
    ],
    [
      '7 같은 갈래에서 회차 경로 404 → goneOrNone',
      LIVE_READY,
      segment('forbiddenAfterRefresh', { listed: true, live: true, roundGone: true }),
      'goneOrNone',
    ],
    [
      '8 같은 갈래 · seq 아직 목록에 있고 회차 정상 → access',
      LIVE_READY,
      segment('forbiddenAfterRefresh', { listed: true, live: true }),
      'access',
    ],
    [
      '9 조각 404 · 빠짐 · 방송 중 → outsideDvr',
      LIVE_READY,
      segment('gone', { listed: false, live: true }),
      'outsideDvr',
    ],
    [
      '9 조각 404 · 그 밖 → goneOrNone',
      LIVE_READY,
      segment('gone', { listed: true, live: true }),
      'goneOrNone',
    ],
  ])('%s', (_name, prev, event, kind) => {
    expect(nextEditorSourceState(prev, event).kind).toBe(kind);
  });

  it('출입증 만료는 뒤에 어떤 답이 와도 그대로다(1행이 가장 위)', () => {
    const expired = nextEditorSourceState(LIVE_READY, { type: 'accessExpired' });

    expect(nextEditorSourceState(expired, round(ok('vod', true, ROUND_URL))).kind).toBe('expired');
    expect(
      nextEditorSourceState(expired, segment('gone', { listed: true, live: false })).kind,
    ).toBe('expired');
  });

  it('없음(goneOrNone)은 뒤 답으로 풀리지 않고 만료만 그 위에 선다', () => {
    const gone = nextEditorSourceState(CHECKING, round(ok('ended', false, null)));

    expect(nextEditorSourceState(gone, round(ok('vod', true, ROUND_URL))).kind).toBe('goneOrNone');
    expect(nextEditorSourceState(gone, { type: 'accessExpired' }).kind).toBe('expired');
  });

  it('회차 경로 vod ∧ ready 면 그 목록 주소로 선다', () => {
    expect(nextEditorSourceState(CHECKING, round(ok('vod', true, ROUND_URL)))).toEqual({
      kind: 'ready',
      mode: 'vod',
      url: ROUND_URL,
    });
  });

  it('회차 경로 404 가 유예 안이면 아직 모름이라 직전 상태를 둔다', () => {
    expect(nextEditorSourceState(LIVE_READY, round({ kind: 'none' }, RESUME_WAIT_MS - 1))).toEqual(
      LIVE_READY,
    );
  });

  it('잠시 불가는 직전 상태를 두고 아직 아무 답도 없을 때만 unavailable 이다', () => {
    expect(nextEditorSourceState(LIVE_READY, round({ kind: 'unavailable' }))).toEqual(LIVE_READY);
    expect(nextEditorSourceState(CHECKING, round({ kind: 'unavailable' })).kind).toBe(
      'unavailable',
    );
  });

  it('엣지 dvr-floor: 방송 중 하한 아래 seq 의 404 는 목록을 다시 받아 빠졌으면 outsideDvr · 남았으면 goneOrNone 이고 403 갈래로 가지 않는다', () => {
    expect(
      nextEditorSourceState(LIVE_READY, segment('gone', { listed: false, live: true })).kind,
    ).toBe('outsideDvr');
    expect(
      nextEditorSourceState(LIVE_READY, segment('gone', { listed: true, live: true })).kind,
    ).toBe('goneOrNone');
    expect(
      nextEditorSourceState(LIVE_READY, segment('gone', { listed: false, live: false })).kind,
    ).toBe('goneOrNone');
  });
});

describe('nextEditorSourceState — R58 같은 방송 규칙', () => {
  it('A 가 끝나고 같은 키로 B 가 시작하면(지금 방송 B) 스트림 url 을 받지 않는다', () => {
    const next = nextEditorSourceState(CHECKING, stream(ok('live', true, STREAM_URL), 'r2'));

    expect(next.kind).toBe('finalizing');
  });

  it('지금 방송 번호를 모르면 이번 스트림 답을 버린다', () => {
    expect(nextEditorSourceState(CHECKING, stream(ok('live', true, STREAM_URL), null))).toEqual(
      CHECKING,
    );
  });

  it("지금 방송이 없다('')면 통과해 ready ∧ live url 을 쓴다", () => {
    expect(nextEditorSourceState(CHECKING, stream(ok('live', true, STREAM_URL), ''))).toEqual(
      LIVE_READY,
    );
  });

  it('같은 방송이면 ready ∧ live url 을 쓴다', () => {
    expect(nextEditorSourceState(CHECKING, stream(ok('live', true, STREAM_URL), 'r1'))).toEqual(
      LIVE_READY,
    );
  });

  it('조각 실패로 정한 상태는 스트림 폴링이 덮지 않는다', () => {
    const access = nextEditorSourceState(
      LIVE_READY,
      segment('forbiddenAfterRefresh', { listed: true, live: true }),
    );

    expect(nextEditorSourceState(access, stream(ok('live', true, STREAM_URL), 'r1')).kind).toBe(
      'access',
    );
  });
});

/** 사건을 차례로 더한 상태 */
function run(events: readonly EditorSourceEvent[], from: EditorSourceState = LIVE_READY) {
  return events.reduce(nextEditorSourceState, from);
}

describe('nextEditorSourceState — 판정 순서는 사건 도착 순서와 무관하다', () => {
  const ending = round(ok('ending', false, null));
  const vodReady = round(ok('vod', true, ROUND_URL));
  const foreign = stream(ok('live', true, STREAM_URL), 'r2');

  it('3행 finalizing 은 조각 404 보다 위라 순서를 바꿔도 같고 뒤 vod 준비로 풀린다', () => {
    const gone = segment('gone', { listed: false, live: false });

    const covered = roundFinalizing({ kind: 'goneOrNone', segment: LIVE_FAILURE });

    expect(run([ending, gone])).toEqual(covered);
    expect(run([gone, ending])).toEqual(covered);
    expect(run([ending, gone, vodReady])).toEqual({ kind: 'ready', mode: 'vod', url: ROUND_URL });
    expect(run([gone, ending, vodReady])).toEqual({ kind: 'ready', mode: 'vod', url: ROUND_URL });
  });

  it('3행 finalizing 은 조각 403(seq 가 목록에 남음)보다 위라 순서를 바꿔도 finalizing 이다', () => {
    const forbidden = segment('forbiddenAfterRefresh', { listed: true, live: false });

    const covered = roundFinalizing({ kind: 'access', segment: LIVE_FAILURE });

    expect(run([ending, forbidden])).toEqual(covered);
    expect(run([forbidden, ending])).toEqual(covered);
  });

  it('R58(다른 방송)은 조각 실패로 정한 outsideDvr 보다 위라 순서를 바꿔도 finalizing 이다', () => {
    const outside = segment('gone', { listed: false, live: true });

    expect(run([outside, foreign])).toEqual(R58_FINALIZING);
    expect(run([foreign, outside])).toEqual(R58_FINALIZING);
  });

  it('조각 404 로 정한 goneOrNone 은 끝 상태가 아니라 뒤 R58 이 finalizing 으로 올린다', () => {
    const gone = segment('gone', { listed: true, live: true });

    expect(run([gone]).kind).toBe('goneOrNone');
    expect(run([gone, foreign])).toEqual(R58_FINALIZING);
    expect(run([foreign, gone])).toEqual(R58_FINALIZING);
  });

  it('스트림 경로 답(R58 · 같은 방송)은 회차 경로 vod 원천을 덮지 않아 순서를 바꿔도 ready vod 다', () => {
    const vod = { kind: 'ready', mode: 'vod', url: ROUND_URL };

    expect(run([vodReady, foreign])).toEqual(vod);
    expect(run([foreign, vodReady])).toEqual(vod);
    expect(run([vodReady, stream(ok('live', true, STREAM_URL), 'r1')])).toEqual(vod);
  });

  it('조각 실패 판정은 같은 목록 주소로 원천이 다시 서도 풀리지 않는다(폴링 깜박임 방지)', () => {
    const gone = segment('gone', { listed: true, live: false, listUrl: ROUND_URL });
    const forbidden = segment('forbiddenAfterRefresh', {
      listed: true,
      live: false,
      listUrl: ROUND_URL,
    });

    expect(run([vodReady, gone, vodReady], CHECKING).kind).toBe('goneOrNone');
    expect(run([vodReady, forbidden, vodReady], CHECKING).kind).toBe('access');
  });

  it('조각 실패 판정은 다른 목록 주소로 원천이 서면 풀린다', () => {
    const forbidden = segment('forbiddenAfterRefresh', { listed: true, live: true });

    expect(run([segment('gone', { listed: true, live: true }), vodReady])).toEqual({
      kind: 'ready',
      mode: 'vod',
      url: ROUND_URL,
    });
    expect(run([forbidden, vodReady])).toEqual({ kind: 'ready', mode: 'vod', url: ROUND_URL });
  });
});

const VOD_READY: EditorSourceState = { kind: 'ready', mode: 'vod', url: ROUND_URL };

/** 사건 목록의 모든 순열 */
function permutations<T>(items: readonly T[]): T[][] {
  if (items.length <= 1) return [[...items]];
  return items.flatMap((item, i) =>
    permutations([...items.slice(0, i), ...items.slice(i + 1)]).map((rest) => [item, ...rest]),
  );
}

describe('nextEditorSourceState — 조각 실패는 그 조각을 받은 목록 원천에 붙는다', () => {
  const vodReady = round(ok('vod', true, ROUND_URL));
  const foreign = stream(ok('live', true, STREAM_URL), 'r2');

  it('vod 조각 403 · R58 · 같은 vod 준비 세 사건은 어떤 순서로 와도 access 다(vod 실패도 회차 경로 몫)', () => {
    const vodForbidden = segment('forbiddenAfterRefresh', {
      listed: true,
      live: false,
      listUrl: ROUND_URL,
    });

    const ends = permutations([vodForbidden, foreign, vodReady]).map((order) =>
      run(order, VOD_READY),
    );

    expect(new Set(ends.map((end) => JSON.stringify(end)))).toEqual(
      new Set([JSON.stringify({ kind: 'access', segment: { mode: 'vod', listUrl: ROUND_URL } })]),
    );
  });

  it('스트림 목록에서 받던 조각 404 가 vod 준비보다 늦게 와도 같은 ready vod 다(낡은 창의 실패는 버린다)', () => {
    const streamGone = segment('gone', { listed: true, live: true, listUrl: STREAM_URL });

    expect(run([vodReady, streamGone])).toEqual(VOD_READY);
    expect(run([streamGone, vodReady])).toEqual(VOD_READY);
  });

  it('늦은 스트림 조각 실패 뒤 정상 vod 답이 거듭 와도 ready vod 에 머문다(고착 없음)', () => {
    const streamGone = segment('gone', { listed: true, live: true, listUrl: STREAM_URL });
    const polls = Array.from({ length: 100 }, () => vodReady);

    expect(run([vodReady, streamGone, ...polls])).toEqual(VOD_READY);
  });

  it('지금 원천과 다른 목록의 조각 실패는 버려 상태를 그대로 둔다', () => {
    const roundForbidden = segment('forbiddenAfterRefresh', {
      listed: true,
      live: true,
      listUrl: ROUND_URL,
    });
    const otherStream = segment('gone', { listed: false, live: true, listUrl: 'https://x/a.m3u8' });

    expect(nextEditorSourceState(LIVE_READY, roundForbidden)).toBe(LIVE_READY);
    expect(nextEditorSourceState(VOD_READY, otherStream)).toBe(VOD_READY);
  });

  it('원천이 서기 전(checking · unavailable)의 조각 실패는 버린다', () => {
    const unavailable: EditorSourceState = { kind: 'unavailable' };
    const gone = segment('gone', { listed: true, live: true });

    expect(nextEditorSourceState(CHECKING, gone)).toBe(CHECKING);
    expect(nextEditorSourceState(unavailable, gone)).toBe(unavailable);
  });

  it('조각 실패 상태에서 같은 목록의 뒤 조각 실패는 나중 것이 이긴다', () => {
    const outside = segment('gone', { listed: false, live: true });
    const forbidden = segment('forbiddenAfterRefresh', { listed: true, live: true });

    expect(run([outside, forbidden])).toEqual({
      kind: 'access',
      segment: { mode: 'live', listUrl: STREAM_URL },
    });
  });

  it('방송 중 조각 실패 상태 위에도 R58 은 finalizing 으로 선다(스트림 원천의 실패)', () => {
    const access = run([segment('forbiddenAfterRefresh', { listed: true, live: true })]);

    expect(nextEditorSourceState(access, foreign)).toEqual(R58_FINALIZING);
  });
});

// 순서 성질: 갈래(만료 · 회차 · 스트림 · 조각)마다 사건을 하나씩 골라 2–4개를 묶으면, 그 묶음의 모든 순열이 같은 끝 상태에
// 닿는다. 같은 갈래 안 두 사건은 시계열이라 나중 것이 이긴다 — 이 성질의 대상이 아니라 묶음에 한 갈래 한 사건만 넣는다.
/** 이름표를 단 사건 — 어긋난 묶음을 보고할 때 쓴다 */
type LabeledEvent = [string, EditorSourceEvent];

const SOURCE_BRANCHES: Record<string, readonly LabeledEvent[]> = {
  expired: [['410', { type: 'accessExpired' }]],
  round: [
    ['ending', round(ok('ending', false, null))],
    ['vod 미준비', round(ok('vod', false, null))],
    ['ended', round(ok('ended', false, null))],
    ['404 유예 뒤', round({ kind: 'none' }, RESUME_WAIT_MS)],
    ['404 유예 안', round({ kind: 'none' }, RESUME_WAIT_MS - 1)],
    ['잠시 불가', round({ kind: 'unavailable' })],
    ['vod 준비', round(ok('vod', true, ROUND_URL))],
    ['다른 주소 vod 준비', round(ok('vod', true, 'https://cdn.test/dvr/s/rounds/r9/index.m3u8'))],
  ],
  stream: [
    ['R58', stream(ok('live', true, STREAM_URL), 'r2')],
    ['같은 방송', stream(ok('live', true, STREAM_URL), 'r1')],
    ['방송 번호 모름', stream(ok('live', true, STREAM_URL), null)],
    ['방송 없음', stream(ok('live', true, STREAM_URL), '')],
  ],
  segment: [STREAM_URL, ROUND_URL].flatMap((listUrl) => [
    [`${listUrl} 404 빠짐`, segment('gone', { listed: false, live: true, listUrl })],
    [`${listUrl} 404 남음`, segment('gone', { listed: true, live: false, listUrl })],
    [`${listUrl} 403`, segment('forbiddenAfterRefresh', { listed: true, live: false, listUrl })],
    [
      `${listUrl} 403 회차 404`,
      segment('forbiddenAfterRefresh', { listed: true, live: true, listUrl, roundGone: true }),
    ],
  ]),
};

/** 모든 부분 묶음(빈 묶음 포함) */
function subsetsOf<T>(items: readonly T[]): T[][] {
  return items.reduce<T[][]>(
    (acc, item) => [...acc, ...acc.map((combo) => [...combo, item])],
    [[]],
  );
}

/** 갈래 묶음 중 2–4개짜리 전부 */
function branchCombos<T>(branches: readonly T[]): T[][] {
  return subsetsOf(branches).filter((combo) => combo.length >= 2);
}

/** 갈래마다 사건 하나씩 고른 모든 묶음 */
function eventPicks(combo: readonly (readonly LabeledEvent[])[]): LabeledEvent[][] {
  return combo.reduce<LabeledEvent[][]>(
    (acc, branch) => acc.flatMap((pick) => branch.map((e) => [...pick, e])),
    [[]],
  );
}

/**
 * 생기지 않는 순서인가. 회차 목록(ROUND_URL)은 시작 원천이 그것이거나 앞선 회차 답이 그 주소를 실어 준 뒤에만 받을 수 있어
 * 그 전의 회차 목록 조각 실패는 생기지 않는다. 스트림 목록 조각 실패는 편집기가 방송 중에 받던 창의 늦은 실패일 수 있어
 * 어느 자리에서나 생긴다(시작이 vod 여도 넣는다)
 */
function isImpossibleOrder(start: EditorSourceState, order: readonly EditorSourceEvent[]): boolean {
  return order.some((event, i) => {
    if (event.type !== 'segment' || event.listUrl !== ROUND_URL) return false;
    if (start.kind === 'ready' && start.url === ROUND_URL) return false;
    return !order
      .slice(0, i)
      .some(
        (e) => e.type === 'round' && e.result.kind === 'ok' && e.result.status.url === ROUND_URL,
      );
  });
}

describe('nextEditorSourceState — 갈래마다 한 사건씩이면 도착 순서와 무관하다', () => {
  it.each<[string, EditorSourceState]>([
    ['방송 중 원천(LIVE_READY)', LIVE_READY],
    ['회차 vod 원천(VOD_READY)', VOD_READY],
  ])('%s 에서 2–4 갈래 묶음의 모든 순열이 같은 끝 상태다', (_name, start) => {
    const mismatches: string[] = [];
    let checked = 0;

    for (const combo of branchCombos(Object.values(SOURCE_BRANCHES))) {
      for (const pick of eventPicks(combo)) {
        const orders = permutations(pick).filter(
          (order) =>
            !isImpossibleOrder(
              start,
              order.map(([, e]) => e),
            ),
        );
        const ends = new Set(
          orders.map((order) =>
            JSON.stringify(
              run(
                order.map(([, e]) => e),
                start,
              ),
            ),
          ),
        );
        checked += orders.length;
        if (ends.size > 1)
          mismatches.push(`${pick.map(([label]) => label).join(' · ')} → ${[...ends].join(' | ')}`);
      }
    }

    expect(checked).toBeGreaterThan(1000);
    expect(mismatches).toEqual([]);
  });
});

const ENDING = round(ok('ending', false, null));
/** 회차 경로 live 답 — 회차 경로는 방송 중 url 을 싣지 않는다(설계 5절 1) */
const ROUND_LIVE = round(ok('live', false, null));
const SAME_STREAM = stream(ok('live', true, STREAM_URL), 'r1');

// 같은 회차 재접속: 서버는 끊긴 뒤 여유 안에 다시 온 송출을 기존 회차에 합류시키고(media/internal/session/round_rule.go:105),
// 회차에 live 세션이 있으면 회차 경로가 다시 live 를 답한다(media/internal/index/round_playback.go:66).
describe('nextEditorSourceState — 3행 finalizing 은 선 원인별로 풀린다', () => {
  const vodReady = round(ok('vod', true, ROUND_URL));
  const foreign = stream(ok('live', true, STREAM_URL), 'r2');

  it('같은 회차 재접속(회차 ending → live) 뒤 같은 방송 스트림 답이 오면 ready live 로 돌아온다', () => {
    expect(run([ENDING, ROUND_LIVE, SAME_STREAM])).toEqual(LIVE_READY);
  });

  it('재접속 뒤 정상 폴링(회차 live · 같은 방송 스트림)이 거듭 와도 ready live 에 머문다(고착 없음)', () => {
    const polls = Array.from({ length: 100 }, () => [ROUND_LIVE, SAME_STREAM]).flat();

    expect(run([ENDING, ROUND_LIVE, ...polls])).toEqual(LIVE_READY);
  });

  it('회차 ending 이 덮은 동안 온 스트림 답 · 조각 실패는 덮인 상태에 쌓여 회차 live 때 드러난다', () => {
    const forbidden = segment('forbiddenAfterRefresh', { listed: true, live: true });

    expect(run([ENDING, SAME_STREAM], CHECKING)).toEqual(roundFinalizing(LIVE_READY));
    expect(run([ENDING, SAME_STREAM, ROUND_LIVE], CHECKING)).toEqual(LIVE_READY);
    expect(run([ENDING, ROUND_LIVE], CHECKING)).toEqual(CHECKING);
    expect(run([ENDING, forbidden, ROUND_LIVE])).toEqual({ kind: 'access', segment: LIVE_FAILURE });
  });

  it('R58 로 선 finalizing 은 회차 live 답으로 풀리지 않고 스트림 답도 받지 않는다', () => {
    expect(run([foreign, ROUND_LIVE])).toEqual(R58_FINALIZING);
    expect(run([foreign, ROUND_LIVE, SAME_STREAM])).toEqual(R58_FINALIZING);
    expect(run([ENDING, foreign, ROUND_LIVE])).toEqual(R58_FINALIZING);
  });

  it('R58 로 선 finalizing 은 회차 경로가 정하면(vod 준비 · ended) 그 답을 따른다', () => {
    expect(run([foreign, ENDING, vodReady])).toEqual(VOD_READY);
    expect(run([foreign, round(ok('ended', false, null))])).toEqual({ kind: 'goneOrNone' });
  });

  it('같은 갈래의 회차 시계열은 나중 답이 이긴다', () => {
    expect(run([ENDING, vodReady])).toEqual(VOD_READY);
    expect(run([ENDING, round(ok('vod', false, null)), vodReady])).toEqual(VOD_READY);
    expect(run([ENDING, round(ok('ended', false, null))])).toEqual({ kind: 'goneOrNone' });
    expect(run([ROUND_LIVE, ENDING, ROUND_LIVE, ENDING])).toEqual(roundFinalizing(LIVE_READY));
    expect(run([ROUND_LIVE, ENDING, ROUND_LIVE, ENDING, ROUND_LIVE])).toEqual(LIVE_READY);
  });

  it('덮인 상태를 바꾸지 않는 답(방송 번호 모름 · 낡은 창 실패 · 회차 ending 반복)은 덮개 객체를 그대로 둔다', () => {
    const covered = run([ENDING]);
    const staleRound = segment('gone', { listed: true, live: false, listUrl: ROUND_URL });

    expect(nextEditorSourceState(covered, stream(ok('live', true, STREAM_URL), null))).toBe(
      covered,
    );
    expect(nextEditorSourceState(covered, staleRound)).toBe(covered);
    expect(nextEditorSourceState(covered, ENDING)).toBe(covered);
  });

  it('회차 live 답은 3행이 아닌 상태를 바꾸지 않는다', () => {
    expect(nextEditorSourceState(LIVE_READY, ROUND_LIVE)).toBe(LIVE_READY);
    expect(nextEditorSourceState(CHECKING, ROUND_LIVE)).toBe(CHECKING);
  });
});

/** 이름표를 단 회차 경로 시계열 — 한 회차의 답이 서버 국면 차례대로 온다 */
type LabeledSeries = [string, readonly EditorSourceEvent[]];

// 생길 수 있는 회차 경로 시계열만 둔다. 뺀 것과 까닭:
//   vod 뒤 ending · live    vod 는 회차가 닫힌 뒤라 다시 온 송출은 새 회차가 된다(round_rule.go:105 의 !prev.roundClosed)
//   ended · 404 유예 뒤 뒤 답  끝 상태(2행)라 뒤 답이 바꾸지 않는다 — 한 사건 묶음(위 순서 성질)이 이미 덮는다
//   404 유예 안 · 다른 주소 vod  있던 회차가 다시 「모름」 이 되거나 vod 목록 주소가 바뀌지 않는다
const ROUND_SERIES: readonly LabeledSeries[] = [
  ['live', [ROUND_LIVE]],
  ['ending → live(재접속)', [ENDING, ROUND_LIVE]],
  ['live → ending', [ROUND_LIVE, ENDING]],
  ['ending → live → ending', [ENDING, ROUND_LIVE, ENDING]],
  ['live → ending → live', [ROUND_LIVE, ENDING, ROUND_LIVE]],
  ['ending → 잠시 불가 → live', [ENDING, round({ kind: 'unavailable' }), ROUND_LIVE]],
  ['ending → vod 준비', [ENDING, round(ok('vod', true, ROUND_URL))]],
  [
    'ending → vod 미준비 → vod 준비',
    [ENDING, round(ok('vod', false, null)), round(ok('vod', true, ROUND_URL))],
  ],
  ['ending → ended', [ENDING, round(ok('ended', false, null))]],
];

/** 시계열 묶음의 모든 끼워 넣기 — 시계열마다 안의 차례는 지킨다 */
function interleavings<T>(series: readonly (readonly T[])[]): T[][] {
  const rest = series.filter((s) => s.length > 0);
  if (rest.length === 0) return [[]];
  return rest.flatMap((s, i) => {
    const [head, ...tail] = s;
    if (head === undefined) return [];
    return interleavings(rest.map((t, j) => (j === i ? tail : t))).map((order) => [head, ...order]);
  });
}

/** 이름으로 고른 갈래(SOURCE_BRANCHES) */
function branchesNamed(...names: string[]): (readonly LabeledEvent[])[] {
  return names.map((name) => {
    const branch = SOURCE_BRANCHES[name];
    if (branch === undefined) throw new Error(`갈래 없음: ${name}`);
    return branch;
  });
}

// 시작마다 넣는 다른 갈래. 아직 답이 없을 때(CHECKING)는 조각 갈래를 뺀다 — 목록을 받기 전의 조각 실패는 생기지 않고,
// 원천이 선 뒤의 실패는 방송 중 원천(LIVE_READY) 시작 묶음이 덮는다. 회차 vod 원천 시작은 위 시계열이 생기지 않아 뺀다.
describe('nextEditorSourceState — 회차 경로 시계열을 끼워도 도착 순서와 무관하다', () => {
  it.each<[string, EditorSourceState, readonly (readonly LabeledEvent[])[], number]>([
    ['방송 중 원천(LIVE_READY)', LIVE_READY, branchesNamed('expired', 'stream', 'segment'), 10_000],
    ['아직 답 없음(CHECKING)', CHECKING, branchesNamed('expired', 'stream'), 500],
  ])(
    '%s 에서 회차 시계열 하나 · 다른 갈래 한 사건씩의 모든 끼워 넣기가 같은 끝 상태다',
    (_name, start, others, minOrders) => {
      const mismatches: string[] = [];
      let checked = 0;

      for (const [seriesLabel, series] of ROUND_SERIES) {
        for (const pick of subsetsOf(others).flatMap(eventPicks)) {
          const orders = interleavings([series, ...pick.map(([, e]) => [e])]).filter(
            (order) => !isImpossibleOrder(start, order),
          );
          const ends = new Set(orders.map((order) => JSON.stringify(run(order, start))));
          checked += orders.length;
          if (ends.size > 1) {
            const labels = [seriesLabel, ...pick.map(([label]) => label)].join(' · ');
            mismatches.push(`${labels} → ${[...ends].join(' | ')}`);
          }
        }
      }

      expect(checked).toBeGreaterThan(minOrders);
      expect(mismatches).toEqual([]);
    },
  );
});

describe('editorSourceView — 4 · 5행(방송 중 카드 자리 대 목록)', () => {
  const list = { firstPdtMs: 1000, endMs: 9000 };

  it('방송 중 카드 자리가 목록 끝 뒤면 uploading · 첫 PDT 앞이면 outsideDvr · 안이면 그대로다', () => {
    expect(editorSourceView(LIVE_READY, list, 9500).kind).toBe('uploading');
    expect(editorSourceView(LIVE_READY, list, 500).kind).toBe('outsideDvr');
    expect(editorSourceView(LIVE_READY, list, 5000)).toEqual(LIVE_READY);
  });

  it('목록 전 · 카드 없음 · 끝난 방송(vod)이면 그대로다', () => {
    const vod: EditorSourceState = { kind: 'ready', mode: 'vod', url: ROUND_URL };

    expect(editorSourceView(LIVE_READY, null, 9500)).toEqual(LIVE_READY);
    expect(editorSourceView(LIVE_READY, list, null)).toEqual(LIVE_READY);
    expect(editorSourceView(vod, list, 9500)).toEqual(vod);
  });
});

describe('cardSceneLabelOf — 카드 표지는 원천 상태와 따로다', () => {
  it('playerAtMs 값이면 located · null 이면 sceneMissing · 칸 없으면 estimated 다', () => {
    expect(cardSceneLabelOf(1234)).toBe('located');
    expect(cardSceneLabelOf(null)).toBe('sceneMissing');
    expect(cardSceneLabelOf(undefined)).toBe('estimated');
  });

  it('sceneMissing 카드도 원천이 서면 영상 창은 열린다', () => {
    const label = cardSceneLabelOf(null);

    expect(label).toBe('sceneMissing');
    expect(editorSourceView(LIVE_READY, { firstPdtMs: 1000, endMs: 9000 }, 5000)).toEqual(
      LIVE_READY,
    );
  });
});
