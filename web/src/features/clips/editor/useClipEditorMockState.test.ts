import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
import type { EditorPlayback, PlaybackBounds } from './editorPlayback';
import type { AudioPeaks, EditorMediaSource } from './editorSource';
import { MAX_RANGE_SECONDS } from './timelineMath';
import { useClipEditorMockState, type ClipEditorOptions } from './useClipEditorMockState';

function renderEditor(options?: ClipEditorOptions) {
  return renderHook(() => useClipEditorMockState(options), { wrapper: withToastProvider });
}

describe('useClipEditorMockState', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('시안 1d-a 기본값으로 시작한다 — 상하분할·자막 생성 후·정지', () => {
    const { result } = renderEditor();

    expect(result.current.clipTitle).toBe('승급전 마지막 한타 역전');
    expect(result.current.layout).toBe('split');
    expect(result.current.subtitle.status).toBe('ready');
    expect(result.current.playing).toBe(false);
    expect(result.current.rangeLengthLabel).toBe('12.4초');
    expect(result.current.rangeStartLabel).toBe('1:22:08.4');
    expect(result.current.rangeGaugeLabel).toBe('0:12.4 / 최대 3:00');
  });

  it('트랙 6종을 시안 볼륨과 클립 이름 그대로 준다', () => {
    const { result } = renderEditor();

    expect(result.current.tracks.map((t) => t.label)).toEqual([
      '영상',
      '마이크',
      '게임 사운드',
      'BGM',
      '효과음',
      '이미지',
    ]);
    expect(result.current.tracks.map((t) => t.volume)).toEqual([null, 80, 60, 40, 70, null]);
    expect(result.current.tracks[3]?.clips[0]?.label).toBe('Neon Drive.mp3 · 페이드 인/아웃');
    expect(result.current.tracks[4]?.clips.map((c) => c.label)).toEqual(['띠용', '박수']);
  });

  it('자막 생성 전에는 제목 추천이 잠기고, 생성하면 열린다', () => {
    const { result } = renderEditor({ initialSubtitleStatus: 'idle' });

    expect(result.current.titlesLocked).toBe(true);
    expect(result.current.subtitle).toEqual({ status: 'idle', estimateLabel: '약 20초' });

    act(() => result.current.generateSubtitles());
    expect(result.current.subtitle.status).toBe('generating');
    expect(result.current.titlesLocked).toBe(true);

    act(() => vi.runAllTimers());
    expect(result.current.subtitle.status).toBe('ready');
    expect(result.current.titlesLocked).toBe(false);
    expect(result.current.selectedSubtitleId).toBe('sub-1');
  });

  it('3분을 넘기려 하면 구간이 그대로 멈춘다', () => {
    const { result } = renderEditor();
    const before = result.current.range;

    act(() => result.current.setRangeEdge('start', before.endSeconds - 181));

    expect(result.current.range).toEqual(before);
    // 되돌릴 거리도 생기지 않는다 — 아무 일도 일어나지 않았다
    expect(result.current.canUndo).toBe(false);
  });

  it('5초 미만으로 줄이려 해도 거부한다', () => {
    const { result } = renderEditor();
    const before = result.current.range;

    act(() => result.current.setRangeEdge('end', before.startSeconds + 4));

    expect(result.current.range).toEqual(before);
    expect(result.current.canUndo).toBe(false);
  });

  it('막힌 뒤에도 경계 안쪽 조작은 그대로 먹는다', () => {
    const { result } = renderEditor();
    const before = result.current.range;

    act(() => result.current.setRangeEdge('end', before.startSeconds + 4));
    expect(result.current.range).toEqual(before);

    act(() =>
      result.current.setRangeEdge('start', result.current.range.endSeconds - MAX_RANGE_SECONDS),
    );
    expect(result.current.rangeLengthSeconds).toBe(MAX_RANGE_SECONDS);
  });

  it('I·O는 플레이헤드로 구간 끝을 옮긴다', () => {
    const { result } = renderEditor();

    act(() => result.current.seekTo(4960));
    act(() => result.current.markOut());

    expect(result.current.range.endSeconds).toBe(4960);
  });

  it('되돌리기가 구간·레이아웃·제목 선택을 되돌린다', () => {
    const { result } = renderEditor();
    const originalRange = result.current.range;

    act(() => result.current.setLayout('9:16'));
    act(() => result.current.selectTitle('title-2'));
    act(() => result.current.setRangeEdge('end', originalRange.startSeconds + 30));

    expect(result.current.canUndo).toBe(true);
    act(() => result.current.undo());
    expect(result.current.range).toEqual(originalRange);
    act(() => result.current.undo());
    expect(result.current.selectedTitleId).toBeNull();
    act(() => result.current.undo());
    expect(result.current.layout).toBe('split');
    expect(result.current.canUndo).toBe(false);

    act(() => result.current.redo());
    expect(result.current.layout).toBe('9:16');
  });

  it('같은 값을 다시 고르면 되돌릴 거리가 생기지 않는다', () => {
    const { result } = renderEditor();

    act(() => result.current.setLayout('split'));

    expect(result.current.canUndo).toBe(false);
  });

  it('트랙 볼륨 조절도 되돌릴 수 있다', () => {
    const { result } = renderEditor();

    act(() => result.current.setTrackVolume('mic', 20));
    expect(result.current.tracks[1]?.volume).toBe(20);

    act(() => result.current.undo());
    expect(result.current.tracks[1]?.volume).toBe(80);
  });

  it('재생하면 구간 안에서 플레이헤드가 흐르고 끝에서 되감는다', () => {
    const { result } = renderEditor();

    act(() => result.current.seekTo(result.current.range.endSeconds - 0.15));
    act(() => result.current.togglePlay());
    expect(result.current.playing).toBe(true);

    act(() => vi.advanceTimersByTime(200));
    expect(result.current.playheadSeconds).toBeCloseTo(result.current.range.startSeconds, 5);
  });

  it('패널 위치를 옮기면 브라우저에 남는다 — 다음 방문에도 그 자리다', () => {
    window.localStorage.removeItem('pc-editor-panel-side');
    const first = renderEditor();

    expect(first.result.current.panelSide).toBe('left');
    act(() => first.result.current.togglePanelSide());
    expect(first.result.current.panelSide).toBe('right');
    expect(window.localStorage.getItem('pc-editor-panel-side')).toBe('right');

    // 새로 마운트해도 저장된 자리에서 시작한다
    const second = renderEditor();
    expect(second.result.current.panelSide).toBe('right');
    window.localStorage.removeItem('pc-editor-panel-side');
  });

  it('드래그 한 번은 실행취소 한 칸이다 — 이력을 삼키지 않는다', () => {
    const { result } = renderEditor();
    act(() => result.current.setLayout('9:16'));
    const beforeDrag = result.current.range;

    act(() => result.current.beginGesture());
    for (const seconds of [4950, 4955, 4960, 4965, 4970]) {
      act(() => result.current.setRangeEdge('end', seconds));
    }
    act(() => result.current.endGesture());

    expect(result.current.range.endSeconds).toBe(4970);
    // 한 번 되돌리면 드래그 이전 구간으로, 두 번이면 레이아웃 이전으로 간다
    act(() => result.current.undo());
    expect(result.current.range).toEqual(beforeDrag);
    act(() => result.current.undo());
    expect(result.current.layout).toBe('split');
    expect(result.current.canUndo).toBe(false);
  });

  it('드래그 중에는 타임라인 창이 고정된다 — 눈금이 손 아래에서 미끄러지지 않게', () => {
    const { result } = renderEditor();

    act(() => result.current.beginGesture());
    const frozen = result.current.view;
    act(() => result.current.setRangeEdge('end', 4970));
    expect(result.current.view).toEqual(frozen);

    // 놓으면 새 구간을 따라 창이 다시 잡힌다
    act(() => result.current.endGesture());
    expect(result.current.view).not.toEqual(frozen);
  });

  it('볼륨 드래그도 실행취소 한 칸이다 — 슬라이더 문이 제스처를 연다', () => {
    const { result } = renderEditor();
    act(() => result.current.setLayout('9:16'));

    act(() => result.current.gestureHandlers.onPointerDownCapture());
    for (const v of [70, 60, 50, 40, 30]) {
      act(() => result.current.setTrackVolume('mic', v));
    }
    act(() => result.current.gestureHandlers.onPointerUp());

    expect(result.current.tracks[1]?.volume).toBe(30);
    act(() => result.current.undo());
    expect(result.current.tracks[1]?.volume).toBe(80);
    // 드래그 이전 편집도 그대로 남아 있다
    act(() => result.current.undo());
    expect(result.current.layout).toBe('split');
  });

  it('드래그가 취소돼도 제스처가 끝난다 — 창이 고정된 채 남지 않는다', () => {
    const { result } = renderEditor();

    act(() => result.current.gestureHandlers.onPointerDownCapture());
    const frozen = result.current.view;
    act(() => result.current.gestureHandlers.onPointerCancel());

    act(() => result.current.setRangeEdge('end', 4970));
    expect(result.current.view).not.toEqual(frozen);
  });

  it('구간 앞에서 재생하면 반복이 구간 안으로 데려온다', () => {
    const { result } = renderEditor();

    act(() => result.current.seekTo(result.current.range.startSeconds - 20));
    act(() => result.current.togglePlay());
    act(() => vi.advanceTimersByTime(100));

    expect(result.current.playheadSeconds).toBe(result.current.range.startSeconds);
  });

  it('원본 끝에서 더 밀어도 이력이 쌓이지 않는다', () => {
    const { result } = renderEditor();

    // 끝점을 원본 끝까지 보낸 뒤 한 번 더 민다
    act(() => result.current.setRangeEdge('end', result.current.sourceDurationSeconds));
    const afterFirst = result.current.canUndo;
    act(() => result.current.setRangeEdge('end', result.current.sourceDurationSeconds + 50));
    act(() => result.current.undo());

    expect(afterFirst).toBe(true);
    // 두 번째 밀기가 이력을 안 쌓았으므로 한 번의 undo로 처음 구간에 돌아온다
    expect(result.current.range.endSeconds).toBe(4940.8);
  });

  it('자리바꿈은 상하분할에서만 먹는다 — 9:16으로 새지 않는다', () => {
    const { result } = renderEditor();

    expect(result.current.sources[0]?.id).toBe('game');
    act(() => result.current.swapSources());
    expect(result.current.sources[0]?.id).toBe('cam');

    act(() => result.current.setLayout('9:16'));
    // 단일 소스 모드에서는 늘 첫 소스(게임)가 온다
    expect(result.current.sources[0]?.id).toBe('game');
  });

  it('슬라이더 키 자동반복은 썸까지 가지 못한다', () => {
    const { result } = renderEditor();
    let stopped = false;
    const stopPropagation = () => {
      stopped = true;
    };

    // DS Slider는 defaultPrevented를 안 보므로 전파를 끊어야 실제로 막힌다
    result.current.gestureHandlers.onKeyDownCapture({ repeat: true, stopPropagation });
    expect(stopped).toBe(true);

    stopped = false;
    result.current.gestureHandlers.onKeyDownCapture({ repeat: false, stopPropagation });
    expect(stopped).toBe(false);
  });

  it('줌은 단계로 움직이고 표기가 따라온다', () => {
    const { result } = renderEditor();

    expect(result.current.zoomLabel).toBe('100%');
    act(() => result.current.zoomIn());
    expect(result.current.zoomLabel).toBe('200%');
    act(() => result.current.zoomOut());
    act(() => result.current.zoomOut());
    expect(result.current.zoomLabel).toBe('50%');
    act(() => result.current.zoomOut());
    expect(result.current.zoomLabel).toBe('25%');
  });

  it('타임라인 높이는 화면이 준 상한을 넘지 않는다', () => {
    const { result } = renderEditor();

    expect(result.current.timelineHeight).toBeNull();

    // 상한 없이 부르면 상수 범위 안에서만 잘린다
    act(() => result.current.setTimelineHeight(200));
    expect(result.current.timelineHeight).toBe(200);

    // 화면이 「여기까지」라고 하면 그 값에서 멈춘다 — 미리보기를 밀어내지 않는다
    act(() => result.current.setTimelineHeight(400, 300));
    expect(result.current.timelineHeight).toBe(300);

    // null은 기본 높이(트랙 수에 맞춤)로 되돌린다 — 손잡이 더블클릭 경로
    act(() => result.current.setTimelineHeight(null));
    expect(result.current.timelineHeight).toBeNull();
  });
});

