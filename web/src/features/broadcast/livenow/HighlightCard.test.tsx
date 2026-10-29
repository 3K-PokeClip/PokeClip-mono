import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { prefetchCardEditor } from '@/features/clips/editor/prefetchCardEditor';
import { HighlightCard } from './HighlightCard';
import { PENDING_MAX_RECHECKS, PENDING_RECHECK_MS } from './useCardEditorReady';
import type { LiveHighlight, LiveStream } from './useLiveMockState';

vi.mock('@/features/clips/editor/prefetchCardEditor', () => ({ prefetchCardEditor: vi.fn() }));

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
};

function renderCard(highlight: LiveHighlight = HIGHLIGHT) {
  return renderWithProviders(
    <HighlightCard highlight={highlight} stream={STREAM} visuals={{}} onSeek={() => undefined} />,
  );
}

const pendingButton = () => screen.getByRole('button', { name: /^편집/ });

beforeEach(() => vi.mocked(prefetchCardEditor).mockReset());

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

  it('다 받으면 「편집」이 이 카드의 편집기로 열린다', async () => {
    vi.mocked(prefetchCardEditor).mockResolvedValue('ready');
    renderCard();
    const link = await screen.findByRole('link', { name: '편집' });
    expect(link.getAttribute('href')).toMatch(/card=23$/);
    expect(prefetchCardEditor).toHaveBeenCalledWith(expect.any(String), '23');
  });

  it('실패하면 영원히 막지 않는다: 「편집」을 열고 다시 시도 단추를 둔다', async () => {
    let finish: (value: 'ready') => void = () => undefined;
    vi.mocked(prefetchCardEditor)
      .mockRejectedValueOnce(new Error('500'))
      .mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    renderCard();
    expect(await screen.findByRole('link', { name: '편집' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
    await waitFor(() => expect(prefetchCardEditor).toHaveBeenCalledTimes(2));
    // 다시 받는 동안에도 이미 열린 「편집」을 다시 잠그지 않는다
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
    await act(async () => finish('ready'));
    expect(screen.queryByRole('button', { name: /다시 시도/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
  });

  it('녹화가 덜 올라왔으면 막아 두고 조금 뒤 다시 묻는다', async () => {
    vi.useFakeTimers();
    vi.mocked(prefetchCardEditor).mockResolvedValueOnce('pending').mockResolvedValueOnce('ready');
    renderCard();
    await act(async () => undefined);
    expect(pendingButton()).toHaveAccessibleName('편집 녹화를 기다리는 중');
    await act(async () => {
      vi.advanceTimersByTime(PENDING_RECHECK_MS);
    });
    expect(prefetchCardEditor).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('link', { name: '편집' })).toBeInTheDocument();
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

  it('가린 카드 · 만료 카드는 받지 않는다(편집 단추가 없다)', () => {
    renderCard({ ...HIGHLIGHT, hidden: true });
    renderCard({ ...HIGHLIGHT, id: 'card-24', status: 'expired' });
    expect(prefetchCardEditor).not.toHaveBeenCalled();
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
