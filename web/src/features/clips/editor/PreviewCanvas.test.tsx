import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PreviewCanvas } from './PreviewCanvas';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 미리보기가 끊겨 기다리는 동안의 스피너(POK-439). 분할 · PIP여도 판마다 하나다 — 칸마다 복제하지 않는다.

function state(overrides: Partial<ClipEditorMockState>) {
  return {
    activeTool: 'audio',
    subtitle: { status: 'idle' },
    regions: [],
    previewVideo: document.createElement('video'),
    previewBuffering: false,
    previewError: null,
    trackPreviewStatus: null,
    ...overrides,
  } as unknown as ClipEditorMockState;
}

function spinners(overrides: Partial<ClipEditorMockState>) {
  const { container } = render(<PreviewCanvas state={state(overrides)} />);
  return container.querySelectorAll('[data-buffering]').length;
}

describe('PreviewCanvas 버퍼링', () => {
  it('끊기지 않았으면 없다', () => {
    expect(spinners({})).toBe(0);
  });

  it('결과 판 하나일 때는 스피너도 하나다', () => {
    expect(spinners({ previewBuffering: true })).toBe(1);
  });

  it('레이아웃 도구는 원본 · 결과 판에 하나씩이다', () => {
    expect(spinners({ previewBuffering: true, activeTool: 'layout' })).toBe(2);
  });

  it('못 트는 이유를 안내하는 중이면 없다 — 기다리는 것이 아니다', () => {
    expect(spinners({ previewBuffering: true, previewError: '미리보기를 틀 수 없어요' })).toBe(0);
  });
});

describe('PreviewCanvas 트랙별 미리 듣기 안내', () => {
  it('트랙 소리를 기다리는 중이면 그렇게 알리고 못 부름으로 알리지 않는다(kty 결정 (나) c10b′ r1 5번)', () => {
    const { getByText, queryByText } = render(
      <PreviewCanvas state={state({ trackPreviewStatus: 'waiting' })} />,
    );
    expect(getByText('트랙 소리를 기다리는 중이에요')).toHaveAttribute('role', 'status');
    expect(queryByText(/트랙별 미리 듣기를 못 불러왔어요/)).toBeNull();
  });

  // 리뷰(mem2 cx M): 창을 바꾸면 옛 파형이 남은 채 새 창 소리를 받는다 — 음량을 바꿔 둔 편집본은 그동안 무음이라 안내가 없으면
  // 고장처럼 보였다(kty 결정 ④ 「잠깐 무음 · 기다림 표시」)
  it('트랙 소리를 불러오는 중이면 그렇게 알린다', () => {
    const { getByText } = render(
      <PreviewCanvas state={state({ trackPreviewStatus: 'loading' })} />,
    );
    expect(getByText('트랙 소리를 불러오는 중이에요')).toHaveAttribute('role', 'status');
  });

  it('다 불렀거나 받을 소리가 없으면 불러오는 중으로 알리지 않는다', () => {
    for (const status of ['ready', 'idle', 'waiting', 'unavailable', null] as const) {
      const { queryByText, unmount } = render(
        <PreviewCanvas state={state({ trackPreviewStatus: status })} />,
      );
      expect(queryByText('트랙 소리를 불러오는 중이에요'), String(status)).toBeNull();
      unmount();
    }
  });

  it('불러오는 중 · 다 불렀으면 기다림으로 알리지 않는다', () => {
    for (const status of ['loading', 'ready'] as const) {
      const { queryByText, unmount } = render(
        <PreviewCanvas state={state({ trackPreviewStatus: status })} />,
      );
      expect(queryByText('트랙 소리를 기다리는 중이에요')).toBeNull();
      unmount();
    }
  });
});