// --- 실소스·실재생 주입 ------------------------------------------------------
// 화면과 허브는 재생이 목업인지 hls.js 인지 모른다. 그 경계를 가짜 어댑터로 확인한다 —
// jsdom 에는 미디어 구현이 없어 진짜 hls 경로는 여기서 못 돈다.

const SOURCE: EditorMediaSource = {
  streamId: 'editor-sample',
  label: '로컬 샘플 · 2026-08-31 방송',
  sourceStartAtMs: 1788176806750,
  durationSeconds: 600,
  width: 1920,
  height: 1080,
  fps: 60,
  playlistUrl: 'http://localhost:8080/live/editor-sample/index.m3u8',
  filmstrip: {
    sheets: ['thumbs_001.jpg'],
    sheetUrls: ['http://localhost:8080/live/editor-sample/thumbs_001.jpg'],
    columns: 10,
    rows: 10,
    tileWidth: 160,
    tileHeight: 90,
    intervalSeconds: 2,
    count: 300,
    lastSheetCount: 100,
  },
  audioTracks: [
    {
      trackId: 0,
      kind: 'mix',
      label: '오디오 · 최종 믹스',
      channels: 2,
      sampleRate: 48000,
      peaksUrl: 'http://localhost:8080/live/editor-sample/peaks_0.json',
    },
  ],
};

