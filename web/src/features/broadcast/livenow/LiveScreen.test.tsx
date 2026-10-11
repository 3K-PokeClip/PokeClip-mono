import {
  act,
  StrictMode,
  useInsertionEffect,
  useLayoutEffect,
  useRef,
  type ReactNode,
} from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveScreen } from '@/features/broadcast/livenow/LiveScreen';
import { publishLiveData, resetLiveData } from '@/features/broadcast/livenow/liveDataStore';
import { forgetLiveStreamId } from '@/features/broadcast/streamSelection';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

// 라이브 대시보드(시안 1b)는 clip 창구에 붙어 있다(POK-251). 이 시험은 서버 응답을 흉내 내고
// 화면이 그 값으로 무엇을 그리는지 잰다. 시계는 가짜다 — 경과·마킹 시각이 결정적이어야 한다.

const nav = vi.hoisted(() => ({ search: '', pathname: null as string | null }));

// useSearchParams 대체 — ?stream=(useMediaSource)은 비워 두고 아래 env 고정과 함께 소스를 null로 만들어
// 플레이어가 「영상 신호 없음」 자리로 결정적으로 선다. ?mock=(오프라인 목업 토글)은 케이스가 nav.search로 정한다.
const notFound = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(nav.search),
  // pathname을 주면 주소창과 갈라 잰다: 다른 화면에서 넘어온 첫 그리기 때 주소창은 아직 옛 주소다
  usePathname: () => nav.pathname ?? window.location.pathname,
  notFound,
}));

// 영상 주소를 만든 키를 잰다(POK-233) — 실제 구현을 그대로 부르고 받은 키만 적는다
const mediaCalls = vi.hoisted(() => ({
  keys: [] as (string | null | undefined)[],
  spans: [] as string[],
  /**
   * 녹화 목록의 답. 답이 null이면 묻지 못한 것이다. 정하지 않으면 묻지 못함(null)으로 답한다: 재생 서버 주소가 빈 dev·운영과
   * 같다. 실제 구현은 부르지 않는다: 그 주소는 셸 환경값으로 정해져 셸에 주소가 있으면 시험이 갈렸다(리뷰 7라운드)
   */
  answer: null as Promise<{ startMs: number; durationSeconds: number }[] | null> | null,
  /** 재생 서버 주소가 정해졌나. 시험 환경은 비어 있다 */
  configured: false,
}));
vi.mock('@/features/player/mediaSource', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/player/mediaSource')>();
  return {
    ...actual,
    useMediaSource: (key?: string | null) => {
      mediaCalls.keys.push(key);
      return actual.useMediaSource(key);
    },
  };
});
vi.mock('@/api/mediaPlayback', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/mediaPlayback')>();
  return {
    ...actual,
    playbackConfigured: () => mediaCalls.configured,
    lookupRecordingSpans: (streamId: string) => {
      mediaCalls.spans.push(streamId);
      return mediaCalls.answer ?? Promise.resolve(null);
    },
  };
});

// 채팅 패널이 그려질 때 받은 mode: 지난 방송으로 들어오는 동안 라이브 문구로 깜박이는지 잰다(리뷰 2라운드)
const chatModes = vi.hoisted(() => [] as (string | undefined)[]);
// 채팅 패널이 그려질 때 받은 첫 줄: 카드 시점 줄이 수를 모르는 동안 한 번이라도 「0건」으로 그려지는지 잰다(리뷰 7라운드)
const chatHeads = vi.hoisted(() => [] as string[]);
vi.mock('@/features/broadcast/livenow/ChatPanel', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/broadcast/livenow/ChatPanel')>();
  const { createElement } = await import('react');
  return {
    ...actual,
    ChatPanel: (props: import('react').ComponentProps<typeof actual.ChatPanel>) => {
      chatModes.push(props.mode);
      const head = props.messages[0];
      if (head?.kind === 'system') chatHeads.push(head.text);
      return createElement(actual.ChatPanel, props);
    },
  };
});

// 플레이어가 받은 상태: 지난 방송에 들어오는 동안 플레이어만 라이브·오프라인 문구로 깜박이는지 잰다(리뷰 3라운드)
const playerStatuses = vi.hoisted(() => [] as (string | undefined)[]);
vi.mock('@/features/player/GlassPlayer', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/player/GlassPlayer')>();
  const { createElement } = await import('react');
  return {
    ...actual,
    GlassPlayer: (props: import('react').ComponentProps<typeof actual.GlassPlayer>) => {
      playerStatuses.push(props.broadcastStatus);
      return createElement(actual.GlassPlayer, props);
    },
  };
});

const STREAM_ID = 'live-1';
const NOW = Date.parse('2026-09-26T12:00:00Z');
/** 방송 경과 1:24:03 */
const STARTED_AT = NOW - 5043_000;
const ME = '9';

interface Card {
  id: number;
  streamId: string;
  source: string;
  streamTimestampMs: number;
  window: { startMs: number; endMs: number };
  score: number | null;
  evidence: Record<string, unknown> | null;
  claimedBy: string | null;
  hidden: boolean;
  eventSeq: number;
  createdAt: string;
}

function card(id: number, over: Partial<Card>): Card {
  return {
    id,
    streamId: STREAM_ID,
    source: 'chat-surge',
    streamTimestampMs: id * 60_000,
    window: { startMs: id * 60_000 - 20_000, endMs: id * 60_000 + 22_000 },
    score: 80,
    evidence: { ratio: 3.2, count: 120 },
    claimedBy: null,
    hidden: false,
    eventSeq: id,
    // 만든 시각은 전부 차트(최근 10분) 밖이다 — 마커는 만든 시각이 아니라 카드가 가리키는 방송 시점에
    // 찍혀야 하므로(PR #200 codex), 만든 시각 기준이면 「하이라이트 4곳」 시험이 깨진다
    createdAt: new Date(NOW - 30 * 60_000 - id * 60_000).toISOString(),
    ...over,
  };
}

// 자동 6 · 수동 2. eventSeq가 큰 것이 앞에 선다.
const CARDS: Card[] = [
  card(8, { score: 97, streamTimestampMs: 5_000_000, eventSeq: 80 }),
  // 차트(최근 10분)에 마커가 서는 넷: 8·7·6·5 — 마커는 카드가 가리키는 방송 시점에 찍힌다
  card(7, {
    source: 'hotkey',
    score: null,
    evidence: null,
    eventSeq: 70,
    streamTimestampMs: 4_900_000,
  }),
  card(6, { claimedBy: '5', eventSeq: 60, streamTimestampMs: 4_800_000 }),
  card(5, { claimedBy: ME, eventSeq: 50, streamTimestampMs: 4_700_000 }),
  card(4, { streamTimestampMs: 2_842_000, eventSeq: 40 }),
  card(3, { source: 'hotkey', score: null, evidence: null, eventSeq: 30 }),
  card(2, { eventSeq: 20 }),
  card(1, { eventSeq: 10 }),
];

const chatAt = (secondsAgo: number) => new Date(NOW - secondsAgo * 1000).toISOString();
const CHAT = [
  {
    kind: 'chat',
    id: 1,
    time: chatAt(90),
    nickname: '새벽러',
    senderChannelId: 'c1',
    text: '오늘도 새벽 랭크인가요',
    amount: null,
  },
  {
    kind: 'chat',
    id: 2,
    time: chatAt(60),
    nickname: '너구리팬',
    senderChannelId: 'c2',
    text: 'ㅋㅋㅋㅋ',
    amount: null,
  },
  {
    kind: 'chat',
    id: 3,
    time: chatAt(50),
    nickname: '지나가던',
    senderChannelId: 'c3',
    text: 'ㅋㅋㅋㅋ 미쳤다',
    amount: null,
  },
  {
    kind: 'donation',
    id: 4,
    time: chatAt(40),
    nickname: '도네초코',
    senderChannelId: 'c4',
    text: '승급 기원!! 가즈아',
    amount: 5000,
  },
  {
    kind: 'chat',
    id: 5,
    time: chatAt(20),
    nickname: '랭커',
    senderChannelId: 'c5',
    text: 'ㅋㅋㅋㅋ 미쳤다',
    amount: null,
  },
  {
    kind: 'chat',
    id: 6,
    time: chatAt(5),
    nickname: '막차',
    senderChannelId: 'c6',
    text: '방금 그거 다시 보여주세요',
    amount: null,
  },
];

// 최근 10분, 30초 버킷 20개. 후원은 두 버킷에 있다.
const CHART = {
  bucketSeconds: 30,
  appliedOffsetMs: 0,
  buckets: Array.from({ length: 20 }, (_, i) => ({
    start: new Date(NOW - (20 - i) * 30_000).toISOString(),
    chats: 10 + ((i * 7) % 30),
    donations: i === 6 || i === 14 ? 1 : 0,
  })),
};

const INFO = {
  latest: {
    title: '새벽 랭크 올리기 — 다이아 승급전 가보자',
    tags: ['다이아승급', '솔랭'],
    category: '리그 오브 레전드',
    viewers: 1842,
  },
  series: [
    { observedAt: new Date(NOW - 8 * 60_000).toISOString(), viewers: 1500 },
    { observedAt: new Date(NOW - 5 * 60_000).toISOString(), viewers: 2310 },
    { observedAt: new Date(NOW - 60_000).toISOString(), viewers: 1842 },
  ],
};

const server = vi.hoisted(() => ({
  live: true,
  failLive: false,
  relation: 'OWNER',
  hidden: new Set<number>(),
  chatPaged: false,
  vodChatEndless: false,
  /** 출입증: 'off'는 서명 재료 없음(503), 'grant'는 10분짜리, 'fail'은 500 */
  playback: 'off' as 'off' | 'grant' | 'fail',
  /** 지난 방송의 시각 기준점(POK-255). null이면 서버가 모른다 */
  vodOrigin: null as number | null,
  /** 영상 경로의 키(POK-233). undefined면 이 칸을 모르는 옛 서버다 */
  ingest: undefined as string | undefined,
  /** 방송 목록 줄의 제목(POK-259). undefined면 이 칸을 모르는 옛 서버다 */
  listTitle: undefined as string | undefined,
  /** 방송 정보 최신 관측에 제목이 비었다(그 회차에 제목을 못 얻었다) */
  infoTitleMissing: false,
  /** 지난 방송 줄에 시작 시각이 없다(종료 알림이 먼저 와서 생긴 자리표시 방송) */
  vodStartUnknown: false,
  /** 지난 방송이 15시간이다. 차트는 끝 12시간만 읽는다 */
  vodLong: false,
  /** 지난 방송 시간대(한 시간 넘게 지난 창)를 읽는 차트만 실패한다 */
  vodChartFail: false,
  /** 방송 정보 시청자 기록의 마지막 관측이 비었다(그 회차에 목록에서 못 찾음) */
  infoLastNull: false,
  /** 방송 정보 조회가 실패한다 */
  infoFail: false,
  /** 라이브 방송이 끝났다: 「방송 중」 목록에서 빠지고 지난 목록에 끝난 줄로 온다 */
  liveEnded: false,
  /**
   * 방송 번호로 명부를 찾는 질의의 답을 이 약속이 풀릴 때까지 붙든다: 명부보다 다른 답이 먼저 오는 순서를 잰다. 방송 번호를
   * 고르는 질의(limit=1)는 붙들지 않는다
   */
  listGate: null as Promise<void> | null,
  /** 「방송 중」 목록의 방송이 위임받은 다른 방송(live-2, EDITOR)으로 바뀌었다 */
  liveStream2: false,
  /** since를 단 방송 정보 조회만 실패한다 */
  infoSinceFail: false,
  /** 방송 정보 시청자 기록이 전부 빈 관측이다(목록에서 채널을 한 번도 못 찾음) */
  infoNoViewers: false,
  /** since 없는 방송 정보 답을 이 약속이 풀릴 때까지 붙든다: 늦게 온 옛 창의 답을 잰다 */
  infoNoSinceGate: null as Promise<void> | null,
  /** 지금 기준 최근 창의 채팅 차트 답을 이 약속이 풀릴 때까지 붙든다 */
  chartRecentGate: null as Promise<void> | null,
  /** 방송 정보에 최신 관측이 없다(수집기가 그 방송을 한 번도 못 쟀다) */
  infoNoLatest: false,
  /** 지난 방송 채팅: 1시간 창은 카드 시점의 채팅을 주지 않고(창마다 상한이 있다) 시점 앞뒤 60초 창만 그 1건을 준다 */
  vodFocusChat: false,
  /** vodFocusChat의 시점 앞뒤 60초 창 답을 이 약속이 풀릴 때까지 붙든다 */
  focusGate: null as Promise<void> | null,
  /** vodFocusChat의 시점 앞뒤 60초 창 조회가 실패한다 */
  focusFail: false,
  /**
   * 지난 방송 차트가 창마다 첫 칸에 채팅 7건뿐이다. 이 서버는 창마다 30초 칸 20개(10분)를 주므로 2시간 반 방송(1시간 창 셋)은
   * 30분에 21건, 분당 평균 0.7이다
   */
  vodChartSparse: false,
  /** vodChartSparse의 첫 칸이 12,345건이다: 30분에 37,035건, 분당 평균 1,234.5(천 단위 자리) */
  vodChartBusy: false,
  /** 지난 방송의 끝 시각을 이만큼 늦춘다(침묵으로 닫힌 방송에 진짜 종료가 뒤늦게 온 경우) */
  vodEndShiftMs: 0,
}));

