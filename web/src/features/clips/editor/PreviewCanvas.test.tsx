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
