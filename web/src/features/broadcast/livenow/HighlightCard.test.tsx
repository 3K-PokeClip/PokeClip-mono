import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { prefetchCardEditor } from '@/features/clips/editor/prefetchCardEditor';
import { saveCardDraft } from '@/features/clips/editor/saveCardDraft';
import type { RecipeSnapshot } from '@/api/clipEditor';
import { forgetCardSaves } from './cardSave';
import { HighlightCard } from './HighlightCard';
import { reuseHighlights } from './highlightCardView';
import { PENDING_MAX_RECHECKS, PENDING_RECHECK_MS } from './useCardEditorReady';
import type { LiveHighlight, LiveStream } from './useLiveMockState';

vi.mock('@/features/clips/editor/prefetchCardEditor', () => ({ prefetchCardEditor: vi.fn() }));
vi.mock('@/features/clips/editor/saveCardDraft', () => ({ saveCardDraft: vi.fn() }));
const routerPush = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: routerPush }) }));

// 「편집」은 편집기가 쓰는 영상 · 소리 · 파형을 미리 다 받은 뒤에 열린다(POK-499)

const STREAM: LiveStream = {
  title: '새벽 랭크 올리기',
  platform: '치지직',
  channelName: '게임하는너구리',
  startedNote: '오후 7:12 시작',
  uptimeSeconds: 5043,
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  editorName: '박편집',
};

const HIGHLIGHT: LiveHighlight = {
  id: 'card-23',
  timestamp: '1:07:50',
  title: '채팅이 튄 순간',
  meta: '채팅 ×4.2 급증 · 42초 · 방금',
  status: 'scored',
  source: 'auto',
  score: 97,
  lengthSec: 60,
  ago: '3분 전',
};

function renderCard(highlight: LiveHighlight = HIGHLIGHT) {
  return renderWithProviders(
    <HighlightCard highlight={highlight} stream={STREAM} visuals={{}} onSeek={() => undefined} />,
  );
}

const pendingButton = () =>
  screen.getByRole('button', { name: /^(편집 준비 중|녹화 기다리는 중)$/ });
/** 준비 중 표시(마젠타 점선 테두리)를 두른 카드인가 */
const isPreparing = (card: HTMLElement) => /cardPreparing/.test(card.className);

beforeEach(() => {
  vi.mocked(prefetchCardEditor).mockReset();
  vi.mocked(saveCardDraft).mockReset();
  routerPush.mockReset();
  forgetCardSaves();
});

describe('HighlightCard: 사진을 못 받았을 때', () => {
  const signedAt = Date.parse('2026-10-31T00:00:00Z');
  const photo = (at: string) =>
    `https://bucket.s3.amazonaws.com/cards/23.jpg?X-Amz-Date=${at}&X-Amz-Signature=sig-${at}`;
  const withPhoto = (at: string): LiveHighlight => ({ ...HIGHLIGHT, thumbnailUrl: photo(at) });

  /** 라이브 훅처럼 목록이 올 때마다 reuseHighlights를 거쳐 카드에 넘긴다 */
  function renderFeed() {
    let poll: (at: string, now: number) => void = () => undefined;
    function Feed() {
      const [list, setList] = useState([withPhoto('20261031T000000Z')]);
      poll = (at, now) => setList((prev) => reuseHighlights(prev, [withPhoto(at)], now));
      return (
        <HighlightCard highlight={list[0]!} stream={STREAM} visuals={{}} onSeek={() => undefined} />
      );
    }
    const view = renderWithProviders(<Feed />);
    return {
      image: () => view.container.querySelector('img'),
      poll: (...a: [string, number]) => poll(...a),
    };
  }

  it('한 번 실패해도 1분 안에 새 주소로 다시 시도한다: 새 주소를 막으면 일시 오류 하나로 50분간 자리표시에 머문다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    const { image, poll } = renderFeed();
    await act(async () => undefined);
    fireEvent.error(image()!);
    expect(image()).toBeNull();

    act(() => poll('20261031T000030Z', signedAt + 30_000));
    expect(image()).toBeNull();

    act(() => poll('20261031T000100Z', signedAt + 60_000));
    expect(image()).toHaveAttribute('src', photo('20261031T000100Z'));
  });

  it('멀쩡한 사진은 새 주소를 받아도 보던 주소를 이어 쓴다: 브라우저 캐시가 안 맞아 다시 받게 된다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(signedAt + 60_000);
    const { image, poll } = renderFeed();
    await act(async () => undefined);

    act(() => poll('20261031T000100Z', signedAt + 60_000));

    expect(image()).toHaveAttribute('src', photo('20261031T000000Z'));
    vi.useRealTimers();
  });
});