/** 지난 방송(2시간 반) — 수집기 1시간 창을 넘는다 */
const VOD_ID = 'vod-1';
const VOD_STARTED = NOW - 10 * 3_600_000;
const VOD_ENDED = VOD_STARTED + 2.5 * 3_600_000;

/** 방송 목록(명부)의 답 */
function listResponse(u: URL): Response {
  if (server.failLive) return jsonResponse(503, { error: 'unavailable' });
  const live = u.searchParams.get('state') === 'live' && server.live;
  const row = {
    streamId: server.liveStream2 ? 'live-2' : STREAM_ID,
    status: 'live',
    relation: server.liveStream2 ? 'EDITOR' : server.relation,
    startedAt: new Date(STARTED_AT).toISOString(),
    endedAt: null,
    vodExpiresAt: null,
    ingestStreamId: server.ingest,
    title: server.listTitle,
  };
  const vod = {
    streamId: VOD_ID,
    status: 'ended',
    relation: 'OWNER',
    startedAt: server.vodStartUnknown ? null : new Date(VOD_STARTED).toISOString(),
    endedAt: new Date(
      (server.vodLong ? VOD_STARTED + 15 * 3_600_000 : VOD_ENDED) + server.vodEndShiftMs,
    ).toISOString(),
    vodExpiresAt: null,
    timelineOriginAt: server.vodOrigin === null ? null : new Date(server.vodOrigin).toISOString(),
    ingestStreamId: server.ingest,
  };
  if (u.searchParams.get('state') === 'past') {
    const endedLive = { ...row, status: 'ended', endedAt: new Date(Date.now()).toISOString() };
    return jsonResponse(200, {
      broadcasts: server.liveEnded ? [endedLive, vod] : [vod],
      nextCursor: null,
    });
  }
  return jsonResponse(200, {
    broadcasts: live && !server.liveEnded ? [row] : [],
    nextCursor: null,
  });
}

/** 방송 정보의 답. 수집기는 since가 없으면 최근 1시간만 준다(DEFAULT_WINDOW): 몇 시간 전에 끝난 지난 방송은 기록이 비어 온다 */
function infoResponse(path: string, noSince: boolean): Response {
  if (server.infoSinceFail && !noSince) return jsonResponse(503, { error: 'unavailable' });
  const info = server.infoNoLatest
    ? { ...INFO, latest: null }
    : server.infoTitleMissing
      ? { ...INFO, latest: { ...INFO.latest, title: null } }
      : INFO;
  const body = server.infoNoViewers
    ? { ...info, series: info.series.map((p) => ({ ...p, viewers: null })) }
    : server.infoLastNull
      ? { ...info, series: [...info.series.slice(0, -1), { ...info.series[2], viewers: null }] }
      : info;
  return jsonResponse(
    200,
    noSince && path.includes(`/${VOD_ID}/`) ? { ...body, series: [] } : body,
  );
}

function handle(url: string) {
  const u = new URL(url, 'http://localhost');
  const path = u.pathname;
  if (path === '/api/clip/broadcasts')
    return server.listGate && u.searchParams.get('limit') !== '1'
      ? server.listGate.then(() => listResponse(u))
      : listResponse(u);
  if (path.endsWith('/jump-cards')) {
    // 숨긴 카드는 includeHidden일 때만 hidden:true로 온다(서버 기본은 뺀다)
    const withHidden = u.searchParams.get('includeHidden') === 'true';
    const cards = CARDS.map((c) => ({ ...c, hidden: server.hidden.has(c.id) }));
    return jsonResponse(200, { cards: withHidden ? cards : cards.filter((c) => !c.hidden) });
  }
  if (path.endsWith('/chat-chart') && server.chartRecentGate) {
    const gate = server.chartRecentGate;
    if (Date.parse(u.searchParams.get('to') ?? '') >= NOW - 60_000)
      return gate.then(() => jsonResponse(200, CHART));
  }
  if (path.endsWith('/chat-chart')) {
    if (server.vodChartSparse && path.includes(`/${VOD_ID}/`)) {
      const first = server.vodChartBusy ? 12_345 : 7;
      return jsonResponse(200, {
        ...CHART,
        buckets: CHART.buckets.map((b, i) => ({ ...b, chats: i === 0 ? first : 0, donations: 0 })),
      });
    }
    if (server.vodChartFail && Date.parse(u.searchParams.get('from')!) < NOW - 3_600_000)
      return jsonResponse(503, { error: 'unavailable' });
    return jsonResponse(200, CHART);
  }
  if (path.endsWith('/broadcast-info')) {
    if (server.infoFail) return jsonResponse(503, { error: 'unavailable' });
    const noSince = u.searchParams.get('since') === null;
    return noSince && server.infoNoSinceGate
      ? server.infoNoSinceGate.then(() => infoResponse(path, noSince))
      : infoResponse(path, noSince);
  }
  if (path.endsWith('/chat-messages')) {
    if (server.vodFocusChat && path.includes(`/${VOD_ID}/`)) {
      const from = Date.parse(u.searchParams.get('from') ?? '');
      const to = Date.parse(u.searchParams.get('to') ?? '');
      if (to - from > 5 * 60_000)
        return jsonResponse(200, { items: [], nextCursor: null, appliedOffsetMs: 0 });
      const item = { ...CHAT[0], id: 99, time: new Date(from + 30_000).toISOString() };
      const focus = () =>
        server.focusFail
          ? jsonResponse(503, { error: 'unavailable' })
          : jsonResponse(200, { items: [item], nextCursor: null, appliedOffsetMs: 0 });
      return server.focusGate ? server.focusGate.then(focus) : focus();
    }
    // 채팅이 아주 많은 지난 방송 — 어느 창이든 쪽이 끝나지 않는다
    if (server.vodChatEndless && path.includes(`/${VOD_ID}/`))
      return jsonResponse(200, { items: [], nextCursor: 'more', appliedOffsetMs: 0 });
    // 200건이 넘으면 서버는 오래된 쪽부터 주고 커서를 단다 — chatPaged면 두 쪽으로 나눠 준다
    if (server.chatPaged && u.searchParams.get('cursor') === null)
      return jsonResponse(200, { items: CHAT.slice(0, 3), nextCursor: 'p2', appliedOffsetMs: 0 });
    if (server.chatPaged)
      return jsonResponse(200, { items: CHAT.slice(3), nextCursor: null, appliedOffsetMs: 0 });
    return jsonResponse(200, { items: CHAT, nextCursor: null, appliedOffsetMs: 0 });
  }
  // 통로는 용량 초과로 답한다 — 폴링이 카드를 채운다(통로 자체는 useLiveMockState의 몫)
  if (path.endsWith('/events')) return jsonResponse(503, { error: 'sse_capacity' });
  if (path.endsWith('/playback-access')) {
    if (server.playback === 'grant')
      return jsonResponse(200, {
        streamId: STREAM_ID,
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      });
    if (server.playback === 'fail') return jsonResponse(500, { error: 'boom' });
    return jsonResponse(503, { error: 'signing_unavailable' });
  }
  if (path === '/api/chzzk-link')
    return jsonResponse(200, { linked: true, channelName: '게임하는너구리', status: 'ACTIVE' });
  if (path === '/api/auth/me')
    return jsonResponse(200, {
      id: 9,
      email: 'me@example.com',
      name: '박편집',
      profileImageUrl: null,
    });
  if (path.endsWith('/hide')) {
    server.hidden.add(Number(path.split('/')[4]));
    return new Response(null, { status: 204 });
  }
  if (path.endsWith('/claim')) return new Response(null, { status: 204 });
  return jsonResponse(404, { error: 'unexpected_call' });
}

