import type { AnchorHTMLAttributes, ReactNode } from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionCard } from './ConnectionCard';
import type { HomeLinks } from './useHomeMockState';

vi.mock('next/link', () => ({
  default: ({
    children,
    ...rest
  }: { children?: ReactNode } & AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...rest}>{children}</a>
  ),
}));

const base: HomeLinks = {
  chzzk: { linked: false },
  soop: { linked: false },
  youtube: { linked: false },
  streamKeyIssued: true,
  pluginPaired: true,
};
const chzzkOn = { linked: true, status: 'ACTIVE' as const, channelName: '재환의 방송' };
const soopOn = { linked: true, status: 'ACTIVE' as const, nickname: '숲너구리' };

function channelRow(links: HomeLinks) {
  render(<ConnectionCard links={links} />);
  return screen.getByText('방송 채널').closest('li')!;
}

describe('ConnectionCard: 방송 채널 한 줄에 치지직·SOOP (POK-396)', () => {
  it('SOOP 줄을 따로 만들지 않는다: 줄은 방송 채널·플러그인·유튜브 셋', () => {
    render(<ConnectionCard links={{ ...base, soop: soopOn }} />);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    expect(screen.queryByText('SOOP', { exact: true })).not.toBeInTheDocument();
  });

  it('둘 다 연동이면 연결됨, 상세에 「치지직 · SOOP」', () => {
    const row = channelRow({ ...base, chzzk: chzzkOn, soop: soopOn });
    expect(row).toHaveTextContent('치지직 · SOOP');
    expect(row).toHaveTextContent('연결됨');
    expect(within(row).queryByRole('link')).not.toBeInTheDocument();
  });

  it('SOOP만 연동이어도 연결됨, 상세에 SOOP과 닉네임', () => {
    const row = channelRow({ ...base, soop: soopOn });
    expect(row).toHaveTextContent('SOOP · 숲너구리');
    expect(row).toHaveTextContent('연결됨');
  });

  it('치지직만 연동이면 지금 모양 그대로 「치지직 · 채널 이름」', () => {
    expect(channelRow({ ...base, chzzk: chzzkOn })).toHaveTextContent('치지직 · 재환의 방송');
  });

  it('SOOP이 BROKEN이면 그 줄에 끊김 문구와 「다시 연동」 링크', () => {
    const row = channelRow({ ...base, soop: { linked: false, status: 'BROKEN' } });
    expect(row).toHaveTextContent('SOOP 연동이 끊겼어요');
    expect(within(row).getByRole('link', { name: '다시 연동' })).toHaveAttribute(
      'href',
      '/settings/channels',
    );
  });

  it('SOOP 하나만 못 읽고 치지직이 미연동이면 확인 중: 연동을 권하지 않는다', () => {
    const row = channelRow({ ...base, soop: null });
    expect(row).toHaveTextContent('확인 중…');
    expect(within(row).queryByRole('link')).not.toBeInTheDocument();
  });

  it('하나를 못 읽어도 다른 하나가 연동이면 연결됨', () => {
    expect(channelRow({ ...base, chzzk: null, soop: soopOn })).toHaveTextContent('연결됨');
  });

  it('둘 다 미연동이면 「연동」 링크', () => {
    const row = channelRow(base);
    expect(row).toHaveTextContent('연결 안 됨');
    expect(within(row).getByRole('link', { name: '연동' })).toHaveAttribute(
      'href',
      '/settings/channels',
    );
  });
});

describe('ConnectionCard: OBS 플러그인 줄은 연결 시각으로 정한다 (POK-608·609)', () => {
  function pluginRow(links: HomeLinks) {
    render(<ConnectionCard links={links} />);
    return screen.getByText('OBS 플러그인').closest('li')!;
  }

  it('연결했으면 연결됨: 버튼이 없다', () => {
    const row = pluginRow(base);
    expect(row).toHaveTextContent('연결됨');
    expect(row).toHaveTextContent('연동 코드로 연결했어요');
    expect(within(row).queryByRole('link')).not.toBeInTheDocument();
  });

  it('코드만 받았으면 연결됨이 아니다: 남은 일과 「연결하기」', () => {
    const row = pluginRow({ ...base, pluginPaired: false });
    expect(row).not.toHaveTextContent('연결됨');
    expect(row).toHaveTextContent('코드를 OBS에 넣으면 연결돼요');
    expect(within(row).getByRole('link', { name: '연결하기' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
  });

  it('코드도 받지 않았으면 코드가 아니라 「설치하기」', () => {
    const row = pluginRow({ ...base, streamKeyIssued: false, pluginPaired: false });
    expect(within(row).getByRole('link', { name: '설치하기' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
    expect(within(row).queryByRole('link', { name: /코드/ })).not.toBeInTheDocument();
  });

  it('상태를 못 읽었으면 확인 중: 버튼을 내밀지 않는다', () => {
    const row = pluginRow({ ...base, streamKeyIssued: null, pluginPaired: null });
    expect(row).toHaveTextContent('확인 중…');
    expect(within(row).queryByRole('link')).not.toBeInTheDocument();
  });
});