const PEAKS: AudioPeaks = { binMs: 100, count: 2, scale: 'abs16', maxPeak: 0.5, peaks: [0.2, 0.5] };

function fakePlayback(overrides: Partial<EditorPlayback> = {}) {
  const calls = { bounds: [] as PlaybackBounds[], rates: [] as number[], seeks: [] as number[] };
  const playback: EditorPlayback = {
    playing: false,
    currentSeconds: 60,
    durationSeconds: 600,
    error: null,
    togglePlay: vi.fn(),
    seekTo: vi.fn((seconds: number) => calls.seeks.push(seconds)),
    seekBy: vi.fn(),
    setRate: (rate: number) => calls.rates.push(rate),
    setBounds: (bounds: PlaybackBounds) => calls.bounds.push(bounds),
    ...overrides,
  };
  return { playback, calls };
}

describe('useClipEditorMockState — 실소스 주입', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('길이·라벨·구간을 소스에서 가져온다', () => {
    const { result } = renderEditor({ source: SOURCE });

    expect(result.current.sourceDurationSeconds).toBe(600);
    expect(result.current.sourceLabel).toBe('로컬 샘플 · 2026-08-31 방송');
    // 600초의 10% = 60초에서 시안 길이(12.4초)만큼
    expect(result.current.range.startSeconds).toBe(60);
    expect(result.current.rangeLengthLabel).toBe('12.4초');
    expect(result.current.rangeStartLabel).toBe('0:01:00.0');
  });

  it('타임라인 트랙이 소스의 오디오 + 편집기 자산으로 바뀐다', () => {
    const { result } = renderEditor({ source: SOURCE, peaks: new Map([[0, PEAKS]]) });

    expect(result.current.tracks.map((track) => track.label)).toEqual([
      '영상',
      '오디오 · 최종 믹스',
      'BGM',
      '효과음',
      '이미지',
    ]);
    // 실데이터가 트랙에 실려야 레인이 자리 표시자 대신 그림을 그린다
    expect(result.current.tracks[0]?.filmstrip?.count).toBe(300);
    expect(result.current.tracks[1]?.peaks?.count).toBe(2);
  });

  it('목업 장식 클립이 새 구간을 따라 옮겨온다 — 창 밖에 남으면 안 된다', () => {
    const { result } = renderEditor({ source: SOURCE });
    const bgm = result.current.tracks.find((track) => track.kind === 'bgm');

    expect(bgm?.clips[0]?.startSeconds).toBeGreaterThan(0);
    expect(bgm?.clips[0]?.startSeconds).toBeLessThan(600);
  });

  it('미리보기 첫 칸에 영상 표식이 붙고 자리바꿈을 따라간다', () => {
    const { result } = renderEditor({ source: SOURCE });

    expect(result.current.sources[0]?.media).toBe(true);
    act(() => result.current.swapSources());
    expect(result.current.sources[1]?.media).toBe(true);
  });

  it('소스가 없으면 목업 트랙 6종 그대로다', () => {
    const { result } = renderEditor();
    expect(result.current.tracks).toHaveLength(6);
    expect(result.current.sources[0]?.media).toBeUndefined();
  });
});