/** sub=9인 access 토큰 — 카드의 claimedBy와 견줘 「내가 편집 중」을 가른다 */
function tokenFor(sub: string) {
  const b64 = (v: object) => btoa(JSON.stringify(v)).replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64({ sub })}.sig`;
}

let fetchSpy: ReturnType<typeof stubFetch>;

beforeEach(() => {
  nav.search = '';
  nav.pathname = null;
  server.vodOrigin = null;
  server.ingest = undefined;
  server.listTitle = undefined;
  server.infoTitleMissing = false;
  server.vodStartUnknown = false;
  server.vodLong = false;
  server.vodChartFail = false;
  server.infoLastNull = false;
  server.infoFail = false;
  server.liveEnded = false;
  server.listGate = null;
  server.infoNoSinceGate = null;
  server.liveStream2 = false;
  server.infoSinceFail = false;
  server.infoNoViewers = false;
  server.chartRecentGate = null;
  server.infoNoLatest = false;
  server.vodFocusChat = false;
  server.focusGate = null;
  server.focusFail = false;
  server.vodChartSparse = false;
  server.vodChartBusy = false;
  server.vodEndShiftMs = 0;
  chatModes.length = 0;
  chatHeads.length = 0;
  playerStatuses.length = 0;
  // 앞 시험이 단언에서 멈춰도 지난 방송 주소가 다음 시험으로 새지 않는다
  window.history.pushState({}, '', '/');
  mediaCalls.keys = [];
  mediaCalls.spans = [];
  mediaCalls.answer = null;
  mediaCalls.configured = false;
  server.live = true;
  server.failLive = false;
  server.relation = 'OWNER';
  server.hidden = new Set();
  server.chatPaged = false;
  server.vodChatEndless = false;
  server.playback = 'off';
  notFound.mockClear();
  // env는 셸에서 그대로 상속된다 — 로컬/CI 셸에 NEXT_PUBLIC_MEDIA_*가 export돼 있어도 소스가 null이게 고정한다.
  vi.stubEnv('NEXT_PUBLIC_MEDIA_STUB_URL', '');
  vi.stubEnv('NEXT_PUBLIC_MEDIA_LIVE_BASE_URL', '');
  vi.useFakeTimers({ now: NOW });
  forgetLiveStreamId();
  resetLiveData();
  useAuthStore.setState({ accessToken: tokenFor(ME) });
  fetchSpy = stubFetch(handle);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs(); // NODE_ENV 스텁이 다음 테스트로 새면 ?mock=offline 분기가 죽는다
  vi.unstubAllGlobals();
  vi.restoreAllMocks(); // document.addEventListener 스파이 — 단언이 먼저 실패해도 걷힌다
  useAuthStore.setState({ accessToken: null });
});

/** 응답 → 상태 → 다음 요청으로 이어지는 사슬을 가짜 시계를 움직이지 않고 끝까지 푼다 */
async function settle() {
  for (let i = 0; i < 12; i += 1) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }
}

async function renderLive() {
  const result = renderWithProviders(<LiveScreen />);
  await settle();
  return result;
}

describe('LiveScreen — 방송 꺼짐', () => {
  it('방송 중인 것이 있으면 대시보드를 그린다', async () => {
    const addListener = vi.spyOn(document, 'addEventListener');
    await renderLive();

    expect(screen.getByRole('button', { name: /수동 마킹/ })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: '지금은 방송 중이 아니에요' }),
    ).not.toBeInTheDocument();
    // 아래 오프라인 케이스의 대조군 — 이 스파이가 F8 리스너를 실제로 잡아낸다
    expect(addListener).toHaveBeenCalledWith('keydown', expect.any(Function));
  });

  it('방송 중인 것이 없으면 꺼짐 화면을 그린다', async () => {
    server.live = false;
    await renderLive();

    expect(
      screen.getByRole('heading', { level: 1, name: '지금은 방송 중이 아니에요' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /수동 마킹/ })).not.toBeInTheDocument();
  });

  it('꺼져 있다가 방송이 시작되면 새로 고침 없이 대시보드로 넘어간다', async () => {
    server.live = false;
    await renderLive();
    expect(screen.getByRole('heading', { name: '지금은 방송 중이 아니에요' })).toBeInTheDocument();

    server.live = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await settle();
    expect(screen.getByRole('button', { name: /수동 마킹/ })).toBeInTheDocument();
  });

  it('방송 목록을 잠깐 못 읽어도 꺼짐 화면으로 바꾸지 않는다 — 모르는 것은 「없다」가 아니다', async () => {
    await renderLive();
    expect(screen.getByRole('button', { name: /수동 마킹/ })).toBeInTheDocument();

    server.failLive = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await settle();
    expect(screen.getByRole('button', { name: /수동 마킹/ })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: '지금은 방송 중이 아니에요' }),
    ).not.toBeInTheDocument();
  });

  it('?mock=offline이면 대시보드 대신 오프라인 안내를 그린다', async () => {
    nav.search = 'mock=offline';
    await renderLive();

    expect(
      screen.getByRole('heading', { level: 1, name: '지금은 방송 중이 아니에요' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '지난 방송 보기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /수동 마킹/ })).not.toBeInTheDocument();
    expect(screen.queryByText('실시간 채팅')).not.toBeInTheDocument();
  });

  it('오프라인에서는 대시보드 훅이 돌지 않아 F8 리스너가 붙지 않는다', async () => {
    // 분기가 훅보다 위에 있어야 한다 — 화면만 숨기면 문서 리스너가 살아 보이지 않는 마킹을 찍는다
    const addListener = vi.spyOn(document, 'addEventListener');
    nav.search = 'mock=offline';
    await renderLive();

    expect(addListener).not.toHaveBeenCalledWith('keydown', expect.any(Function));
  });

  it('프로덕션에서는 목업 토글이 죽어 방송 중 화면이 사라지지 않는다', async () => {
    nav.search = 'mock=offline';
    vi.stubEnv('NODE_ENV', 'production');
    await renderLive();

    expect(screen.getByRole('button', { name: /수동 마킹/ })).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: '지금은 방송 중이 아니에요' }),
    ).not.toBeInTheDocument();
  });
});

describe('LiveScreen — 실시간 통로', () => {
  it('방송 상태가 정해져도 통로를 다시 열지 않는다 — 다시 열면 그 사이 중계 채팅을 잃는다', async () => {
    await renderLive();
    // 방송 시계가 「확인 중 → 방송 중」과 시작 시각을 알린 뒤다. 차트만 다시 읽고 통로는 처음 한 번뿐이어야 한다
    const events = fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/events'));
    expect(events).toHaveLength(1);
    const charts = fetchSpy.mock.calls.filter(([url]) => String(url).includes('/chat-chart'));
    expect(charts.length).toBeGreaterThan(1);
  });
});

describe('LiveScreen — 영상 출입증', () => {
  const accessCalls = () =>
    fetchSpy.mock.calls.filter(([url]) => String(url).endsWith('/playback-access')).length;

  it('출입증은 만료 5분 전에 다시 받는다 — 한 번만 받으면 한 시간 뒤 재생이 멈춘다', async () => {
    // 쿠키가 끝나면 새 영상 조각이 403이 된다(PR #200 codex P1)
    server.playback = 'grant';
    await renderLive();
    expect(accessCalls()).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4 * 60_000);
    });
    expect(accessCalls()).toBe(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2 * 60_000);
    });
    expect(accessCalls()).toBe(2);
  });

  it('출입증을 못 받으면 1분 뒤 다시 받는다', async () => {
    server.playback = 'fail';
    await renderLive();
    expect(accessCalls()).toBe(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(accessCalls()).toBe(2);
  });

  it('서명 재료가 없는 서버(503)에는 다시 묻지 않는다 — 출입증이 필요 없는 곳이다', async () => {
    await renderLive();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000);
    });
    expect(accessCalls()).toBe(1);
  });
});

describe('LiveScreen — 방송 정보 바', () => {
  it('영상 아래 줄에 제목·태그·시청자·경과 시간을 세운다', async () => {
    await renderLive();

    expect(
      screen.getByRole('heading', { name: '새벽 랭크 올리기 — 다이아 승급전 가보자' }),
    ).toBeInTheDocument();
    expect(screen.getAllByText('리그 오브 레전드').length).toBeGreaterThan(0);
    expect(screen.getByText('다이아승급')).toBeInTheDocument();
    expect(screen.getAllByText('1,842명 시청 중').length).toBeGreaterThan(0);
    expect(screen.getByText('1:24:03')).toBeInTheDocument();
    expect(screen.getByText('스트리밍 중')).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('같은 화면에서 방송이 바뀌어도 앞 방송의 관계가 남지 않는다: 명부 답 전에 남의 방송에 내 채널 이름을 붙이지 않는다', async () => {
    // 저장소는 방송 번호가 바뀌면 앞 방송의 값을 통째로 버린다. 칸마다 따로 비우면 빠뜨린 칸(관계)이 남았다(리뷰 4라운드)
    await renderLive();
    expect(screen.getAllByText('게임하는너구리').length).toBeGreaterThan(0);

    server.liveStream2 = true;
    server.listGate = new Promise(() => undefined);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    await settle();

    expect(screen.queryByText('게임하는너구리')).toBeNull();
  });

  it('라이브의 시청자 수도 비어 있는 마지막 관측이 아니라 마지막으로 잰 값이다: 통계와 같은 규칙이다', async () => {
    // 수집기는 그 회차에 목록에서 채널을 못 찾으면 빈 값을 남긴다. 그때 「수집 전」이라 하면 바로 아래 통계의 최고·평균과
    // 모순이다(리뷰 3라운드)
    server.infoLastNull = true;
    await renderLive();

    expect(screen.getAllByText('2,310명 시청 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 수집 전')).toBeNull();
  });

  it('방송 정보 관측에 제목이 비면 목록 줄의 제목을 보인다 — 번호로 돌아가지 않는다(POK-259)', async () => {
    server.listTitle = '목록에서 온 제목';
    server.infoTitleMissing = true;
    await renderLive();

    expect(screen.getByRole('heading', { name: '목록에서 온 제목' })).toBeInTheDocument();
  });

  it('관측 제목이 있으면 그것이 먼저다 — 목록보다 늦게 바뀐 제목이다', async () => {
    server.listTitle = '목록에서 온 옛 제목';
    await renderLive();

    expect(
      screen.getByRole('heading', { name: '새벽 랭크 올리기 — 다이아 승급전 가보자' }),
    ).toBeInTheDocument();
  });

  it('내 방송이면 채널 이름은 내 치지직 연동에서 온다', async () => {
    await renderLive();
    expect(screen.getByText('게임하는너구리')).toBeInTheDocument();
  });

  it('위임받은 방송에는 내 채널 이름을 붙이지 않는다', async () => {
    server.relation = 'EDITOR';
    await renderLive();
    expect(screen.queryByText('게임하는너구리')).not.toBeInTheDocument();
  });

  it('페이지 헤더가 없다 — 시안 1b는 콘텐츠부터 시작한다', async () => {
    await renderLive();
    expect(screen.queryByRole('link', { name: '홈으로' })).not.toBeInTheDocument();
  });
});

describe('LiveScreen — 하이라이트 카드', () => {
  it('상태 배지를 카드 원본에 맞춰 그린다 — 점수·남이 집음·내가 집음', async () => {
    await renderLive();

    expect(screen.getByText('97점')).toBeInTheDocument();
    expect(screen.getByText('편집 중')).toBeInTheDocument();
    expect(screen.getByText('내가 편집 중')).toBeInTheDocument();
  });

  it('"편집"은 그 카드의 방송·카드 번호를 싣고 편집기로 가고, 누르면 카드를 집는다', async () => {
    await renderLive();

    const edits = screen.getAllByRole('link', { name: '편집' });
    expect(edits[0]).toHaveAttribute('href', `/clips/editor?stream=${STREAM_ID}&card=8`);
    fireEvent.click(edits[0] as HTMLElement);
    await settle();
    expect(
      fetchSpy.mock.calls.some(
        ([url, init]) => url === '/api/clip/jump-cards/8/claim' && init?.method === 'POST',
      ),
    ).toBe(true);
    // 업로드 라우트는 아직 없다 — 자리만 두고 비활성
    expect(screen.getAllByRole('button', { name: '원클릭 업로드' })[0]).toBeDisabled();
  });

  it('남이 집은 카드는 편집 단추를 감추고, 내가 집은 카드는 다시 들어갈 수 있다', async () => {
    await renderLive();
    // 8장 중 남이 집은 1장만 단추가 없다
    expect(screen.getAllByRole('link', { name: '편집' })).toHaveLength(7);
  });

  it('점수가 보이는 곳에만 있지 않다 — 듣는 쪽에도 남긴다', async () => {
    // 시안상 점수는 썸네일 위에만 있는데 그쪽은 aria-hidden이라 어디서도 안 읽혔다
    await renderLive();
    expect(screen.getByText(/^97점 · /)).toBeInTheDocument();
  });

  it('강조는 맨 앞 카드 하나뿐이다: 여럿이면 「방금」이라는 뜻을 잃는다', async () => {
    const { container } = await renderLive();
    expect(container.querySelectorAll('[class*="cardEmphasized"]')).toHaveLength(1);
  });

  it('필터 칩이 실제 카드 수를 센다 — 전체 8 · 자동 6 · 수동 2', async () => {
    await renderLive();

    expect(screen.getByRole('button', { name: '전체 8' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '자동 6' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 2' })).toBeInTheDocument();
  });

  it('"자동" 필터를 누르면 수동 마킹 카드가 사라진다', async () => {
    await renderLive();

    expect(screen.getAllByText('핫키로 남긴 순간')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: '자동 6' }));
    expect(screen.queryByText('핫키로 남긴 순간')).not.toBeInTheDocument();
    expect(screen.getByText('채팅이 튄 순간 #8')).toBeInTheDocument();
  });

  it('카드를 누르면 그 시점으로 옮긴다 — 영상이 없으면 그렇다고 말한다', async () => {
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    // 라이브 채팅은 재생 위치를 따르지 않는다: 「채팅을 그 시점으로 옮겼어요」는 거짓이다(리뷰 2라운드)
    expect(screen.getByText('영상은 아직 없어요')).toBeInTheDocument();
    expect(screen.queryByText(/채팅을 그 시점으로 옮겼어요/)).not.toBeInTheDocument();
  });

  it('지난 방송에서는 영상이 없어도 채팅을 그 시점으로 옮기고 그렇다고 말한다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    expect(
      screen.getByText('영상은 아직 없어요 · 채팅을 그 시점으로 옮겼어요'),
    ).toBeInTheDocument();
  });

  it('숨긴 카드는 통로가 없어도 다음 폴링에서 목록에서 빠진다', async () => {
    // 통로(SSE)는 이 시험에서 503이다 — 폴링이 숨김을 반영해야 한다(PR #200 codex)
    await renderLive();
    expect(screen.getByText('채팅이 튄 순간 #8')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: '이 카드 숨기기' })[0] as HTMLElement);
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000);
    });
    await settle();
    expect(screen.queryByText('채팅이 튄 순간 #8')).not.toBeInTheDocument();
  });

  it('숨기기를 누르면 clip에 숨김을 보낸다', async () => {
    await renderLive();

    fireEvent.click(screen.getAllByRole('button', { name: '이 카드 숨기기' })[0] as HTMLElement);
    await settle();
    expect(
      fetchSpy.mock.calls.some(
        ([url, init]) => url === '/api/clip/jump-cards/8/hide' && init?.method === 'POST',
      ),
    ).toBe(true);
  });
});

describe('LiveScreen: 라이브 문구', () => {
  it('방송 중에는 라이브 문구 그대로다: 실시간 채팅·실시간 통계·자동 감지 중·그래프 끝 「지금」', async () => {
    await renderLive();

    expect(screen.getByRole('complementary', { name: '실시간 채팅' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '실시간 통계' })).toBeInTheDocument();
    expect(screen.getByText('자동 감지 중 · 카드 클릭 = 시점 이동')).toBeInTheDocument();
    expect(screen.getByText('지금')).toBeInTheDocument();
    // 채팅 수 이름은 차트가 읽은 창대로다: 라이브는 지금 기준 최근 창이다
    expect(screen.getByText('최근 10분 채팅')).toBeInTheDocument();
  });
});

describe('LiveScreen — 수동 마킹', () => {
  // 이 파일은 전부 fireEvent다 — userEvent는 가짜 시계와 엉켜 멈춘다(GlassPlayer 자동 숨김 케이스와 같은 이유).
  //
  // 누른 순간을 서버에 카드로 남기는 길이 아직 없다(POK-257, MVP 밖). 화면에만 카드를 세우면 새로 고침에 사라지고 편집도
  // 못 하는 가짜 성공이다(QA 2026-10-11). 준비 중이라고만 말한다.
  const PREPARING = '수동 마킹은 아직 준비 중이에요. 준비되면 알려드릴게요.';

  it('버튼을 누르면 준비 중이라고 알리고 카드를 만들지 않는다', async () => {
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));
    expect(screen.getByText(PREPARING)).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    expect(screen.queryByText(/마킹됨 · 카드 생성 중/)).not.toBeInTheDocument();
    // 카드 수·필터·지표가 그대로다. 서버에 없는 카드를 세지 않는다
    expect(screen.getByRole('button', { name: '전체 8' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 2' })).toBeInTheDocument();
    expect(screen.getByText('자동 6 · 수동 2')).toBeInTheDocument();
  });

  it('F8도 어디에 포커스가 있든 같은 안내다', async () => {
    await renderLive();

    fireEvent.keyDown(document, { key: 'F8' });
    expect(screen.getByText(PREPARING)).toBeInTheDocument();
  });

  it('자동반복과 입력 중인 곳에서 누른 F8은 무시한다', async () => {
    await renderLive();

    // 누르고 있으면 초당 수십 번 눌린다
    fireEvent.keyDown(document, { key: 'F8', repeat: true });
    // 글자를 치는 곳에서 누른 키는 그쪽 것이다
    const input = document.createElement('input');
    document.body.appendChild(input);
    fireEvent.keyDown(input, { key: 'F8' });
    input.remove();

    expect(screen.queryByText(PREPARING)).not.toBeInTheDocument();
  });

  it('지난 방송에서도 같다: 방송 끝 시각에 카드가 생기지 않는다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));
    expect(screen.getByText(PREPARING)).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(screen.queryByRole('heading', { name: /수동 마킹$/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 2' })).toBeInTheDocument();
  });

  it('경과 시간이 흐른다', async () => {
    await renderLive();

    expect(screen.getByText('1:24:03')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(120_000); // 2분
    });
    expect(screen.getByText('1:26:03')).toBeInTheDocument();
  });
});

describe('LiveScreen — 실시간 채팅 패널', () => {
  it('급증 키워드·후원·채팅 줄을 그린다', async () => {
    await renderLive();

    expect(screen.getByText('ㅋㅋㅋㅋ ×3')).toBeInTheDocument();
    expect(screen.getByText('미쳤다 ×2')).toBeInTheDocument();
    expect(screen.getByText('도네초코 · 치즈 5,000')).toBeInTheDocument();
    expect(screen.getByText('승급 기원!! 가즈아')).toBeInTheDocument();
  });

  it('수집 상태를 채팅 헤더의 라이브 리전이 말한다', async () => {
    await renderLive();

    // 배지를 갈아끼우는 게 아니라 늘 서 있는 리전 안에서 글만 바뀌어야 알림이 닿는다
    const panel = screen.getByRole('complementary', { name: '실시간 채팅' });
    expect(within(panel).getByRole('status')).toHaveTextContent('수집 중');
  });

  it('접으면 패널이 사라지고, 플레이어 상단의 여는 버튼으로 되살아난다', async () => {
    await renderLive();

    expect(screen.getByRole('complementary', { name: '실시간 채팅' })).toBeInTheDocument();
    // 열려 있는 동안엔 여는 버튼이 자리를 차지하지 않는다
    expect(screen.queryByRole('button', { name: '채팅 열기' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '채팅 패널 접기' }));
    expect(screen.queryByRole('complementary', { name: '실시간 채팅' })).not.toBeInTheDocument();

    // 패널이 사라져도 복귀 통로는 플레이어 안에 남는다
    fireEvent.click(screen.getByRole('button', { name: '채팅 열기' }));
    expect(screen.getByRole('complementary', { name: '실시간 채팅' })).toBeInTheDocument();
  });

  it('목록은 DOM도 시간순이다 — 스크린 리더가 화면과 같은 흐름으로 읽는다', async () => {
    await renderLive();

    const list = screen.getByRole('list', { name: '채팅 메시지' });
    const items = within(list).getAllByRole('listitem');
    expect(items[0]).toHaveTextContent('오늘도 새벽 랭크인가요');
    expect(items[items.length - 1]).toHaveTextContent('방금 그거 다시 보여주세요');
    // 스크롤 영역은 키보드로 들어갈 수 있어야 한다 (axe scrollable-region-focusable)
    expect(list).toHaveAttribute('tabindex', '0');
  });

  it('채팅이 한 쪽을 넘으면 커서를 따라가 최신 채팅까지 읽는다', async () => {
    // 첫 쪽만 읽으면 폴링 창이 실시간을 영영 못 따라잡는다(PR #200 codex P1)
    server.chatPaged = true;
    await renderLive();
    expect(screen.getByText('방금 그거 다시 보여주세요')).toBeInTheDocument();
  });

  it('키워드 설정은 라우트가 설 때까지 잠겨 있다', async () => {
    await renderLive();
    expect(screen.getByRole('button', { name: '키워드 설정' })).toBeDisabled();
  });
});

describe('LiveScreen — 실시간 통계', () => {
  it('지표를 방송 정보와 채팅 차트에서 센다', async () => {
    await renderLive();

    expect(screen.getByText('최고 시청자')).toBeInTheDocument();
    expect(screen.getByText('2,310')).toBeInTheDocument();
    expect(screen.getByText('분당 평균 채팅')).toBeInTheDocument();
    expect(screen.getByText('2회')).toBeInTheDocument();
  });

  it('타임라인이 채팅량·시청자·하이라이트·후원을 한 그림으로 알린다', async () => {
    await renderLive();

    expect(
      screen.getByRole('img', {
        name: '방송 타임라인 — 채팅량과 시청자 추이, 하이라이트 4곳, 후원 2회',
      }),
    ).toBeInTheDocument();
  });

  it('하이라이트 내역은 총계 앞에 선다 — 총계가 다른 지표와 같은 오른쪽 끝에 맞는다', async () => {
    await renderLive();

    const note = screen.getByText('자동 6 · 수동 2');
    const value = note.parentElement;
    expect(value?.textContent).toBe('자동 6 · 수동 28');
    expect(note.nextElementSibling).toHaveTextContent('8');
  });

  it('카드를 숨기면 지표와 필터 칩이 같이 준다: 요약이 첫 값에 굳지 않는다', async () => {
    await renderLive();
    expect(screen.getByText('자동 6 · 수동 2')).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole('button', { name: '이 카드 숨기기' })[0] as HTMLElement);
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000);
    });
    await settle();

    expect(screen.getByText('자동 5 · 수동 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자동 5' })).toBeInTheDocument();
  });

  it('하이라이트 지표는 카드 목록에서 센다: 필터 칩과 같은 수다', async () => {
    await renderLive();

    expect(screen.getByText('자동 6 · 수동 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자동 6' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 2' })).toBeInTheDocument();
  });
});

describe('LiveScreen — 접근성', () => {
  it('접근성 위반이 없다', async () => {
    const { container } = await renderLive();
    // axe는 진짜 시계로 돈다 — 가짜 시계에서는 제 타이머를 기다리다 멈춘다
    vi.useRealTimers();
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('LiveScreen — 긴 지난 방송', () => {
  it('1시간 넘는 방송의 채팅·차트를 1시간 창으로 나눠 읽고, 시청자 기록은 시작 시각부터 달라고 한다', async () => {
    // 수집기는 한 번에 1시간까지만 받는다 — 넘으면 400 too_wide로 채팅·차트가 통째로 빈다(PR #200 codex)
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    const spans = (part: string) =>
      fetchSpy.mock.calls
        .map(([url]) => new URL(String(url), 'http://localhost'))
        .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/${part}`)
        .map(
          (u) =>
            Date.parse(u.searchParams.get('to') ?? '') -
            Date.parse(u.searchParams.get('from') ?? ''),
        );
    for (const part of ['chat-chart', 'chat-messages']) {
      const got = spans(part);
      expect(got.length).toBeGreaterThanOrEqual(3);
      for (const ms of got) expect(ms).toBeLessThanOrEqual(3_600_000);
    }
    const info = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/broadcast-info`);
    expect(
      info.some((u) => u.searchParams.get('since') === new Date(VOD_STARTED).toISOString()),
    ).toBe(true);
  });
});

describe('LiveScreen — 영상 경로의 키(POK-233)', () => {
  it('라이브 영상 주소는 방송 번호가 아니라 영상 경로의 키로 만든다 — 방송 번호가 회차 번호면 그 경로가 없다', async () => {
    server.ingest = 'key-live';
    await renderLive();
    expect(mediaCalls.keys.at(-1)).toBe('key-live');
  });

  it('주소의 ?stream= 은 방송 번호다 — 그 값을 영상 경로로 바로 쓰지 않는다', async () => {
    server.ingest = 'key-live';
    nav.search = `?stream=${STREAM_ID}`;
    window.history.pushState({}, '', `/broadcast?stream=${STREAM_ID}`);
    await renderLive();
    expect(mediaCalls.keys.at(-1)).toBe('key-live');
  });

  it('칸을 모르는 옛 서버면 방송 번호가 곧 영상 경로다', async () => {
    await renderLive();
    expect(mediaCalls.keys.at(-1)).toBe(STREAM_ID);
  });

  it('끝난 방송의 녹화는 영상 경로의 키로 찾는다', async () => {
    server.ingest = 'key-vod';
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    expect(mediaCalls.spans).toContain('key-vod');
    expect(mediaCalls.spans).not.toContain(VOD_ID);
  });
});

describe('LiveScreen: 홈에서 고른 카드로 들어오기', () => {
  // 홈 「최근 하이라이트」는 지난 방송 주소에 ?card= 를 싣는다(QA 2026-10-11). 전에는 방송 번호만 실어 처음부터 틀었다.
  //
  // 🔴 Next는 다른 화면에서 넘어올 때 새 화면을 먼저 그리고 주소창은 그 뒤에 바꾼다. 그래서 이 시험들은 카드 번호를
  // useSearchParams(nav.search)로만 주고 주소창(window.location)에는 싣지 않는다. 주소창을 먼저 바꾸면 그 순서를 못 잰다
  // (주소창을 읽던 첫 구현이 시험은 통과하고 실기동에서 처음부터 틀었다).
  function openFromHome(cardId: number) {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    nav.search = `?card=${cardId}`;
  }

  /** 지금 녹화 플레이어가 달라고 한 줄기의 시작(epoch ms) */
  function requestedStart(container: HTMLElement): number | null {
    const video = container.querySelector('video');
    if (video === null || video.src === '') return null;
    return Date.parse(new URL(video.src, 'http://localhost').searchParams.get('start') ?? '');
  }

  /**
   * 개발 서버처럼 StrictMode로 그린다. 새로 선 부품의 효과를 껐다 켜므로, 녹화가 서는 순간에 건 이동이 그 「끄기」에
   * 지워지는지를 잰다(실기동에서 그렇게 처음부터 틀었다)
   */
  async function renderLiveStrict() {
    const result = renderWithProviders(
      <StrictMode>
        <LiveScreen />
      </StrictMode>,
    );
    await settle();
    return result;
  }

  function stubMediaElement() {
    // jsdom에는 미디어 재생이 없다. 재생 요청만 받아 둔다
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined);
    vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined);
  }

  it('녹화가 없으면 채팅을 그 카드 시점으로 옮긴다', async () => {
    server.vodOrigin = VOD_STARTED + 32_000;
    openFromHome(4);
    await renderLive();

    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => u.searchParams.get('from'));
    expect(froms).toContain(
      new Date(VOD_STARTED + 32_000 + 2_842_000 - 60_000 + 3_900).toISOString(),
    );
    // 사람이 누른 것이 아니다. 「영상은 아직 없어요」를 먼저 띄우지 않는다
    expect(screen.queryByText(/영상은 아직 없어요/)).not.toBeInTheDocument();
  });

  it('녹화가 있으면 녹화가 뜨는 대로 그 카드 시점부터 튼다: 처음부터 틀지 않는다', async () => {
    stubMediaElement();
    server.vodOrigin = VOD_STARTED;
    mediaCalls.answer = Promise.resolve([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
    openFromHome(4);
    const { container } = await renderLiveStrict();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(VOD_STARTED + 2_842_000);
    // 카드 목록이 녹화보다 먼저 와도 거짓 「영상은 아직 없어요」를 띄우지 않는다. 녹화를 찾는 중일 뿐이다
    expect(screen.queryByText(/영상은 아직 없어요/)).not.toBeInTheDocument();
  });

  it('시작 시각을 모르는 지난 방송은 끝 12시간 밖의 녹화를 틀지 않는다: 같은 송출 키로 한 다른 날 방송이다', async () => {
    // 시작 시각이 없으면 녹화를 끝 이전 전부에서 골라 이틀 전 방송의 녹화를 틀고, 채팅도 그 날짜를 물었다(리뷰 5라운드)
    stubMediaElement();
    server.vodStartUnknown = true;
    mediaCalls.answer = Promise.resolve([
      { startMs: VOD_ENDED - 48 * 3_600_000, durationSeconds: 3_600 },
      { startMs: VOD_STARTED, durationSeconds: 9_000 },
    ]);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    const { container } = await renderLive();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(VOD_STARTED);
  });

  it('재생 서버가 잠깐 실패하면 녹화를 60초 뒤 다시 묻고, 녹화가 있으면 그때 튼다', async () => {
    // 조회 실패를 빈 목록으로 접어 「녹화 영상을 찾지 못했어요」로 굳혔다. 다시 묻지도 않았다(리뷰 6라운드)
    stubMediaElement();
    mediaCalls.configured = true;
    mediaCalls.answer = Promise.resolve(null);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    const { container } = await renderLive();
    expect(screen.getByText('영상 서버에 아직 연결되지 않았어요')).toBeInTheDocument();
    expect(screen.queryByText('녹화 영상을 찾지 못했어요')).toBeNull();

    mediaCalls.answer = Promise.resolve([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(59_000);
    });
    // 지난 방송 목록과 같은 주기다(1분)
    expect(mediaCalls.spans).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(mediaCalls.spans).toHaveLength(2);
    expect(requestedStart(container)).toBe(VOD_STARTED);
  });

  it('녹화가 없다고 나온 뒤 녹화가 생기면 다시 물어 튼다: 지난 방송 목록처럼 「있음」만 굳힌다', async () => {
    // 「없음」을 마지막 답으로 굳혀, 목록은 녹화가 생겨 「다시 보기 가능」이 됐는데 열린 화면은 계속 「찾지 못했어요」였다
    // (리뷰 7라운드). 재생 서버가 폴더를 못 읽은 오류도 400이라 「없음」으로 온다
    stubMediaElement();
    mediaCalls.configured = true;
    mediaCalls.answer = Promise.resolve([]);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    const { container } = await renderLive();
    expect(screen.getByText('녹화 영상을 찾지 못했어요')).toBeInTheDocument();

    mediaCalls.answer = Promise.resolve([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(requestedStart(container)).toBe(VOD_STARTED);

    // 찾은 뒤에는 다시 묻지 않는다
    const asked = mediaCalls.spans.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180_000);
    });
    expect(mediaCalls.spans).toHaveLength(asked);
  });

  it('시작 시각을 아는 12시간 넘는 방송은 녹화 전체를 튼다: 앞쪽 카드에도 영상이 있어야 한다', async () => {
    // 그래프·채팅은 끝 12시간만 읽지만 녹화까지 자르면 앞 3시간 카드는 영상이 없다(리뷰 6라운드에서 시험 빈틈을 찾음)
    stubMediaElement();
    server.vodLong = true;
    // 송출이 한 번 끊긴 방송: 앞 구간(2시간)은 끝 12시간 창보다 먼저 끝난다. 창으로 자르면 이 구간이 빠진다
    mediaCalls.answer = Promise.resolve([
      { startMs: VOD_STARTED, durationSeconds: 2 * 3_600 },
      { startMs: VOD_STARTED + 2 * 3_600_000 + 60_000, durationSeconds: 13 * 3_600 },
    ]);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    const { container } = await renderLive();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(VOD_STARTED);
  });

  it('카드 시점 채팅을 읽는 동안은 그 수를 「확인 중」이라 쓴다: 모르는 수를 0건이라 하지 않는다', async () => {
    let release: () => void = () => undefined;
    server.vodFocusChat = true;
    server.focusGate = new Promise((resolve) => {
      release = resolve;
    });
    openFromHome(4);
    await renderLive();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 확인 중/)).toBeInTheDocument();

    await act(async () => {
      release();
    });
    await settle();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
    // 처음 그릴 때부터 「확인 중」이다: 읽기를 시작하며 표시를 켜면 그 앞 한 번은 받아 둔 수로 「0건」을 그렸다(리뷰 7라운드)
    expect(chatHeads.filter((t) => t.includes('시점 앞뒤 60초'))).not.toContainEqual(
      expect.stringMatching(/0건$/),
    );
  });

  it('다른 카드를 누르면 그 시점을 다 읽기 전까지 「확인 중」이다: 앞 카드에서 읽은 것으로 수를 세지 않는다', async () => {
    server.vodFocusChat = true;
    openFromHome(4);
    await renderLive();
    expect(screen.getByText(/0:47:22 시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();

    let release: () => void = () => undefined;
    server.focusGate = new Promise((resolve) => {
      release = resolve;
    });
    chatHeads.length = 0;
    fireEvent.click(screen.getByRole('button', { name: '1:23:20 시점으로 이동' }));
    await settle();
    expect(screen.getByText(/1:23:20 시점 앞뒤 60초 채팅 확인 중/)).toBeInTheDocument();
    expect(chatHeads.filter((t) => t.startsWith('1:23:20'))).not.toContainEqual(
      expect.stringMatching(/건$/),
    );

    await act(async () => {
      release();
    });
    await settle();
    expect(screen.getByText(/1:23:20 시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
  });

  it('카드 시점 채팅을 읽지 못하면 그렇다고 쓴다: 0건이라 하지 않는다', async () => {
    // 읽기가 실패해도 「다 읽음」으로 넘어가 「0건」이라 단정했다(리뷰 7라운드)
    server.vodFocusChat = true;
    server.focusFail = true;
    openFromHome(4);
    await renderLive();

    expect(screen.getByText(/시점 앞뒤 60초 채팅을 불러오지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByText(/시점 앞뒤 60초 채팅 0건/)).toBeNull();
  });

  it('카드 시점 채팅을 읽은 뒤 채팅 패널을 접었다 펴도 그 수는 0건으로 바뀌지 않는다', async () => {
    // 패널을 펴면 방송 창을 처음부터 다시 읽으며 받아 둔 채팅을 비운다. 시점 채팅이 거기에 섞여 있으면 함께 지워진다
    server.vodFocusChat = true;
    openFromHome(4);
    await renderLive();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
    chatHeads.length = 0;

    fireEvent.click(screen.getByRole('button', { name: '채팅 패널 접기' }));
    fireEvent.click(screen.getByRole('button', { name: '채팅 열기' }));
    await settle();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
    expect(chatHeads.filter((t) => t.includes('시점 앞뒤 60초'))).not.toContainEqual(
      expect.stringMatching(/0건$/),
    );
  });

  it('방송 끝 시각이 고쳐져 채팅을 다시 읽어도 카드 시점의 채팅은 남는다', async () => {
    // 1시간 창 읽기만 다시 돌며 처음에 비우고, 시점 읽기는 다시 돌지 않아 「채팅 0건」이 됐다(리뷰 6라운드)
    server.vodFocusChat = true;
    openFromHome(4);
    await renderLive();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();

    server.vodEndShiftMs = 90_000;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(61_000);
    });
    await settle();
    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
  });

  it('카드 시점의 채팅은 방송 전체 채팅을 다 읽은 뒤에도 남는다: 1시간 창은 상한이 있어 그 시점이 빠질 수 있다', async () => {
    // 시점 앞뒤 60초는 따로 끝까지 읽는다. 그런데 차례로 도는 1시간 창 읽기가 나중에 끝나며 통째로 덮어 「채팅 0건」이 됐다.
    // 카드는 채팅이 몰린 순간에 생겨 바로 그 자리가 빠진다(리뷰 5라운드)
    server.vodFocusChat = true;
    openFromHome(4);
    await renderLive();

    expect(screen.getByText(/시점 앞뒤 60초 채팅 1건/)).toBeInTheDocument();
  });

  it('녹화 플레이어가 시작 위치를 받은 뒤에는 비운다: 다시 서도 옛 카드 시점으로 되돌아가지 않는다', async () => {
    // 시작 위치는 녹화가 서기 전에 고른 시점을 위한 것이다. 받은 뒤에도 남기면 시크바·「끝으로」로 옮긴 뒤 녹화가
    // 다시 설 때 오래전 카드로 튄다(리뷰). 다시 선 녹화는 처음부터다. 지금 위치는 플레이어 밖에서 모른다(예전과 같다)
    stubMediaElement();
    mediaCalls.answer = Promise.resolve([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
    openFromHome(4);
    const { container } = await renderLiveStrict();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(requestedStart(container)).toBe(VOD_STARTED + 2_842_000);

    act(() => publishLiveData({ originMs: VOD_STARTED }));
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(mediaCalls.spans.length).toBeGreaterThan(1);
    expect(requestedStart(container)).toBe(VOD_STARTED);
  });

  it('라이브에서 영상 없이 누른 카드는 남지 않는다: 방송이 끝나 녹화가 서도 녹화도 채팅도 그 카드로 가지 않는다', async () => {
    // 라이브 영상(HLS)은 시작 위치를 읽지 않고 라이브 채팅은 재생 위치를 따르지 않는다. 남겨 두면 방송이 끝나 녹화로
    // 바뀔 때 오래전 카드로 튀거나(리뷰 2라운드), 녹화는 처음부터인데 채팅만 그 카드에 묶인다(리뷰 3라운드)
    stubMediaElement();
    nav.search = `?stream=${STREAM_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${STREAM_ID}`);
    const { container } = await renderLive();
    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));

    server.liveEnded = true;
    mediaCalls.answer = Promise.resolve([{ startMs: STARTED_AT, durationSeconds: 5_100 }]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(STARTED_AT);
    expect(screen.queryByText(/시점 앞뒤 60초/)).toBeNull();
  });

  it('명부 답 전에 누른 카드는 끝난 방송으로 밝혀지면 그 시점부터 튼다: 라이브 주소로 연 끝난 방송', async () => {
    // 방송 중일 때 만든 홈 링크(livenow?stream=)를 끝난 뒤에 눌렀다. 모르는 동안 고른 시점을 라이브처럼 버리면
    // 녹화가 처음부터 선다(리뷰 3라운드)
    stubMediaElement();
    server.vodOrigin = VOD_STARTED;
    let release: () => void = () => undefined;
    server.listGate = new Promise((resolve) => {
      release = resolve;
    });
    mediaCalls.answer = Promise.resolve([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${VOD_ID}`);
    const { container } = await renderLiveStrict();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await act(async () => {
      release();
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(VOD_STARTED + 2_842_000);
  });

  it('명부 답 전에 누른 카드도 라이브로 밝혀지면 버린다: 방송이 끝난 뒤 녹화도 채팅도 그 카드로 가지 않는다', async () => {
    stubMediaElement();
    let release: () => void = () => undefined;
    server.listGate = new Promise((resolve) => {
      release = resolve;
    });
    nav.search = `?stream=${STREAM_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${STREAM_ID}`);
    const { container } = await renderLive();
    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await act(async () => {
      release();
    });
    await settle();
    expect(screen.getByText('스트리밍 중')).toBeInTheDocument();

    server.listGate = null;
    server.liveEnded = true;
    mediaCalls.answer = Promise.resolve([{ startMs: STARTED_AT, durationSeconds: 5_100 }]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(STARTED_AT);
    expect(screen.queryByText(/시점 앞뒤 60초/)).toBeNull();
  });

  it('주소의 카드는 한 번만 옮긴다: 사람이 다른 카드를 고른 뒤 몇 초가 지나도 주소의 카드로 돌아가지 않는다', async () => {
    // 카드 목록은 매초 새로 만들어져 주소 카드 효과가 매초 다시 돈다. 이미 옮겼다는 표시가 없으면 사람이 고른 시점을
    // 매초 되돌린다(리뷰 4라운드)
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    nav.search = '?card=4';
    await renderLive();
    expect(screen.getByText(/^0:47:22 시점 앞뒤 60초/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '1:23:20 시점으로 이동' }));
    await settle();
    expect(screen.getByText(/^1:23:20 시점 앞뒤 60초/)).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000);
    });
    await settle();
    expect(screen.getByText(/^1:23:20 시점 앞뒤 60초/)).toBeInTheDocument();
  });

  it('녹화를 찾는 동안 누른 카드도 녹화가 뜨면 그 시점부터다', async () => {
    stubMediaElement();
    server.vodOrigin = VOD_STARTED;
    let answer: (spans: { startMs: number; durationSeconds: number }[]) => void = () => undefined;
    mediaCalls.answer = new Promise((resolve) => {
      answer = resolve;
    });
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    const { container } = await renderLiveStrict();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await act(async () => {
      answer([{ startMs: VOD_STARTED, durationSeconds: 9_000 }]);
      await vi.advanceTimersByTimeAsync(0);
    });
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });

    expect(requestedStart(container)).toBe(VOD_STARTED + 2_842_000);
  });
});

