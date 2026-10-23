import { act } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomeScreen } from '@/features/home/HomeScreen';
import { LiveNowBand } from '@/features/home/LiveNowBand';
import { TipCard, TIPS } from '@/features/home/TipCard';
import { daysAgoLabel, weekStarts, type LiveNow } from '@/features/home/useHomeMockState';
import { useOnboardingStore } from '@/stores/onboarding';
import { ChannelRelationContext } from '@/features/channel/ChannelScope';

const LIVE: LiveNow = {
  streamId: 'stream-1',
  title: 'stream-1',
  platform: '치지직',
  startedNote: '오후 7:12 시작',
  uptimeLabel: '1:24:03',
  viewers: null,
  detectedCards: 8,
  completedClips: null,
};

// 홈은 마운트 뒤 clip·auth·연동 창구를 부른다 — 시험마다 필요한 것만 채운 가짜 서버를 세운다.
interface Server {
  live?: unknown[];
  past?: unknown[];
  /** streamId → 카드 */
  cards?: Record<string, unknown[]>;
  library?: unknown[] | 'fail';
  info?: unknown;
  chzzk?: unknown;
  soop?: unknown;
  youtube?: unknown;
  streamKey?: unknown;
  me?: unknown;
}

function ok(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function route(url: string, s: Server): Response {
  if (url.startsWith('/api/clip/library')) {
    return s.library === 'fail'
      ? new Response(JSON.stringify({ error: 'unavailable' }), { status: 503 })
      : ok({ items: s.library ?? [], nextCursor: null });
  }
  if (url.includes('state=live')) return ok({ broadcasts: s.live ?? [], nextCursor: null });
  if (url.includes('state=past')) return ok({ broadcasts: s.past ?? [], nextCursor: null });
  const cards = url.match(/\/broadcasts\/([^/]+)\/jump-cards/);
  if (cards) return ok({ cards: s.cards?.[decodeURIComponent(cards[1]!)] ?? [], nextCursor: null });
  if (url.endsWith('/broadcast-info')) return ok(s.info ?? { latest: null, series: [] });
  if (url.startsWith('/api/chzzk-link')) return ok(s.chzzk ?? { linked: false });
  if (url.startsWith('/api/soop-link')) return ok(s.soop ?? { linked: false });
  if (url.startsWith('/api/youtube-link')) return ok(s.youtube ?? { linked: false });
  if (url.startsWith('/api/stream-keys')) return ok(s.streamKey ?? { issued: false });
  return ok(s.me ?? { id: 1, email: 'demo@example.com', name: '재환', profileImageUrl: null });
}

function serve(s: Server = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => Promise.resolve(route(String(input), s))),
  );
}

/** 지난 방송 한 줄 — 시작 시각만 바꿔 쓴다 */
function pastRow(streamId: string, startedAt: Date, extra: Record<string, unknown> = {}) {
  return {
    streamId,
    status: 'vod_ready',
    relation: 'OWNER',
    startedAt: startedAt.toISOString(),
    endedAt: new Date(startedAt.getTime() + 2 * 3_600_000).toISOString(),
    vodExpiresAt: null,
    title: streamId,
    ...extra,
  };
}

function card(id: number, hidden = false) {
  return {
    id,
    source: 'auto',
    streamTimestampMs: id * 1000,
    score: 80,
    hidden,
    createdAt: '2026-10-20T10:00:00Z',
  };
}

/** 보관함 한 줄 — 가장 최근 영상과 업로드를 붙여 쓴다 */
function entry(
  recipeId: number,
  clip: { status: string; createdAt: Date } | null,
  upload?: { status: string; updatedAt: Date },
) {
  return {
    recipeId,
    streamId: 'past-1',
    creatorId: '1',
    recipeVersion: 1,
    cut: null,
    status: 'rendered',
    broadcast: { status: 'vod_ready', startedAt: null, endedAt: null, vodExpiresAt: null },
    latestClip: clip && {
      id: recipeId,
      streamId: 'past-1',
      recipeId,
      recipeVersion: 1,
      requestedBy: '1',
      status: clip.status,
      progress: null,
      outputs: [],
      error: null,
      createdAt: clip.createdAt.toISOString(),
      updatedAt: clip.createdAt.toISOString(),
      upload: null,
    },
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    liveUploads: upload
      ? [
          {
            id: recipeId,
            clipId: recipeId,
            recipeVersion: 1,
            outputId: 'o1',
            title: `영상 ${recipeId}`,
            status: upload.status,
            videoId: null,
            createdAt: upload.updatedAt.toISOString(),
            updatedAt: upload.updatedAt.toISOString(),
          },
        ]
      : [],
  };
}

function resetOnboarding() {
  window.localStorage.clear();
  useOnboardingStore.setState({
    welcomeSeen: false,
    tourDone: false,
    channelLinked: false,
    pluginLinked: false,
    hydrated: false,
    tourStep: null,
  });
}

