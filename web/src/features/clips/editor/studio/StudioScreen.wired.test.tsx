import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { StudioScreen } from './StudioScreen';

// 실제 모드 편집기(POK-251) — 주소(?stream=&card= · ?recipe=)로 열어 clip에 저장·주문하는 길을 잰다.
// 녹화 재생 서버가 없는 환경이라 구간의 기준점은 방송 시작 시각이다(README 「편집 구간의 시각 기준점」).

const nav = vi.hoisted(() => ({ search: '' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(nav.search),
}));

// 녹화 목록을 어느 키로 물었는지 잰다(POK-233) — 실제 구현을 그대로 부른다(재생 서버 주소가 없어 빈 목록이다)
const spansAsked = vi.hoisted(() => [] as string[]);
vi.mock('@/api/mediaPlayback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/mediaPlayback')>();
  return {
    ...actual,
    fetchRecordingSpans: (streamId: string) => {
      spansAsked.push(streamId);
      return actual.fetchRecordingSpans(streamId);
    },
  };
});

const STREAM_ID = 'stream-1';
const STARTED_AT = Date.parse('2026-09-20T10:00:00Z');

const server = vi.hoisted(() => ({
  cardWindow: { startMs: 60_000, endMs: 72_400 },
  savedTracks: [{ trackId: 0, gain: 1 }] as { trackId: number; gain: number }[],
  /** 저장된 편집본의 판 — 1이면 옛 모양(crop 하나), 2면 편집기가 지금 저장하는 모양 */
  savedSchema: 2 as 1 | 2,
  /** 저장된 v2 출력. 없으면 세로 한 장 */
  savedOutput: null as unknown,
  /** 시각 기준점(POK-255). null이면 서버가 모른다(방송 시작 시각으로 대신) */
  origin: null as number | null,
  /** 저장된 v1 출력에 덧붙는 정사각 한 벌 */
  savedSquare: false,
  /** 저장된 자막. clip은 자막이 없으면 칸을 빼지 않고 null 로 준다 */
  savedSubtitles: null as unknown,
  relation: 'OWNER',
  /** 영상 경로의 키(POK-233). undefined면 이 칸을 모르는 옛 서버다 */
  ingest: undefined as string | undefined,
  delegations: [] as {
    id: number;
    counterpartId: number;
    counterpartName: string;
    grantedAt: string;
  }[],
}));

/** 원본(16:9)에서 9:16 을 오른쪽으로 치우쳐 잡은 자리 — 편집기 계산과 같은 정확한 비율 */
const SAVED_CROP = { x: 0.5, y: 0, w: 0.31640625, h: 1 };

function broadcastRow() {
  return {
    streamId: STREAM_ID,
    status: 'ended',
    relation: server.relation,
    startedAt: new Date(STARTED_AT).toISOString(),
    endedAt: new Date(STARTED_AT + 3_600_000).toISOString(),
    vodExpiresAt: null,
    timelineOriginAt: server.origin === null ? null : new Date(server.origin).toISOString(),
    ingestStreamId: server.ingest,
  };
}

function recipeSnapshot(id: number, version: number, recipe: unknown) {
  return {
    id,
    streamId: STREAM_ID,
    creatorId: '9',
    recipeVersion: version,
    recipe,
    createdAt: '2026-09-20T11:00:00Z',
    updatedAt: '2026-09-20T11:00:00Z',
  };
}

