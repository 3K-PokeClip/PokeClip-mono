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
    expect(await screen.findByText('준비 중')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '만료 임박 VOD' })).toBeInTheDocument();
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
