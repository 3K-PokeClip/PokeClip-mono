'use client';

import { Suspense, useCallback, useEffect, useMemo, useRef, useState, type Ref } from 'react';
import clsx from 'clsx';
import styles from './LiveScreen.module.css';
import { GlassPlayer, type GlassPlayerController } from '@/features/player/GlassPlayer';
import { useMediaSource } from '@/features/player/mediaSource';
import { formatUptime, parseClockLabel } from '@/features/player/playerMath';
import { ChatPanel } from './ChatPanel';
import { HighlightCardPanel } from './HighlightCardPanel';
import { LiveOfflineScreen } from './LiveOfflineScreen';
import { LiveStatsPanel } from './LiveStatsPanel';
import { StreamInfoBar } from './StreamInfoBar';
import { requestPlaybackAccess } from '@/api/clipEditor';
import { fetchRecordingSpans } from '@/api/mediaPlayback';
import type { RecordedSource } from '@/features/player/useRecordedPlayback';
import { publishLiveData, useLiveData } from './liveDataStore';
import { useBroadcastClock, type BroadcastStatus } from './useBroadcastClock';
import { useChatPanelMockState } from './useChatPanelMockState';
import { useLiveDetailsMockState } from './useLiveDetailsMockState';
import { useLiveMockState, type LiveStream } from './useLiveMockState';
import { useLiveStatsMockState } from './useLiveStatsMockState';
import { useLiveStatusMockState } from './useLiveStatusMockState';
import { useLiveStreamSelection } from './useLiveStreamSelection';
import { useManualMarking } from './useManualMarking';

// 디자인 1b — 라이브 대시보드. 시안은 페이지 헤더 없이 콘텐츠부터 시작하고,
// 방송 정보를 영상 아래 줄에 둔다. 세로로 길게 쌓아 문서 스크롤로 내려가는 화면이라
// 높이를 뷰포트에 맞추지 않는다 — 채팅만 예외로 화면 높이에 sticky(시안 갱신, POK-239).
//
// 시계의 주인은 플레이어가 아니라 clip의 방송 명부다(useBroadcastClock, POK-251). 영상이 없어도
// 경과·마킹 시각이 흘러야 해서, 경과는 방송 시작 시각(startedAt)에서 센다.

// useSearchParams(?stream=)는 프리렌더에서 가장 가까운 Suspense 경계까지 CSR로 전환한다 —
// 화면 전체가 아니라 플레이어만 빠지도록 여기서 분리하고 경계는 playerFrame 안에 둔다.
// 단, 오프라인 목업 토글(?mock=offline)이 화면 맨 위에서 같은 훅을 읽는 동안에는 page.tsx의
// 경계까지 화면 전체가 빠진다 — 토글이 실제 방송 상태 조회로 바뀌면 이 경계가 다시 뜻을 갖는다.
//
// 다만 지금 이 라우트의 프리렌더 HTML은 어차피 비어 있다 — AuthGuard가 hydrate 전에
// (dock) 서브트리를 통째로 null로 만들기 때문이다(.next/server/app/broadcast/livenow.html로 확인).
// 경계를 좁힌 이득은 AuthGuard가 서버에서도 그릴 수 있게 되는 시점에 생긴다.
// 이 주석을 LCP·SEO 근거로 쓰지 말 것.
function LivePlayer({
  stream,
  viewersNote,
  broadcastStatus,
  uptimeSeconds,
  controllerRef,
  chatPanelOpen,
  onToggleChatPanel,
}: {
  stream: LiveStream;
  viewersNote: string;
  broadcastStatus: BroadcastStatus;
  uptimeSeconds: number | null;
  controllerRef: Ref<GlassPlayerController>;
  chatPanelOpen: boolean;
  onToggleChatPanel: () => void;
}) {
  // env 미설정이면 null → GlassPlayer가 「영상 신호 없음」 자리 표시를 그린다.
  // 주소에 ?stream= 이 없으면 clip 명부에서 고른 방송(liveDataStore)의 영상을 튼다.
  const { streamId: liveStreamId } = useLiveData();
  const src = useMediaSource(liveStreamId);
  // 끝난 방송은 LL-HLS 주소가 닫힌다 — 녹화 재생 서버에서 다시보기를 튼다(있을 때만).
  const [recorded, setRecorded] = useState<RecordedSource | null>(null);
  useEffect(() => {
    setRecorded(null);
    if (broadcastStatus !== 'ended' || !liveStreamId) return undefined;
    let alive = true;
    void fetchRecordingSpans(liveStreamId).then((spans) => {
      const first = spans[0];
      if (alive && first) setRecorded({ streamId: liveStreamId, ...first });
    });
    return () => {
      alive = false;
    };
  }, [broadcastStatus, liveStreamId]);
  // 영상 출입증(POK-122) — 방송을 열 때 한 번 받아 둔다. 실패해도 재생은 시도한다(로컬 media는 쿠키를 안 본다).
  useEffect(() => {
    if (liveStreamId) void requestPlaybackAccess(liveStreamId);
  }, [liveStreamId]);
  return (
    <GlassPlayer
      // 실재생 훅은 경과 시드를 마운트 때 한 번만 읽는다 — startedAt이 도착하면 한 번 다시 세운다
      key={uptimeSeconds === null ? 'clock-pending' : 'clock-known'}
      channelName={stream.channelName}
      viewersNote={viewersNote}
      broadcastStatus={broadcastStatus}
      src={src}
      recorded={recorded}
      embed
      simulationOptions={{ initialUptimeSeconds: uptimeSeconds ?? 0 }}
      controllerRef={controllerRef}
      chatPanelOpen={chatPanelOpen}
      onToggleChatPanel={onToggleChatPanel}
    />
  );
}

