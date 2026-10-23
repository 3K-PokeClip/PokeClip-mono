import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { scenePicks } from './uploadInfo';
import { UploadThumbnailPane } from './UploadThumbnailPane';

// 썸네일 탭의 장면 띠(POK-381). 창 전체의 동작은 UploadInfoDialog.test.tsx가 잰다.

describe('UploadThumbnailPane 장면 띠', () => {
  it('띠가 사라지면 올린 자리를 비운다: 탭을 바꾸면 pointerleave가 안 와 옛 자리가 남았다(PR #301 2차 리뷰)', () => {
    const onHover = vi.fn();
    const { unmount } = renderWithProviders(
      <UploadThumbnailPane
        source="scene"
        sceneOffsetMs={6_200}
        file={null}
        previewUrl={null}
        cutLengthMs={12_400}
        picks={scenePicks(12_400, null, null)}
        frameOf={() => null}
        error={undefined}
        onSource={vi.fn()}
        onScene={vi.fn()}
        onFile={vi.fn()}
        onFileProblem={vi.fn()}
        onHover={onHover}
      />,
    );

    fireEvent.pointerMove(screen.getByRole('slider', { name: '썸네일 장면' }), { clientX: 10 });
    expect(onHover).toHaveBeenLastCalledWith(expect.any(Number));

    unmount();
    expect(onHover).toHaveBeenLastCalledWith(null);
  });
});
