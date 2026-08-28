import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { withToastProvider } from '@/test/testProviders';
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

  it('3분을 넘기려 하면 구간을 그대로 두고 거부 신호를 올린다', () => {
    const { result } = renderEditor();
    const before = result.current.range;

    act(() => result.current.setRangeEdge('start', before.endSeconds - 181));

    expect(result.current.range).toEqual(before);
    expect(result.current.rangeRejection?.reason).toBe('tooLong');
    const firstNonce = result.current.rangeRejection?.nonce ?? 0;

    // 같은 거부가 이어져도 흔들림이 다시 돌도록 nonce가 오른다
    act(() => result.current.setRangeEdge('start', before.endSeconds - 181));
    expect(result.current.rangeRejection?.nonce).toBe(firstNonce + 1);
  });

  it('5초 미만으로 줄이려 해도 거부한다', () => {
    const { result } = renderEditor();
    const before = result.current.range;

    act(() => result.current.setRangeEdge('end', before.startSeconds + 4));

    expect(result.current.range).toEqual(before);
    expect(result.current.rangeRejection?.reason).toBe('tooShort');
  });

  it('경계 안쪽으로 옮기면 적용되고 거부 신호가 사라진다', () => {
    const { result } = renderEditor();

    act(() => result.current.setRangeEdge('end', result.current.range.startSeconds + 4));
    expect(result.current.rangeRejection).not.toBeNull();

    act(() =>
      result.current.setRangeEdge('start', result.current.range.endSeconds - MAX_RANGE_SECONDS),
    );
    expect(result.current.rangeRejection).toBeNull();
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

  it('되돌리면 거부 안내가 사라진다', () => {
    const { result } = renderEditor();

    act(() => result.current.setRangeEdge('end', result.current.range.startSeconds + 4));
    expect(result.current.rangeRejection).not.toBeNull();

    act(() => result.current.undo());
    expect(result.current.rangeRejection).toBeNull();
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
});