describe('LiveScreen — 지난 방송 상세', () => {
  it('명부에 없는 방송이면 404 화면으로 보낸다 — 빈 대시보드를 보이지 않는다', async () => {
    window.history.pushState({}, '', '/broadcast/vod/nope');
    await renderLive();
    expect(notFound).toHaveBeenCalled();
  });

  it('끝난 방송에는 닫힌 라이브 영상 주소를 넘기지 않는다 — 녹화가 없으면 지난 방송 자리를 그린다', async () => {
    vi.stubEnv('NEXT_PUBLIC_MEDIA_LIVE_BASE_URL', 'http://live.test');
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    expect(screen.getByText('지난 방송 영상')).toBeInTheDocument();
    expect(notFound).not.toHaveBeenCalled();
  });

  it('채팅이 아주 많은 지난 방송도 모든 시간대를 읽는다 — 앞 시간대가 상한을 다 쓰지 않는다', async () => {
    // 서버가 오래된 것부터 주므로 방송 전체에 상한 하나를 두면 후반이 통째로 빈다(PR #200 codex P1)
    server.vodChatEndless = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    const froms = new Set(
      fetchSpy.mock.calls
        .map(([url]) => new URL(String(url), 'http://localhost'))
        .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
        .map((u) => u.searchParams.get('from')),
    );
    for (const at of [VOD_STARTED, VOD_STARTED + 3_600_000, VOD_STARTED + 7_200_000])
      expect(froms.has(new Date(at).toISOString())).toBe(true);
  });

  it('카드를 누르면 그 시점 앞뒤 60초를 영상과 같은 기준점(녹화 시작)으로 따로 읽는다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    // 녹화 첫 조각이 방송 시작보다 30초 늦게 시작했다 — 카드 시점은 그 축이다
    const base = VOD_STARTED + 30_000;
    act(() => publishLiveData({ timeBaseMs: base }));

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await settle();

    const expectedFrom = new Date(base + 2_842_000 - 60_000 + 3_900).toISOString();
    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => u.searchParams.get('from'));
    expect(froms).toContain(expectedFrom);
  });

  it('녹화가 없으면 서버가 준 시각 기준점으로 읽는다 — 방송 시작 시각이 아니다(POK-255)', async () => {
    // 첫 조각이 방송 시작보다 32초 늦었다 — 방송 시작으로 대신하면 채팅 시점이 32초 어긋난다
    server.vodOrigin = VOD_STARTED + 32_000;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await settle();

    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => u.searchParams.get('from'));
    expect(froms).toContain(
      new Date(VOD_STARTED + 32_000 + 2_842_000 - 60_000 + 3_900).toISOString(),
    );
  });

  /** Next의 HistoryUpdater처럼 그린 뒤 커밋에서(효과보다 먼저) 주소창을 바꾼다. 그리는 동안의 주소창은 옛 주소다 */
  function NavigatedTo({ to, children }: { to: string; children: ReactNode }) {
    useInsertionEffect(() => {
      window.history.pushState({}, '', to);
    }, [to]);
    return children;
  }

  it('다른 화면에서 넘어온 첫 그리기부터 방송 번호를 정하기 전(offline)까지 지난 방송 문구다', async () => {
    // 채팅만 덮고 플레이어·제목은 라이브·오프라인 문구로 깜박였다(리뷰 3라운드). 경로는 usePathname으로 읽는다: 그리는 동안의
    // 주소창은 옛 주소일 수 있다(리뷰 2라운드). 지금은 저장소를 비운 뒤에 그려 대시보드가 주소창이 바뀐 뒤에 서므로, 이 시험은
    // 주소창을 읽는 구현을 더는 가르지 못한다(리뷰 5라운드)
    window.history.pushState({}, '', '/');
    nav.pathname = `/broadcast/vod/${VOD_ID}`;
    renderWithProviders(
      <NavigatedTo to={`/broadcast/vod/${VOD_ID}`}>
        <LiveScreen />
      </NavigatedTo>,
    );
    // 방송 번호는 비동기로 정한다: 여기는 아직 그 전이다
    expect(screen.queryByText('지금 방송 중이 아니에요')).toBeNull();
    expect(screen.queryByText('방송 중인 채널이 없어요')).toBeNull();
    await settle();

    expect(chatModes[0]).toBe('ended');
    expect(chatModes.filter((mode) => mode !== 'ended')).toEqual([]);
    expect(playerStatuses.length).toBeGreaterThan(0);
    expect(playerStatuses.filter((status) => status !== 'ended')).toEqual([]);
  });

  it('방송 전체 그래프를 못 받으면 채팅 수를 지어내지 않는다: 「최근 1분 채팅 0」도 「방송 중 채팅」도 아니다', async () => {
    // 차트가 없을 때 「최근 N분」으로 떨어지면 N은 지어낸 1분이고 수는 0이다(리뷰 3라운드)
    server.vodChartFail = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.queryByText(/^최근 \d+분 채팅$/)).toBeNull();
    expect(screen.queryByText('방송 중 채팅')).toBeNull();
    expect(screen.queryByText('지금')).toBeNull();
    expect(screen.getAllByText('확인 중').length).toBeGreaterThan(0);
  });

  it('라이브가 끝난 뒤 방송 전체 그래프를 못 받으면 라이브 때의 최근 그래프를 「지금」으로 남기지 않는다', async () => {
    // 그 그래프는 시계가 흘러도 다시 읽지 않아 「지금」이 굳는다(리뷰 3라운드)
    server.vodChartFail = true;
    nav.search = `?stream=${STREAM_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${STREAM_ID}`);
    await renderLive();
    expect(screen.getByText('지금')).toBeInTheDocument();

    server.liveEnded = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000);
    });
    await settle();

    expect(screen.getByText('방송함')).toBeInTheDocument();
    expect(screen.queryByText('지금')).toBeNull();
    expect(screen.queryByText(/^최근 \d+분 채팅$/)).toBeNull();
  });

  it('12시간 넘는 방송은 끝 12시간만 읽으므로 그 채팅 수를 방송 전체라고 하지 않는다', async () => {
    server.vodLong = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    // 시험 서버는 칸 크기와 무관하게 창마다 30초 칸 20개(10분)를 준다: 1시간 창 12개면 120분이다
    expect(screen.getByText('마지막 120분 채팅')).toBeInTheDocument();
    expect(screen.queryByText('방송 중 채팅')).toBeNull();
  });

  it('12시간 넘는 방송의 분당 채팅은 읽은 끝 12시간으로 나눈다: 방송 전체 길이로 나누면 줄어 보인다', async () => {
    // 창 12개 × 칸 20개(30초) = 120분에 480건씩 → 분당 48. 방송 15시간으로 나누면 분당 6이다(리뷰 2라운드)
    server.vodLong = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    // 통계의 「분당 평균 채팅」과 같은 수다(소수 한 자리): 반올림이 다르면 한 화면에 1과 0.7이 선다(리뷰 5라운드)
    expect(screen.getByText(/분당 평균 48\.0$/)).toBeInTheDocument();
  });

  it('끝난 방송의 시청자 수는 비어 있는 마지막 관측이 아니라 마지막으로 잰 값이다', async () => {
    // 수집기는 목록에서 채널을 못 찾은 회차를 빈 값으로 남긴다. 그 값으로 「기록 없음」이라 하면 통계와 모순이다
    server.infoLastNull = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('끝날 때 2,310명 시청').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
  });

  it('방송 정보를 받기 전에는 끝난 방송의 시청자 수를 「기록 없음」이라 단정하지 않는다', async () => {
    server.infoFail = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('시청자 수 확인 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
    // 태그 자리도 같다: 받기 전이다(「수집 전」은 앞으로 모으겠다는 라이브 말이다, 리뷰 4라운드)
    expect(screen.getByText('방송 정보 확인 중')).toBeInTheDocument();
    expect(screen.queryByText('방송 정보 수집 전')).toBeNull();
  });

  it('시작 시각도 기준점도 모르는 지난 방송에서 카드를 누르면 채팅을 옮겼다고 하지 않는다: 그 시점을 절대 시각으로 못 바꾼다', async () => {
    server.vodStartUnknown = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    expect(screen.getByText('영상은 아직 없어요')).toBeInTheDocument();
    expect(screen.queryByText(/채팅을 그 시점으로 옮겼어요/)).toBeNull();
  });

  it('다른 방송을 보던 화면에서 넘어와도 앞 방송으로 요청을 보내지 않는다: 저장소를 비운 뒤에 그린다', async () => {
    // 비우기 전에 그리면 그 그리기가 앞 방송의 값을 칠하고, 그 효과들이 앞 방송 번호로 요청을 보낸다(리뷰 4라운드)
    act(() => publishLiveData({ streamId: STREAM_ID, status: 'live', startedAt: STARTED_AT }));
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    const stale = fetchSpy.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes(`/broadcasts/${STREAM_ID}/`));
    expect(stale).toEqual([]);
  });

  it('명부 답 전에도 지난 방송의 제목·카테고리는 바로 보인다: 시간 창과 무관한 방송 정보다', async () => {
    // 시청자 기록 때문에 방송 정보 전체를 기다리면 제목 자리에 방송 번호가, 태그 자리에 「방송 정보 수집 전」이 남는다
    // (리뷰 4라운드)
    server.listGate = new Promise(() => undefined);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByRole('heading', { level: 1, name: INFO.latest.title })).toBeInTheDocument();
    expect(screen.getByText('리그 오브 레전드')).toBeInTheDocument();
    expect(screen.queryByText('방송 정보 수집 전')).toBeNull();
  });

  it('지난 방송은 경과 시간을 모를 때도 「방송 중이 아니에요」라고 쓰지 않는다: 명부 답 전이다', async () => {
    server.listGate = new Promise(() => undefined);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.queryByText('방송 중이 아니에요')).toBeNull();
    expect(screen.getByText('방송함')).toBeInTheDocument();
  });

  it('지난 방송의 시청자 지표는 라이브 말(「수집 전」)을 쓰지 않는다: 기록을 받기 전에는 「확인 중」이다', async () => {
    // 명부 답 전에는 최근 창으로 읽은 방송 정보만 있다. 그 기록은 끝난 방송의 것이 아니다
    server.listGate = new Promise(() => undefined);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.queryByText('수집 전')).toBeNull();
    expect(screen.getByText('최고 시청자').nextElementSibling).toHaveTextContent('확인 중');
    expect(screen.getByText('평균 시청자').nextElementSibling).toHaveTextContent('확인 중');
  });

  it('시청자 기록이 전부 빈 지난 방송은 정보 바와 통계가 같은 말이다: 「기록 없음」', async () => {
    server.infoNoViewers = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('시청자 수 기록 없음').length).toBeGreaterThan(0);
    expect(screen.queryByText('수집 전')).toBeNull();
    expect(screen.getByText('최고 시청자').nextElementSibling).toHaveTextContent('기록 없음');
  });

  it('명부 답보다 먼저 온 최근 창의 시청자 기록을 끝난 방송의 기록으로 보지 않는다: since 답이 실패해도 「기록 없음」이 아니다', async () => {
    // 라이브 주소로 연 끝난 방송은 명부 답 전에 최근 창으로 읽는다. 그 답은 끝난 방송의 기록이 아니다(리뷰 4라운드)
    server.infoSinceFail = true;
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('시청자 수 확인 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
  });

  it('차트가 없으면 채팅 바닥도 분당 수를 지어내지 않는다: 통계와 같은 규칙이다', async () => {
    // 받은 채팅은 창마다 상한이 있어 방송 길이로 나누면 늘 적게 나온다. 통계는 「확인 중」인데 채팅 바닥은 「분당 0」이었다
    // (리뷰 4라운드)
    server.vodChartFail = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    // 바닥은 「 · 분당 N」(끝난 화면은 「 · 분당 평균 N」)이다. 통계의 「분당 평균 채팅」 이름표는 걸리지 않는다
    expect(screen.queryByText(/· 분당/)).toBeNull();
  });

  it('명부 답을 기다리는 동안 지난 방송의 그래프·채팅은 읽지 않고, 최근 창의 방송 정보로 시청자 수를 「기록 없음」이라 단정하지 않는다', async () => {
    // 수집기는 since가 없으면 최근 1시간만 준다. 명부 답 전에 그렇게 읽은 빈 기록을 끝난 방송의 기록으로 보면
    // 「기록 없음」이 된다(리뷰 3라운드). 차트·채팅은 그 동안 읽지 않고, 방송 정보는 제목·카테고리만 쓴다(리뷰 4라운드)
    let release: () => void = () => undefined;
    server.listGate = new Promise((resolve) => {
      release = resolve;
    });
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('시청자 수 확인 중').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
    const vodCalls = (part: string) =>
      fetchSpy.mock.calls
        .map(([url]) => new URL(String(url), 'http://localhost'))
        .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/${part}`);
    expect(vodCalls('chat-chart')).toEqual([]);
    expect(vodCalls('chat-messages')).toEqual([]);
    // 채팅 바닥도 그 동안은 분당 수를 적지 않는다: 라이브의 최근 1분 수(0)는 이 방송의 수가 아니다(리뷰 5라운드)
    expect(screen.queryByText(/· 분당/)).toBeNull();

    await act(async () => {
      release();
    });
    await settle();
    expect(screen.getAllByText('끝날 때 1,842명 시청').length).toBeGreaterThan(0);
  });

  it('명부 답 전에 보낸 옛 창의 답이 늦게 와도 끝난 방송의 시청자 수를 덮지 않는다', async () => {
    // 라이브 주소로 연 끝난 방송은 명부 답 전에 최근 창(since 없음)으로 읽는다. 그 답이 since 답보다 늦게 오면
    // 「끝날 때 N명」을 「기록 없음」으로 덮었다(리뷰 3라운드)
    let releaseInfo: () => void = () => undefined;
    server.infoNoSinceGate = new Promise((resolve) => {
      releaseInfo = resolve;
    });
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${VOD_ID}`);
    await renderLive();
    expect(screen.getAllByText('끝날 때 1,842명 시청').length).toBeGreaterThan(0);

    await act(async () => {
      releaseInfo();
    });
    await settle();

    expect(screen.getAllByText('끝날 때 1,842명 시청').length).toBeGreaterThan(0);
    expect(screen.queryByText('시청자 수 기록 없음')).toBeNull();
  });

  it('명부 답 전에 읽은 최근 그래프의 답이 늦게 와도 끝난 방송의 그래프를 덮지 않는다', async () => {
    // 덮으면 「방송 중 채팅」이 「최근 10분 채팅」·「지금」으로 돌아간다(리뷰 3라운드)
    let releaseChart: () => void = () => undefined;
    server.chartRecentGate = new Promise((resolve) => {
      releaseChart = resolve;
    });
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast/livenow?stream=${VOD_ID}`);
    await renderLive();
    expect(screen.getByText('방송 중 채팅')).toBeInTheDocument();

    await act(async () => {
      releaseChart();
    });
    await settle();

    expect(screen.getByText('방송 중 채팅')).toBeInTheDocument();
    expect(screen.queryByText('지금')).toBeNull();
  });

  it('시작 시각이 없는 지난 방송도 시청자 기록을 끝 시각 기준으로 읽는다: 최근 1시간 창이면 늘 비어 「기록 없음」이다', async () => {
    server.vodStartUnknown = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    const sinces = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/broadcast-info`)
      .map((u) => u.searchParams.get('since'));
    // 명부 답 전에는 제목·카테고리를 위해 최근 창으로도 읽는다(그 시청자 기록은 끝난 방송의 것으로 보지 않는다)
    expect(sinces).toContain(new Date(VOD_ENDED - 12 * 3_600_000).toISOString());
    expect(screen.getAllByText('끝날 때 1,842명 시청').length).toBeGreaterThan(0);
  });

  it('명부 답을 받기 전에도 지난 방송 주소면 지난 방송 문구다: 조회가 늦거나 실패해도 「실시간」이라 하지 않는다', async () => {
    server.failLive = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByRole('complementary', { name: '채팅 기록' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '방송 통계' })).toBeInTheDocument();
    expect(screen.queryByText(/자동 감지 중/)).toBeNull();
  });

  it('끝난 방송의 시청자 수는 「시청 중」이 아니라 끝날 때 값이다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('끝날 때 1,842명 시청').length).toBeGreaterThan(0);
    expect(screen.queryByText(/명 시청 중/)).toBeNull();
  });

  it('방송 전체를 읽은 그래프의 채팅 수는 「최근 N분」이 아니다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('방송 중 채팅')).toBeInTheDocument();
    expect(screen.queryByText(/^최근 \d+분 채팅$/)).toBeNull();
  });

  it('시작 시각을 모르는 지난 방송은 그래프·채팅·시청자 기록을 같은 창(끝 12시간)으로 읽는다', async () => {
    // 그래프만 지금 기준 최근 10분을 읽으면 끝난 지 한참 된 방송은 늘 비고, 시청자 기록과 다른 창이라 최고·평균은 있는데
    // 선이 없다. 채팅은 아예 읽지 않았다(리뷰 4라운드)
    server.vodStartUnknown = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.queryByText('지금')).toBeNull();
    expect(screen.getByText('마지막 120분 채팅')).toBeInTheDocument();
    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => Date.parse(u.searchParams.get('from') ?? ''));
    expect(froms).toContain(VOD_ENDED - 12 * 3_600_000);
  });

  it('지난 방송은 라이브 문구를 쓰지 않는다: 채팅 기록·방송 통계, 감지 중 안내 없음, 그래프 끝은 끝난 시각', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByRole('complementary', { name: '채팅 기록' })).toBeInTheDocument();
    expect(screen.queryByRole('complementary', { name: '실시간 채팅' })).toBeNull();
    expect(screen.getByRole('region', { name: '방송 통계' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '실시간 통계' })).toBeNull();
    expect(screen.getByText('카드 클릭 = 시점 이동')).toBeInTheDocument();
    expect(screen.queryByText(/자동 감지 중/)).toBeNull();
    // 끝난 방송에 「지금」은 없다. 그래프 오른쪽 끝은 마지막 칸이 끝난 시각이다
    expect(screen.queryByText('지금')).toBeNull();
  });

  it('12시간 넘는 지난 방송의 채팅도 그래프·시청자 기록과 같은 끝 12시간 창으로 읽는다', async () => {
    // 채팅만 처음부터 읽어 통계의 「마지막 720분」과 다른 창을 보였다. 앞쪽 카드는 그 시점 앞뒤 60초를 따로 읽는다(리뷰 5라운드)
    server.vodLong = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => Date.parse(u.searchParams.get('from') ?? ''));
    expect(froms.length).toBeGreaterThan(0);
    expect(Math.min(...froms)).toBe(VOD_STARTED + 3 * 3_600_000);
  });

  it('방송 정보에 최신 관측이 없는 지난 방송은 태그 자리가 「방송 정보 기록 없음」이다', async () => {
    server.infoNoLatest = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('방송 정보 기록 없음')).toBeInTheDocument();
  });

  it('끝난 방송의 영상 자리는 녹화를 확인하는 동안 「확인하고 있어요」, 없으면 「찾지 못했어요」다: 「아직 연결되지 않았어요」라고 하지 않는다', async () => {
    // 녹화가 없거나 보관 기간이 지난 방송에도 「아직」이라 해서 기다리면 나올 것처럼 읽혔다(리뷰 4라운드 실기동)
    let answer: (spans: { startMs: number; durationSeconds: number }[]) => void = () => undefined;
    mediaCalls.configured = true;
    mediaCalls.answer = new Promise((resolve) => {
      answer = resolve;
    });
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    expect(screen.getByText('녹화 영상을 확인하고 있어요')).toBeInTheDocument();
    expect(screen.queryByText(/아직 연결되지 않았어요/)).toBeNull();

    await act(async () => {
      answer([]);
    });
    await settle();
    expect(screen.getByText('녹화 영상을 찾지 못했어요')).toBeInTheDocument();
  });

  it('재생 서버가 없는 환경에서는 녹화가 없다고 단정하지 않는다: 영상 서버에 아직 연결되지 않았다', async () => {
    // dev·운영은 녹화 재생 서버 주소가 비어 있다. 묻지도 않고 끝난 방송마다 「녹화 영상을 찾지 못했어요」라고 했다(리뷰 6라운드).
    // 녹화 답을 붙들어 두어도 「확인하고 있어요」라 하지 않는다: 물을 곳이 없다
    mediaCalls.answer = new Promise(() => undefined);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('영상 서버에 아직 연결되지 않았어요')).toBeInTheDocument();
    expect(screen.queryByText(/녹화 영상을/)).toBeNull();
  });

  it('재생 서버가 없는 환경에서는 녹화를 다시 묻지 않는다: 물을 곳이 없다', async () => {
    mediaCalls.answer = Promise.resolve(null);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180_000);
    });

    expect(mediaCalls.spans).toHaveLength(1);
  });

  it('지난 방송 주소는 명부 답 전에도 녹화를 확인하고 있다고 쓴다: 라이브 화면에는 녹화 말이 없다', async () => {
    mediaCalls.configured = true;
    server.listGate = new Promise(() => undefined);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('녹화 영상을 확인하고 있어요')).toBeInTheDocument();
  });

  it('라이브 화면의 영상 자리에는 녹화를 찾는다는 말이 없다', async () => {
    mediaCalls.configured = true;
    await renderLive();

    expect(screen.queryByText(/녹화 영상을/)).toBeNull();
  });

  it('녹화가 없다고 확인된 지난 방송에서 카드를 누르면 「녹화 영상이 없어요」다: 플레이어와 같은 판정이다', async () => {
    // 플레이어는 「찾지 못했어요」인데 알림은 「영상은 아직 없어요」라 한 화면에서 말이 어긋났다(리뷰 6라운드)
    mediaCalls.configured = true;
    mediaCalls.answer = Promise.resolve([]);
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    expect(
      screen.getByText('녹화 영상이 없어요 · 채팅을 그 시점으로 옮겼어요'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/영상은 아직 없어요/)).toBeNull();
  });

  it('명부 답 전에도 최신 관측이 없는 지난 방송의 태그 자리는 「방송 정보 기록 없음」이다', async () => {
    server.listGate = new Promise(() => undefined);
    server.infoNoLatest = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('방송 정보 기록 없음')).toBeInTheDocument();
  });

  it('끝난 방송 채팅 바닥의 분당 수는 통계와 같은 「분당 평균」이다: 반올림이 다르면 1과 0.7이 선다', async () => {
    // 가짜 차트의 평균이 정수면 반올림이 되살아나도 모른다(리뷰 6라운드). 창마다 7건 × 3창 ÷ 30분 = 0.7
    server.vodChartSparse = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText(/분당 평균 0\.7$/)).toBeInTheDocument();
    expect(screen.getByText('분당 평균 채팅').nextElementSibling).toHaveTextContent('0.7');
  });

  it('큰 방송에서도 채팅 바닥과 통계의 분당 평균은 같은 꼴이다: 천 단위 쉼표까지', async () => {
    // 0.7로만 재면 통계가 쉼표 없는 꼴로 되돌아가도 모른다. 큰 방송에서 「1,234.5」와 「1234.5」가 한 화면에 섰다(리뷰 7라운드)
    server.vodChartSparse = true;
    server.vodChartBusy = true;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.getByText(/분당 평균 1,234\.5$/)).toBeInTheDocument();
    expect(screen.getByText('분당 평균 채팅').nextElementSibling).toHaveTextContent(/^1,234\.5$/);
  });

  it('지난 방송 채팅 바닥은 「최신 메시지 따라가는 중」이라 하지 않는다: 새 메시지가 오지 않는 기록이다', async () => {
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();

    expect(screen.queryByText(/최신 메시지 따라가는 중/)).toBeNull();
    expect(screen.getByText(/마지막 메시지까지 보는 중/)).toBeInTheDocument();
  });

  it('틀고 있는 녹화가 있으면 채팅도 그 녹화 기준이다 — 플레이어와 같은 축이어야 영상과 맞는다', async () => {
    server.vodOrigin = VOD_STARTED + 32_000;
    window.history.pushState({}, '', `/broadcast/vod/${VOD_ID}`);
    await renderLive();
    act(() => publishLiveData({ timeBaseMs: VOD_STARTED + 30_000 }));

    fireEvent.click(screen.getByRole('button', { name: '0:47:22 시점으로 이동' }));
    await settle();

    const froms = fetchSpy.mock.calls
      .map(([url]) => new URL(String(url), 'http://localhost'))
      .filter((u) => u.pathname === `/api/clip/broadcasts/${VOD_ID}/chat-messages`)
      .map((u) => u.searchParams.get('from'));
    expect(froms).toContain(
      new Date(VOD_STARTED + 30_000 + 2_842_000 - 60_000 + 3_900).toISOString(),
    );
  });
});

