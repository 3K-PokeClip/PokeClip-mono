import { screen, waitFor } from '@testing-library/react';
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

const STREAM_ID = 'stream-1';
const STARTED_AT = Date.parse('2026-09-20T10:00:00Z');

const server = vi.hoisted(() => ({
  cardWindow: { startMs: 60_000, endMs: 72_400 },
  savedTracks: [{ trackId: 0, gain: 1 }] as { trackId: number; gain: number }[],
  relation: 'OWNER',
  delegations: [] as {
    id: number;
    counterpartId: number;
    counterpartName: string;
    grantedAt: string;
  }[],
}));

function broadcastRow() {
  return {
    streamId: STREAM_ID,
    status: 'ended',
    relation: server.relation,
    startedAt: new Date(STARTED_AT).toISOString(),
    endedAt: new Date(STARTED_AT + 3_600_000).toISOString(),
    vodExpiresAt: null,
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
        schemaVersion: 1,
        streamId: STREAM_ID,
        cut: { inAtMs: STARTED_AT + 120_000, outAtMs: STARTED_AT + 150_000 },
        outputs: [{ outputId: 'o1', aspect: 'VERT_9_16', crop: { x: 0.34, y: 0, w: 0.32, h: 1 } }],
        audio: { tracks: server.savedTracks },
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
  server.relation = 'OWNER';
  server.delegations = [];
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
      schemaVersion: 1,
      streamId: STREAM_ID,
      cut: { inAtMs: STARTED_AT + 60_000, outAtMs: STARTED_AT + 72_400 },
      // 트랙을 따로 켜지 않았으면 최종 믹스(0) 하나다 — 0과 1~5를 같이 보내면 저장 문이 거절한다
      audio: { tracks: [{ trackId: 0, gain: 1 }] },
    });
    expect((doc?.outputs as { aspect: string }[])[0]?.aspect).toBe('VERT_9_16');
    expect(await screen.findByText(/편집본 #31 v1 저장됨/)).toBeInTheDocument();
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

  it('저장하기 전에는 영상을 주문하지 않고, 저장한 뒤에는 그 편집본으로 주문한다', async () => {
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    expect(await screen.findByText('먼저 편집본을 저장해요')).toBeInTheDocument();
    expect(fetchSpy.mock.calls.some(([url]) => String(url).endsWith('/renders'))).toBe(false);

    await user.click(save);
    await screen.findByText(/편집본 #31 v1 저장됨/);
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
    // 헤더 상태 문구와 토스트 둘 다 말한다
    expect((await screen.findAllByText(/영상 #77 주문됨/)).length).toBeGreaterThan(0);
  });
});

describe('StudioScreen — 편집본으로 다시 연 실제 편집기', () => {
  it('저장된 컷이 그대로 돌아와 같은 편집본에 새 판으로 저장된다 — 기준점이 저장 때와 같다', async () => {
    server.savedTracks = [{ trackId: 1, gain: 0.5 }];
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
    // 저장된 자르는 자리도 잃지 않는다
    expect((doc?.outputs as { crop: unknown }[])[0]?.crop).toEqual({
      x: 0.34,
      y: 0,
      w: 0.32,
      h: 1,
    });
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
