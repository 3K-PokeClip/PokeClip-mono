import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { HighlightCardPanel } from './HighlightCardPanel';
import type { CardListStatus, LiveHighlight, LiveStream } from './useLiveMockState';

const STREAM: LiveStream = {
  title: '새벽 랭크 올리기',
  platform: '치지직',
  channelName: '게임하는너구리',
  startedNote: '오후 7:12 시작',
  uptimeSeconds: 5043,
  uptimeLabel: '1:24:03',
  viewers: '1,842',
  editorName: '박편집',
};

function highlight(id: number, overrides: Partial<LiveHighlight> = {}): LiveHighlight {
  return {
    id: `card-${id}`,
    timestamp: '1:07:50',
    title: `채팅이 튄 순간 #${id}`,
    meta: '채팅 ×4.2 급증 · 42초 · 방금',
    status: 'scored',
    source: 'auto',
    ...overrides,
  };
}

function renderPanel(props: {
  detectionPaused: boolean;
  ended?: boolean;
  listStatus?: CardListStatus;
  highlights?: LiveHighlight[];
  hiddenHighlights?: LiveHighlight[];
}) {
  return renderWithProviders(
    <HighlightCardPanel
      highlights={[]}
      stream={STREAM}
      visuals={{}}
      pendingLabel={null}
      listStatus="loaded"
      onSeek={() => undefined}
      {...props}
    />,
  );
}

describe('HighlightCardPanel: 머리 안내', () => {
  it('수집이 끊겨 감지가 멈추면 멈췄다고만 말한다: 핫키로 남기는 길은 아직 없다(POK-119·257)', () => {
    renderPanel({ detectionPaused: true });
    expect(screen.getByText('자동 감지 멈춤 · 카드 클릭 = 시점 이동')).toBeInTheDocument();
    expect(screen.queryByText(/핫키/)).not.toBeInTheDocument();
  });

  it('끝난 방송에는 감지 상태를 말하지 않는다', () => {
    renderPanel({ detectionPaused: false, ended: true });
    expect(screen.getByText('카드 클릭 = 시점 이동')).toBeInTheDocument();
  });
});

describe('HighlightCardPanel: 빈 카드 줄', () => {
  it('카드가 하나도 없으면 빈 상태 카드로 아직 없다고 말한다', () => {
    renderPanel({ detectionPaused: false });
    expect(screen.getByText('아직 감지된 하이라이트가 없어요')).toBeInTheDocument();
    expect(
      screen.getByText('채팅 반응이 튀는 순간을 찾으면 여기에 카드가 실시간으로 쌓여요.'),
    ).toBeInTheDocument();
    // 빈 줄에 키보드 멈춤 자리를 남기지 않는다
    expect(screen.queryByRole('list', { name: '하이라이트 카드 목록' })).not.toBeInTheDocument();
  });

  it('목록을 받기 전에는 없다고 하지 않는다: 모르는 것은 없는 것이 아니다', () => {
    renderPanel({ detectionPaused: false, listStatus: 'loading' });
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('카드를 불러오는 중…');
    // 도는 표시가 있되 낭독은 문장 하나다: Spinner의 status는 숨겨 둔다
    expect(within(status).getByRole('status', { hidden: true })).toHaveAttribute(
      'aria-hidden',
      'true',
    );
    expect(screen.queryByText(/하이라이트가 없어요/)).not.toBeInTheDocument();
  });

  it('받기 전에 읽기가 실패하면 불러오는 중이라고 가리지 않고 못 읽었다고 말한다', () => {
    renderPanel({ detectionPaused: false, listStatus: 'failed' });
    expect(screen.getByRole('status')).toHaveTextContent(
      '카드를 불러오지 못했어요. 다시 시도하는 중…',
    );
    expect(screen.queryByText(/하이라이트가 없어요/)).not.toBeInTheDocument();
  });

  it('카드가 0장이어도 「수동」 칸에서는 자동 카드가 쌓인다고 하지 않는다', () => {
    renderPanel({ detectionPaused: false });

    fireEvent.click(screen.getByRole('button', { name: '수동 0' }));
    expect(screen.getByRole('status')).toHaveTextContent('수동으로 남긴 카드가 없어요.');
    expect(screen.queryByText(/쌓여요/)).not.toBeInTheDocument();

    // 「자동」 칸은 자동 카드가 쌓이는 곳이라 빈 상태 카드 그대로다
    fireEvent.click(screen.getByRole('button', { name: '자동 0' }));
    expect(screen.getByText('아직 감지된 하이라이트가 없어요')).toBeInTheDocument();
  });

  it('끝난 방송에는 「아직」이라고 하지 않는다: 기다려도 더 생기지 않는다', () => {
    renderPanel({ detectionPaused: false, ended: true });
    expect(screen.getByText('이 방송에서 감지된 하이라이트가 없어요')).toBeInTheDocument();
    expect(screen.queryByText(/아직/)).not.toBeInTheDocument();
    expect(screen.queryByText(/실시간/)).not.toBeInTheDocument();
  });

  it('감지가 멈췄으면 카드가 쌓인다고 하지 않는다', () => {
    renderPanel({ detectionPaused: true });
    expect(screen.getByText('채팅 수집이 끊겨 자동 감지가 멈춰 있어요.')).toBeInTheDocument();
    expect(screen.queryByText(/쌓여요/)).not.toBeInTheDocument();
  });

  it('필터로 빈 자리는 빈 상태 카드가 아니라 한 줄이다: 카드는 있다', () => {
    renderPanel({ detectionPaused: false, highlights: [highlight(1), highlight(2)] });

    fireEvent.click(screen.getByRole('button', { name: '수동 0' }));
    expect(screen.getByRole('status')).toHaveTextContent('수동으로 남긴 카드가 없어요.');
    expect(screen.queryByText(/감지된 하이라이트가 없어요/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '자동 2' }));
    expect(screen.getByText('채팅이 튄 순간 #1')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('자동 칩이 비면 자동 감지한 카드가 없다고 말한다', () => {
    renderPanel({
      detectionPaused: false,
      highlights: [highlight(1, { source: 'manual', status: 'manual' })],
    });
    fireEvent.click(screen.getByRole('button', { name: '자동 0' }));
    expect(screen.getByRole('status')).toHaveTextContent('자동 감지한 카드가 없어요.');
  });

  it('숨김 칩이 비면 숨긴 카드가 없다고 말한다', () => {
    renderPanel({ detectionPaused: false, highlights: [highlight(1)] });
    fireEvent.click(screen.getByRole('button', { name: '숨김 0' }));
    expect(screen.getByRole('status')).toHaveTextContent('숨긴 카드가 없어요.');
  });

  it('전부 숨겼으면 아직 없다고 하지 않고 「숨김」에 있다고 알려 준다', () => {
    renderPanel({
      detectionPaused: false,
      hiddenHighlights: [highlight(1, { hidden: true }), highlight(2, { hidden: true })],
    });
    expect(screen.getByRole('status')).toHaveTextContent(
      '보이는 카드가 없어요. 숨긴 카드는 「숨김」에서 볼 수 있어요.',
    );
    expect(screen.queryByText(/감지된 하이라이트가 없어요/)).not.toBeInTheDocument();
  });
});
