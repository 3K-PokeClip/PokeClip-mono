import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
import type { EditorPlayback, PlaybackBounds } from './editorPlayback';
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

    act(() => result.current.setLayout('vert'));
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
    expect(result.current.layout).toBe('vert');
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
    act(() => result.current.setLayout('vert'));
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
    act(() => result.current.setLayout('vert'));

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

  it('레이아웃이 영역을 정한다 — 분할은 둘, 나머지는 하나', () => {
    const { result } = renderEditor();

    // 시안 갱신분의 기본은 분할이다
    expect(result.current.regions.map((region) => region.id)).toEqual(['top', 'bottom']);

    act(() => result.current.setLayout('vert'));
    expect(result.current.regions.map((region) => region.id)).toEqual(['main']);

    act(() => result.current.setLayout('crop'));
    expect(result.current.regions.map((region) => region.id)).toEqual(['main', 'pip']);
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

// --- 재생 어댑터 주입 ----------------------------------------------------------
// 허브는 재생이 목업 시뮬레이션인지 실재생인지 모른다. 그 경계를 가짜 어댑터로 확인한다.

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

describe('useClipEditorMockState — 크롭 영역 (E5)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  /** 계약6 픽셀 기준 종횡비 — 목업 소스는 1920×1080 이다 */
  const pixelAspect = (crop: { w: number; h: number }) => (crop.w * 1920) / (crop.h * 1080);

  it('상하분할은 한 소스에서 두 영역을 잡는다 — 칸마다 자기 사각형이 있다', () => {
    const { result } = renderEditor();

    expect(result.current.regions[0]?.crop).toBeDefined();
    expect(result.current.regions[1]?.crop).toBeDefined();
    // 기본값은 위·아래로 갈라 둔다 — 겹쳐 있으면 뭘 잡았는지 안 보인다
    expect(result.current.regions[0]!.crop!.y).toBeLessThan(result.current.regions[1]!.crop!.y);
  });

  it('처음에는 비율마다의 기본 자리를 계약6 모양으로 준다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    const crop = result.current.regions[0]?.crop;

    expect(crop).toBeDefined();
    expect(crop!.x).toBeGreaterThanOrEqual(0);
    expect(crop!.x + crop!.w).toBeLessThanOrEqual(1 + 1e-12);
    expect(pixelAspect(crop!)).toBeCloseTo(9 / 16, 8);
    expect(result.current.regions[0]?.cropZoom).toBe(1);
  });

  it('끈 만큼 사각형이 손을 따라온다 — 부호가 같다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    const before = result.current.regions[0]!.crop!.x;

    act(() => result.current.dragCrop('main', { x: 80, y: 0 }, { width: 800, height: 450 }));

    expect(result.current.regions[0]!.crop!.x).toBeGreaterThan(before);
  });

  it('소스 밖으로는 안 나간다 — 계약6 x+w ≤ 1', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });

    act(() => result.current.dragCrop('main', { x: 99999, y: 0 }, { width: 800, height: 450 }));

    const crop = result.current.regions[0]!.crop!;
    expect(crop.x + crop.w).toBeCloseTo(1, 10);
  });

  it('모서리를 끌면 범위가 바뀌고 비율은 그대로다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    act(() => result.current.zoomCrop('main', -0.4)); // 먼저 줄여 둔다
    const before = result.current.regions[0]!.crop!;

    act(() => result.current.resizeCrop('main', 'se', { x: 0.99, y: 0.99 }));

    const after = result.current.regions[0]!.crop!;
    expect(after.w).toBeGreaterThan(before.w);
    expect(pixelAspect(after)).toBeCloseTo(9 / 16, 8);
    // 반대편 모서리가 고정된다
    expect(after.x).toBeCloseTo(before.x, 8);
  });

  it('계약6 하한(0.05)보다 작게는 못 줄인다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });

    act(() => result.current.zoomCrop('main', -5));

    const crop = result.current.regions[0]!.crop!;
    expect(Math.min(crop.w, crop.h)).toBeGreaterThanOrEqual(0.05 - 1e-12);
  });

  it('당겨 보면 세로로도 움직인다 — 여유가 생긴다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    act(() => result.current.zoomCrop('main', -0.5));
    const before = result.current.regions[0]!.crop!.y;

    act(() => result.current.nudgeCrop('main', { x: 0, y: 0.1 }));

    expect(result.current.regions[0]!.crop!.y).toBeGreaterThan(before);
  });

  it('크롭은 실행취소 대상이다 — 레시피에 들어간다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    const before = result.current.regions[0]!.crop!.x;

    act(() => result.current.nudgeCrop('main', { x: 0.05, y: 0 }));
    expect(result.current.regions[0]!.crop!.x).not.toBeCloseTo(before, 10);

    act(() => result.current.undo());
    expect(result.current.regions[0]!.crop!.x).toBeCloseTo(before, 10);
  });

  it('가장자리에서 더 끌어도 히스토리가 늘지 않는다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    act(() => result.current.nudgeCrop('main', { x: 99, y: 0 }));
    const atEdge = result.current.regions[0]!.crop!.x;

    act(() => result.current.nudgeCrop('main', { x: 99, y: 0 }));
    act(() => result.current.undo());

    expect(result.current.regions[0]!.crop!.x).not.toBeCloseTo(atEdge, 10);
  });

  it('비율을 바꿔도 잡은 자리와 확대율이 남는다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    act(() => result.current.zoomCrop('main', -0.3));
    act(() => result.current.nudgeCrop('main', { x: 0.08, y: 0 }));
    const before = result.current.regions[0]!;
    const center = before.crop!.x + before.crop!.w / 2;

    act(() => result.current.setLayout('horiz'));

    const after = result.current.regions[0]!;
    expect(after.cropZoom).toBeCloseTo(before.cropZoom!, 10);
    expect(after.crop!.x + after.crop!.w / 2).toBeCloseTo(center, 10);
    // 비율은 새 화면을 따라간다
    expect(pixelAspect(after.crop!)).toBeCloseTo(16 / 9, 8);
  });

  it('되돌리기로 기본 자리로 초기화한다', () => {
    const { result } = renderEditor({ initialLayout: 'vert' });
    const initial = result.current.regions[0]!.crop!.x;
    act(() => result.current.nudgeCrop('main', { x: 0.08, y: 0 }));
    act(() => result.current.resetCrop('main'));

    expect(result.current.regions[0]!.crop!.x).toBeCloseTo(initial, 10);
  });
});

