import { describe, expect, it } from 'vitest';
import {
  boundaryAction,
  editorSourceError,
  effectiveBounds,
  MEDIA_END_MARGIN_SECONDS,
  playbackErrorMessage,
  type PlaybackBounds,
} from './editorPlayback';
import type { EditorSourceState } from './source/editorSourceState';

// 구간 경계는 시뮬레이션과 실재생 어댑터가 나눠 쓰는 규칙이다. 실재생 쪽은 jsdom에 미디어 구현이 없어
// 렌더로 못 재므로, 규칙 자체를 여기서 표로 못박는다.

const RANGE: PlaybackBounds = { startSeconds: 100, endSeconds: 112.4, loop: true };
const NO_LOOP: PlaybackBounds = { ...RANGE, loop: false };

describe('boundaryAction', () => {
  it('구간 안이면 그대로 간다', () => {
    expect(boundaryAction(105, RANGE)).toEqual({ kind: 'continue' });
    expect(boundaryAction(105, NO_LOOP)).toEqual({ kind: 'continue' });
  });

  it('구간 시작 직전은 반복일 때만 끌어온다', () => {
    // 반복을 켜 두고 앞에서 재생하면 클립 밖이 재생된다 — 시작으로 데려온다
    expect(boundaryAction(80, RANGE)).toEqual({ kind: 'seek', toSeconds: 100 });
    // 반복이 꺼져 있으면 앞뒤 맥락을 보려고 일부러 나간 것이라 두고 본다
    expect(boundaryAction(80, NO_LOOP)).toEqual({ kind: 'continue' });
  });

  it('시작점 자체는 구간 안이다', () => {
    expect(boundaryAction(100, RANGE)).toEqual({ kind: 'continue' });
  });

  it('끝점에 닿으면 반복은 되감고 아니면 멈춘다', () => {
    // 끝은 열린 경계다 — 정확히 끝이면 이미 구간 밖이다
    expect(boundaryAction(112.4, RANGE)).toEqual({ kind: 'seek', toSeconds: 100 });
    expect(boundaryAction(112.4, NO_LOOP)).toEqual({ kind: 'stop', atSeconds: 112.4 });
  });

  it('끝을 지나쳤어도 멈추는 자리는 끝이다 — 지나친 만큼 되돌린다', () => {
    expect(boundaryAction(120, NO_LOOP)).toEqual({ kind: 'stop', atSeconds: 112.4 });
  });

  it('반복이면 시작 앞 판정이 끝 판정보다 앞선다', () => {
    // 구간이 뒤집힌 값이 들어와도(끝 < 시작) 무한 루프가 아니라 한쪽으로 결론이 난다
    const inverted: PlaybackBounds = { startSeconds: 112.4, endSeconds: 100, loop: true };
    expect(boundaryAction(50, inverted)).toEqual({ kind: 'seek', toSeconds: 112.4 });
  });
});

describe('effectiveBounds: 받은 영상이 구간보다 짧을 때(POK-490)', () => {
  it('영상이 구간 끝보다 먼저 끝나면 영상 끝 바로 앞이 구간 끝이다: 반복이 영상 끝에서 걸린다', () => {
    expect(effectiveBounds(RANGE, 108)).toEqual({
      ...RANGE,
      endSeconds: 108 - MEDIA_END_MARGIN_SECONDS,
    });
    // 그 자리에서 반복은 되감고, 반복이 꺼져 있으면 멈춘다
    expect(boundaryAction(107.96, effectiveBounds(RANGE, 108))).toEqual({
      kind: 'seek',
      toSeconds: 100,
    });
    expect(boundaryAction(107.96, effectiveBounds(NO_LOOP, 108)).kind).toBe('stop');
  });

  it('영상이 구간을 다 덮으면 그대로다', () => {
    expect(effectiveBounds(RANGE, 122.4)).toBe(RANGE);
  });

  it('영상 길이를 아직 모르면(NaN · Infinity) 그대로다', () => {
    expect(effectiveBounds(RANGE, Number.NaN)).toBe(RANGE);
    expect(effectiveBounds(RANGE, Number.POSITIVE_INFINITY)).toBe(RANGE);
  });

  it('영상이 구간 시작 앞에서 끝나면 그대로다: 시작과 끝이 겹치면 매 틱 되감는다', () => {
    expect(effectiveBounds(RANGE, 99)).toBe(RANGE);
    expect(effectiveBounds(RANGE, 100.04)).toBe(RANGE);
  });
});

describe('editorSourceError — 원천 상태 문구(설계 prw2 14.2 D3 (ㄱ))', () => {
  const LIST = 'https://media.example/vod/k/r/index.m3u8';
  const segment = { mode: 'vod' as const, listUrl: LIST };
  const message = (view: EditorSourceState, hasList = true, listFailed = false) => {
    const error = editorSourceError(view, hasList, listFailed);
    return error === null ? null : playbackErrorMessage(error);
  };

  it('상태마다 정한 문구를 고른다', () => {
    expect(message({ kind: 'checking' })).toBe('영상을 확인하는 중이에요');
    expect(message({ kind: 'uploading' })).toBe('방금 지난 장면이라 몇 초 뒤에 보여요');
    expect(message({ kind: 'finalizing', cause: 'stream' })).toBe(
      '방송 녹화를 정리하는 중이에요(보통 몇 분)',
    );
    expect(message({ kind: 'outsideDvr' })).toBe(
      '방송 중에는 최근 1시간만 편집할 수 있어요. 끝나면 전체를 편집할 수 있어요',
    );
    expect(message({ kind: 'goneOrNone', segment })).toBe('보관 기간이 지났거나 영상이 없어요');
    expect(message({ kind: 'expired' })).toBe(
      '보관 기간(60일)이 지나 이 방송의 영상을 볼 수 없어요',
    );
    expect(message({ kind: 'access', segment })).toBe(
      '영상 출입증이 거절됐어요. 잠시 뒤 다시 열어 주세요',
    );
  });

  it('원천이 서고 목록이 있으면 알릴 것이 없고, 목록 전이면 받는 중 · 받다 실패를 가른다', () => {
    const ready: EditorSourceState = { kind: 'ready', mode: 'vod', url: LIST };
    expect(editorSourceError(ready, true, false)).toBeNull();
    expect(message(ready, false, false)).toBe('영상 목록을 받는 중이에요');
    expect(message(ready, false, true)).toBe('영상 목록을 받지 못했어요. 다시 받는 중이에요');
  });

  it('잠시 불가 · 제어면 없음(unavailable)은 알리지 않는다: 미리보기 없이 연다', () => {
    expect(editorSourceError({ kind: 'unavailable' }, false, false)).toBeNull();
  });
});