describe('LiveScreen: 값이 없을 때의 말(리뷰 5라운드)', () => {
  // 「확인 중」은 모른다, 「기록 없음」은 끝난 방송에 없다, 「수집 전」은 라이브에서 받았는데 아직 못 쟀다. 네 칸이 이 말을 따로
  // 정해서 한 화면에 「확인 중」과 「수집 전」이 같이 섰다

  it('라이브에서 방송 정보를 못 받으면 시청자 수도 통계도 「확인 중」이다', async () => {
    server.infoFail = true;
    await renderLive();

    expect(screen.getByText('방송 정보 확인 중')).toBeInTheDocument();
    expect(screen.getAllByText('시청자 수 확인 중').length).toBeGreaterThan(0);
    expect(screen.queryByText(/수집 전/)).toBeNull();
    expect(screen.getByText('최고 시청자').nextElementSibling).toHaveTextContent('확인 중');
  });

  it('라이브 주소로 연 방송도 명부 답 전에는 「수집 전」이라 하지 않는다: 라이브인지 아직 모른다', async () => {
    server.listGate = new Promise(() => undefined);
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast?stream=${VOD_ID}`);
    await renderLive();

    expect(screen.getAllByText('시청자 수 확인 중').length).toBeGreaterThan(0);
    expect(screen.queryByText(/수집 전/)).toBeNull();
    // 경과 칸도 모른다고 쓴다: 꺼짐 말이 아니다
    expect(screen.queryByText('방송 중이 아니에요')).toBeNull();
  });

  it('상태를 모르는 동안은 방송 정보에 최신 관측이 없어도 태그 자리에 「수집 전」이라 하지 않는다', async () => {
    server.listGate = new Promise(() => undefined);
    server.infoNoLatest = true;
    nav.search = `?stream=${VOD_ID}`;
    window.history.pushState({}, '', `/broadcast?stream=${VOD_ID}`);
    await renderLive();

    expect(screen.getByText('방송 정보 확인 중')).toBeInTheDocument();
  });

  it('주소에 박힌 라이브가 명부에서 사라지면 경과 시간을 지운다: 「방송 중이 아니에요」 옆에서 시계가 흐르지 않는다', async () => {
    // 위임이 회수되거나 탈퇴로 지워진 방송이다. 시계의 시작 시각이 남아 「1:24:04 방송 중이 아니에요」가 됐다(리뷰 5라운드)
    nav.search = `?stream=${STREAM_ID}`;
    window.history.pushState({}, '', `/broadcast?stream=${STREAM_ID}`);
    await renderLive();
    expect(screen.getByText('스트리밍 중')).toBeInTheDocument();

    server.live = false;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(61_000);
    });
    await settle();

    expect(screen.getByText('방송 중이 아니에요')).toBeInTheDocument();
    expect(screen.queryByText(/^1:2\d:\d\d$/)).toBeNull();
    expect(screen.queryByText(/명 시청 중/)).toBeNull();
  });

  it('저장소를 비우는 첫 그림도 빈칸이 아니라 상자 하나다: Next 스크롤 처리기가 잡을 화면이 있어야 맨 위로 올린다', async () => {
    // 첫 그림이 비면 Next 16.3 스크롤 처리기는 「잡을 화면 없음」으로 건너뛰고, 다음 그림에는 다시 돌지 않아 맨 위로 올리라는
    // 뜻이 남는다(리뷰 5라운드). 첫 그림의 레이아웃 효과에서 본 자식 수를 잰다
    const firstCommit: number[] = [];
    function Probe({ children }: { children: ReactNode }) {
      const ref = useRef<HTMLDivElement>(null);
      useLayoutEffect(() => {
        firstCommit.push(ref.current?.childElementCount ?? -1);
      }, []);
      return <div ref={ref}>{children}</div>;
    }
    renderWithProviders(
      <Probe>
        <LiveScreen />
      </Probe>,
    );
    await settle();

    expect(firstCommit[0]).toBeGreaterThan(0);
  });
});