describe('useClipEditorMockState — 크롭 모드의 작은 화면 자리', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('크롭 모드에서만 자리가 있고, 결과 배치가 그 값을 따른다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });

    expect(result.current.pipPlacement).not.toBeNull();
    const pip = result.current.regions.find((region) => region.id === 'pip');
    expect(pip?.placement.kind).toBe('overlay');
    if (pip?.placement.kind === 'overlay') {
      // 시안 기본값 left:18% top:50% width:64%
      expect(pip.placement.left).toBeCloseTo(0.18, 10);
      expect(pip.placement.top).toBeCloseTo(0.5, 10);
      expect(pip.placement.width).toBeCloseTo(0.64, 10);
    }

    act(() => result.current.setLayout('vert'));
    expect(result.current.pipPlacement).toBeNull();
  });

  it('작은 화면의 높이는 결과 화면에서 4:3 이 되게 잡힌다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    const pip = result.current.pipPlacement!;
    // 결과가 9:16 이므로 정규화 높이 = w × (3/4) × (9/16)
    expect(pip.h).toBeCloseTo(((pip.w * 3) / 4) * (9 / 16), 10);
  });

  it('끈 만큼 자리가 옮겨지고 메인 밖으로는 안 나간다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    const before = result.current.pipPlacement!;

    act(() => result.current.dragPip({ x: 40, y: 0 }, { width: 400, height: 700 }));
    expect(result.current.pipPlacement!.x).toBeCloseTo(before.x + 0.1, 10);

    act(() => result.current.dragPip({ x: 99999, y: 99999 }, { width: 400, height: 700 }));
    const far = result.current.pipPlacement!;
    expect(far.x + far.w).toBeCloseTo(1, 10);
    expect(far.y + far.h).toBeCloseTo(1, 10);
  });

  it('모서리를 끌면 반대편이 고정된 채 비율 제한 없이 모양이 바뀌고, 원본 프레임이 그 비율을 따른다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    act(() => result.current.resetPip());
    const before = result.current.pipPlacement!;

    // 가로로 길게, 세로로 짧게 — 4:3 에 묶여 있으면 나올 수 없는 모양
    act(() => result.current.resizePip('se', { x: before.x + 0.5, y: before.y + 0.1 }));

    const after = result.current.pipPlacement!;
    expect(after.x).toBeCloseTo(before.x, 10);
    expect(after.y).toBeCloseTo(before.y, 10);
    expect(after.w).toBeCloseTo(0.5, 10);
    expect(after.h).toBeCloseTo(0.1, 10);

    // 결과가 9:16 이라 0.5×0.1 의 픽셀 비율 = 5 × 9/16. 결과 배치와 원본 프레임이 같은 비율이다
    const aspect = (0.5 / 0.1) * (9 / 16);
    const pip = result.current.regions.find((region) => region.id === 'pip')!;
    expect(pip.placement.kind).toBe('overlay');
    if (pip.placement.kind === 'overlay') expect(pip.placement.aspect).toBeCloseTo(aspect, 10);
    const crop = pip.crop!;
    expect((crop.w * 1920) / (crop.h * 1080)).toBeCloseTo(aspect, 8);
  });

  it('자리는 최소 크기 아래로 못 줄이고 메인 밖으로 못 넓힌다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    const before = result.current.pipPlacement!;

    act(() => result.current.resizePip('se', { x: before.x, y: before.y }));
    const tiny = result.current.pipPlacement!;
    expect(tiny.w).toBeCloseTo(0.2, 10);
    expect(tiny.h).toBeCloseTo(0.06, 10);

    act(() => result.current.resizePip('se', { x: 9, y: 9 }));
    const huge = result.current.pipPlacement!;
    expect(huge.x).toBeCloseTo(before.x, 10);
    expect(huge.x + huge.w).toBeLessThanOrEqual(1 + 1e-12);
    expect(huge.y + huge.h).toBeLessThanOrEqual(1 + 1e-12);
  });

  it('자리는 실행취소 대상이고 초기화로 시안 기본값에 돌아온다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    act(() => result.current.dragPip({ x: 0.2, y: 0 }, { width: 1, height: 1 }));
    expect(result.current.pipPlacement!.x).not.toBeCloseTo(0.18, 10);

    act(() => result.current.undo());
    expect(result.current.pipPlacement!.x).toBeCloseTo(0.18, 10);

    act(() => result.current.dragPip({ x: 0.2, y: 0 }, { width: 1, height: 1 }));
    act(() => result.current.resetPip());
    expect(result.current.pipPlacement!.x).toBeCloseTo(0.18, 10);
  });

  it('원본의 작은 화면 프레임은 자리가 정한 비율에 묶인 채 크기만 바뀐다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    const regionOf = () => result.current.regions.find((region) => region.id === 'pip')!;
    // 자리를 세로로 긴 모양으로 — 원본 프레임이 그 비율이 된다
    const target = result.current.pipPlacement!;
    act(() => result.current.resizePip('se', { x: target.x + 0.3, y: target.y + 0.45 }));
    const aspect = (0.3 / 0.45) * (9 / 16);
    const before = regionOf().crop!;
    expect((before.w * 1920) / (before.h * 1080)).toBeCloseTo(aspect, 8);

    // 원본 프레임의 모서리를 가로로만 길게 끌어도 비율은 그대로고 크기만 커진다
    act(() => result.current.resizeCrop('pip', 'se', { x: 0.99, y: before.y + before.h }));

    const after = regionOf().crop!;
    expect(after.x).toBeCloseTo(before.x, 8);
    expect(after.y).toBeCloseTo(before.y, 8);
    expect(after.w).toBeGreaterThan(before.w);
    expect((after.w * 1920) / (after.h * 1080)).toBeCloseTo(aspect, 8);
    // 자리는 프레임을 잡아도 바뀌지 않는다
    expect(result.current.pipPlacement!.w).toBeCloseTo(0.3, 10);
    expect(result.current.pipPlacement!.h).toBeCloseTo(0.45, 10);
  });

  it('자리의 비율이 바뀐 뒤에도 프레임 옮기기·당기기가 그 비율로 잰다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    const regionOf = () => result.current.regions.find((region) => region.id === 'pip')!;
    const target = result.current.pipPlacement!;
    act(() => result.current.resizePip('se', { x: target.x + 0.3, y: target.y + 0.45 }));
    const shaped = regionOf().crop!;
    const aspect = (shaped.w * 1920) / (shaped.h * 1080);

    act(() => result.current.nudgeCrop('pip', { x: -0.1, y: 0 }));
    act(() => result.current.zoomCrop('pip', -0.1));

    const moved = regionOf().crop!;
    expect((moved.w * 1920) / (moved.h * 1080)).toBeCloseTo(aspect, 8);
    expect(moved.w).toBeLessThan(shaped.w);
  });

  it('모양 잡기는 실행취소 대상이고 같은 모양은 기록을 남기지 않는다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    act(() => result.current.resizePip('se', { x: 0.7, y: 0.6 }));
    act(() => result.current.resizeCrop('pip', 'se', { x: 0.9, y: 0.9 }));
    expect(result.current.canUndo).toBe(true);

    act(() => result.current.undo());
    act(() => result.current.undo());
    expect(result.current.pipPlacement!.h).toBeCloseTo(0.27, 10);
    expect(result.current.canUndo).toBe(false);

    act(() => result.current.dragPip({ x: 0, y: 0 }, { width: 1, height: 1 }));
    expect(result.current.canUndo).toBe(false);
  });
});

