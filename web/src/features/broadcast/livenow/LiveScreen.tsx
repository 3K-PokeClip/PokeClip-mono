'use client';

import { Suspense, useCallback, useRef, useState, type Ref } from 'react';
import styles from './LiveScreen.module.css';
import { GlassPlayer, type GlassPlayerController } from '@/features/player/GlassPlayer';
import { useMediaSource } from '@/features/player/mediaSource';
import { parseClockLabel } from '@/features/player/playerMath';
import { ChatPanel } from './ChatPanel';
import { HighlightCardPanel } from './HighlightCardPanel';
import { LiveStatsPanel } from './LiveStatsPanel';
import { StreamInfoBar } from './StreamInfoBar';
import { useChatPanelMockState } from './useChatPanelMockState';
import { useLiveDetailsMockState } from './useLiveDetailsMockState';
import { useLiveMockState, type LiveStream } from './useLiveMockState';
import { useLiveStatsMockState } from './useLiveStatsMockState';
import { useManualMarking } from './useManualMarking';

// 디자인 1b — 라이브 대시보드. 시안은 페이지 헤더 없이 콘텐츠부터 시작하고,
// 방송 정보를 영상 아래 줄에 둔다. 세로로 길게 쌓아 문서 스크롤로 내려가는 화면이라
// 높이를 뷰포트에 맞추지 않는다.

// useSearchParams(?stream=)는 프리렌더에서 가장 가까운 Suspense 경계까지 CSR로 전환한다 —
// 화면 전체가 아니라 플레이어만 빠지도록 여기서 분리하고 경계는 playerFrame 안에 둔다.
//
// 다만 지금 이 라우트의 프리렌더 HTML은 어차피 비어 있다 — AuthGuard가 hydrate 전에
// (dock) 서브트리를 통째로 null로 만들기 때문이다(.next/server/app/broadcast/livenow.html로 확인).
// 경계를 좁힌 이득은 AuthGuard가 서버에서도 그릴 수 있게 되는 시점에 생긴다.
// 이 주석을 LCP·SEO 근거로 쓰지 말 것.
function LivePlayer({
  stream,
  controllerRef,
  chatPanelOpen,
  onToggleChatPanel,
}: {
  stream: LiveStream;
  controllerRef: Ref<GlassPlayerController>;
  chatPanelOpen: boolean;
  onToggleChatPanel: () => void;
}) {
  // env 미설정이면 null → GlassPlayer가 시뮬레이션으로 폴백 (테스트 포함)
  const src = useMediaSource();
  return (
    <GlassPlayer
      channelName={stream.channelName}
      title={stream.title}
      viewersNote={`시청자 ${stream.viewers}`}
      src={src}
      embed
      simulationOptions={{ initialUptimeSeconds: stream.uptimeSeconds }}
      controllerRef={controllerRef}
      chatPanelOpen={chatPanelOpen}
      onToggleChatPanel={onToggleChatPanel}
    />
  );
}

export function LiveScreen() {
  const { stream, highlights, chatVolume, chatWarning } = useLiveMockState();
  const { streamMeta, cardVisuals } = useLiveDetailsMockState();
  const marking = useManualMarking(stream.uptimeLabel);
  const playerRef = useRef<GlassPlayerController>(null);
  // 접으면 패널 자체가 사라지므로 되살릴 통로는 플레이어 컨트롤의 토글 버튼이다
  const [chatPanelOpen, setChatPanelOpen] = useState(true);
  const toggleChatPanel = useCallback(() => setChatPanelOpen((open) => !open), []);
  const chat = useChatPanelMockState(chatPanelOpen);
  const stats = useLiveStatsMockState();

  // 찍어 만든 카드가 앞, 그다음이 감지된 카드 — 필터 개수도 통계의 하이라이트 줄도
  // 이 합친 목록에서 센다. 어느 한쪽을 따로 세면 두 표기가 언젠가 어긋난다.
  const cards = [...marking.manualCards, ...highlights];
  const manualCount = cards.filter((card) => card.source === 'manual').length;
  const highlightSummary = {
    total: cards.length,
    auto: cards.length - manualCount,
    manual: manualCount,
  };

  const handleSeek = useCallback((timestamp: string) => {
    const seconds = parseClockLabel(timestamp);
    if (seconds === null) return;
    playerRef.current?.seekToUptime(seconds);
  }, []);

  return (
    <main className={styles.container}>
      <div className={styles.grid}>
        <div className={styles.mainCol}>
          <div className={styles.playerFrame}>
            {/* 폴백은 플레이어와 같은 16:9 빈 블록 — 서스펜드 중에도 레이아웃이 흔들리지 않는다 */}
            <Suspense fallback={<div className={styles.playerFallback} aria-hidden />}>
              <LivePlayer
                stream={stream}
                controllerRef={playerRef}
                chatPanelOpen={chatPanelOpen}
                onToggleChatPanel={toggleChatPanel}
              />
            </Suspense>
          </div>
          <StreamInfoBar
            stream={stream}
            meta={streamMeta}
            pendingLabel={marking.pendingLabel}
            onMark={marking.mark}
          />
          <HighlightCardPanel
            highlights={cards}
            stream={stream}
            visuals={cardVisuals}
            pendingLabel={marking.pendingLabel}
            onSeek={handleSeek}
          />
        </div>
        {chatPanelOpen ? (
          <ChatPanel
            surges={chat.surges}
            messages={chat.messages}
            collectionWarning={chatWarning}
            onCollapse={toggleChatPanel}
          />
        ) : null}
      </div>
      {/* 전폭 — 스크롤로 내려와 만나는 자리다 */}
      <LiveStatsPanel
        chatVolume={chatVolume}
        viewerLine={stats.viewerLine}
        donations={stats.donations}
        categorySegments={stats.categorySegments}
        metrics={stats.metrics}
        highlightSummary={highlightSummary}
      />
    </main>
  );
}
