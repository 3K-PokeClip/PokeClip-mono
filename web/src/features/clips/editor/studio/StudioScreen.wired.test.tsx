import { resetPlaybackAccessForTests } from '@/api/playbackAccess';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { act, StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';
import { StudioScreen } from './StudioScreen';

// 실제 모드 편집기(POK-251) — 주소(?stream=&card= · ?recipe=)로 열어 clip에 저장·주문하는 길을 잰다.
// 녹화 재생 서버가 없는 환경이라 구간의 기준점은 방송 시작 시각이다(README 「편집 구간의 시각 기준점」).

// 주소 뒤 값. Next는 window.history.replaceState를 라우터에 이어 useSearchParams를 맞춘다(Next 문서
// 04-linking-and-navigating 「Native History API」): 모의도 그렇게 따라가야 「주소를 바꾸면 다시 읽는다」가 재어진다
const nav = vi.hoisted(() => ({
  search: '',
  listeners: new Set<() => void>(),
  pushed: [] as string[],
}));
vi.mock('next/navigation', async () => {
  const React = await import('react');
  const subscribe = (cb: () => void) => {
    nav.listeners.add(cb);
    return () => nav.listeners.delete(cb);
  };
  return {
    useSearchParams: () => {
      const search = React.useSyncExternalStore(subscribe, () => nav.search);
      return React.useMemo(() => new URLSearchParams(search), [search]);
    },
    useRouter: () => ({ push: (href: string) => nav.pushed.push(href) }),
  };
});

// 사용 통계(POK-357) — 편집기 진입·영상 만들기 이벤트를 잰다
const { track, setActiveRole } = vi.hoisted(() => ({ track: vi.fn(), setActiveRole: vi.fn() }));
vi.mock('@/lib/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/analytics')>()),
  track,
  setActiveRole,
  identifyUser: vi.fn(),
}));
const tracked = (name: string) =>
  track.mock.calls.filter(([n]) => n === name).map(([, props]) => props as Record<string, unknown>);

// 녹화 목록을 어느 키로 물었는지 잰다(POK-233) — 실제 구현을 그대로 부른다(재생 서버 주소가 없어 빈 목록이다)
const spansAsked = vi.hoisted(() => [] as string[]);
const playbackOn = vi.hoisted(() => ({ value: false }));
vi.mock('@/api/mediaPlayback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/mediaPlayback')>();
  return {
    ...actual,
    playbackConfigured: () => playbackOn.value,
    fetchRecordingSpans: (streamId: string) => {
      spansAsked.push(streamId);
      return actual.fetchRecordingSpans(streamId);
    },
  };
});

const STREAM_ID = 'stream-1';
const STARTED_AT = Date.parse('2026-09-20T10:00:00Z');

