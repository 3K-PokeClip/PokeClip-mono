import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/testProviders';
import { HighlightCardPanel } from './HighlightCardPanel';
import type { LiveStream } from './useLiveMockState';

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

function renderPanel(props: { detectionPaused: boolean; ended?: boolean }) {
  return renderWithProviders(
    <HighlightCardPanel
      highlights={[]}
      stream={STREAM}
      visuals={{}}
      pendingLabel={null}
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
