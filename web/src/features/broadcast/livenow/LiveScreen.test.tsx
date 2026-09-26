import { act } from 'react';
import { fireEvent, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveScreen } from '@/features/broadcast/livenow/LiveScreen';
import { resetLiveData } from '@/features/broadcast/livenow/liveDataStore';
import { CARD_CREATE_MS } from '@/features/broadcast/livenow/useManualMarking';
import { forgetLiveStreamId } from '@/features/broadcast/streamSelection';
import { useAuthStore } from '@/stores/auth';
import { jsonResponse, stubFetch } from '@/test/mockFetch';
import { renderWithProviders } from '@/test/testProviders';

// 라이브 대시보드(시안 1b)는 clip 창구에 붙어 있다(POK-251). 이 시험은 서버 응답을 흉내 내고
// 화면이 그 값으로 무엇을 그리는지 잰다. 시계는 가짜다 — 경과·마킹 시각이 결정적이어야 한다.

const nav = vi.hoisted(() => ({ search: '' }));

// useSearchParams 대체 — ?stream=(useMediaSource)은 비워 두고 아래 env 고정과 함께 소스를 null로 만들어
// 플레이어가 「영상 신호 없음」 자리로 결정적으로 선다. ?mock=(오프라인 목업 토글)은 케이스가 nav.search로 정한다.
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(nav.search),
}));

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
    // 앞 넷은 최근 10분 차트 안, 뒤 넷은 그보다 오래됐다
    createdAt: new Date(NOW - (id <= 4 ? id * 60_000 : 30 * 60_000 + id * 60_000)).toISOString(),
    ...over,
  };
}