describe('useClipEditorMockState — 재생 어댑터 주입', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('재생 상태와 액션을 어댑터에 넘긴다', () => {
    const { playback } = fakePlayback({ playing: true, currentSeconds: 123.4 });
    const { result } = renderEditor({ playback });

    expect(result.current.playing).toBe(true);
    expect(result.current.playheadSeconds).toBe(123.4);
    expect(result.current.playheadLabel).toBe('0:02:03.4');

    act(() => result.current.togglePlay());
    expect(playback.togglePlay).toHaveBeenCalledOnce();
    act(() => result.current.seekBy(-5));
    expect(playback.seekBy).toHaveBeenCalledWith(-5);
  });

  it('마운트할 때 구간과 배속을 어댑터에 알린다', () => {
    const { playback, calls } = fakePlayback();
    const { result } = renderEditor({ playback });

    expect(calls.bounds.at(-1)).toEqual({
      startSeconds: result.current.range.startSeconds,
      endSeconds: result.current.range.endSeconds,
      loop: true,
    });
    expect(calls.rates.at(-1)).toBe(1);
  });

  it('구간 반복을 끄거나 배속을 바꾸면 어댑터가 다시 듣는다', () => {
    const { playback, calls } = fakePlayback();
    const { result } = renderEditor({ playback });

    act(() => result.current.toggleLoop());
    expect(calls.bounds.at(-1)?.loop).toBe(false);

    act(() => result.current.setSpeed(2));
    expect(calls.rates.at(-1)).toBe(2);
  });

  it('구간 핸들을 옮기면 새 구간이 어댑터로 간다', () => {
    const { playback, calls } = fakePlayback();
    const { result } = renderEditor({ playback });
    const before = calls.bounds.length;

    act(() => result.current.setRangeEdge('end', result.current.range.endSeconds + 3));

    expect(calls.bounds.length).toBeGreaterThan(before);
    expect(calls.bounds.at(-1)?.endSeconds).toBe(result.current.range.endSeconds);
  });
});
