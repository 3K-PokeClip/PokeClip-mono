import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { sceneLabel, scenePicks } from './uploadInfo';
import { stripTileTimes, UploadThumbnailPane } from './UploadThumbnailPane';

// 썸네일 탭의 장면 띠(POK-381). 창 전체의 동작은 UploadInfoDialog.test.tsx가 잰다.

const CUT_MS = 12_400;

function renderPane(overrides: Partial<Parameters<typeof UploadThumbnailPane>[0]> = {}) {
  return renderWithProviders(
    <UploadThumbnailPane
      source="scene"
      sceneOffsetMs={6_200}
      file={null}
      previewUrl={null}
      cutLengthMs={CUT_MS}
      picks={scenePicks(CUT_MS, null, null)}
      frameOf={() => null}
      frameFailed={() => true}
      error={undefined}
      onSource={vi.fn()}
      onScene={vi.fn()}
      onFile={vi.fn()}
      onFileProblem={vi.fn()}
      onHover={vi.fn()}
      {...overrides}
    />,
  );
}

describe('UploadThumbnailPane 장면 띠', () => {
  it('띠가 사라지면 올린 자리를 비운다: 탭을 바꾸면 pointerleave가 안 와 옛 자리가 남았다(PR #301 2차 리뷰)', () => {
    const onHover = vi.fn();
    const { unmount } = renderPane({ onHover });

    fireEvent.pointerMove(screen.getByRole('slider', { name: '썸네일 장면' }), { clientX: 10 });
    expect(onHover).toHaveBeenLastCalledWith(expect.any(Number));

    unmount();
    expect(onHover).toHaveBeenLastCalledWith(null);
  });

  it('뽑는 중인 타일은 스켈레톤 위에 시각을 남기고, 못 뽑은 타일은 시각만 있는 정적 자리다(POK-444)', () => {
    const [start, middle, end] = scenePicks(CUT_MS, null, null);
    // 중간 장면만 못 뽑았다. 나머지는 아직 뽑는 중이다
    const failedMs = middle!.offsetMs;
    renderPane({ frameFailed: (ms) => ms === failedMs });

    const tile = (pick: typeof start) =>
      screen.getByRole('button', { name: `${pick!.label} ${sceneLabel(pick!.offsetMs)}` });
    for (const pick of [start, end]) {
      expect(tile(pick).querySelector('[data-frame-loading]')).toHaveAttribute(
        'aria-hidden',
        'true',
      );
      expect(tile(pick)).toHaveTextContent(sceneLabel(pick!.offsetMs));
    }
    expect(tile(middle).querySelector('[data-frame-loading]')).toBeNull();
    expect(tile(middle)).toHaveTextContent(sceneLabel(failedMs));

    // 띠 칸도 같은 규칙: 못 뽑은 시각의 칸만 빈 칸이다
    const strip = screen.getByRole('slider', { name: '썸네일 장면' });
    const loadingTiles = stripTileTimes(CUT_MS).filter((ms) => ms !== failedMs).length;
    expect(strip.querySelectorAll('[data-frame-loading]')).toHaveLength(loadingTiles);
  });

  it('재료가 없으면(녹화 없음) 어느 타일도 로더가 아니다', () => {
    renderPane({ frameFailed: () => true });
    expect(document.querySelectorAll('[data-frame-loading]')).toHaveLength(0);
  });
});
