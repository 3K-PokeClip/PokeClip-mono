import { fireEvent, render, screen, within } from '@testing-library/react';
import { axe } from 'jest-axe';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PLUGIN_DOWNLOADS, PLUGIN_VERSION, RELEASES_PAGE_URL } from '@/lib/pluginDownload';
import { PluginDownloadDialog } from './PluginDownloadDialog';

const { track } = vi.hoisted(() => ({ track: vi.fn() }));
vi.mock('@/lib/analytics', () => ({ track }));

const VERSION_RE = PLUGIN_VERSION.replace(/\./g, '\\.');

function open(detectedOs: 'windows' | 'macos' | 'unknown') {
  const onClose = vi.fn();
  const view = render(<PluginDownloadDialog open onClose={onClose} detectedOs={detectedOs} />);
  return { ...view, onClose };
}

beforeEach(() => track.mockReset());

describe('PluginDownloadDialog: OS별 설치 파일 받기', () => {
  it('감지한 OS의 탭으로 열린다 — Mac이면 pkg', () => {
    open('macos');
    expect(screen.getByRole('dialog', { name: '플러그인 다운로드' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'macOS' })).toHaveAttribute('aria-selected', 'true');
    expect(
      screen.getByRole('link', { name: new RegExp(`macOS 설치 파일, 버전 ${VERSION_RE}`) }),
    ).toHaveAttribute('href', PLUGIN_DOWNLOADS.macos.url);
  });

  it('Windows는 exe 하나만 받는다 — 포터블 zip·체크섬·설치 안내 링크는 두지 않는다', () => {
    open('windows');
    const primary = screen.getByRole('link', {
      name: new RegExp(`Windows 설치 파일, 버전 ${VERSION_RE}`),
    });
    // 파일은 같은 탭에서 받는다 — GitHub가 attachment로 줘서 페이지가 바뀌지 않는다
    expect(primary).toHaveAttribute('href', PLUGIN_DOWNLOADS.windows.url);
    expect(primary).not.toHaveAttribute('target');
    expect(screen.queryByRole('link', { name: /zip|체크섬|설치 안내/ })).not.toBeInTheDocument();
    const allVersions = screen.getByRole('link', { name: /모든 버전 보기/ });
    expect(allVersions).toHaveAttribute('href', RELEASES_PAGE_URL);
    expect(allVersions).toHaveAttribute('target', '_blank');
    expect(allVersions).toHaveAttribute('rel', 'noopener noreferrer');
    expect(allVersions).toHaveTextContent('(새 창)');
    expect(screen.getByText(/위험한 파일 다운로드/)).toBeInTheDocument();
    expect(screen.getByText(/「독 › PokeClip」/)).toBeInTheDocument();
  });

  it('OS를 모르면 Windows 탭이고, 다른 탭으로 직접 옮길 수 있다', () => {
    open('unknown');
    expect(screen.getByRole('tab', { name: 'Windows' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('tab', { name: 'macOS' }));
    expect(screen.getByRole('tab', { name: 'macOS' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText(/그래도 열기/)).toBeInTheDocument();
  });

  it('실제 하는 순서대로 세 단계 — 버튼은 1단계, 서명 경고는 2단계 안에 있다', () => {
    open('windows');
    const panel = within(screen.getByRole('tabpanel'));
    // Safari는 list-style: none 목록의 의미를 벗긴다 — 명시한 role이 남아 있어야 VoiceOver가 순서를 읽는다
    expect(panel.getByRole('list')).toHaveAttribute('role', 'list');
    const [download, install, pair] = panel.getAllByRole('listitem');
    expect(download).toContainElement(screen.getByRole('link', { name: /Windows 설치 파일/ }));
    expect(install).toHaveTextContent(/OBS를 끄고 받은 파일을 실행합니다/);
    expect(install).toHaveTextContent(/추가 정보 › 실행/);
    expect(pair).toHaveTextContent(/「독 › PokeClip」에 연동 코드를 넣습니다/);
  });

  it('누른 파일 종류를 센다 — 받은 수가 아니라 누른 수', () => {
    open('windows');
    fireEvent.click(screen.getByRole('link', { name: /Windows 설치 파일/ }));
    fireEvent.click(screen.getByRole('tab', { name: 'macOS' }));
    fireEvent.click(screen.getByRole('link', { name: /macOS 설치 파일/ }));
    expect(track).toHaveBeenNthCalledWith(1, 'plugin_download_clicked', {
      os: 'windows',
      kind: 'installer',
    });
    expect(track).toHaveBeenNthCalledWith(2, 'plugin_download_clicked', {
      os: 'macos',
      kind: 'pkg',
    });
  });

  it('닫기로 닫히고, 설치·연결 상태를 지어내지 않으며, 접근성 위반이 없다', async () => {
    const { onClose } = open('windows');
    const dialog = screen.getByRole('dialog');
    expect(dialog).not.toHaveTextContent(/설치됨|연결됨/);
    expect(await axe(document.body)).toHaveNoViolations();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(onClose).toHaveBeenCalled();
  });
});
