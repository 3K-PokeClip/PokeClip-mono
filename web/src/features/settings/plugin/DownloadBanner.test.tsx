import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it, vi } from 'vitest';
import { RELEASES_PAGE_URL, type PluginRelease } from '@/lib/pluginRelease/types';
import { DownloadBanner } from './DownloadBanner';
import type { PluginReleaseViewState } from './usePluginReleaseState';

const RELEASE: PluginRelease = {
  version: '0.2.0',
  tag: 'obs-plugin-v0.2.0',
  prerelease: false,
  publishedAt: '2026-10-22T03:00:00Z',
  releaseUrl: `${RELEASES_PAGE_URL}/tag/obs-plugin-v0.2.0`,
  guideUrl:
    'https://github.com/3K-PokeClip/PokeClip-mono/blob/obs-plugin-v0.2.0/obs-plugin/README.md#설치',
  assets: {},
};

describe('DownloadBanner: 설치 파일 정보 네 상태', () => {
  it('불러오는 중엔 누를 것이 없다 — 눌리지 않는 버튼을 잠깐 보이지 않는다', () => {
    render(<DownloadBanner state={{ kind: 'loading' }} onOpen={() => {}} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('받을 수 있으면 「플러그인 다운로드」가 창을 연다', () => {
    const onOpen = vi.fn();
    render(<DownloadBanner state={{ kind: 'ready', release: RELEASE }} onOpen={onOpen} />);
    fireEvent.click(screen.getByRole('button', { name: '플러그인 다운로드' }));
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('아직 없으면 문장만 있다 — 갈 곳이 없으니 링크·버튼을 두지 않는다', () => {
    render(<DownloadBanner state={{ kind: 'unavailable' }} onOpen={() => {}} />);
    expect(screen.getByText('아직 받을 수 있는 설치 파일이 없어요.')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('오류면 다시 시도와 GitHub Releases 새 창 링크를 준다', () => {
    const retry = vi.fn();
    render(<DownloadBanner state={{ kind: 'error', retry }} onOpen={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /다시 시도.*설치 파일 정보/ }));
    expect(retry).toHaveBeenCalledTimes(1);
    const github = screen.getByRole('link', { name: /GitHub에서 받기/ });
    expect(github).toHaveAttribute('href', RELEASES_PAGE_URL);
    expect(github).toHaveAttribute('target', '_blank');
    expect(github).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('어느 상태에서도 설치·연결 상태를 지어내지 않고, 접근성 위반이 없다', async () => {
    const states: PluginReleaseViewState[] = [
      { kind: 'loading' },
      { kind: 'ready', release: RELEASE },
      { kind: 'unavailable' },
      { kind: 'error', retry: () => {} },
    ];
    for (const state of states) {
      const { container, unmount } = render(<DownloadBanner state={state} onOpen={() => {}} />);
      expect(container).not.toHaveTextContent(/설치됨|연결됨/);
      expect(await axe(container)).toHaveNoViolations();
      unmount();
    }
  });
});