describe('HomeScreen', () => {
  beforeEach(() => {
    resetOnboarding();
    serve();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('첫 응답 전에는 신규 계정 화면(준비 단계)을 보이지 않는다', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => {})),
    );
    render(<HomeScreen />);

    expect(screen.getByRole('region', { name: '불러오는 중' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
    expect(screen.queryByText(/첫 클립까지/)).not.toBeInTheDocument();
  });

  describe('맨 위 큰 칸(POK-378)', () => {
    // 2026-10-21(수) 정오 — 이번 주는 10월 19일(월) 0시부터, 지난주는 10월 12일(월)부터
    const NOW = new Date(2026, 9, 21, 12, 0);
    const at = (month: number, day: number, hour = 20) => new Date(2026, month - 1, day, hour);

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(NOW);
    });

    it('방송 기록이 없으면 첫 클립까지 가는 길과 「포키가 해 주는 일」을 보인다', async () => {
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '첫 클립까지 4단계 남았어요' }),
      ).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '채널 연동' })).toHaveAttribute(
        'href',
        '/settings/channels',
      );
      // 방송 채널은 플랫폼을 가리지 않는다 — 치지직만 말하지 않는다
      expect(screen.getByText('방송 채널 연동')).toBeInTheDocument();
      expect(screen.getByText('방송 플랫폼을 연결하면 방송을 알아서 찾아요')).toBeInTheDocument();
      // 방송 채널·플러그인은 순서가 없다 — 지금 단계가 아니어도 버튼이 있다
      expect(screen.getByRole('link', { name: '코드 받기' })).toHaveAttribute(
        'href',
        '/settings/plugin',
      );
      expect(screen.getByRole('region', { name: '포키가 해 주는 일' })).toBeInTheDocument();
      expect(screen.queryByRole('region', { name: '지난 방송 · VOD' })).not.toBeInTheDocument();
      expect(screen.queryByRole('complementary')).not.toBeInTheDocument();
    });

    it('방송 채널·연동 코드가 끝났으면 방송을 기다리고, 시작 가이드 체크에도 올린다', async () => {
      serve({
        chzzk: { linked: true, channelName: '재환의 방송', status: 'ACTIVE' },
        streamKey: { issued: true, createdAt: '2026-10-20T00:00:00Z' },
      });
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '이제 방송만 켜면 돼요' }),
      ).toBeInTheDocument();
      expect(screen.getByText('재환의 방송 연결됨')).toBeInTheDocument();
      expect(screen.getByText('기다리는 중')).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: '채널 연동' })).not.toBeInTheDocument();
      // 유튜브가 아직이면 아래 한 줄로 권한다
      expect(screen.getByRole('link', { name: '유튜브 연동' })).toHaveAttribute(
        'href',
        '/settings/channels',
      );
      expect(useOnboardingStore.getState().channelLinked).toBe(true);
      expect(useOnboardingStore.getState().pluginLinked).toBe(true);
    });

    it('SOOP만 연동해도 방송만 남았다고 하고 시작 가이드 1단계를 세운다 (POK-396)', async () => {
      serve({
        soop: { linked: true, status: 'ACTIVE', nickname: '숲너구리' },
        streamKey: { issued: true },
      });
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '이제 방송만 켜면 돼요' }),
      ).toBeInTheDocument();
      expect(screen.getByText('SOOP 연결됨')).toBeInTheDocument();
      expect(useOnboardingStore.getState().channelLinked).toBe(true);
    });

    it('이번 주 기록 — 방송·카드는 이번 주에 시작한 방송, 클립·발행은 이번 주에 만든·올린 것만 센다', async () => {
      serve({
        past: [pastRow('this-week', at(10, 20)), pastRow('last-week', at(10, 14))],
        cards: {
          // 숨김 칸이 찬 옛 카드도 센다: 숨기기는 POK-393에서 없앴다
          'this-week': [card(1), card(2), card(3, true)],
          'last-week': [card(4), card(5)],
        },
        library: [
          entry(
            1,
            { status: 'rendered', createdAt: at(10, 20) },
            { status: 'uploaded', updatedAt: at(10, 21, 9) },
          ),
          entry(
            2,
            { status: 'rendered', createdAt: at(10, 13) },
            { status: 'uploaded', updatedAt: at(10, 13) },
          ),
          entry(3, { status: 'rendering', createdAt: at(10, 20) }),
          entry(
            4,
            { status: 'rendered', createdAt: at(10, 19, 1) },
            { status: 'checking', updatedAt: at(10, 20) },
          ),
        ],
      });
      render(<HomeScreen />);

      const hero = await screen.findByRole('region', { name: '이번 주' });
      expect(
        within(hero).getByRole('heading', { level: 1, name: '방송 1번에서 카드 3장을 찾았어요' }),
      ).toBeInTheDocument();
      expect(hero).toHaveTextContent('만든 클립2개');
      expect(hero).toHaveTextContent('유튜브 발행1개');
      expect(screen.getByRole('region', { name: '지난 방송 · VOD' })).toBeInTheDocument();
    });

    it('기록은 있지만 이번 주엔 아직 — 마지막 방송이 며칠 전인지와 흐린 지난주 기록을 보인다', async () => {
      serve({
        past: [pastRow('sunday', at(10, 18)), pastRow('earlier', at(10, 13))],
        cards: { sunday: [card(1)], earlier: [card(2), card(3)] },
      });
      render(<HomeScreen />);

      const hero = await screen.findByRole('region', { name: '이번 주' });
      expect(
        within(hero).getByRole('heading', { level: 1, name: '마지막 방송은 3일 전이었어요' }),
      ).toBeInTheDocument();
      expect(hero).toHaveTextContent('10월 18일 이후로 쉬고 있어요');
      expect(within(hero).getByText('지난주 기록')).toBeInTheDocument();
      expect(hero).toHaveTextContent('방송2회');
      expect(hero).toHaveTextContent('찾은 카드3장');
    });

    it('지난주에도 방송이 없었으면 숫자 줄 없이 마지막 방송만 말한다', async () => {
      serve({ past: [pastRow('old', at(10, 1))] });
      render(<HomeScreen />);

      const hero = await screen.findByRole('region', { name: '이번 주' });
      expect(within(hero).getByRole('heading', { level: 1 })).toHaveTextContent(
        '마지막 방송은 20일 전이었어요',
      );
      expect(within(hero).queryByText('지난주 기록')).not.toBeInTheDocument();
    });

    it('보관함을 못 읽었으면 만든 클립·유튜브 발행은 0이 아니라 「–」다', async () => {
      serve({ past: [pastRow('this-week', at(10, 20))], library: 'fail' });
      render(<HomeScreen />);

      const hero = await screen.findByRole('region', { name: '이번 주' });
      expect(hero).toHaveTextContent('만든 클립–');
      expect(hero).toHaveTextContent('유튜브 발행–');
    });

    it('동시에 방송 중인 방송이 여럿이면(여러 스트리머의 편집자) 이번 주 숫자에 모두 센다(리뷰 #299)', async () => {
      const liveRow = (streamId: string) => ({
        streamId,
        status: 'live',
        relation: 'EDITOR',
        startedAt: at(10, 21, 10).toISOString(),
        endedAt: null,
        vodExpiresAt: null,
        title: streamId,
      });
      serve({
        live: [liveRow('live-a'), liveRow('live-b')],
        cards: { 'live-a': [card(1), card(2)], 'live-b': [card(3), card(4), card(5)] },
      });
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '방송 2번에서 카드 5장을 찾았어요' }),
      ).toBeInTheDocument();
    });

    it('상위 8개 밖의 지난 방송 카드는 매 주기 다시 읽지 않고 기억해 둔다(리뷰 #299)', async () => {
      // 위 beforeEach는 Date만 가짜다 — 주기(setInterval)까지 돌리려면 갈아 끼운다
      vi.useRealTimers();
      vi.useFakeTimers({ shouldAdvanceTime: true });
      vi.setSystemTime(NOW);
      // 이번 주·지난주에 9번 방송 — 가장 오래된 하나(10월 12일)가 상위 8개 밖이다
      const rows = [20, 19, 18, 17, 16, 15, 14, 13, 12].map((day) =>
        pastRow(`b-${day}`, at(10, day)),
      );
      const spy = vi.fn((input: RequestInfo | URL) =>
        Promise.resolve(route(String(input), { past: rows })),
      );
      vi.stubGlobal('fetch', spy);
      const cardCalls = (streamId: string) =>
        spy.mock.calls.filter(([u]) => String(u).includes(`/${streamId}/jump-cards`)).length;
      render(<HomeScreen />);
      await screen.findByRole('region', { name: '이번 주' });
      expect(cardCalls('b-12')).toBe(1);
      expect(cardCalls('b-20')).toBe(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000);
      });
      // 상위 8개는 매 주기 다시, 그 밖은 기억해 둔 값을 쓴다
      expect(cardCalls('b-20')).toBe(2);
      expect(cardCalls('b-12')).toBe(1);
    });

    it('상위 8개 밖 방송의 카드를 못 읽었으면 빈 값을 기억하지 않고 다음 주기에 다시 읽는다(리뷰 #299 2차)', async () => {
      vi.useRealTimers();
      vi.useFakeTimers({ shouldAdvanceTime: true });
      vi.setSystemTime(NOW);
      // 이번 주엔 아직 — 지난주에만 9번 방송했고 가장 오래된 「old」가 상위 8개 밖이다
      const slots: [number, number][] = [
        [18, 20],
        [17, 20],
        [16, 20],
        [15, 20],
        [14, 20],
        [13, 20],
        [12, 22],
        [12, 21],
      ];
      const rows = [
        ...slots.map(([day, hour], i) => pastRow(`b-${i}`, at(10, day, hour))),
        pastRow('old', at(10, 12, 20)),
      ];
      let failOld = true;
      const spy = vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (failOld && url.includes('/old/jump-cards'))
          return Promise.resolve(new Response('{}', { status: 503 }));
        return Promise.resolve(route(url, { past: rows, cards: { old: [card(1), card(2)] } }));
      });
      vi.stubGlobal('fetch', spy);
      render(<HomeScreen />);

      const hero = await screen.findByRole('region', { name: '이번 주' });
      expect(hero).toHaveTextContent('찾은 카드0장');

      failOld = false;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000);
      });
      // 실패를 5분 동안 붙들지 않고 바로 다음 주기에 다시 읽어 바로잡는다
      expect(hero).toHaveTextContent('찾은 카드2장');
    });

    it('방송이 막 끝나 라이브·지난 방송 목록에 다 실려 와도 한 번만 센다(리뷰 #299 2차)', async () => {
      const row = pastRow('just-ended', at(10, 21, 9));
      serve({
        live: [{ ...row, status: 'live', endedAt: null }],
        past: [row],
        cards: { 'just-ended': [card(1), card(2)] },
      });
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '방송 1번에서 카드 2장을 찾았어요' }),
      ).toBeInTheDocument();
    });

    it('방송 중이면 라이브 밴드가 지난 방송 위에 서고, 지난주에 켠 방송도 이번 주에 센다', async () => {
      serve({
        live: [
          {
            streamId: 'live-1',
            status: 'live',
            relation: 'OWNER',
            // 일요일 밤에 켜서 아직 하고 있다
            startedAt: at(10, 18, 23).toISOString(),
            endedAt: null,
            vodExpiresAt: null,
            title: '새벽까지 랭크',
          },
        ],
        past: [pastRow('past-1', at(10, 10))],
      });
      render(<HomeScreen />);

      expect(
        await screen.findByRole('heading', { level: 1, name: '방송 1번에서 카드 0장을 찾았어요' }),
      ).toBeInTheDocument();
      const live = screen.getByRole('region', { name: '라이브' });
      const vod = screen.getByRole('region', { name: '지난 방송 · VOD' });
      expect(live.compareDocumentPosition(vod) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });
  });

  it('이어서 편집 배너·발행 현황·만료 임박 카드는 없다(POK-378)', async () => {
    serve({
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      library: [entry(1, null)],
    });
    render(<HomeScreen />);

    await screen.findByRole('region', { name: '지난 방송 · VOD' });
    expect(screen.queryByRole('region', { name: '이어서 편집' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '발행 현황' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '만료 임박 VOD' })).not.toBeInTheDocument();
  });

  it('연결 상태 — 연결됨은 표시만, 끊긴 연동은 다시 연결, 코드 전이면 코드 받기', async () => {
    serve({
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      chzzk: { linked: true, channelName: '재환의 방송', status: 'ACTIVE' },
      youtube: { linked: false, channelName: '재환 클립', status: 'BROKEN' },
      streamKey: { issued: false },
    });
    render(<HomeScreen />);

    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    const rows = within(aside).getAllByRole('listitem');
    // 줄 이름은 플랫폼을 가리지 않고, 연결된 플랫폼은 설명 앞에 붙는다(치지직·SOOP·씨미)
    expect(rows[0]).toHaveTextContent('방송 채널치지직 · 재환의 방송 연결됨');
    expect(within(rows[1]!).getByRole('link', { name: '코드 받기' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
    expect(rows[2]).toHaveTextContent('재환 클립 · 다시 연결이 필요해요');
    expect(within(rows[2]!).getByRole('link', { name: '다시 연결' })).toHaveAttribute(
      'href',
      '/settings/channels',
    );
    expect(within(aside).getByText('확인 필요')).toBeInTheDocument();
  });

  it('끊긴 연동에 채널 이름이 없어도 「연결됨」이라 하지 않는다(리뷰 #299 3차)', async () => {
    serve({
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      youtube: { linked: false, status: 'BROKEN' },
    });
    render(<HomeScreen />);

    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    const youtube = within(aside).getAllByRole('listitem')[2]!;
    expect(youtube).toHaveTextContent('유튜브다시 연결이 필요해요');
    expect(youtube).not.toHaveTextContent('연결됨');
  });

  it('OBS 플러그인은 코드를 발급했어도 「연결됨」이라 하지 않는다 — 웹은 연결 상태를 모른다(#298 계약)', async () => {
    serve({
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      chzzk: { linked: true, channelName: '재환의 방송', status: 'ACTIVE' },
      youtube: { linked: true, channelName: '재환 클립', status: 'ACTIVE' },
      streamKey: { issued: true },
    });
    render(<HomeScreen />);

    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    const plugin = within(aside).getAllByRole('listitem')[1]!;
    await within(plugin).findByText('코드 발급됨');
    expect(plugin).toHaveTextContent('연결 확인은 OBS 독에서 해요');
    expect(plugin).not.toHaveTextContent('연결됨');
    expect(within(aside).getByText('준비 완료')).toBeInTheDocument();
  });

  it('연동 상태를 다시 읽다 실패하면 알던 값을 그대로 둔다 — 「확인 중」으로 되돌리지 않는다(리뷰 #299)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const server: Server = {
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      chzzk: { linked: true, channelName: '재환의 방송', status: 'ACTIVE' },
    };
    let chzzkDown = false;
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (chzzkDown && url.startsWith('/api/chzzk-link'))
          return Promise.resolve(new Response('{}', { status: 503 }));
        return Promise.resolve(route(url, server));
      }),
    );
    render(<HomeScreen />);
    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    expect(within(aside).getAllByRole('listitem')[0]).toHaveTextContent('치지직 · 재환의 방송');

    chzzkDown = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(within(aside).getAllByRole('listitem')[0]).toHaveTextContent('치지직 · 재환의 방송');
    expect(within(aside).getAllByRole('listitem')[0]).not.toHaveTextContent('확인 중');
  });

  it('방송 기록이 없는데 연동 상태를 못 읽었으면 그 단계에 버튼을 내밀지 않고 「확인 중」이라 한다(리뷰 #299)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/chzzk-link'))
          return Promise.resolve(new Response('{}', { status: 503 }));
        return Promise.resolve(route(url, {}));
      }),
    );
    render(<HomeScreen />);

    const hero = await screen.findByRole('region', { name: '지금 할 일' });
    // 못 읽은 단계가 있으면 남은 수를 단정하지 않는다(리뷰 #299 2차)
    expect(within(hero).getByRole('heading', { level: 1 })).toHaveTextContent(
      '첫 클립까지 남은 단계를 확인하고 있어요',
    );
    const channelRow = within(hero).getByText('방송 채널 연동').closest('li')!;
    expect(channelRow).toHaveTextContent('확인 중…');
    expect(within(channelRow).queryByRole('link')).not.toBeInTheDocument();
    // 상태를 아는 플러그인 단계가 지금 할 일이 된다
    expect(within(hero).getByRole('link', { name: '코드 받기' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
  });

  it('연동 상태를 못 읽은 줄은 「확인 중」이고 버튼을 내밀지 않는다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/chzzk-link'))
          return Promise.resolve(new Response('{}', { status: 503 }));
        return Promise.resolve(
          route(url, { past: [pastRow('past-1', new Date(Date.now() - 86_400_000))] }),
        );
      }),
    );
    render(<HomeScreen />);

    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    const chzzk = within(aside).getAllByRole('listitem')[0]!;
    expect(chzzk).toHaveTextContent('방송 채널확인 중…');
    expect(within(chzzk).queryByRole('link')).not.toBeInTheDocument();
  });

  it('포키의 팁 — 처음은 아무거나 하나, 화살표로 앞뒤로 넘기고 끝에서 처음으로 돈다', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0);
    serve({ past: [pastRow('past-1', new Date(Date.now() - 86_400_000))] });
    const user = userEvent.setup();
    render(<HomeScreen />);

    const tips = await screen.findByRole('region', { name: '포키의 팁' });
    expect(await within(tips).findByText(TIPS[0]!.text)).toBeInTheDocument();
    expect(within(tips).getByRole('link', { name: TIPS[0]!.action.label })).toHaveAttribute(
      'href',
      TIPS[0]!.action.href,
    );

    await user.click(within(tips).getByRole('button', { name: '다음 팁' }));
    expect(within(tips).getByText(TIPS[1]!.text)).toBeInTheDocument();

    await user.click(within(tips).getByRole('button', { name: '이전 팁' }));
    await user.click(within(tips).getByRole('button', { name: '이전 팁' }));
    expect(within(tips).getByText(TIPS[TIPS.length - 1]!.text)).toBeInTheDocument();
    expect(tips).toHaveTextContent(`${TIPS.length} / ${TIPS.length}`);
  });

  it('방송 번호 대신 방송 제목을 보이고, 제목이 없는 방송만 번호다(POK-259)', async () => {
    const at = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
    serve({
      live: [
        {
          streamId: 'S-live-1',
          status: 'live',
          relation: 'OWNER',
          startedAt: at(30),
          endedAt: null,
          vodExpiresAt: null,
          title: '롤 마스터 승급전',
          category: 'League of Legends',
        },
      ],
      past: [
        {
          streamId: 'S-past-1',
          status: 'vod_ready',
          relation: 'OWNER',
          startedAt: at(3000),
          endedAt: at(2900),
          vodExpiresAt: new Date(Date.now() + 2 * 86_400_000).toISOString(),
          title: '  새벽 랭크  ',
          category: null,
        },
        {
          streamId: 'S-past-2',
          status: 'vod_ready',
          relation: 'OWNER',
          startedAt: at(6000),
          endedAt: at(5900),
          vodExpiresAt: null,
          title: null,
        },
      ],
      // 방송 정보 관측은 있는데 그 회차에 제목을 못 얻었다 — 목록 제목으로 대신한다
      info: { latest: { title: null, tags: [], category: null, viewers: 12 }, series: [] },
    });
    render(<HomeScreen />);

    expect(await screen.findByText('롤 마스터 승급전')).toBeInTheDocument();
    expect(await screen.findAllByText('새벽 랭크')).not.toHaveLength(0);
    expect(screen.getAllByText('S-past-2')).not.toHaveLength(0);
    expect(screen.queryByText('S-live-1')).toBeNull();
    expect(screen.queryByText('S-past-1')).toBeNull();
  });

  // 띠 컴포넌트는 받은 값을 그대로 그리므로 컴포넌트 시험만으로는 홈이 방송 목록의 칸을 실제로 넘기는지 못 잰다(계획 검증 중대 2).
  // 홈 다른 곳(연동 카드)에도 「치지직」이 있어 라이브 영역으로 좁혀 본다
  it('방송 목록의 플랫폼과 접속 편집자 수가 라이브 띠에 실린다(POK-398)', async () => {
    serve({
      live: [
        {
          streamId: 'S-live-p',
          status: 'live',
          relation: 'OWNER',
          startedAt: new Date(Date.now() - 60_000).toISOString(),
          endedAt: null,
          vodExpiresAt: null,
          title: '플랫폼 방송',
          platform: 'CHZZK',
          editorsOnline: 2,
        },
      ],
    });
    render(<HomeScreen />);

    const band = await screen.findByRole('region', { name: '라이브' });
    expect(within(band).getByText('치지직')).toBeInTheDocument();
    expect(within(band).getByText(/· 편집자 2명 접속 중$/)).toBeInTheDocument();
  });

  it('접속 편집자가 0이거나 칸이 없으면 수를 안 보이고, 플랫폼 칸이 없으면 태그도 없다(POK-398)', async () => {
    const row = {
      status: 'live',
      relation: 'OWNER',
      startedAt: new Date(Date.now() - 60_000).toISOString(),
      endedAt: null,
      vodExpiresAt: null,
    };
    serve({
      live: [{ ...row, streamId: 'S-zero', title: '영 명', platform: 'SOOP', editorsOnline: 0 }],
    });
    const { unmount } = render(<HomeScreen />);
    let band = await screen.findByRole('region', { name: '라이브' });
    expect(await within(band).findByText('영 명')).toBeInTheDocument();
    expect(within(band).getByText('SOOP')).toBeInTheDocument();
    expect(within(band).queryByText(/접속 중/)).toBeNull();
    unmount();

    // 이 칸을 모르는 옛 서버
    serve({ live: [{ ...row, streamId: 'S-old', title: '옛 서버' }] });
    render(<HomeScreen />);
    band = await screen.findByRole('region', { name: '라이브' });
    expect(await within(band).findByText('옛 서버')).toBeInTheDocument();
    expect(within(band).queryByText('치지직')).toBeNull();
    expect(within(band).queryByText(/접속 중/)).toBeNull();
  });

  it('라이브 띠의 클립 완료는 그 방송에서 영상까지 만든 편집본 수다', async () => {
    const rendered = { status: 'rendered', createdAt: new Date('2026-09-26T10:00:00Z') };
    serve({
      live: [
        {
          streamId: 'stream-1',
          status: 'live',
          relation: 'OWNER',
          startedAt: new Date(Date.now() - 60_000).toISOString(),
          endedAt: null,
          vodExpiresAt: null,
        },
      ],
      library: [
        { ...entry(12, null), streamId: 'stream-1', status: 'editing' },
        // 영상을 만든 뒤 새 판을 저장만 한 편집본 — 만든 영상은 그대로 있다
        { ...entry(17, rendered), streamId: 'stream-1', status: 'editing' },
        { ...entry(13, rendered), streamId: 'stream-1' },
        { ...entry(14, rendered), streamId: 'stream-1', status: 'uploaded' },
        { ...entry(15, null), streamId: 'stream-1', status: 'failed' },
        { ...entry(16, rendered), streamId: 'other' },
      ],
    });
    render(<HomeScreen />);

    expect(
      await screen.findByText((_, el) => el?.textContent === '감지된 카드 0 · 클립 완료 3'),
    ).toBeInTheDocument();
  });

  it('지난 방송 카드는 「카드 N개」를 단다(POK-254)', async () => {
    serve({
      past: [pastRow('past-1', new Date('2026-10-08T05:41:00Z'))],
      cards: { 'past-1': [card(35)] },
    });
    render(<HomeScreen />);

    expect(await screen.findByText(/10월 8일 · 카드 1개/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '최근 하이라이트 카드' })).not.toBeInTheDocument();
  });

  it('지난 방송 격자: 만료된 방송은 열리지 않고 「보관 만료」, 「준비 중」은 끝난 지 한 시간 안만(POK-254)', async () => {
    const at = (ms: number) => new Date(Date.now() + ms).toISOString();
    const HOUR = 3_600_000;
    const row = (
      streamId: string,
      status: string,
      endedAt: string,
      vodExpiresAt: string | null,
    ) => ({
      streamId,
      status,
      relation: 'OWNER',
      startedAt: endedAt,
      endedAt,
      vodExpiresAt,
      title: streamId,
    });
    serve({
      past: [
        row('fresh', 'ended', at(-HOUR / 2), null),
        row('stale', 'ended', at(-2 * HOUR), null),
        row('gone', 'vod_ready', at(-61 * 24 * HOUR), at(-HOUR)),
      ],
    });
    render(<HomeScreen />);

    const grid = await screen.findByRole('region', { name: '지난 방송 · VOD' });
    await within(grid).findByText('gone');
    const tile = (title: string) => within(grid).getByText(title).closest('li')!;
    // 썸네일 자리표시 「썸네일 준비 중」과 가르려고 배지 글자만 정확히 찾는다
    expect(within(tile('fresh')).getByText('준비 중')).toBeInTheDocument();
    expect(within(tile('stale')).queryByText('준비 중')).toBeNull();
    expect(within(tile('gone')).getByText('보관 만료')).toBeInTheDocument();
    expect(tile('gone').querySelector('a')).toBeNull();
    expect(tile('fresh').querySelector('a')).not.toBeNull();
  });

  it('방송 목록을 잠깐 못 읽어도 지난 화면을 지우지 않는다 — 라이브 띠가 그대로다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let failing = false;
    const server: Server = {
      live: [
        {
          streamId: 'live-1',
          status: 'live',
          relation: 'OWNER',
          startedAt: new Date(Date.now() - 60_000).toISOString(),
          endedAt: null,
          vodExpiresAt: null,
        },
      ],
    };
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/clip/broadcasts?') && failing) {
          return Promise.resolve(
            new Response(JSON.stringify({ error: 'unavailable' }), { status: 503 }),
          );
        }
        return Promise.resolve(route(url, server));
      }),
    );
    render(<HomeScreen />);
    expect(await screen.findByRole('link', { name: '대시보드 열기' })).toBeInTheDocument();

    failing = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(screen.getByRole('link', { name: '대시보드 열기' })).toBeInTheDocument();
  });

  it('라이브 띠는 카드를 둘째 쪽까지 세고, 수집된 시청자 수를 보인다', async () => {
    // 카드 목록은 50장씩 오래된 것부터 온다 — 첫 쪽만 세면 50에서 잘린다(PR #200 codex)
    const liveRow = {
      streamId: 'live-1',
      status: 'live',
      relation: 'OWNER',
      startedAt: new Date(Date.now() - 60_000).toISOString(),
      endedAt: null,
      vodExpiresAt: null,
    };
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/live-1/jump-cards'))
          return Promise.resolve(
            ok(
              url.includes('cursor=c2')
                ? { cards: [card(2), card(3)], nextCursor: null }
                : { cards: [card(1)], nextCursor: 'c2' },
            ),
          );
        return Promise.resolve(
          route(url, {
            live: [liveRow],
            info: {
              latest: { title: '방송', tags: [], category: null, viewers: 1842 },
              series: [],
            },
          }),
        );
      }),
    );
    render(<HomeScreen />);

    expect(await screen.findByText('시청자 1,842')).toBeInTheDocument();
    const live = screen.getByRole('region', { name: '라이브' });
    expect(live).toHaveTextContent('감지된 카드 3');
  });

  it('접근성 위반이 없다 — 방송 기록 없음', async () => {
    const { container } = render(<HomeScreen />);
    await screen.findByRole('region', { name: '포키가 해 주는 일' });
    // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });

  it('접근성 위반이 없다 — 방송 기록 있음', async () => {
    serve({ past: [pastRow('past-1', new Date(Date.now() - 86_400_000))] });
    const { container } = render(<HomeScreen />);
    await screen.findByRole('region', { name: '포키의 팁' });
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('HomeScreen: 남의 채널(POK-390)', () => {
  beforeEach(() => {
    resetOnboarding();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function asEditor() {
    return render(
      <ChannelRelationContext.Provider value="EDITOR">
        <HomeScreen />
      </ChannelRelationContext.Provider>,
    );
  }

  it('설정 가이드의 연동·코드 받기 버튼이 비활성이고 링크가 아니다: 인사·단계는 그대로', async () => {
    serve();
    asEditor();

    const hero = await screen.findByRole('region', { name: '지금 할 일' });
    expect(within(hero).getByText('방송 채널 연동')).toBeInTheDocument();
    expect(within(hero).queryByRole('link')).not.toBeInTheDocument();
    expect(within(hero).getByRole('button', { name: '채널 연동' })).toBeDisabled();
    expect(within(hero).getByRole('button', { name: '코드 받기' })).toBeDisabled();
  });

  it('연결 상태 카드는 그대로 보이고 다시 연결·코드 받기 버튼만 비활성이다', async () => {
    serve({
      past: [pastRow('past-1', new Date(Date.now() - 86_400_000))],
      chzzk: { linked: true, channelName: '재환의 방송', status: 'ACTIVE' },
      youtube: { linked: false, channelName: '재환 클립', status: 'BROKEN' },
      streamKey: { issued: false },
    });
    asEditor();

    const aside = await screen.findByRole('complementary', { name: '연결 상태·팁' });
    // 연동 상태는 방송 목록과 따로 온다: 줄이 다 찰 때까지 기다린 뒤 잰다(안 기다리면 「확인 중」 줄을 잰다)
    await within(aside).findByRole('button', { name: '다시 연결' });
    const rows = within(aside).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('방송 채널치지직 · 재환의 방송 연결됨');
    // aside 전체가 아니라 연결 상태 줄만 본다: 같은 aside의 포키의 팁은 무작위로 링크를 가진다
    for (const row of rows.slice(0, 3))
      expect(within(row).queryByRole('link')).not.toBeInTheDocument();
    expect(within(rows[1]!).getByRole('button', { name: '코드 받기' })).toBeDisabled();
    expect(within(rows[2]!).getByRole('button', { name: '다시 연결' })).toBeDisabled();
  });
});

describe('포키의 팁: 남의 채널(POK-390)', () => {
  const OWNER_ONLY = ['/settings/plugin', '/settings/editors', '/settings/channels'];

  /** 팁 다섯을 한 바퀴 돌며 지금 팁의 버튼을 잰다: 첫 팁은 무작위라 순서와 무관하게 전부 본다 */
  async function eachTip(
    relation: 'OWNER' | 'EDITOR',
    check: (tip: (typeof TIPS)[number]) => void,
  ) {
    const user = userEvent.setup();
    render(
      <ChannelRelationContext.Provider value={relation}>
        <TipCard />
      </ChannelRelationContext.Provider>,
    );
    const seen = new Set<string>();
    for (let k = 0; k < TIPS.length; k++) {
      const tip = await waitForTip();
      seen.add(tip.action.href);
      check(tip);
      await user.click(screen.getByRole('button', { name: '다음 팁' }));
    }
    expect(seen.size).toBe(TIPS.length);
  }

  async function waitForTip() {
    let found: (typeof TIPS)[number] | undefined;
    await vi.waitFor(() => {
      found = TIPS.find((t) => screen.queryByText(t.text) !== null);
      expect(found).toBeDefined();
    });
    return found!;
  }

  it('남의 채널이면 플러그인·편집자 초대·채널 연동 팁의 버튼은 비활성이고 링크가 아니다', async () => {
    await eachTip('EDITOR', (tip) => {
      if (OWNER_ONLY.includes(tip.action.href)) {
        expect(screen.queryByRole('link', { name: tip.action.label })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: tip.action.label })).toBeDisabled();
      } else {
        expect(screen.getByRole('link', { name: tip.action.label })).toHaveAttribute(
          'href',
          tip.action.href,
        );
      }
    });
  });

  it('내 채널이면 팁 버튼은 전부 링크다', async () => {
    await eachTip('OWNER', (tip) => {
      expect(screen.getByRole('link', { name: tip.action.label })).toHaveAttribute(
        'href',
        tip.action.href,
      );
    });
  });
});

describe('주 경계·며칠 전', () => {
  it('이번 주는 월요일 0시(로컬)부터다 — 일요일은 아직 그 주', () => {
    const wed = new Date(2026, 9, 21, 12);
    const { lastWeekStart, weekStart } = weekStarts(wed);
    expect(new Date(weekStart)).toEqual(new Date(2026, 9, 19));
    expect(new Date(lastWeekStart)).toEqual(new Date(2026, 9, 12));
    // 일요일 밤은 그 주의 끝이다
    expect(new Date(weekStarts(new Date(2026, 9, 25, 23)).weekStart)).toEqual(
      new Date(2026, 9, 19),
    );
  });

  it('「며칠 전」은 시각이 아니라 달력 날짜로 센다', () => {
    const morning = new Date(2026, 9, 21, 8);
    expect(daysAgoLabel(new Date(2026, 9, 20, 23).toISOString(), morning)).toBe('어제');
    expect(daysAgoLabel(new Date(2026, 9, 21, 1).toISOString(), morning)).toBe('오늘');
    expect(daysAgoLabel(new Date(2026, 9, 18, 20).toISOString(), morning)).toBe('3일 전');
  });
});

describe('LiveNowBand', () => {
  it('라이브 사진이 있으면 칸을 채우고, 없으면 자리표시를 보인다(POK-277)', () => {
    const src =
      'http://localhost:4566/clips-local/thumbnails/live/stream-1.jpg?X-Amz-Date=20261004T101500Z';
    const { container, rerender } = render(<LiveNowBand live={{ ...LIVE, thumbnailUrl: src }} />);
    expect(container.querySelector('img')).toHaveAttribute('src', src);
    expect(screen.queryByText('라이브 방송 화면')).not.toBeInTheDocument();

    rerender(<LiveNowBand live={LIVE} />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('라이브 방송 화면')).toBeInTheDocument();
  });

  it('플랫폼을 모르면 플랫폼 태그를 달지 않는다 — 치지직·SOOP·씨미 중 무엇인지 지어내지 않는다', () => {
    const { rerender } = render(<LiveNowBand live={{ ...LIVE, platform: null }} />);
    expect(screen.queryByText('치지직')).not.toBeInTheDocument();

    rerender(<LiveNowBand live={{ ...LIVE, platform: 'SOOP' }} />);
    expect(screen.getByText('SOOP')).toBeInTheDocument();
  });

  it('편집자가 접속해 있으면 시작 줄 뒤에 수를 붙이고, 0이거나 모르면 안 붙인다(POK-398)', () => {
    const { rerender } = render(<LiveNowBand live={{ ...LIVE, editorsOnline: 2 }} />);
    expect(screen.getByText('오후 7:12 시작 · 편집자 2명 접속 중')).toBeInTheDocument();

    for (const editorsOnline of [0, null, undefined]) {
      rerender(<LiveNowBand live={{ ...LIVE, editorsOnline }} />);
      expect(screen.getByText('오후 7:12 시작')).toBeInTheDocument();
      expect(screen.queryByText(/접속 중/)).toBeNull();
    }
  });

  it('방송 정보와 라이브 대시보드 진입 링크를 렌더한다', () => {
    render(<LiveNowBand live={LIVE} />);

    expect(screen.getByText('LIVE 1:24:03')).toBeInTheDocument();
    expect(screen.getByText('시청자 준비 중')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '대시보드 열기' })).toHaveAttribute(
      'href',
      '/broadcast/livenow?stream=stream-1',
    );
    expect(screen.getByRole('link', { name: '카드 검토' })).toHaveAttribute(
      'href',
      '/broadcast/livenow?stream=stream-1',
    );
  });
});
