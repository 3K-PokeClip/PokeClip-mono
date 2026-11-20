import { existsSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { INSTALL_VIDEO, InstallVideoCard } from './InstallVideoCard';

// web/public — 이 파일은 web/src/features/settings/plugin 에 있다
const PUBLIC = join(__dirname, '..', '..', '..', '..', 'public');

describe('InstallVideoCard — 설치 과정 영상 (POK-615)', () => {
  it('누르기 전에는 영상을 받지 않고 표지만 보인다 — 자동 재생도 없다', () => {
    render(<InstallVideoCard />);

    const region = screen.getByRole('region', { name: '설치 과정 영상' });
    const video = region.querySelector('video');
    expect(video).not.toBeNull();
    expect(video).toHaveAttribute('controls');
    // 3MB를 화면에 들어온 모든 회원이 받지 않게 — 누를 때 받는다
    expect(video).toHaveAttribute('preload', 'none');
    // 소리가 있는 영상이다
    expect(video).not.toHaveAttribute('autoplay');
    expect(video).toHaveAttribute('poster', INSTALL_VIDEO.poster);
    const source = video?.querySelector('source');
    expect(source).toHaveAttribute('src', INSTALL_VIDEO.src);
    expect(source).toHaveAttribute('type', 'video/mp4');
  });

  it('영상과 표지 파일이 공개 폴더에 실제로 있다 — 주소만 바꾸고 파일을 안 옮기면 화면이 빈다', () => {
    for (const path of [INSTALL_VIDEO.src, INSTALL_VIDEO.poster]) {
      const file = join(PUBLIC, path);
      expect(existsSync(file), path).toBe(true);
      expect(statSync(file).size, path).toBeGreaterThan(1000);
    }
    // 누른 회원이 받는 크기다 — 다시 찍을 때 불어나지 않게 10MB로 묶는다
    expect(statSync(join(PUBLIC, INSTALL_VIDEO.src)).size).toBeLessThan(10 * 1024 * 1024);
  });
});