describe('HighlightCard: 편집 준비', () => {
  afterEach(() => vi.useRealTimers());

  it('셋이 다 오기 전에는 「편집」이 눌리지 않는다', async () => {
    let finish: (value: 'ready') => void = () => undefined;
    vi.mocked(prefetchCardEditor).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderCard();
    expect(pendingButton()).toBeDisabled();
    expect(pendingButton()).toHaveAccessibleName('편집 준비 중');
    expect(screen.queryByRole('link', { name: '편집' })).not.toBeInTheDocument();
    await act(async () => finish('ready'));
  });

  it('준비 중에는 카드를 마젠타 점선으로 두르고, 다 받으면 기본 카드와 같아진다', async () => {
    let finish: (value: 'ready') => void = () => undefined;
    vi.mocked(prefetchCardEditor).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderCard();
    expect(isPreparing(screen.getByRole('article'))).toBe(true);
    // 점선은 카드 테두리만 맡는다: 「편집」 버튼에는 따로 두르지 않는다
    expect(pendingButton().className).not.toMatch(/Preparing|Pending/);
    await act(async () => finish('ready'));
    expect(isPreparing(screen.getByRole('article'))).toBe(false);
  });

  it('다 받으면 「편집」이 이 카드의 편집기로 열린다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    renderCard();
    const link = await screen.findByRole('link', { name: '편집' });
    expect(link.getAttribute('href')).toMatch(/card=23$/);
    expect(prefetchCardEditor).toHaveBeenCalledWith(
      expect.any(String),
      '23',
      undefined,
      expect.anything(),
    );
  });

  it('실패하면 영원히 막지 않는다: 「편집」을 열고 다시 시도 단추를 둔다', async () => {
    let finish: (value: 'ready') => void = () => undefined;
    vi.mocked(prefetchCardEditor)
      .mockRejectedValueOnce(new Error('500'))
      .mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    renderCard();
    expect(await screen.findByRole('link', { name: '편집' })).toBeInTheDocument();
    // 실패로 연 카드도 「편집」이 열렸으니 점선을 걷는다
    expect(isPreparing(screen.getByRole('article'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
    await waitFor(() => expect(prefetchCardEditor).toHaveBeenCalledTimes(2));
    // 다시 받는 동안에도 이미 열린 「편집」을 다시 잠그지 않는다
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
    expect(isPreparing(screen.getByRole('article'))).toBe(false);
    await act(async () => finish('ready'));
    expect(screen.queryByRole('button', { name: /다시 시도/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
  });

  it('녹화가 덜 올라왔으면 막아 두고 조금 뒤 다시 묻는다', async () => {
    vi.useFakeTimers();
    vi.mocked(prefetchCardEditor).mockResolvedValueOnce('pending').mockResolvedValueOnce('ready');
    renderCard();
    await act(async () => undefined);
    expect(pendingButton()).toHaveAccessibleName('녹화 기다리는 중');
    expect(isPreparing(screen.getByRole('article'))).toBe(true);
    await act(async () => {
      vi.advanceTimersByTime(PENDING_RECHECK_MS);
    });
    expect(prefetchCardEditor).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
    expect(isPreparing(screen.getByRole('article'))).toBe(false);
  });

  it(`녹화가 ${PENDING_MAX_RECHECKS}번 다시 물어도 안 올라오면 실패로 넘겨 「편집」을 연다: 영원히 막지 않는다`, async () => {
    vi.useFakeTimers();
    vi.mocked(prefetchCardEditor).mockResolvedValue('pending');
    renderCard();
    await act(async () => undefined);
    for (let i = 0; i < PENDING_MAX_RECHECKS; i += 1) {
      await act(async () => {
        vi.advanceTimersByTime(PENDING_RECHECK_MS);
      });
    }
    expect(prefetchCardEditor).toHaveBeenCalledTimes(PENDING_MAX_RECHECKS + 1);
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /다시 시도/ })).toBeInTheDocument();
    // 더는 저절로 다시 묻지 않는다
    await act(async () => {
      vi.advanceTimersByTime(PENDING_RECHECK_MS * 3);
    });
    expect(prefetchCardEditor).toHaveBeenCalledTimes(PENDING_MAX_RECHECKS + 1);
  });

  it('실패 뒤 다시 시도가 녹화 기다림으로 돌아와도 「편집」을 다시 잠그지 않는다', async () => {
    vi.useFakeTimers();
    vi.mocked(prefetchCardEditor).mockResolvedValue('pending');
    renderCard();
    await act(async () => undefined);
    for (let i = 0; i < PENDING_MAX_RECHECKS; i += 1) {
      await act(async () => {
        vi.advanceTimersByTime(PENDING_RECHECK_MS);
      });
    }
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
    await act(async () => undefined);
    expect(prefetchCardEditor).toHaveBeenCalledTimes(PENDING_MAX_RECHECKS + 2);
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
    // 녹화가 올라오면 준비된다
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    await act(async () => {
      vi.advanceTimersByTime(PENDING_RECHECK_MS);
    });
    expect(screen.queryByRole('button', { name: /다시 시도/ })).not.toBeInTheDocument();
  });

  it('가린 카드 · 만료 카드는 받지 않는다(편집 단추가 없다)', () => {
    renderCard({ ...HIGHLIGHT, hidden: true });
    renderCard({ ...HIGHLIGHT, id: 'card-24', status: 'expired' });
    expect(prefetchCardEditor).not.toHaveBeenCalled();
    // 받지 않으니 상태가 loading에 머무르지만 기다릴 것이 없다: 점선을 두르지 않는다
    for (const card of screen.getAllByRole('article')) expect(isPreparing(card)).toBe(false);
  });
});

describe('HighlightCard: 시안 B2-1 모양', () => {
  beforeEach(() => vi.mocked(prefetchCardEditor).mockResolvedValue('ready'));

  it('사진 위에 점수 · 방송 시각 · 길이 · 경과를 쓰고, 감지 사유 · 상태 배지 · 원클릭 업로드는 없다', async () => {
    const { container } = renderCard();
    await screen.findByRole('link', { name: '편집' });
    const thumb = screen.getByRole('button', { name: '1:07:50 시점으로 이동' });
    expect(thumb).toHaveTextContent('97점');
    expect(thumb).toHaveTextContent('1:07:50');
    expect(thumb).toHaveTextContent('1:00');
    expect(thumb).toHaveTextContent('3분 전');
    expect(thumb).toHaveTextContent('여기부터 보기');
    expect(thumb).not.toHaveTextContent('자동 감지');
    expect(screen.queryByText('검토 대기')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '원클릭 업로드' })).not.toBeInTheDocument();
    // 제목은 화면에 안 쓰지만 제목으로 훑는 쪽에는 남는다
    expect(screen.getByRole('heading', { name: '채팅이 튄 순간' })).toBeInTheDocument();
    expect(container.querySelector('article')).not.toBeNull();
  });

  it('수동 카드는 점수 자리에 「수동」이다', async () => {
    renderCard({ ...HIGHLIGHT, source: 'manual', status: 'manual', score: undefined });
    await screen.findByRole('link', { name: '편집' });
    expect(screen.getByRole('button', { name: '1:07:50 시점으로 이동' })).toHaveTextContent('수동');
  });

  it('만료 카드는 버튼줄 · 저장 대신 곧 사라진다고 쓴다', () => {
    renderCard({ ...HIGHLIGHT, status: 'expired' });
    expect(screen.getByText('되감기 범위를 지나 곧 사라져요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '보관함에 저장' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '이 카드 가리기' })).not.toBeInTheDocument();
  });
});

describe('HighlightCard: 보관함 저장', () => {
  const snapshot = { id: 31 } as RecipeSnapshot;

  it('「저장」을 누르면 카드 구간 그대로 편집본을 만들고 「저장됨」이 되며, 알림에서 보관함을 연다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    let finish: (value: RecipeSnapshot) => void = () => undefined;
    vi.mocked(saveCardDraft).mockReturnValue(new Promise((resolve) => (finish = resolve)));
    renderCard();

    fireEvent.click(screen.getByRole('button', { name: '보관함에 저장' }));
    expect(saveCardDraft).toHaveBeenCalledWith(expect.any(String), '23');
    expect(screen.getByRole('button', { name: '보관함에 저장하는 중' })).toBeDisabled();
    await act(async () => finish(snapshot));

    const done = screen.getByRole('link', { name: '보관함에 저장됨 · 보관함 열기' });
    expect(done).toHaveAttribute('href', '/clips/library');
    expect(await screen.findByText('보관함에 저장했어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '보관함 열기' }));
    expect(routerPush).toHaveBeenCalledWith('/clips/library');
  });

  it('편집 준비 중이어도 저장된다: 저장은 영상을 받지 않는다', async () => {
    vi.mocked(prefetchCardEditor).mockReturnValue(new Promise(() => undefined));
    vi.mocked(saveCardDraft).mockResolvedValue(snapshot);
    renderCard();
    expect(pendingButton()).toBeDisabled();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '보관함에 저장' }));
    });
    expect(screen.getByRole('link', { name: '보관함에 저장됨 · 보관함 열기' })).toBeInTheDocument();
  });

  it('저장된 카드는 다시 그려져도(목록이 새로 와도) 「저장됨」이다 — 두 번 눌러 편집본이 둘 생기지 않게', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    vi.mocked(saveCardDraft).mockResolvedValue(snapshot);
    const first = renderCard();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '보관함에 저장' }));
    });
    first.unmount();
    renderCard();
    expect(screen.getByRole('link', { name: '보관함에 저장됨 · 보관함 열기' })).toBeInTheDocument();
    expect(saveCardDraft).toHaveBeenCalledTimes(1);
  });

  it('실패하면 저장 전으로 돌아가고 알린다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    vi.mocked(saveCardDraft).mockRejectedValue(new Error('503'));
    renderCard();
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '보관함에 저장' }));
    });
    expect(await screen.findByText('보관함에 저장하지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '보관함에 저장' })).toBeEnabled();
  });

  it('가린 카드에는 저장이 없다', () => {
    renderCard({ ...HIGHLIGHT, hidden: true });
    expect(screen.queryByRole('button', { name: '보관함에 저장' })).not.toBeInTheDocument();
  });
});

describe('HighlightCard: 화면에 보이는 카드만 받는다', () => {
  let observed: { callback: IntersectionObserverCallback; target: Element | null }[];
  beforeEach(() => {
    observed = [];
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        private entry: (typeof observed)[number];
        constructor(callback: IntersectionObserverCallback) {
          this.entry = { callback, target: null };
          observed.push(this.entry);
        }
        observe(target: Element) {
          this.entry.target = target;
        }
        disconnect() {}
      },
    );
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('보이기 전에는 받지 않고, 보이면 받는다', async () => {
    renderCard();
    expect(prefetchCardEditor).not.toHaveBeenCalled();
    const { callback, target } = observed[0]!;
    act(() => {
      callback(
        [{ isIntersecting: true, target } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );
    });
    expect(await screen.findByRole('link', { name: '편집' })).toBeInTheDocument();
    expect(prefetchCardEditor).toHaveBeenCalledTimes(1);
  });
});