describe('useClipEditorMockState — 중앙 모드의 바깥 띠 채움', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('기본은 블러 60 이고 결과 배치가 그 값을 나른다', () => {
    const { result } = renderEditor({ initialLayout: 'center' });

    expect(result.current.centerFill).toEqual({ kind: 'blur', strength: 60 });
    const main = result.current.regions[0];
    expect(main?.placement.kind).toBe('contain');
    if (main?.placement.kind === 'contain') expect(main.placement.fill.kind).toBe('blur');
  });

  it('블러 강도를 바꾸면 배치에 실리고 같은 값은 기록을 안 남긴다', () => {
    const { result } = renderEditor({ initialLayout: 'center' });

    act(() => result.current.setCenterFill({ kind: 'blur', strength: 25 }));
    const main = result.current.regions[0];
    expect(main?.placement.kind === 'contain' && main.placement.fill).toEqual({
      kind: 'blur',
      strength: 25,
    });

    act(() => result.current.setCenterFill({ kind: 'blur', strength: 25 }));
    act(() => result.current.undo());
    expect(result.current.centerFill).toEqual({ kind: 'blur', strength: 60 });
    expect(result.current.canUndo).toBe(false);
  });

  it('단색으로 바꾸면 배치에 그 색이 실리고, 실행취소로 돌아온다', () => {
    const { result } = renderEditor({ initialLayout: 'center' });

    act(() => result.current.setCenterFill({ kind: 'color', color: '#ffffff' }));
    const main = result.current.regions[0];
    expect(main?.placement.kind === 'contain' && main.placement.fill).toEqual({
      kind: 'color',
      color: '#ffffff',
    });

    act(() => result.current.undo());
    expect(result.current.centerFill).toEqual({ kind: 'blur', strength: 60 });
  });

  it('같은 값을 다시 고르면 히스토리가 늘지 않는다', () => {
    const { result } = renderEditor({ initialLayout: 'center' });
    act(() => result.current.setCenterFill({ kind: 'color', color: '#0b0b10' }));
    act(() => result.current.setCenterFill({ kind: 'color', color: '#0b0b10' }));

    act(() => result.current.undo());
    expect(result.current.centerFill).toEqual({ kind: 'blur', strength: 60 });
    expect(result.current.canUndo).toBe(false);
  });
});