function handle(url: string, init?: RequestInit) {
  const u = new URL(url, 'http://localhost');
  const path = u.pathname;
  const method = init?.method ?? 'GET';
  if (path === '/api/clip/broadcasts') {
    const past = u.searchParams.get('state') === 'past';
    return jsonResponse(200, { broadcasts: past ? [broadcastRow()] : [], nextCursor: null });
  }
  if (path.endsWith('/jump-cards')) {
    return jsonResponse(200, {
      cards: [
        {
          id: 5,
          streamId: STREAM_ID,
          source: 'chat-surge',
          streamTimestampMs: server.cardWindow.startMs + 5_000,
          window: server.cardWindow,
          score: 80,
          evidence: null,
          claimedBy: null,
          hidden: false,
          eventSeq: 1,
          createdAt: '2026-09-20T10:01:00Z',
        },
      ],
    });
  }
  if (path === '/api/clip/library/31') {
    return jsonResponse(200, {
      recipeId: 31,
      streamId: STREAM_ID,
      creatorId: '9',
      recipeVersion: 2,
      cut: { inAtMs: STARTED_AT + 120_000, outAtMs: STARTED_AT + 150_000 },
      status: 'editing',
      broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
      latestClip: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
      recipe: {
        schemaVersion: server.savedSchema,
        streamId: STREAM_ID,
        cut: { inAtMs: STARTED_AT + 120_000, outAtMs: STARTED_AT + 150_000 },
        outputs: [
          ...(server.savedSquare
            ? [
                {
                  outputId: 'square',
                  aspect: 'SQUARE_1_1',
                  crop: { x: 0.21875, y: 0, w: 0.5625, h: 1 },
                },
              ]
            : []),
          server.savedSchema === 1
            ? { outputId: 'vert', aspect: 'VERT_9_16', crop: SAVED_CROP }
            : (server.savedOutput ?? {
                outputId: 'o1',
                aspect: 'VERT_9_16',
                layers: [{ crop: SAVED_CROP, box: { x: 0, y: 0, w: 1, h: 1 } }],
              }),
        ],
        audio: { tracks: server.savedTracks },
        subtitles: server.savedSubtitles,
      },
    });
  }
  if (path === `/api/clip/broadcasts/${STREAM_ID}/recipes` && method === 'POST') {
    return jsonResponse(201, recipeSnapshot(31, 1, JSON.parse(String(init?.body))));
  }
  if (path === `/api/clip/broadcasts/${STREAM_ID}/recipes/31` && method === 'PUT') {
    return jsonResponse(200, recipeSnapshot(31, 3, JSON.parse(String(init?.body))));
  }
  if (path === `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders` && method === 'POST') {
    return jsonResponse(201, {
      id: 77,
      streamId: STREAM_ID,
      recipeId: 31,
      recipeVersion: 1,
      requestedBy: '9',
      status: 'queued',
      progress: null,
      outputs: null,
      error: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
    });
  }
  if (path === '/api/auth/me')
    return jsonResponse(200, { id: 9, email: 'me@example.com', name: '나' });
  if (path === '/api/streamers/9/audio-tracks') return jsonResponse(200, { labels: {} });
  if (path.startsWith('/api/streamers/'))
    return jsonResponse(200, { labels: ['남의 트랙', null, null, null, null, null] });
  if (path === '/api/editor-delegations/as-editor') return jsonResponse(200, server.delegations);
  return jsonResponse(404, { error: 'unexpected_call' });
}

let fetchSpy: ReturnType<typeof stubFetch>;

