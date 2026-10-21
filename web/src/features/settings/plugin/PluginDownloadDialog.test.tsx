import { fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PluginRelease } from '@/lib/pluginRelease/types';
import { PluginDownloadDialog } from './PluginDownloadDialog';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', () => ({ track }));

const DL = 'https://github.com/3K-PokeClip/PokeClip-mono/releases/download/obs-plugin-v0.2.0';
const RELEASE: PluginRelease = {
  version: '0.2.0',
  tag: 'obs-plugin-v0.2.0',
  prerelease: false,
  publishedAt: '2026-10-22T03:00:00Z',
  releaseUrl: 'https://github.com/3K-PokeClip/PokeClip-mono/releases/tag/obs-plugin-v0.2.0',
  assets: {
    installer: {
      name: 'i.exe',
      url: `${DL}/pokeclip-obs-0.2.0-windows-x64-installer.exe`,
      sizeBytes: 31_457_280,
    },
    portable: {
      name: 'p.zip',
      url: `${DL}/pokeclip-obs-0.2.0-windows-x64.zip`,
      sizeBytes: 30_408_704,
    },
    pkg: {
      name: 'm.pkg',
      url: `${DL}/pokeclip-obs-0.2.0-macos-universal.pkg`,
      sizeBytes: 58_720_256,
    },
  },
};

function open(release: PluginRelease, detectedOs: 'windows' | 'macos' | 'unknown') {
  const onClose = vi.fn();
  const view = render(
    <PluginDownloadDialog open onClose={onClose} release={release} detectedOs={detectedOs} />,
  );
  return { ...view, onClose };
}

beforeEach(() => track.mockReset());

describe('PluginDownloadDialog: OS별 설치 파일 받기', () => {
  it('감지한 OS의 탭으로 열린다 — Mac이면 pkg, zip은 없다', () => {
    open(RELEASE, 'macos');
    expect(screen.getByRole('dialog', { name: '플러그인 다운로드' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'macOS' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('link', { name: /macOS 설치 파일, 버전 0\.2\.0/ })).toHaveAttribute(
      'href',
      RELEASE.assets.pkg!.url,
    );
    expect(screen.queryByRole('link', { name: '포터블 OBS용 zip' })).not.toBeInTheDocument();
  });

  it('Windows는 exe가 주 버튼이고 포터블 zip이 따라온다 — 체크섬·설치 안내 링크는 두지 않는다', () => {
    open(RELEASE, 'windows');
    const primary = screen.getByRole('link', { name: /Windows 설치 파일, 버전 0\.2\.0/ });
    // 파일은 같은 탭에서 받는다 — GitHub가 attachment로 줘서 페이지가 바뀌지 않는다
    expect(primary).toHaveAttribute('href', RELEASE.assets.installer!.url);
    expect(primary).not.toHaveAttribute('target');
    expect(screen.getByRole('link', { name: '포터블 OBS용 zip' })).toHaveAttribute(
      'href',
      RELEASE.assets.portable!.url,
    );
    expect(screen.queryByRole('link', { name: /체크섬|설치 안내/ })).not.toBeInTheDocument();
    const allVersions = screen.getByRole('link', { name: /모든 버전 보기/ });
    expect(allVersions).toHaveAttribute('target', '_blank');
    expect(allVersions).toHaveAttribute('rel', 'noopener noreferrer');
    expect(allVersions).toHaveTextContent('(새 창)');
    expect(screen.getByText(/위험한 파일 다운로드/)).toBeInTheDocument();
    expect(screen.getByText(/「독 › PokeClip」/)).toBeInTheDocument();
  });

  it('OS를 모르면 Windows 탭이고, 다른 탭으로 직접 옮길 수 있다', () => {
    open(RELEASE, 'unknown');
    expect(screen.getByRole('tab', { name: 'Windows' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('tab', { name: 'macOS' }));
    expect(screen.getByRole('tab', { name: 'macOS' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText(/확인되지 않은 개발자/)).toBeInTheDocument();
  });

  it('빠진 파일은 링크 대신 문장이고, 다른 판으로 채우지 않는다', () => {
    open({ ...RELEASE, assets: { installer: RELEASE.assets.installer } }, 'macos');
    expect(screen.getByText('macOS 설치 파일은 지금 받을 수 없어요.')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /macOS 설치 파일/ })).not.toBeInTheDocument();
  });

  it('누른 파일 종류를 센다 — 받은 수가 아니라 누른 수', () => {
    open(RELEASE, 'windows');
    fireEvent.click(screen.getByRole('link', { name: /Windows 설치 파일/ }));
    fireEvent.click(screen.getByRole('link', { name: '포터블 OBS용 zip' }));
    expect(track).toHaveBeenNthCalledWith(1, 'plugin_download_clicked', {
      os: 'windows',
      kind: 'installer',
    });
    expect(track).toHaveBeenNthCalledWith(2, 'plugin_download_clicked', {
      os: 'windows',
      kind: 'portable',
    });
  });

  it('닫기로 닫히고, 설치·연결 상태를 지어내지 않으며, 접근성 위반이 없다', async () => {
    const { onClose } = open(RELEASE, 'windows');
    const dialog = screen.getByRole('dialog');
    expect(dialog).not.toHaveTextContent(/설치됨|연결됨/);
    expect(await axe(document.body)).toHaveNoViolations();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(onClose).toHaveBeenCalled();
  });
});
