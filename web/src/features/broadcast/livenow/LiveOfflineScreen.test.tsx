import { act } from 'react';
import { render, screen } from '@testing-library/react';
import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';
import { LiveOfflineScreen } from '@/features/broadcast/livenow/LiveOfflineScreen';
import { ChannelRelationContext } from '@/features/channel/ChannelScope';

describe('LiveOfflineScreen', () => {
  it('시안 1b 오프라인 문구를 그대로 쓴다', () => {
    render(<LiveOfflineScreen />);

    expect(
      screen.getByRole('heading', { level: 1, name: '지금은 방송 중이 아니에요' }),
    ).toBeInTheDocument();
    expect(screen.getByText(/방송을 켜면 채팅·시청자 반응을 분석해/)).toBeInTheDocument();
    expect(screen.getByText(/하이라이트 카드를 자동으로 만들어 드려요\./)).toBeInTheDocument();
  });

  it('대시보드 대신 본문 랜드마크를 스스로 세운다', () => {
    // 방송 레이아웃은 랜드마크를 화면에 맡긴다 — 오프라인에서 빠지면 페이지에 main이 없다
    render(<LiveOfflineScreen />);

    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('다음 행동은 지난 방송과 플러그인 설정 둘뿐이다', () => {
    // 티켓의 「VOD에서 이어서 정리」는 시안 갱신으로 빠졌다 — 링크가 늘면 시안과 어긋난다
    render(<LiveOfflineScreen />);

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(screen.getByRole('link', { name: '지난 방송 보기' })).toHaveAttribute(
      'href',
      '/broadcast/vod',
    );
    expect(screen.getByRole('link', { name: '플러그인 설정' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
  });

  it('포키 이미지의 대체 텍스트가 비어 있지 않다', () => {
    render(<LiveOfflineScreen />);

    expect(screen.getByRole('img', { name: '라이브 신호가 꺼진 포키 캐릭터' })).toBeInTheDocument();
  });

  it('접근성 위반이 없다', async () => {
    // next/link가 마운트 뒤 비동기로 갱신한다 — act로 감싸야 경고가 안 샌다
    const { container } = render(<LiveOfflineScreen />);
    await act(async () => {
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('LiveOfflineScreen: 남의 채널(POK-390)', () => {
  it('「플러그인 설정」은 비활성 버튼이고 링크가 아니다: 지난 방송은 그대로 링크', () => {
    render(
      <ChannelRelationContext.Provider value="EDITOR">
        <LiveOfflineScreen />
      </ChannelRelationContext.Provider>,
    );

    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.getByRole('link', { name: '지난 방송 보기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '플러그인 설정' })).toBeDisabled();
  });

  it('내 채널이면 둘 다 링크다', () => {
    render(
      <ChannelRelationContext.Provider value="OWNER">
        <LiveOfflineScreen />
      </ChannelRelationContext.Provider>,
    );

    expect(screen.getByRole('link', { name: '플러그인 설정' })).toHaveAttribute(
      'href',
      '/settings/plugin',
    );
  });
});