// 방송 상태로 먼저 가른다 — 오프라인에서는 대시보드 훅(F8 수동 마킹 리스너·채팅 타이머)이
// 아예 돌지 않아야 하므로, 분기를 그 훅들보다 위인 컴포넌트 경계에서 한다.
// 꺼짐은 둘이다: 시안 확인용 토글(?mock=offline, 개발 전용)과 실제로 방송 중인 것이 없을 때.
export function LiveScreen() {
  const { status } = useLiveStatusMockState();
  const selection = useLiveStreamSelection();
  const offline = status === 'offline' || (selection.resolved && selection.streamId === '');
  return offline ? <LiveOfflineScreen /> : <LiveDashboard />;
}

function LiveDashboard() {
  const { stream, highlights, chatVolume, chatWarning } = useLiveMockState();
  const { streamMeta, cardVisuals } = useLiveDetailsMockState();
  const { streamId, info } = useLiveData();
  const clock = useBroadcastClock(streamId);
  const playerRef = useRef<GlassPlayerController>(null);

  // 표기는 매초 다시 그려지고(clock이 상태), 「지금 몇 시인가」를 묻는 마킹은 ref로 읽는다 —
  // 둘 다 같은 시계에서 나와야 어긋나지 않는다.
  const uptimeSeconds = clock.uptimeSeconds;
  const uptimeLabel = uptimeSeconds === null ? null : formatUptime(uptimeSeconds);
  const uptimeRef = useRef(0);
  useEffect(() => {
    uptimeRef.current = uptimeSeconds ?? 0;
  }, [uptimeSeconds]);
  const readMarkTimestamp = useCallback(() => formatUptime(uptimeRef.current), []);
  const marking = useManualMarking(readMarkTimestamp);
  // 접으면 패널 자체가 사라지므로 되살릴 통로는 플레이어 상단 오버레이의 여는 버튼이다
  const [chatPanelOpen, setChatPanelOpen] = useState(true);
  const toggleChatPanel = useCallback(() => setChatPanelOpen((open) => !open), []);
  // 수집이 끊겼으면 새 채팅도 멈춘다 — 「수집 끊김」이라면서 메시지가 계속 쌓이면
  // 화면이 스스로 모순된다(ADR-011: 끊기면 자동 탐지를 정직하게 비활성화한다).
  const chat = useChatPanelMockState(chatPanelOpen && !chatWarning);
  const stats = useLiveStatsMockState();

  // 시청자 수는 broadcast-info의 series 마지막 값 — 수집기 PR-C 전에는 없다
  const viewersLabel = useMemo(() => {
    const last = info?.series?.length ? info.series[info.series.length - 1] : null;
    const v = last?.viewers ?? null;
    return v === null ? '시청자 수 수집 전' : `${v.toLocaleString()}명 시청 중`;
  }, [info]);

  // 카드의 타임라인 위치(posPercent)는 방송 경과 대비다 — 실경과를 넣어야 눈금이 맞는다
  const streamForCards = useMemo<LiveStream>(
    () => ({ ...stream, uptimeSeconds: uptimeSeconds ?? 0 }),
    [stream, uptimeSeconds],
  );

  // 찍어 만든 카드가 앞, 그다음이 감지된 카드 — 필터 개수도 통계의 하이라이트 줄도
  // 이 합친 목록에서 센다. 어느 한쪽을 따로 세면 두 표기가 언젠가 어긋난다.
  //
  // 경과 표기 때문에 이 컴포넌트는 매초 다시 그려진다 — 목록·요약이 매번 새 객체면
  // 아래 패널들의 memo가 통째로 무력해지므로 여기서 정체성을 붙잡아 둔다.
  const cards = useMemo(
    () =>
      [...marking.manualCards, ...highlights].map((card, index) =>
        // 강조는 「방금」이라는 뜻이라 맨 앞 하나만 남긴다 — 마킹할수록 쌓이면 아무 말도 안 하게 된다
        index === 0 || !card.emphasized ? card : { ...card, emphasized: false },
      ),
    [marking.manualCards, highlights],
  );
  const highlightSummary = useMemo(() => {
    const manual = cards.filter((card) => card.source === 'manual').length;
    return { total: cards.length, auto: cards.length - manual, manual };
  }, [cards]);

  const handleSeek = useCallback((timestamp: string) => {
    const seconds = parseClockLabel(timestamp);
    if (seconds === null) return;
    // 영상이 없어도 채팅 패널이 그 시점을 보여준다(지난 방송)
    publishLiveData({ playheadMs: seconds * 1000 });
    playerRef.current?.seekToUptime(seconds);
  }, []);

  return (
    <main className={styles.container}>
      <div className={clsx(styles.grid, !chatPanelOpen && styles.gridSolo)}>
        <div className={styles.mainCol}>
          <div className={styles.playerFrame}>
            {/* 폴백은 플레이어와 같은 16:9 빈 블록 — 서스펜드 중에도 레이아웃이 흔들리지 않는다 */}
            <Suspense fallback={<div className={styles.playerFallback} aria-hidden />}>
              <LivePlayer
                stream={stream}
                viewersNote={viewersLabel}
                broadcastStatus={clock.status}
                uptimeSeconds={uptimeSeconds}
                controllerRef={playerRef}
                chatPanelOpen={chatPanelOpen}
                onToggleChatPanel={toggleChatPanel}
              />
            </Suspense>
          </div>
          <StreamInfoBar
            stream={stream}
            meta={streamMeta}
            viewersLabel={viewersLabel}
            uptimeLabel={uptimeLabel}
            uptimeNote={clock.status === 'ended' ? '방송함' : '스트리밍 중'}
            pendingLabel={marking.pendingLabel}
            onMark={marking.mark}
          />
          <HighlightCardPanel
            highlights={cards}
            stream={streamForCards}
            visuals={cardVisuals}
            pendingLabel={marking.pendingLabel}
            detectionPaused={chatWarning}
            onSeek={handleSeek}
          />
        </div>
        {/* 칸이 행 높이를 받고 그 안에서 채팅이 sticky로 붙는다 — 칸의 역할은 CSS .chatCol 주석.
            접으면 칸째 빠진다(.gridSolo가 트랙도 걷는다). */}
        {chatPanelOpen ? (
          <div className={styles.chatCol}>
            <ChatPanel
              surges={chat.surges}
              messages={chat.messages}
              ratePerMinute={chat.ratePerMinute}
              mode={clock.status}
              collectionWarning={chatWarning}
              onCollapse={toggleChatPanel}
            />
          </div>
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