const server = vi.hoisted(() => ({
  /** 출입증 답(POK-317) */
  playbackAccess: 'grant' as 'grant' | 'fail' | 'expired',
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
  /** 내 트랙 이름(POK-240). 칸 i = trackId i, 빈 칸은 「트랙 n」 */
  myTrackLabels: {} as unknown,
  /** 영상 경로의 키(POK-233). undefined면 이 칸을 모르는 옛 서버다 */
  ingest: undefined as string | undefined,
  delegations: [] as {
    id: number;
    counterpartId: number;
    counterpartName: string;
    grantedAt: string;
  }[],
  /** 영상 주문의 답: 상태 코드와 봉투에 덧댈 칸. error가 있으면 그 거절이다 */
  render: { status: 201, clip: {} as Record<string, unknown> },
  renderError: null as null | { status: number; body: Record<string, unknown> },
  /** 저장된 편집본 지금 판의 업로드 정보(POK-291) */
  savedUploadRequest: null as unknown,
  /** 저장된 편집본에 이미 있는 영상 */
  savedLatestClip: null as unknown,
  /** 영상 상태 묻기(폴링)의 답. 시험이 준 약속이 풀릴 때 답한다 */
  clipPoll: null as null | ((clipId: number) => Promise<Response>),
  /** 카드 열기 기록(POK-393)의 답 */
  openStatus: 204,
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
      latestClip: server.savedLatestClip,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
      uploadRequest: server.savedUploadRequest,
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
    if (server.renderError) return jsonResponse(server.renderError.status, server.renderError.body);
    return jsonResponse(server.render.status, {
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
      ...server.render.clip,
    });
  }
  const polled = path.match(new RegExp(`^/api/clip/broadcasts/${STREAM_ID}/clips/(\\d+)$`));
  if (polled && method === 'GET' && server.clipPoll) return server.clipPoll(Number(polled[1]));
  // 상태 창의 「다시 올리기」(POK-492): 새 업로드 줄이 대기열에 선다
  const retried = path.match(
    new RegExp(`^/api/clip/broadcasts/${STREAM_ID}/clips/(\\d+)/uploads/retry$`),
  );
  if (retried && method === 'POST')
    return jsonResponse(201, { id: 6, clipId: Number(retried[1]), status: 'queued' });
  if (path === `/api/clip/broadcasts/${STREAM_ID}/playback-access` && method === 'POST')
    return server.playbackAccess === 'grant'
      ? jsonResponse(200, {
          streamId: STREAM_ID,
          // 1분짜리라 만료 5분 전 여유에 걸려 다시 쓰지 않는다: 받아 둔 것이 다음 시험으로 새지 않는다
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
        })
      : server.playbackAccess === 'expired'
        ? jsonResponse(410, { error: 'vod_expired' })
        : jsonResponse(500, { error: 'boom' });
  if (path === '/api/auth/me')
    return jsonResponse(200, { id: 9, email: 'me@example.com', name: '나' });
  if (path === '/api/streamers/9/audio-tracks')
    return jsonResponse(200, { labels: server.myTrackLabels });
  if (path.startsWith('/api/streamers/'))
    return jsonResponse(200, { labels: ['남의 트랙', null, null, null, null, null] });
  if (path === '/api/editor-delegations/as-editor') return jsonResponse(200, server.delegations);
  if (/^\/api\/clip\/jump-cards\/\d+\/open$/.test(path) && method === 'POST')
    return server.openStatus === 204
      ? new Response(null, { status: 204 })
      : jsonResponse(server.openStatus, { error: 'unavailable' });
  return jsonResponse(404, { error: 'unexpected_call' });
}

let fetchSpy: ReturnType<typeof stubFetch>;

beforeEach(() => {
  track.mockClear();
  server.cardWindow = { startMs: 60_000, endMs: 72_400 };
  server.savedTracks = [{ trackId: 0, gain: 1 }];
  server.savedSchema = 2;
  server.savedOutput = null;
  server.savedSquare = false;
  server.origin = null;
  server.savedSubtitles = null;
  server.relation = 'OWNER';
  server.myTrackLabels = {};
  server.playbackAccess = 'grant';
  server.delegations = [];
  server.ingest = undefined;
  server.render = { status: 201, clip: {} };
  server.renderError = null;
  server.savedUploadRequest = null;
  server.savedLatestClip = null;
  server.clipPoll = null;
  server.openStatus = 204;
  spansAsked.length = 0;
  nav.pushed.length = 0;
  fetchSpy = stubFetch(handle);
  // jsdom의 주소는 시험 사이에 남는다: 처음 자리로 돌리고, 바꾸면 모의 useSearchParams가 따라가게 한다
  window.history.replaceState(null, '', '/clips/editor/studio');
  const original = window.history.replaceState.bind(window.history);
  vi.spyOn(window.history, 'replaceState').mockImplementation((data, unused, url) => {
    original(data, unused, url);
    nav.search = window.location.search;
    for (const listener of nav.listeners) listener();
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  playbackOn.value = false;
  // 출입증 기억(만료 · 필요 없음)이 다음 시험으로 새지 않게
  resetPlaybackAccessForTests();
});

function bodiesOf(method: string, path: string) {
  return fetchSpy.mock.calls
    .filter(([url, init]) => String(url).split('?')[0] === path && init?.method === method)
    .map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
}

/** 카드 열기 기록 요청(POK-393). 본문이 없어 bodiesOf로는 못 센다 */
function openCalls() {
  return fetchSpy.mock.calls.filter(
    ([url, init]) =>
      /\/api\/clip\/jump-cards\/\d+\/open$/.test(String(url)) && init?.method === 'POST',
  );
}

/**
 * 편집기가 다 읽혀 섰다. 첫 읽기 스켈레톤에도 「편집본 저장」이 꺼진 채 서 있어(POK-444) 나타나기만 기다리면 읽기 전에
 * 지나간다 — 켜질 때까지 기다린다
 */
async function editorReady() {
  await waitFor(() => expect(screen.getByRole('button', { name: '편집본 저장' })).toBeEnabled());
  return screen.getByRole('button', { name: '편집본 저장' });
}

async function openFrom(search: string) {
  nav.search = search;
  renderWithProviders(<StudioScreen />);
  return editorReady();
}

/** 업로드 창의 공개 탭을 열고 「업로드」. 업로드 단추는 공개 탭에만 있다(POK-381) */
async function uploadFromPublishTab(user: ReturnType<typeof userEvent.setup>) {
  const dialog = within(screen.getByRole('dialog', { name: '유튜브에 올리기' }));
  await user.click(dialog.getByRole('tab', { name: '공개' }));
  await user.click(dialog.getByRole('button', { name: '업로드' }));
}

/** 「영상 만들기」 → 업로드 창 정보 탭에 제목을 적고 공개 탭에서 「업로드」 */
async function orderVideo(user: ReturnType<typeof userEvent.setup>, title = '보스 막타') {
  await user.click(screen.getByRole('button', { name: '영상 만들기' }));
  const dialog = within(screen.getByRole('dialog', { name: '유튜브에 올리기' }));
  const field = dialog.getByRole('textbox', { name: /제목/ });
  await user.clear(field);
  await user.type(field, title);
  await uploadFromPublishTab(user);
}

const RENDERS = `/api/clip/broadcasts/${STREAM_ID}/recipes/31/renders`;

/** 영상 주문 본문의 request 파트(JSON)와 thumbnail 파트 */
async function renderPartsOf(index = 0) {
  const init = fetchSpy.mock.calls.filter(([url, i]) => url === RENDERS && i?.method === 'POST')[
    index
  ]?.[1];
  const form = init?.body as FormData;
  return {
    request: JSON.parse(await (form.get('request') as Blob).text()) as { upload: unknown },
    thumbnail: form.get('thumbnail'),
  };
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

  it('녹화를 찾기 전에 출입증을 받는다: 문은 방송 번호다(POK-317, CDN 뒤 녹화는 쿠키가 없으면 403)', async () => {
    // 녹화 재생 서버가 있는 곳에서만 받는다. 모듈이 주소를 읽어 두므로 흉내 낸 함수만 주소가 있다고 답하게 한다
    playbackOn.value = true;
    server.ingest = 'key-studio';
    await openFrom(`?stream=${STREAM_ID}&card=5`);
    expect(
      fetchSpy.mock.calls.filter(
        ([url, init]) =>
          url === `/api/clip/broadcasts/${STREAM_ID}/playback-access` && init?.method === 'POST',
      ),
    ).toHaveLength(1);
  });

  it('보관 기간이 지난 방송이면 그렇다고 알린다: 녹화를 찾지 않는다(POK-335)', async () => {
    playbackOn.value = true;
    server.playbackAccess = 'expired';
    nav.search = `?stream=${STREAM_ID}&card=5`;
    renderWithProviders(<StudioScreen />);
    expect(
      await screen.findByText(/보관 기간\(60일\)이 지나 이 방송의 영상을 볼 수 없어요/),
    ).toBeInTheDocument();
    expect(spansAsked).toEqual([]);
  });

  it('같은 탭에서 다시 열어도 받아 둔 만료로 알리고 녹화를 찾지 않는다(PR #348 codex)', async () => {
    playbackOn.value = true;
    server.playbackAccess = 'expired';
    nav.search = `?stream=${STREAM_ID}&card=5`;
    const first = renderWithProviders(<StudioScreen />);
    await screen.findByText(/보관 기간\(60일\)이 지나 이 방송의 영상을 볼 수 없어요/);
    first.unmount();

    renderWithProviders(<StudioScreen />);
    expect(
      await screen.findByText(/보관 기간\(60일\)이 지나 이 방송의 영상을 볼 수 없어요/),
    ).toBeInTheDocument();
    expect(spansAsked).toEqual([]);
  });

  it('출입증을 못 받으면 녹화 없음으로 열지 않고 다시 열라고 알린다: 쿠키 없는 조회는 403이 「녹화 없음」으로 접힌다(PR #320 codex)', async () => {
    playbackOn.value = true;
    server.playbackAccess = 'fail';
    nav.search = `?stream=${STREAM_ID}&card=5`;
    renderWithProviders(<StudioScreen />);
    expect(await screen.findByText(/영상 출입증을 받지 못했어요/)).toBeInTheDocument();
    expect(spansAsked).toEqual([]);
  });
});

describe('StudioScreen: 카드 열기 기록(POK-393)', () => {
  it('카드에서 열면 그 카드의 열기 기록을 한 번 보낸다', async () => {
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await waitFor(() => expect(openCalls()).toHaveLength(1));
    expect(String(openCalls()[0]?.[0])).toBe('/api/clip/jump-cards/5/open');
  });

  it('개발 모드(StrictMode)에서 효과가 두 번 돌아도 한 번이다', async () => {
    nav.search = `stream=${STREAM_ID}&card=5`;
    renderWithProviders(
      <StrictMode>
        <StudioScreen />
      </StrictMode>,
    );
    await editorReady();

    await waitFor(() => expect(openCalls()).toHaveLength(1));
  });

  it('열기 기록이 실패해도 편집기는 열리고 오류를 말하지 않는다', async () => {
    server.openStatus = 503;
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await waitFor(() => expect(openCalls()).toHaveLength(1));
    expect(screen.getByRole('button', { name: '편집본 저장' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('편집본으로 열면 열기 기록을 안 보낸다', async () => {
    await openFrom('recipe=31');

    expect(openCalls()).toHaveLength(0);
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
    // 연 카드를 출처로 쿼리에 싣는다(POK-338): 본문(계약6)에는 칸을 더하지 않는다
    expect(
      fetchSpy.mock.calls.some(
        ([url, init]) =>
          url === `/api/clip/broadcasts/${STREAM_ID}/recipes?sourceCardId=5` &&
          init?.method === 'POST',
      ),
    ).toBe(true);
    expect(doc).not.toHaveProperty('sourceCardId');
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
    // 사람이 누른 저장은 한 번 센다 — 열거·숫자만 싣는다(제목·카드 번호 없음) (POK-357)
    const saves = tracked('clip_recipe_saved');
    expect(saves).toHaveLength(1);
    expect(saves[0]).toMatchObject({ first_save: true, source: 'card' });
    expect(Object.keys(saves[0] ?? {}).sort()).toEqual([
      'caption_position',
      'cut_seconds',
      'first_save',
      'layout',
      'source',
      'subtitle_mode',
    ]);
    expect(Number.isInteger(saves[0]?.cut_seconds)).toBe(true);
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

    await user.click(screen.getByRole('button', { name: '트랙 2 넣기' }));
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

    await orderVideo(user);

    await waitFor(() => expect(renders()).toBe(1));
    // 저장이 주문보다 먼저다 — 저장하지 않은 화면으로는 주문할 판이 없다
    const order = fetchSpy.mock.calls.map(
      ([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`,
    );
    const saveAt = order.indexOf(`POST /api/clip/broadcasts/${STREAM_ID}/recipes?sourceCardId=5`);
    const renderAt = order.indexOf(`POST /api/clip/broadcasts/${STREAM_ID}/recipes/31/renders`);
    expect(saveAt).toBeGreaterThanOrEqual(0);
    expect(saveAt).toBeLessThan(renderAt);
    // 「영상 만들기」 옆 알약이 상태를 말한다(POK-492)
    expect(
      await screen.findByRole('button', { name: '영상 상태: 차례 기다리는 중. 자세히 보기' }),
    ).toBeInTheDocument();
    // 토스트 대신 화면 낭독기에는 헤더의 늘 떠 있는 영역이 받았다고 읽어 준다(PR #454 3차 리뷰)
    await waitFor(() =>
      expect(
        screen
          .getAllByRole('status')
          .some((el) => el.textContent === '영상 상태: 차례 기다리는 중'),
      ).toBe(true),
    );
    // 사용 통계 — 진입은 한 번(첫 저장으로 주소가 ?recipe= 로 바뀌어도 다시 세지 않는다), 주문은 서버가 받은 뒤
    // 한 번이고 열거형 속성만 싣는다(제목·설명·태그 없음)
    expect(tracked('clip_editor_opened')).toEqual([{ source: 'card' }]);
    // 열기 기록도 같다: 첫 저장으로 주소가 바뀌어도 다시 안 보낸다(POK-393)
    expect(openCalls()).toHaveLength(1);
    const orders = tracked('video_render_requested');
    expect(orders).toHaveLength(1);
    expect(Object.keys(orders[0] ?? {}).sort()).toEqual([
      'category_id',
      'made_for_kids',
      'privacy_status',
      'scheduled',
      'thumbnail_source',
    ]);
    // 주문에 딸린 자동 저장은 사람이 누른 저장이 아니다
    expect(tracked('clip_recipe_saved')).toEqual([]);
  });

  it('저장 뒤 또 고치면 새 판으로 저장하고 주문한다 — 고치지 않았으면 다시 저장하지 않는다', async () => {
    // 저장 뒤 고친 것을 무시하고 옛 판을 렌더하면 화면과 다른 영상이 나온다(PR #200 codex P1)
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(save);
    await screen.findByText(/편집본 #31 v1 저장됨/);

    await orderVideo(user);
    await waitFor(() => expect(renders()).toBe(1));
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: '트랙 2 넣기' }));
    await orderVideo(user);
    await waitFor(() => expect(renders()).toBe(2));
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)[0]?.audio).toEqual({
      tracks: [{ trackId: 1, gain: 1 }],
    });
  });

  it('넣기 단추가 계약을 지킨다 — 최종 믹스와 방송 트랙은 하나만, 마지막 트랙은 못 끈다', async () => {
    // 저장할 때 조용히 바꾸면 화면과 다른 소리가 렌더된다(PR #200 codex P1)
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    const mix = () => screen.getByRole('button', { name: '최종 믹스(트랙 1) 넣기' });
    const track2 = () => screen.getByRole('button', { name: '트랙 2 넣기' });
    const on = (button: HTMLElement) => button.getAttribute('aria-pressed') === 'true';
    expect(on(mix())).toBe(true);

    await user.click(track2());
    expect(on(track2())).toBe(true);
    expect(on(mix())).toBe(false);

    // 마지막으로 켜진 트랙은 끄지 않고 이유를 말한다
    await user.click(track2());
    expect(on(track2())).toBe(true);
    expect(await screen.findByText('트랙을 하나는 켜 둬야 해요')).toBeInTheDocument();

    await user.click(mix());
    expect(on(mix())).toBe(true);
    expect(on(track2())).toBe(false);
  });
});

describe('StudioScreen: 편집기에서 고른 썸네일 장면(POK-389)', () => {
  it('녹화가 없으면 재생 위치를 몰라 썸네일 단추가 꺼져 있다', async () => {
    await openFrom(`stream=${STREAM_ID}&card=5`);
    expect(screen.getByRole('button', { name: '이 장면을 썸네일로' })).toBeDisabled();
  });

  it('고르지 않았으면 지금처럼 자동으로 연다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    const dialog = within(await screen.findByRole('dialog'));
    await user.click(dialog.getByRole('tab', { name: '썸네일' }));
    expect(dialog.getByRole('radio', { name: /영상 장면/ })).toBeChecked();
    expect(dialog.queryByRole('button', { name: /편집기에서 고름/ })).not.toBeInTheDocument();
  });
});

describe('StudioScreen: 트랙 줄 넣기 · 음량(POK-389)', () => {
  it('스트리머가 적은 트랙 이름으로 줄 머리가 서고, 음량 막대는 저장 계약대로 200%까지다', async () => {
    server.myTrackLabels = [null, '마이크', '게임', null, null, null];
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    expect(screen.getByRole('button', { name: '마이크 넣기' })).toBeInTheDocument();
    // 처음엔 최종 믹스만 켜져 있다: 마이크를 켜야 음량 숫자가 선다
    await user.click(screen.getByRole('button', { name: '마이크 넣기' }));
    await user.click(screen.getByRole('button', { name: '마이크 음량 100%' }));
    expect(screen.getByRole('slider', { name: '마이크 볼륨' })).toHaveAttribute(
      'aria-valuemax',
      '200',
    );
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

    await orderVideo(user);

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

    await orderVideo(user);

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
    expect(tracked('clip_editor_opened')).toEqual([{ source: 'recipe' }]);
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

  it.each([
    ['OWNER', 'owner'],
    ['EDITOR', 'editor'],
  ])(
    '편집기를 바로 열어도 연 방송의 관계(%s)로 역할 속성을 건다 — 진입 이벤트보다 먼저 (PR #382 2차 리뷰)',
    async (relation, role) => {
      server.relation = relation;
      setActiveRole.mockClear();
      track.mockClear();
      await openFrom(`stream=${STREAM_ID}&card=5`);

      await waitFor(() => expect(tracked('clip_editor_opened')).toHaveLength(1));
      expect(setActiveRole.mock.calls).toEqual([[role]]);
      const [roleAt] = setActiveRole.mock.invocationCallOrder;
      const openedAt =
        track.mock.invocationCallOrder[
          track.mock.calls.findIndex(([name]) => name === 'clip_editor_opened')
        ];
      expect(roleAt).toBeLessThan(openedAt ?? 0);
    },
  );

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

    await orderVideo(user);
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

    expect(
      within(screen.getByRole('region', { name: '자막' })).getByText('저장된 첫 자막'),
    ).toBeInTheDocument();
    expect(screen.queryByText('구간 밖 자막')).not.toBeInTheDocument();
    // 타임라인 자막 줄에도 구간 안 한 줄만 조각으로 선다(POK-389)
    expect(document.querySelectorAll('[data-subtitle-block]')).toHaveLength(1);
    expect(screen.getByRole('radio', { name: '위' })).toBeChecked();

    // 고치지 않았으면 다시 저장하지 않는다 — 자막 방식·자리도 제자리로 돌아왔다
    await orderVideo(user);
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

  it('AI 자막 만들기는 서버 기능이 없어 꺼져 있고 준비 중이라고 말한다(POK-389)', async () => {
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await user.click(screen.getByRole('tab', { name: '자막' }));

    expect(screen.getByRole('button', { name: /AI 자막 만들기/ })).toBeDisabled();
    expect(
      within(screen.getByRole('region', { name: 'AI 자막' })).getByText('준비 중'),
    ).toBeInTheDocument();
  });
});

describe('StudioScreen: 영상 만들기 + 유튜브 올리기(POK-291)', () => {
  const renders = () =>
    fetchSpy.mock.calls.filter(([url, init]) => url === RENDERS && init?.method === 'POST').length;
  const dialog = () => within(screen.getByRole('dialog', { name: '유튜브에 올리기' }));

  it('「영상 만들기」는 창만 연다: 확인하면 저장하고, 고른 정보가 multipart request 파트로 실린다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    expect(renders()).toBe(0);
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(0);

    await user.type(dialog().getByRole('textbox', { name: /제목/ }), ' 보스 막타 ');
    await user.type(dialog().getByRole('textbox', { name: /설명/ }), '역전');
    await user.type(dialog().getByRole('textbox', { name: /태그/ }), '롤,보스 막타{Enter}');
    // 카드에서 연 편집기: 처음 고른 장면은 하이라이트(카드 시각 = 구간 시작 + 5초, POK-381)
    await user.click(dialog().getByRole('tab', { name: '썸네일' }));
    expect(dialog().getByRole('button', { name: '하이라이트 0:05.0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await user.click(dialog().getByRole('tab', { name: '공개' }));
    await user.click(dialog().getByRole('radio', { name: '공개' }));
    await user.click(dialog().getByRole('button', { name: '업로드' }));

    await waitFor(() => expect(renders()).toBe(1));
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1);
    const { request, thumbnail } = await renderPartsOf();
    expect(request).toEqual({
      upload: {
        title: '보스 막타',
        description: '역전',
        tags: ['롤', '보스 막타'],
        categoryId: '20',
        privacyStatus: 'public',
        madeForKids: false,
        thumbnail: { source: 'scene', offsetMs: 5_000 },
      },
    });
    expect(thumbnail).toBeNull();
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: '유튜브에 올리기' })).not.toBeInTheDocument(),
    );

    // 받았다는 토스트 대신 「영상 만들기」 옆 알약이 상태를 말하고, 누르면 할 일이 펼쳐진다(POK-492)
    const pill = await screen.findByRole('button', {
      name: '영상 상태: 차례 기다리는 중. 자세히 보기',
    });
    expect(screen.queryByText('영상을 만들고 유튜브에 올릴게요')).not.toBeInTheDocument();
    // 헤더 문구는 저장 상태로 돌아간다 — 「주문」이라는 말이 화면에 없다
    expect(screen.getByText(/편집본 #31 v1 저장됨/)).toBeInTheDocument();
    expect(screen.queryByText(/주문/)).not.toBeInTheDocument();
    await user.click(pill);
    const panel = screen.getByRole('dialog', { name: '영상 상태' });
    expect(within(panel).getByText('영상을 만들 차례를 기다리고 있어요.')).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: '보관함에서 보기' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
  });

  it('업로드가 실패한 영상은 작은 창의 「다시 올리기」로 다시 올리고, 알약이 올리는 중으로 바뀐다(POK-492)', async () => {
    server.render = {
      status: 200,
      clip: {
        status: 'rendered',
        upload: {
          id: 5,
          clipId: 77,
          status: 'failed',
          error: { code: 'QUOTA_EXCEEDED', message: null },
        },
      },
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await orderVideo(user);

    await user.click(
      await screen.findByRole('button', { name: '영상 상태: 업로드 실패. 자세히 보기' }),
    );
    const panel = screen.getByRole('dialog', { name: '영상 상태' });
    // 보관함과 같은 사유
    expect(
      within(panel).getByText(
        '오늘 유튜브 업로드 한도를 다 써서 올리지 못했어요. 내일 다시 올려 주세요.',
      ),
    ).toBeInTheDocument();
    await user.click(within(panel).getByRole('button', { name: '다시 올리기' }));

    expect(
      await screen.findByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' }),
    ).toBeInTheDocument();
    expect(
      fetchSpy.mock.calls.filter(
        ([url, init]) =>
          String(url) === `/api/clip/broadcasts/${STREAM_ID}/clips/77/uploads/retry` &&
          init?.method === 'POST',
      ),
    ).toHaveLength(1);
  });

  it('이미지를 고르면 thumbnail 파트에 그 파일이 실린다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await user.type(dialog().getByRole('textbox', { name: /제목/ }), '제목');
    await user.click(dialog().getByRole('tab', { name: '썸네일' }));
    await user.click(dialog().getByRole('radio', { name: /내 이미지/ }));
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'cover.png', {
      type: 'image/png',
    });
    await user.upload(document.querySelector('input[type="file"]') as HTMLInputElement, file);
    await uploadFromPublishTab(user);

    await waitFor(() => expect(renders()).toBe(1));
    const { request, thumbnail } = await renderPartsOf();
    expect(request.upload).toMatchObject({ thumbnail: { source: 'file' } });
    expect((thumbnail as File).name).toBe('cover.png');
  });

  it('200(같은 판이 만드는 중이거나 이미 완성)이면 알약이 지금 상태를 그대로 말한다: 처음부터 만든다고 하지 않는다', async () => {
    server.render = { status: 200, clip: { status: 'rendering' } };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await orderVideo(user);
    expect(
      await screen.findByRole('button', { name: '영상 상태: 영상 만드는 중. 자세히 보기' }),
    ).toBeInTheDocument();

    server.render = {
      status: 200,
      clip: { status: 'rendered', upload: { id: 5, clipId: 77, status: 'queued' } },
    };
    await orderVideo(user);
    expect(
      await screen.findByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('영상을 만들고 유튜브에 올릴게요')).not.toBeInTheDocument();
  });

  it('이미 올린 편집본·연결 안 된 채널은 이유를 말하고 창을 닫는다', async () => {
    server.renderError = { status: 409, body: { error: 'already_uploaded', uploadId: 4 } };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await orderVideo(user);
    expect(
      await screen.findByText('이미 올린 편집본이에요. 고친 뒤 다시 만들어 주세요'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: '유튜브에 올리기' })).not.toBeInTheDocument();

    server.renderError = { status: 409, body: { error: 'youtube_not_linked', reason: 'UNLINKED' } };
    await orderVideo(user);
    expect(
      await screen.findByText('스트리머의 유튜브 채널이 연결돼 있지 않아요'),
    ).toBeInTheDocument();

    // 보관 기간(60일)이 지난 방송은 새로 만들 수 없다(POK-335)
    server.renderError = { status: 410, body: { error: 'vod_expired' } };
    await orderVideo(user);
    expect(
      await screen.findByText('보관 기간(60일)이 지나 이 방송으로는 새 영상을 만들 수 없어요'),
    ).toBeInTheDocument();
  });

  it('칸을 짚은 거절(400 field · 413 · 415)은 창 안 그 칸에 보이고 창이 남는다', async () => {
    server.renderError = { status: 400, body: { error: 'invalid_request', field: 'tags' } };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await orderVideo(user);
    await waitFor(() =>
      expect(dialog().getByRole('textbox', { name: /태그/ })).toHaveAccessibleDescription(
        /태그를 확인해 주세요/,
      ),
    );

    // 걸린 칸이 있는 탭(태그 → 정보)이 열린다. 썸네일 사유는 썸네일 탭에 보인다
    server.renderError = { status: 413, body: { error: 'payload_too_large' } };
    await uploadFromPublishTab(user);
    expect(await dialog().findByText('이미지는 10MB까지 올릴 수 있어요')).toBeInTheDocument();
    expect(dialog().getByRole('tab', { name: /썸네일/ })).toHaveAttribute('aria-selected', 'true');

    server.renderError = { status: 415, body: { error: 'unsupported_image' } };
    await uploadFromPublishTab(user);
    expect(await dialog().findByText('JPG나 PNG만 올릴 수 있어요')).toBeInTheDocument();
  });

  it('서버가 짚은 칸은 고치면 오류가 사라지고, 고쳐 보낸 것이 또 거절되면 다시 보인다', async () => {
    server.renderError = { status: 400, body: { error: 'invalid_request', field: 'title' } };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await orderVideo(user);
    const title = () => dialog().getByRole('textbox', { name: /제목/ });
    await waitFor(() => expect(title()).toHaveAccessibleDescription(/제목을 확인해 주세요/));

    await user.type(title(), '!');
    expect(title()).not.toHaveAccessibleDescription(/제목을 확인해 주세요/);

    await uploadFromPublishTab(user);
    await waitFor(() => expect(renders()).toBe(2));
    await waitFor(() => expect(title()).toHaveAccessibleDescription(/제목을 확인해 주세요/));
  });

  it('창이 떠 있는 동안 ⌘Z·Space는 뒤 편집기를 안 바꾼다: 창 안 라디오는 Space로 골라진다', async () => {
    const user = userEvent.setup();
    await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('button', { name: '트랙 2 넣기' }));
    expect(screen.getByRole('button', { name: '트랙 2 넣기' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await user.click(screen.getByRole('button', { name: '영상 만들기' }));
    await user.click(dialog().getByRole('tab', { name: '공개' }));
    dialog()
      .getByRole('switch', { name: /아동용 영상으로 올리기/ })
      .focus();
    await user.keyboard('{Meta>}z{/Meta}');
    const unlisted = dialog().getByRole('radio', { name: '일부 공개' });
    unlisted.focus();
    await user.keyboard(' ');
    expect(unlisted).toBeChecked();

    // 「취소」는 첫 탭(정보)에 있다
    await user.click(dialog().getByRole('tab', { name: '정보' }));
    await user.click(dialog().getByRole('button', { name: '취소' }));
    // ⌘Z가 창 뒤로 샜으면 트랙 2가 꺼져 있다
    expect(screen.getByRole('button', { name: '트랙 2 넣기' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('카드로 연 편집기를 처음 저장하면 주소가 ?recipe= 로 바뀌고, 다시 읽지 않아 실행취소가 남는다', async () => {
    const user = userEvent.setup();
    const save = await openFrom(`stream=${STREAM_ID}&card=5`);
    await user.click(screen.getByRole('button', { name: '트랙 2 넣기' }));

    await user.click(save);
    await screen.findByText(/편집본 #31 v1 저장됨/);

    expect(window.location.search).toBe('?recipe=31');
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1);
    // 스스로 만든 편집본이라 다시 읽지 않는다: 읽으면 새로 마운트되어 기록과 카드 창을 잃는다
    expect(fetchSpy.mock.calls.some(([url]) => url === '/api/clip/library/31')).toBe(false);
    expect(screen.getByRole('heading', { name: '점프카드 #5' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '작업 이전으로' })).toBeEnabled();

    // 다음 저장은 같은 편집본의 새 판이다(편집본이 하나 더 생기지 않는다)
    await user.click(screen.getByRole('button', { name: '편집본 저장' }));
    await waitFor(() =>
      expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1),
    );
    expect(bodiesOf('POST', `/api/clip/broadcasts/${STREAM_ID}/recipes`)).toHaveLength(1);
  });

  it('「다시 올리기」 전에 떠난 상태 확인의 늦은 답은 새 업로드를 덮지 않는다(PR #454 1차 리뷰)', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      // 상태 확인 답을 손으로 준다: 업로드 5번이 status 상태인 영상 #77
      const answers: ((status: string) => void)[] = [];
      server.clipPoll = (clipId) =>
        new Promise((resolve) => {
          answers.push((status) =>
            resolve(
              jsonResponse(200, {
                id: clipId,
                streamId: STREAM_ID,
                recipeId: 31,
                recipeVersion: 1,
                requestedBy: '9',
                status: 'rendered',
                progress: null,
                outputs: null,
                error: null,
                createdAt: '2026-09-20T11:00:00Z',
                updatedAt: '2026-09-20T11:00:00Z',
                upload: {
                  id: 5,
                  clipId,
                  status,
                  error: status === 'failed' ? { code: 'QUOTA_EXCEEDED', message: null } : null,
                },
              }),
            ),
          );
        });
      server.render = {
        status: 200,
        clip: { status: 'rendered', upload: { id: 5, clipId: 77, status: 'uploading' } },
      };
      const user = userEvent.setup();
      await openFrom('recipe=31');
      await orderVideo(user);
      await screen.findByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' });

      // 응답이 느려 상태 확인이 둘 겹쳤다
      vi.advanceTimersByTime(5_000);
      vi.advanceTimersByTime(5_000);
      await waitFor(() => expect(answers).toHaveLength(2));

      // 앞 확인이 실패를 알린다 → 작은 창의 「다시 올리기」 → 새 업로드가 대기열에 선다
      answers[0]!('failed');
      await user.click(
        await screen.findByRole('button', { name: '영상 상태: 업로드 실패. 자세히 보기' }),
      );
      await user.click(
        within(screen.getByRole('dialog', { name: '영상 상태' })).getByRole('button', {
          name: '다시 올리기',
        }),
      );
      await screen.findByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' });

      // 다시 올리기 전에 떠난 뒤 확인이 옛 실패로 늦게 도착한다 — 얹히면 알약이 「업로드 실패」로 돌아가고 확인이 멈춘다
      answers[1]!('failed');
      await new Promise((r) => setTimeout(r, 50));
      expect(
        screen.getByRole('button', { name: '영상 상태: 유튜브에 올리는 중. 자세히 보기' }),
      ).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('옛 영상을 묻던 늦은 답은 새 주문의 영상을 덮지 않는다', async () => {
    // 5초 묻기만 손으로 돌린다(다른 시계는 그대로)
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      let answerOld: (() => void) | null = null;
      server.clipPoll = (clipId) =>
        new Promise((resolve) => {
          answerOld = () =>
            resolve(
              jsonResponse(200, {
                id: clipId,
                streamId: STREAM_ID,
                recipeId: 31,
                recipeVersion: 1,
                requestedBy: '9',
                status: 'rendering',
                progress: { percent: 40 },
                outputs: null,
                error: null,
                createdAt: '2026-09-20T11:00:00Z',
                updatedAt: '2026-09-20T11:00:00Z',
              }),
            );
        });
      const user = userEvent.setup();
      await openFrom('recipe=31');
      await orderVideo(user);
      const waiting = { name: '영상 상태: 차례 기다리는 중. 자세히 보기' };
      await screen.findByRole('button', waiting);

      // 영상 #77을 묻는 요청이 떠났는데 답이 아직 안 왔다
      vi.advanceTimersByTime(5_000);
      await waitFor(() => expect(answerOld).not.toBeNull());

      // 그 사이 새 요청이 영상 #78을 받는다(대기열)
      server.render = { status: 201, clip: { id: 78 } };
      await orderVideo(user, '다시 만든 것');
      await screen.findByRole('button', waiting);

      answerOld!();
      // 늦은 답이 얹히면 알약이 #77의 「영상 만드는 중 40%」로 돌아간다
      await new Promise((r) => setTimeout(r, 50));
      expect(screen.getByRole('button', waiting)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /만드는 중 40%/ })).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('창을 다시 열면 같은 판에 남은 업로드 정보(제목·공개 범위·썸네일 방식)로 채운다', async () => {
    server.savedUploadRequest = {
      title: '먼저 적은 제목',
      privacyStatus: 'unlisted',
      thumbnailSource: 'scene',
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await user.click(screen.getByRole('button', { name: '영상 만들기' }));

    expect(dialog().getByRole('textbox', { name: /제목/ })).toHaveValue('먼저 적은 제목');
    await user.click(dialog().getByRole('tab', { name: '공개' }));
    expect(dialog().getByRole('radio', { name: '일부 공개' })).toBeChecked();
    await user.click(dialog().getByRole('tab', { name: '썸네일' }));
    expect(dialog().getByRole('radio', { name: /영상 장면/ })).toBeChecked();
  });

  it('남은 의도가 예약이면 카테고리·예약 시각까지 되살려, 그대로 다시 올려도 예약이 안 지워진다(PR #301 리뷰)', async () => {
    server.savedUploadRequest = {
      title: '예약한 제목',
      privacyStatus: 'private',
      thumbnailSource: 'scene',
      categoryId: '24',
      publishAt: '2099-01-01T09:00:00Z',
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    await user.click(screen.getByRole('button', { name: '영상 만들기' }));

    expect(dialog().getByRole('combobox', { name: '카테고리' })).toHaveTextContent('엔터테인먼트');
    await user.click(dialog().getByRole('tab', { name: '공개' }));
    expect(dialog().getByRole('radio', { name: '예약' })).toBeChecked();
    // 시험은 Asia/Seoul(vitest.config): 09:00Z = 18:00
    expect(dialog().getByLabelText('공개 날짜')).toHaveValue('2099-01-01');
    expect(dialog().getByLabelText('공개 시각')).toHaveValue('18:00');
    await user.click(dialog().getByRole('button', { name: '예약 업로드' }));

    await waitFor(() => expect(renders()).toBe(1));
    const { request } = await renderPartsOf();
    expect(request.upload).toMatchObject({
      categoryId: '24',
      privacyStatus: 'private',
      publishAt: '2099-01-01T09:00:00.000Z',
    });
  });
});

describe('StudioScreen: 로딩 표시(POK-444)', () => {
  /** 이 경로의 답만 붙잡아 둔다. 놓으면 원래 답이 간다 */
  function holdPath(path: string) {
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    fetchSpy = stubFetch(async (url, init) => {
      if (new URL(url, 'http://localhost').pathname === path) await gate;
      return handle(url, init);
    });
    return () => release();
  }

  it('첫 읽기에는 글자 한 줄 대신 편집기 모양의 스켈레톤이다: status 한 줄, 이전 화면으로는 나가고 고치는 버튼은 꺼져 있다', async () => {
    // 답이 오지 않는 서버: 첫 읽기가 끝나지 않는다
    stubFetch(() => new Promise<Response>(() => {}));
    nav.search = `stream=${STREAM_ID}&card=5`;
    const { container } = renderWithProviders(<StudioScreen />);

    // 낭독기엔 스켈레톤 대신 status 한 줄만 들린다
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('편집기를 불러오는 중');
    expect(screen.queryByText(/카드·방송을 불러오는 중/)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '클립 편집' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
      'href',
      '/clips/library',
    );
    for (const name of ['편집본 저장', '영상 만들기', '작업 이전으로', '작업 앞으로']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    // 로드 뒤와 같은 칸(소리 줄은 트랙 수만큼)으로 선다
    expect(container.querySelectorAll('[data-kind="audio"]')).toHaveLength(6);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('주소가 바뀌어 다시 읽는 동안 옛 화면을 두고 헤더 아래 막대 한 줄만 띄운다: 스켈레톤으로 번쩍이지 않는다', async () => {
    await openFrom(`stream=${STREAM_ID}&card=5`);
    expect(screen.getByRole('heading', { name: '점프카드 #5' })).toBeInTheDocument();

    const release = holdPath('/api/clip/library/31');
    act(() => window.history.replaceState(null, '', '?recipe=31'));

    // 옛 화면이 그대로 있고 만질 수 없다. 이전 화면으로는 나갈 수 있다
    expect(
      await screen.findByRole('progressbar', { name: '편집 대상을 다시 불러오는 중' }),
    ).not.toHaveAttribute('aria-valuenow');
    expect(screen.getByRole('heading', { name: '점프카드 #5' })).toBeInTheDocument();
    expect(screen.queryByText('편집기를 불러오는 중')).not.toBeInTheDocument();
    expect(document.querySelector('main')).toHaveAttribute('inert');
    expect(screen.getByRole('button', { name: '편집본 저장' })).toBeDisabled();
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toBeInTheDocument();

    release();
    expect(await screen.findByRole('heading', { name: '편집본 #31' })).toBeInTheDocument();
    expect(
      screen.queryByRole('progressbar', { name: '편집 대상을 다시 불러오는 중' }),
    ).not.toBeInTheDocument();
    expect(document.querySelector('main')).not.toHaveAttribute('inert');
    await editorReady();
  });

  it('편집본 저장을 보내는 동안 그 버튼만 돌고 영상 만들기는 꺼진다: 끝나면 둘 다 돌아온다', async () => {
    const user = userEvent.setup();
    await openFrom('recipe=31');
    const release = holdPath(`/api/clip/broadcasts/${STREAM_ID}/recipes/31`);

    await user.click(screen.getByRole('button', { name: '편집본 저장' }));
    const saving = screen.getByRole('button', { name: '편집본 저장 중' });
    expect(saving).toHaveAttribute('aria-busy', 'true');
    expect(saving).toBeDisabled();
    expect(screen.getByRole('button', { name: '영상 만들기' })).toBeDisabled();

    release();
    await screen.findAllByText(/편집본 #31 v3 저장됨/);
    expect(screen.getByRole('button', { name: '편집본 저장' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '영상 만들기' })).toBeEnabled();
    expect(bodiesOf('PUT', `/api/clip/broadcasts/${STREAM_ID}/recipes/31`)).toHaveLength(1);
  });

  it('확인 필요 영상이 있는 편집본을 새 판으로 저장하면 보관함으로 보내지 않는다: 보관함에 풀 곳이 없다', async () => {
    server.savedLatestClip = {
      id: 77,
      streamId: STREAM_ID,
      recipeId: 31,
      recipeVersion: 2,
      requestedBy: '9',
      status: 'rendered',
      progress: null,
      outputs: [{ outputId: 'o1', kind: 'video', s3Key: 'k' }],
      error: null,
      createdAt: '2026-09-20T11:00:00Z',
      updatedAt: '2026-09-20T11:00:00Z',
      upload: {
        id: 5,
        clipId: 77,
        outputId: 'o1',
        title: '보스 막타',
        status: 'checking',
        videoId: null,
        error: null,
        requestedBy: '9',
        createdAt: '2026-09-20T11:00:00Z',
        updatedAt: '2026-09-20T11:00:00Z',
      },
    };
    const user = userEvent.setup();
    await openFrom('recipe=31');
    const pill = { name: '영상 상태: 확인 필요. 자세히 보기' };

    await user.click(await screen.findByRole('button', pill));
    const before = within(screen.getByRole('dialog', { name: '영상 상태' }));
    expect(before.getByRole('link', { name: '보관함에서 확인' })).toBeInTheDocument();
    expect(before.getByText(/보관함에서 채널을 확인하고 결과를 알려/)).toBeInTheDocument();
    await user.keyboard('{Escape}');

    await user.click(screen.getByRole('button', { name: '편집본 저장' }));
    await screen.findAllByText(/편집본 #31 v3 저장됨/);

    await user.click(screen.getByRole('button', pill));
    const after = within(screen.getByRole('dialog', { name: '영상 상태' }));
    expect(after.getByRole('link', { name: '보관함에서 보기' })).toBeInTheDocument();
    expect(after.queryByRole('link', { name: '보관함에서 확인' })).not.toBeInTheDocument();
    expect(after.getByText(/지금 판으로 영상을 만들면 다시 올릴 수 있어요/)).toBeInTheDocument();
    expect(after.queryByText(/결과를 알려/)).not.toBeInTheDocument();
  });

  it('요청한 영상이 대기열에 있으면 알약 안에 불확정 막대가 선다: 퍼센트를 지어내지 않는다', async () => {
    const user = userEvent.setup();
    await openFrom('recipe=31');
    expect(screen.queryByRole('progressbar', { name: '영상 진행' })).not.toBeInTheDocument();

    await orderVideo(user);
    await screen.findByRole('button', { name: '영상 상태: 차례 기다리는 중. 자세히 보기' });
    expect(
      screen.getByRole('progressbar', { name: '영상 진행', hidden: true }),
    ).not.toHaveAttribute('aria-valuenow');
  });
});
