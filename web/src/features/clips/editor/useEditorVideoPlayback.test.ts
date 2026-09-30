import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useEditorVideoPlayback } from './useEditorVideoPlayback';

// 편집기 미리보기 창은 녹화 구간 하나 안에 둔다(POK-253). 재생 서버는 틈 안에서 시작하는 요청에 404다(실측).

const BASE = Date.parse('2026-09-30T01:50:00Z');
const PIECES = [
  { fromSeconds: 0, toSeconds: 14 },
  { fromSeconds: 24, toSeconds: 38 },
];

function requestedWindow(fetchSpy: ReturnType<typeof vi.fn>) {
  const url = new URL(String(fetchSpy.mock.calls[0]![0]), 'http://localhost');
  const fromSeconds = (Date.parse(url.searchParams.get('start')!) - BASE) / 1000;
  return { fromSeconds, toSeconds: fromSeconds + Number(url.searchParams.get('duration')) };
}

describe('useEditorVideoPlayback — 녹화 구간', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchSpy = vi.fn(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetchSpy);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('재접속 직후 카드는 앞 여유를 틈까지 늘리지 않는다 — 틈에서 시작하면 미리보기가 통째로 실패했다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 26, endSeconds: 30 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 24, toSeconds: 38 });
  });

  it('틈 앞 구간의 카드는 틈 앞에서 끝난다 — 틈을 건너 달라 하지 않는다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 5, endSeconds: 10 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 0, toSeconds: 14 });
  });

  it('구간이 통째로 틈 안이면 받지 않고 미리보기 실패로 알린다 — 엉뚱한 녹화를 보이지 않는다(PR #205 codex)', () => {
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 114,
        pieces: [
          { fromSeconds: 0, toSeconds: 14 },
          { fromSeconds: 100, toSeconds: 114 },
        ],
        initialRange: { startSeconds: 50, endSeconds: 60 },
      }),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.playback.error).toBe('fatal');
  });

  it('구간은 틈 안인데 여유만 다음 녹화에 닿아도 받지 않는다 — 고른 장면이 아닌 영상이다(PR #205 codex)', () => {
    const { result } = renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 38,
        pieces: PIECES,
        initialRange: { startSeconds: 15, endSeconds: 16 },
      }),
    );
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(result.current.playback.error).toBe('fatal');
  });

  it('구간 목록이 없으면 전처럼 앞뒤 10초 여유다', () => {
    renderHook(() =>
      useEditorVideoPlayback({
        streamId: 'gaptest',
        recordingStartMs: BASE,
        recordingSeconds: 100,
        initialRange: { startSeconds: 30, endSeconds: 40 },
      }),
    );
    expect(requestedWindow(fetchSpy)).toEqual({ fromSeconds: 20, toSeconds: 50 });
  });
});