describe('useClipEditorMockState — 크롭 모드 작은 화면 테두리', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const overlayOf = (regions: ReturnType<typeof renderEditor>['result']['current']['regions']) => {
    const pip = regions.find((region) => region.id === 'pip');
    return pip?.placement.kind === 'overlay' ? pip.placement : null;
  };

  it('기본은 시안대로 1px 흰 라인이 켜져 있고 결과 배치가 그 값을 나른다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });

    expect(result.current.pipBorder).toEqual({ on: true, width: 1, color: '#ffffff' });
    expect(overlayOf(result.current.regions)?.border).toEqual({
      on: true,
      width: 1,
      color: '#ffffff',
    });
  });

  it('끄거나 굵기·색을 바꾸면 배치에 실리고, 실행취소로 돌아온다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });

    act(() => result.current.setPipBorder({ on: true, width: 3, color: '#d44697' }));
    expect(overlayOf(result.current.regions)?.border).toEqual({
      on: true,
      width: 3,
      color: '#d44697',
    });

    act(() => result.current.setPipBorder({ on: false, width: 3, color: '#d44697' }));
    expect(overlayOf(result.current.regions)?.border.on).toBe(false);

    act(() => result.current.undo());
    expect(result.current.pipBorder.on).toBe(true);
    act(() => result.current.undo());
    expect(result.current.pipBorder).toEqual({ on: true, width: 1, color: '#ffffff' });
  });

  it('같은 값을 다시 고르면 히스토리가 늘지 않는다', () => {
    const { result } = renderEditor({ initialLayout: 'crop' });
    act(() => result.current.setPipBorder({ on: true, width: 1, color: '#ffffff' }));
    expect(result.current.canUndo).toBe(false);
  });
});