beforeEach(() => {
  server.cardWindow = { startMs: 60_000, endMs: 72_400 };
  server.savedTracks = [{ trackId: 0, gain: 1 }];
  server.savedSchema = 2;
  server.savedOutput = null;
  server.savedSquare = false;
  server.origin = null;
  server.savedSubtitles = null;
  server.relation = 'OWNER';
  server.delegations = [];
  server.ingest = undefined;
  spansAsked.length = 0;
  fetchSpy = stubFetch(handle);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function bodiesOf(method: string, path: string) {
  return fetchSpy.mock.calls
    .filter(([url, init]) => url === path && init?.method === method)
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
}

async function openFrom(search: string) {
  nav.search = search;
  renderWithProviders(<StudioScreen />);
  return screen.findByRole('button', { name: '편집본 저장' });
}

describe('StudioScreen — 영상 경로의 키(POK-233)', () => {
  it('녹화는 방송 번호가 아니라 영상 경로의 키로 찾는다 — 방송 번호가 회차 번호면 그 이름의 녹화가 없다', async () => {
    server.ingest = 'key-studio';
    await openFrom(`?stream=${STREAM_ID}&card=5`);
    expect(spansAsked).toEqual(['key-studio']);
  });

  it('칸을 모르는 옛 서버면 방송 번호로 찾는다', async () => {
    await openFrom(`?stream=${STREAM_ID}&card=5`);
    expect(spansAsked).toEqual([STREAM_ID]);
  });
});

describe('StudioScreen — 카드로 연 실제 편집기', () => {
  it('저장하면 카드 창을 방송 절대 시각의 컷으로 옮겨 새 편집본을 만든다', async () => {
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1),
    );
    const [doc] = bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`);
    expect(doc).toMatchObject({
      schemaVersion: 2,
      streamId: STREAM_ID,
      cut: { inAtMs: STARTED_AT + 60_000, outAtMs: STARTED_AT + 72_400 },
      // 트랙을 따로 켜지 않았으면 최종 믹스(0) 하나다 — 0과 1~5를 같이 보내면 저장 문이 거절한다
      audio: { tracks: [{ trackId: 0, gain: 1 }] },
    });
    // 편집기의 처음 레이아웃(분할 50:50, 경계선 켬) 그대로다
    expect(doc?.outputs).toEqual([
      expect.objectContaining({
        aspect: 'VERT_9_16',
        layers: [
          expect.objectContaining({ box: { x: 0, y: 0, w: 1, h: 0.5 } }),
          expect.objectContaining({ box: { x: 0, y: 0.5, w: 1, h: 0.5 } }),
        ],
        dividers: [{ y: 0.5, thickness: 2 / 240, color: '#586fc4' }],
      }),
    ]);
    expect(doc).not.toHaveProperty('subtitles');
    expect(await screen.findByText(/편집본 #31 v1 저장됨/)).toBeInTheDocument();
  });

  it('서버가 시각 기준점을 주면 컷은 방송 시작이 아니라 그 기준점에 카드 창을 더한 것이다(POK-255)', async () => {
    // 첫 조각이 방송 시작보다 32초 늦었다(2026-09-17 실측) — 방송 시작으로 대신하면 컷이 32초 앞을 자른다
    server.origin = STARTED_AT + 32_000;
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1),
    );
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)[0]?.cut).toEqual({
      inAtMs: STARTED_AT + 32_000 + 60_000,
      outAtMs: STARTED_AT + 32_000 + 72_400,
    });
  });

  it('방송 트랙을 하나라도 켜면 최종 믹스(0)는 빼고 보낸다 — 같은 소리가 두 번 섞이면 안 된다', async () => {
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    await user.click(screen.getByRole('switch', { name: '트랙 2 사용' }));
    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1),
    );
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)[0]?.audio).toEqual({
      tracks: [{ trackId: 1, gain: 1 }],
    });
  });

  it('5초보다 짧은 카드 창은 5초로 늘려 저장한다 — 저장 문이 5초~3분 밖을 거절한다', async () => {
    server.cardWindow = { startMs: 60_000, endMs: 62_000 };
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1),
    );
    const cut = bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)[0]?.cut as {
      inAtMs: number;
      outAtMs: number;
    };
    expect(cut.outAtMs - cut.inAtMs).toBe(5_000);
  });

  const renders = () =>
    fetchSpy.mock.calls.filter(
      ([url, init]) =>
        url === `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders` && init?.method === 'POST',
    ).length;

  it('저장하지 않고 영상 만들기를 누르면 먼저 저장하고 그 판으로 주문한다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));

    await waitFor(() => expect(renders()).toBe(1));
    // 저장이 주문보다 먼저다 — 저장하지 않은 화면으로는 주문할 판이 없다
    const order = fetchSpy.mock.calls.map(
      ([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`,
    );
    const saveAt = order.indexOf(`POST /api/clip/broadcasts/${STREAM_ID}/recipes`);
    const renderAt = order.indexOf(`POST /api/clip/broadcasts/${STREAM_ID}/recipes/31/renders`);
    expect(saveAt).toBeGreaterThanOrEqual(0);
    expect(saveAt).toBeLessThan(renderAt);
    // 헤더 상태 문구와 토스트 둘 다 말한다
    expect((await screen.findAllByText(/영상 #77 주문됨/)).length).toBeGreaterThan(0);
  });

  it('저장 뒤 또 고치면 새 판으로 저장하고 주문한다 — 고치지 않았으면 다시 저장하지 않는다', async () => {
    // 저장 뒤 고친 것을 무시하고 옛 판을 렌더하면 화면과 다른 영상이 나온다(PR #200 codex P1)
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(save);
    await screen.findByText(/편집본 #31 v1 저장됨/);

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await waitFor(() => expect(renders()).toBe(1));
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(0);

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    await user.click(screen.getByRole('switch', { name: '트랙 2 사용' }));
    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await waitFor(() => expect(renders()).toBe(2));
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]?.audio).toEqual({
      tracks: [{ trackId: 1, gain: 1 }],
    });
  });

  it('오디오 스위치가 계약을 지킨다 — 최종 믹스와 방송 트랙은 하나만, 마지막 트랙은 못 끈다', async () => {
    // 저장할 때 조용히 바꾸면 화면과 다른 소리가 렌더된다(PR #200 codex P1)
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('tab', { name: '오디오' }));
    const mix = () => screen.getByRole('switch', { name: '최종 믹스(트랙 1) 사용' });
    const track2 = () => screen.getByRole('switch', { name: '트랙 2 사용' });
    expect(mix()).toBeChecked();

    await user.click(track2());
    expect(track2()).toBeChecked();
    expect(mix()).not.toBeChecked();

    // 마지막으로 켜진 트랙은 끄지 않고 이유를 말한다
    await user.click(track2());
    expect(track2()).toBeChecked();
    expect(await screen.findByText('트랙을 하나는 켜 둬야 해요')).toBeInTheDocument();

    await user.click(mix());
    expect(mix()).toBeChecked();
    expect(track2()).not.toBeChecked();
  });
});

