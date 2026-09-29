import { act } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HomeScreen } from '@/features/home/HomeScreen';
import { LiveNowBand } from '@/features/home/LiveNowBand';
import type { LiveNow } from '@/features/home/useHomeMockState';

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

// 홈은 마운트 뒤 clip·auth를 부른다 — 시험에서는 빈 응답으로 막고, 보관함에만 편집 중인 편집본 하나를 둔다.
const EDITING_ENTRY = {
  recipeId: 12,
  streamId: 'stream-1',
  creatorId: '1',
  recipeVersion: 3,
  cut: null,
  status: 'editing',
  broadcast: { status: 'ended', startedAt: null, endedAt: null, vodExpiresAt: null },
  latestClip: null,
  createdAt: '2026-09-26T10:00:00Z',
  updatedAt: '2026-09-26T11:00:00Z',
};

function emptyJson(path: string): Response {
  const body = path.startsWith('/api/clip/library')
    ? { items: [EDITING_ENTRY], nextCursor: null }
    : path.includes('/broadcasts?')
      ? { broadcasts: [], nextCursor: null }
      : path.endsWith('/jump-cards')
        ? { cards: [] }
        : path.endsWith('/broadcast-info')
          ? { latest: null, series: [] }
          : { id: 1, email: 'demo@example.com', name: null, profileImageUrl: null };
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('HomeScreen', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => Promise.resolve(emptyJson(String(input)))),
    );
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('인사말·발행 현황·만료 임박 카드를 렌더한다', async () => {
    render(<HomeScreen />);

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '발행 현황' })).toBeInTheDocument();
    expect(await screen.findByText('아직 유튜브에 올린 영상이 없어요')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '만료 임박 VOD' })).toBeInTheDocument();
  });

  it('발행 현황은 보관함의 유튜브 업로드를 최근 순으로 보인다 — 실패는 빼고 확인 필요는 따로', async () => {
    const upload = (id: number, title: string, status: string, updatedAt: string) => ({
      id,
      clipId: id,
      outputId: 'o1',
      title,
      status,
      videoId: status === 'uploaded' ? 'v' : null,
      error: null,
      requestedBy: '1',
      createdAt: updatedAt,
      updatedAt,
    });
    const entry = (recipeId: number, status: string, up: ReturnType<typeof upload>) => ({
      ...EDITING_ENTRY,
      recipeId,
      status,
      latestClip: {
        id: recipeId,
        streamId: 'stream-1',
        recipeId,
        recipeVersion: 3,
        requestedBy: '1',
        status: 'rendered',
        progress: null,
        outputs: [],
        error: null,
        createdAt: '2026-09-26T10:00:00Z',
        updatedAt: '2026-09-26T10:00:00Z',
        upload: up,
      },
    });
    const items = [
      entry(1, 'uploaded', upload(1, '옛 영상', 'uploaded', '2026-09-26T10:00:00Z')),
      entry(2, 'uploading', upload(2, '올리는 영상', 'uploading', '2026-09-26T12:00:00Z')),
      entry(3, 'checking', upload(3, '모르는 영상', 'checking', '2026-09-26T11:00:00Z')),
      entry(4, 'rendered', upload(4, '실패한 영상', 'failed', '2026-09-26T13:00:00Z')),
      // 올린 뒤 새 판을 저장하면 편집본은 「편집 중」이지만 올린 영상은 그대로 유튜브에 있다
      entry(5, 'editing', upload(5, '새 판 저장한 영상', 'uploaded', '2026-09-26T09:00:00Z')),
    ];
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).startsWith('/api/clip/library')
            ? new Response(JSON.stringify({ items, nextCursor: null }), { status: 200 })
            : emptyJson(String(input)),
        ),
      ),
    );
    render(<HomeScreen />);

    const card = (await screen.findByText('올리는 영상')).closest('ul');
    expect(card).not.toBeNull();
    const rows = Array.from(card?.querySelectorAll('li') ?? []).map((li) => li.textContent);
    expect(rows).toEqual([
      '올리는 영상업로드 중',
      '모르는 영상확인 필요',
      '옛 영상발행됨',
      '새 판 저장한 영상발행됨',
    ]);
  });

  it('라이브 띠의 클립 완료는 그 방송에서 영상까지 만든 편집본 수다', async () => {
    const liveRow = {
      streamId: 'stream-1',
      status: 'live',
      relation: 'OWNER',
      startedAt: new Date(Date.now() - 60_000).toISOString(),
      endedAt: null,
      vodExpiresAt: null,
    };
    const renderedClip = {
      id: 1,
      streamId: 'stream-1',
      recipeId: 13,
      recipeVersion: 3,
      requestedBy: '1',
      status: 'rendered',
      progress: null,
      outputs: [],
      error: null,
      createdAt: '2026-09-26T10:00:00Z',
      updatedAt: '2026-09-26T10:00:00Z',
      upload: null,
    };
    const items = [
      EDITING_ENTRY,
      // 영상을 만든 뒤 새 판을 저장만 한 편집본 — 만든 영상은 그대로 있다
      { ...EDITING_ENTRY, recipeId: 17, status: 'editing', latestClip: renderedClip },
      { ...EDITING_ENTRY, recipeId: 13, status: 'rendered', latestClip: renderedClip },
      { ...EDITING_ENTRY, recipeId: 14, status: 'uploaded', latestClip: renderedClip },
      { ...EDITING_ENTRY, recipeId: 15, status: 'failed' },
      {
        ...EDITING_ENTRY,
        recipeId: 16,
        status: 'rendered',
        streamId: 'other',
        latestClip: renderedClip,
      },
    ];
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith('/api/clip/library')) {
          return Promise.resolve(
            new Response(JSON.stringify({ items, nextCursor: null }), { status: 200 }),
          );
        }
        if (url.includes('state=live')) {
          return Promise.resolve(
            new Response(JSON.stringify({ broadcasts: [liveRow], nextCursor: null }), {
              status: 200,
            }),
          );
        }
        return Promise.resolve(emptyJson(url));
      }),
    );
    render(<HomeScreen />);

    expect(
      await screen.findByText((_, el) => el?.textContent === '감지된 카드 0 · 클립 완료 3'),
    ).toBeInTheDocument();
  });

  it('이어서 편집 배너가 가장 최근에 고친 편집본을 편집기로 연다', async () => {
    render(<HomeScreen />);

    expect(await screen.findByRole('link', { name: '이어서 편집' })).toHaveAttribute(
      'href',
      '/clips/editor/studio?recipe=12',
    );
  });

  it('편집 중인 편집본이 없으면 배너가 없다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) =>
        Promise.resolve(
          String(input).startsWith('/api/clip/library')
            ? new Response(JSON.stringify({ items: [], nextCursor: null }), { status: 200 })
            : emptyJson(String(input)),
        ),
      ),
    );
    render(<HomeScreen />);

    expect(await screen.findByText('지금 방송 중인 채널이 없어요.')).toBeInTheDocument();
    expect(screen.queryByLabelText('이어서 편집')).not.toBeInTheDocument();
  });

  it('이어서 편집 배너를 닫을 수 있다', async () => {
    const user = userEvent.setup();
    render(<HomeScreen />);

    expect(await screen.findByLabelText('이어서 편집')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '배너 닫기' }));
    expect(screen.queryByLabelText('이어서 편집')).not.toBeInTheDocument();
  });

  it('곧 만료될 방송이 지난 방송 목록의 둘째 쪽에 있어도 만료 임박에 뜬다', async () => {
    // 곧 만료될 방송은 가장 오래된 것이라 목록 끝에 있다 — 한 쪽만 읽으면 빠진다(POK-251 리뷰 2라운드)
    const inTwoDays = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000 - 60 * 60 * 1000).toISOString();
    const row = (streamId: string, vodExpiresAt: string | null) => ({
      streamId,
      status: 'vod_ready',
      relation: 'OWNER',
      startedAt: '2026-08-01T10:00:00Z',
      endedAt: '2026-08-01T12:00:00Z',
      vodExpiresAt,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('state=past')) {
          const body = url.includes('cursor=p2')
            ? { broadcasts: [row('old-expiring', inTwoDays)], nextCursor: null }
            : { broadcasts: [row('recent', null)], nextCursor: 'p2' };
          return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
        }
        return Promise.resolve(emptyJson(url));
      }),
    );
    render(<HomeScreen />);

    // 만료 임박 줄은 「방송 · 카드 n개」로 선다(지난 방송 격자에도 D-2 배지가 같이 선다)
    expect(await screen.findByText('old-expiring · 카드 0개')).toBeInTheDocument();
    expect(screen.getAllByText('D-2').length).toBeGreaterThan(0);
  });

  it('이미 만료된 방송은 만료 임박에 넣지 않는다 — D-0으로 쌓이지 않는다', async () => {
    const past = (days: number) => new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();
    const row = (streamId: string, vodExpiresAt: string) => ({
      streamId,
      status: 'vod_ready',
      relation: 'OWNER',
      startedAt: '2026-07-01T10:00:00Z',
      endedAt: '2026-07-01T12:00:00Z',
      vodExpiresAt,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('state=past')) {
          const body = {
            broadcasts: [row('gone', past(-3)), row('soon', past(2))],
            nextCursor: null,
          };
          return Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
        }
        return Promise.resolve(emptyJson(url));
      }),
    );
    render(<HomeScreen />);

    expect(await screen.findByText('soon · 카드 0개')).toBeInTheDocument();
    expect(screen.queryByText('gone · 카드 0개')).not.toBeInTheDocument();
    expect(screen.queryByText('D-0')).not.toBeInTheDocument();
  });

  it('방송 목록을 잠깐 못 읽어도 지난 화면을 지우지 않는다 — 라이브 띠가 그대로다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let failing = false;
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
        if (url.startsWith('/api/clip/broadcasts?') && failing) {
          return Promise.resolve(
            new Response(JSON.stringify({ error: 'unavailable' }), { status: 503 }),
          );
        }
        if (url.includes('state=live')) {
          return Promise.resolve(
            new Response(JSON.stringify({ broadcasts: [liveRow], nextCursor: null }), {
              status: 200,
            }),
          );
        }
        return Promise.resolve(emptyJson(url));
      }),
    );
    render(<HomeScreen />);
    expect(await screen.findByRole('link', { name: '대시보드 열기' })).toBeInTheDocument();

    failing = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(screen.getByRole('link', { name: '대시보드 열기' })).toBeInTheDocument();
    expect(screen.queryByText('지금 방송 중인 채널이 없어요.')).not.toBeInTheDocument();
    vi.useRealTimers();
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
    const cardOf = (id: number) => ({
      id,
      source: 'chat-surge',
      streamTimestampMs: id * 1000,
      score: 80,
      hidden: false,
      createdAt: new Date().toISOString(),
    });
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        const ok = (body: unknown) =>
          Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));
        if (url.includes('state=live')) return ok({ broadcasts: [liveRow], nextCursor: null });
        if (url.includes('/live-1/jump-cards'))
          return ok(
            url.includes('cursor=c2')
              ? { cards: [cardOf(2), cardOf(3)], nextCursor: null }
              : { cards: [cardOf(1)], nextCursor: 'c2' },
          );
        if (url.includes('/live-1/broadcast-info'))
          return ok({
            latest: { title: '방송', tags: [], category: null, viewers: 1842 },
            series: [],
          });
        return Promise.resolve(emptyJson(url));
      }),
    );
    render(<HomeScreen />);

    expect(await screen.findByText('시청자 1,842')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    const { container } = render(<HomeScreen />);
    // axe 실행 중 Next Link의 비동기 상태 갱신이 발화한다 — act로 감싸 경고 없이 흡수
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('LiveNowBand', () => {
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