// 자동 6 · 수동 2. eventSeq가 큰 것이 앞에 선다.
const CARDS: Card[] = [
  card(8, { score: 97, streamTimestampMs: 5_000_000, eventSeq: 80 }),
  card(7, { source: 'hotkey', score: null, evidence: null, eventSeq: 70 }),
  card(6, { claimedBy: '5', eventSeq: 60 }),
  card(5, { claimedBy: ME, eventSeq: 50 }),
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

const server = vi.hoisted(() => ({ live: true }));

function handle(url: string) {
  const u = new URL(url, 'http://localhost');
  const path = u.pathname;
  if (path === '/api/clip/broadcasts') {
    const live = u.searchParams.get('state') === 'live' && server.live;
    const row = {
      streamId: STREAM_ID,
      status: 'live',
      relation: 'OWNER',
      startedAt: new Date(STARTED_AT).toISOString(),
      endedAt: null,
      vodExpiresAt: null,
    };
    return jsonResponse(200, { broadcasts: live ? [row] : [], nextCursor: null });
  }
  if (path.endsWith('/jump-cards')) return jsonResponse(200, { cards: CARDS });
  if (path.endsWith('/chat-chart')) return jsonResponse(200, CHART);
  if (path.endsWith('/broadcast-info')) return jsonResponse(200, INFO);
  if (path.endsWith('/chat-messages'))
    return jsonResponse(200, { items: CHAT, nextCursor: null, appliedOffsetMs: 0 });
  // 통로는 용량 초과로 답한다 — 폴링이 카드를 채운다(통로 자체는 useLiveMockState의 몫)
  if (path.endsWith('/events')) return jsonResponse(503, { error: 'sse_capacity' });
  if (path.endsWith('/playback-access')) return jsonResponse(503, { error: 'signing_unavailable' });
  if (path === '/api/chzzk-link')
    return jsonResponse(200, { linked: true, channelName: '게임하는너구리', status: 'ACTIVE' });
  if (path === '/api/auth/me')
    return jsonResponse(200, {
      id: 9,
      email: 'me@example.com',
      name: '박편집',
      profileImageUrl: null,
    });
  if (path.endsWith('/claim') || path.endsWith('/hide')) return new Response(null, { status: 204 });
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
  server.live = true;
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

  it('채널 이름은 치지직 연동에서 온다', async () => {
    await renderLive();
    expect(screen.getByText('게임하는너구리')).toBeInTheDocument();
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

  it('강조는 맨 앞 카드 하나뿐이다 — 마킹할수록 쌓이면 「방금」이라는 뜻을 잃는다', async () => {
    const { container } = await renderLive();

    const emphasizedCount = () => container.querySelectorAll('[class*="cardEmphasized"]').length;
    expect(emphasizedCount()).toBe(1);

    fireEvent.keyDown(document, { key: 'F8' });
    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    expect(emphasizedCount()).toBe(1);
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
    expect(
      screen.getByText('영상은 아직 없어요 · 채팅을 그 시점으로 옮겼어요'),
    ).toBeInTheDocument();
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

describe('LiveScreen — 수동 마킹', () => {
  // 이 파일은 전부 fireEvent다 — userEvent는 가짜 시계와 엉켜 멈춘다(GlassPlayer 자동 숨김 케이스와 같은 이유).

  it('버튼을 누르면 피드백과 만드는 중 자리가 서고, 잠시 뒤 카드가 된다', async () => {
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));
    expect(screen.getByText('1:24:03 마킹됨 · 카드 생성 중')).toBeInTheDocument();
    expect(screen.getByText('카드 만드는 중…')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });

    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '1:24:03 수동 마킹' })).toBeInTheDocument();
    // 만들어진 카드가 필터 개수에도 바로 반영된다
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체 9' })).toBeInTheDocument();
  });

  it('경과 시간이 흐르고, 마킹은 멈춘 값이 아니라 그때의 시각을 찍는다', async () => {
    await renderLive();

    expect(screen.getByText('1:24:03')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(120_000); // 2분
    });
    expect(screen.getByText('1:26:03')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'F8' });
    expect(screen.getByText('1:26:03 마킹됨 · 카드 생성 중')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    // 카드가 서기까지 3초가 더 흘러도 시각은 누른 순간으로 굳어 있다
    expect(screen.getByRole('heading', { name: '1:26:03 수동 마킹' })).toBeInTheDocument();
  });

  it('「자동」을 보고 있을 땐 만드는 중 자리도 두지 않는다', async () => {
    // 만들어질 카드는 수동이라 이 필터에선 안 보인다 — 자리만 섰다 사라지면 실패로 읽힌다
    await renderLive();

    fireEvent.click(screen.getByRole('button', { name: '자동 6' }));
    fireEvent.keyDown(document, { key: 'F8' });

    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    // 눌린 것 자체는 버튼 아래 피드백이 말한다
    expect(screen.getByText(/마킹됨 · 카드 생성 중/)).toBeInTheDocument();
  });

  it('F8을 누르면 어디에 포커스가 있든 같은 흐름이 돈다', async () => {
    await renderLive();

    fireEvent.keyDown(document, { key: 'F8' });
    expect(screen.getByText('카드 만드는 중…')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
  });

  it('만드는 중에 또 눌러도 카드는 하나만 생긴다', async () => {
    await renderLive();

    fireEvent.keyDown(document, { key: 'F8' });
    fireEvent.keyDown(document, { key: 'F8' });
    fireEvent.click(screen.getByRole('button', { name: /수동 마킹/ }));

    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
  });

  it('자동반복과 입력 중인 곳에서 누른 F8은 무시한다', async () => {
    await renderLive();

    // 누르고 있으면 초당 수십 장이 생긴다
    fireEvent.keyDown(document, { key: 'F8', repeat: true });
    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();

    // 글자를 치는 곳에서 누른 키는 그쪽 것이다
    const input = document.createElement('input');
    document.body.appendChild(input);
    fireEvent.keyDown(input, { key: 'F8' });
    expect(screen.queryByText('카드 만드는 중…')).not.toBeInTheDocument();
    input.remove();
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

  it('하이라이트 지표는 카드 목록에서 센다 — 마킹하면 필터와 같이 움직인다', async () => {
    await renderLive();

    expect(screen.getByText('자동 6 · 수동 2')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'F8' });
    act(() => {
      vi.advanceTimersByTime(CARD_CREATE_MS);
    });

    expect(screen.getByText('자동 6 · 수동 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '수동 3' })).toBeInTheDocument();
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