describe('StudioScreen — 편집본으로 다시 연 실제 편집기', () => {
  it('저장된 컷이 그대로 돌아와 같은 편집본에 새 판으로 저장된다 — 기준점이 저장 때와 같다', async () => {
    server.savedTracks = [{ trackId: 1, gain: 0.5 }];
    server.savedSchema = 1;
    const user = userEvent.setup();
    const save = await openFrom('recipe=31');

    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    const [doc] = bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`);
    expect(doc?.cut).toEqual({ inAtMs: STARTED_AT + 120_000, outAtMs: STARTED_AT + 150_000 });
    // 저장된 트랙 선택(1번, 50%)이 되살아나 그대로 나간다
    expect(doc?.audio).toEqual({ tracks: [{ trackId: 1, gain: 0.5 }] });
    // 옛 편집본(v1)의 자르는 자리를 세로 한 장으로 되살려 v2로 싣는다 — 잃지 않는다
    expect(doc?.schemaVersion).toBe(2);
    const [output] = doc?.outputs as { layers: { crop: typeof SAVED_CROP; box: unknown }[] }[];
    expect(output?.layers).toHaveLength(1);
    expect(output?.layers[0]?.box).toEqual({ x: 0, y: 0, w: 1, h: 1 });
    expectRectClose(output!.layers[0]!.crop, SAVED_CROP);
  });

  it('옛 편집본(v1)은 영상 만들기 때 한 번 v2로 다시 저장한다 — 모양이 달라 옛 판으로는 화면과 같은 영상을 못 만든다', async () => {
    server.savedSchema = 1;
    const user = userEvent.setup();
    await openFrom('recipe=31');

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));

    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]?.schemaVersion).toBe(
      2,
    );
  });

  it('저장된 편집본을 고치지 않고 영상 만들기를 누르면 다시 저장하지 않고 그 판으로 주문한다', async () => {
    const user = userEvent.setup();
    await openFrom('recipe=31');

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));

    await waitFor(() =>
      expect(
        fetchSpy.mock.calls.some(
          ([url, init]) =>
            url === `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders` &&
            init?.method === 'POST',
        ),
      ).toBe(true),
    );
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(0);
  });

  it('100%를 넘는 저장된 볼륨은 손대지 않으면 그대로 다시 저장된다', async () => {
    // 계약은 0~200%다 — 다시 열 때 100%로 깎으면 1.5가 1.0으로 바뀌어 믹스가 달라진다(PR #200 codex)
    server.savedTracks = [{ trackId: 2, gain: 1.5 }];
    const user = userEvent.setup();
    const save = await openFrom('recipe=31');

    await user.click(save);

    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]?.audio).toEqual({
      tracks: [{ trackId: 2, gain: 1.5 }],
    });
  });

  it('위임이 여럿인 편집자에게는 어느 스트리머 것인지 몰라 트랙 이름을 붙이지 않는다', async () => {
    server.relation = 'EDITOR';
    server.delegations = [
      { id: 1, counterpartId: 101, counterpartName: 'A', grantedAt: '2026-09-01T00:00:00Z' },
      { id: 2, counterpartId: 102, counterpartName: 'B', grantedAt: '2026-09-01T00:00:00Z' },
    ];
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    expect(screen.queryByText('남의 트랙')).not.toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([url]) => String(url).startsWith('/api/streamers/'))).toBe(
      false,
    );
  });

  it('위임이 하나뿐이면 그 스트리머의 트랙 이름을 쓴다', async () => {
    server.relation = 'EDITOR';
    server.delegations = [
      { id: 1, counterpartId: 101, counterpartName: 'A', grantedAt: '2026-09-01T00:00:00Z' },
    ];
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('tab', { name: '오디오' }));
    expect(await screen.findAllByText('남의 트랙')).not.toHaveLength(0);
  });

  it('주소에 열 것이 없으면 시작하는 곳을 안내한다', async () => {
    nav.search = '';
    renderWithProviders(<StudioScreen />);
    expect(
      await screen.findByText('카드에서 「편집」을 누르거나 보관함에서 편집본을 여세요'),
    ).toBeInTheDocument();
  });
});

function expectRectClose(
  actual: { x: number; y: number; w: number; h: number },
  expected: typeof actual,
) {
  for (const key of ['x', 'y', 'w', 'h'] as const)
    expect(actual[key]).toBeCloseTo(expected[key], 9);
}

type Output = {
  background?: unknown;
  layers: { crop: { x: number; y: number; w: number; h: number }; box: unknown; frame?: unknown }[];
  dividers?: unknown;
};

async function saveAndReadOutput(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: '편집본 저장' }));
  await waitFor(() =>
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1),
  );
  return (
    bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)[0]?.outputs as Output[]
  )[0]!;
}

describe('StudioScreen — 화면에서 고른 모양이 저장 본문에 그대로 실린다(POK-252)', () => {
  it('세로: 자르는 틀을 옮긴 자리가 그대로 실린다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('radio', { name: /세로/ }));
    const frame = screen.getByRole('button', { name: /세로 9:16 영역/ });
    frame.focus();
    await user.keyboard('{ArrowRight}{ArrowRight}');

    const output = await saveAndReadOutput(user);
    expect(output.layers).toHaveLength(1);
    expect(output.layers[0]!.box).toEqual({ x: 0, y: 0, w: 1, h: 1 });
    // 가운데(0.341796875)에서 1%씩 두 번
    expectRectClose(output.layers[0]!.crop, { x: 0.361796875, y: 0, w: 0.31640625, h: 1 });
    expect(output).not.toHaveProperty('background');
    expect(output).not.toHaveProperty('dividers');
  });

  it('분할 70:30에 경계선을 끄면 두 칸이 70·30으로 쌓이고 선이 없다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('radio', { name: '70 : 30' }));
    await user.click(screen.getByRole('switch', { name: '경계선 표시' }));

    const output = await saveAndReadOutput(user);
    expect(output.layers.map((l) => l.box)).toEqual([
      { x: 0, y: 0, w: 1, h: 0.7 },
      { x: 0, y: 0.7, w: 1, h: 1 - 0.7 },
    ]);
    expect(output).not.toHaveProperty('dividers');
    // 칸 모양대로 잘라야 찌그러지지 않는다: 위 칸 1080×1344 → 원본 픽셀 비율 0.8036
    const top = output.layers[0]!.crop;
    expect((top.w * 1920) / (top.h * 1080)).toBeCloseTo(1080 / (0.7 * 1920), 6);
  });

  it('중앙에 단색 흰 바탕이면 원본 비율 칸이 가운데 놓이고 바탕이 흰색이다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    await user.click(screen.getByRole('radio', { name: '단색' }));
    await user.click(
      within(screen.getByRole('radiogroup', { name: '배경 색' })).getByRole('radio', {
        name: '흰색',
      }),
    );

    const output = await saveAndReadOutput(user);
    expect(output.background).toEqual({ kind: 'COLOR', color: '#ffffff' });
    const h = (9 / 16) * (9 / 16);
    expect(output.layers).toEqual([
      expect.objectContaining({ box: { x: 0, y: (1 - h) / 2, w: 1, h } }),
    ]);
  });

  it('중앙의 흐린 바탕은 세기를 싣는다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('radio', { name: /중앙/ }));
    const slider = screen.getByRole('slider', { name: '블러 강도' });
    slider.focus();
    await user.keyboard('{ArrowLeft}');

    expect((await saveAndReadOutput(user)).background).toEqual({ kind: 'BLUR', strength: 59 });
  });

  it('크롭: 작은 화면 자리와 테두리(굵기 3px)가 실린다, 끄면 테두리가 없다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('radio', { name: /크롭/ }));
    await user.click(screen.getByRole('radio', { name: '3px' }));

    const output = await saveAndReadOutput(user);
    expect(output.layers).toHaveLength(2);
    expect(output.layers[0]!.box).toEqual({ x: 0, y: 0, w: 1, h: 1 });
    expect(output.layers[1]).toMatchObject({
      box: { x: 0.18, y: 0.5, w: 0.64, h: 0.27 },
      frame: { width: 3 / 240, color: '#ffffff', radius: 4 / 240, shadow: true },
    });

    await user.click(screen.getByRole('switch', { name: '테두리 표시' }));
    await user.click(screen.getByRole('button', { name: '편집본 저장' }));
    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    const [again] = bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]
      ?.outputs as Output[];
    expect(again!.layers[1]).not.toHaveProperty('frame');
  });

  it('저장된 분할 편집본을 열면 그 모양으로 열리고, 고치지 않으면 다시 저장하지 않는다', async () => {
    server.savedOutput = {
      outputId: 'o1',
      aspect: 'VERT_9_16',
      layers: [
        {
          crop: { x: 0.2, y: 0, w: 1080 / (0.6 * 1920) / (16 / 9), h: 1 },
          box: { x: 0, y: 0, w: 1, h: 0.6 },
        },
        {
          crop: { x: 0.1, y: 0.4, w: (1080 / (0.4 * 1920) / (16 / 9)) * 0.5, h: 0.5 },
          box: { x: 0, y: 0.6, w: 1, h: 0.4 },
        },
      ],
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');

    expect(screen.getByRole('radio', { name: /분할/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: '60 : 40' })).toBeChecked();
    expect(screen.getByRole('switch', { name: '경계선 표시' })).not.toBeChecked();

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await waitFor(() =>
      expect(
        fetchSpy.mock.calls.some(
          ([url, init]) =>
            url === `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders` &&
            init?.method === 'POST',
        ),
      ).toBe(true),
    );
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(0);
  });

  it('옛 편집본(v1)의 정사각 출력은 다시 저장해도 남고, 세로 출력의 이름·자리를 잇는다', async () => {
    server.savedSchema = 1;
    server.savedSquare = true;
    const user = userEvent.setup();
    await openFrom('recipe=31');
    // 첫 출력이 정사각이어도 화면은 세로 출력으로 연다
    expect(screen.getByRole('radio', { name: /세로/ })).toBeChecked();

    await user.click(screen.getByRole('button', { name: '편집본 저장' }));
    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    const outputs = bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]
      ?.outputs as (Output & { outputId: string; aspect: string })[];
    // 저장된 순서 그대로 — 세로 자리에서 갈아 끼운다
    expect(outputs.map((o) => [o.outputId, o.aspect])).toEqual([
      ['square', 'SQUARE_1_1'],
      ['vert', 'VERT_9_16'],
    ]);
    expect(outputs[0]!.layers).toEqual([
      { crop: { x: 0.21875, y: 0, w: 0.5625, h: 1 }, box: { x: 0, y: 0, w: 1, h: 1 } },
    ]);
  });

  it('저장된 자막 줄은 편집기에도 보인다 — 영상에 타는 줄을 화면이 「미생성」이라 하지 않는다', async () => {
    server.savedSubtitles = {
      mode: 'BURN_ONLY',
      segments: [
        { startAtMs: STARTED_AT + 121_500, endAtMs: STARTED_AT + 123_000, text: '저장된 첫 자막' },
        { startAtMs: STARTED_AT + 200_000, endAtMs: STARTED_AT + 201_000, text: '구간 밖 자막' },
      ],
      position: { anchor: 'TOP', y: (10 / 240) * (9 / 16) },
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await user.click(screen.getByRole('tab', { name: '자막' }));

    expect(screen.getByText('저장된 첫 자막')).toBeInTheDocument();
    expect(screen.queryByText('구간 밖 자막')).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '상단' })).toBeChecked();

    // 고치지 않았으면 다시 저장하지 않는다 — 자막 방식·자리도 제자리로 돌아왔다
    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await waitFor(() =>
      expect(
        fetchSpy.mock.calls.some(
          ([url, init]) =>
            url === `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders` &&
            init?.method === 'POST',
        ),
      ).toBe(true),
    );
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(0);
  });

  it('CC만 고른 자막은 영상에 타지 않으니 미리보기에도 안 그린다', async () => {
    server.savedSubtitles = {
      mode: 'CC_ONLY',
      segments: [
        { startAtMs: STARTED_AT + 121_500, endAtMs: STARTED_AT + 123_000, text: '자막 파일에만' },
      ],
    };
    await openFrom('recipe=31');
    // 자막 도구 목록에는 있지만 결과 화면에 얹힌 번인 글자는 없다
    expect(screen.queryByText('“자막 파일에만”')).not.toBeInTheDocument();
  });
});
