'use client';

import {
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type Ref,
} from 'react';
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
import { notFound, usePathname, useSearchParams } from 'next/navigation';
import { requestPlaybackAccess } from '@/api/clipEditor';
import { useToast } from '@/ui';
import { vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { fetchRecordingSpans } from '@/api/mediaPlayback';
import type { RecordedSource } from '@/features/player/useRecordedPlayback';
import { rebaseTimeline, recordingTimeline } from '@/features/player/recordingTimeline';
import { publishLiveData, resetLiveData, useLiveData } from './liveDataStore';
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
// 단, 화면 맨 위의 오프라인 목업 토글(?mock=offline)과 대시보드의 ?card=(홈에서 고른 카드, QA 2026-10-11)도
// 같은 훅을 읽어 라이브 page.tsx의 경계까지 화면 전체가 빠진다. 이 경계로 좁히려면 둘 다 훅 밖으로 옮겨야 한다.
// 지난 방송 상세(vod/[streamId])는 동적 경로라 프리렌더가 없어 그 page.tsx에는 경계가 없다. 그 경로를 정적으로
// 바꾸면(generateStaticParams) 거기에도 경계가 필요하다(리뷰 2라운드).
//
// 다만 지금 이 라우트의 프리렌더 HTML은 어차피 비어 있다 — AuthGuard가 hydrate 전에
// (dock) 서브트리를 통째로 null로 만들기 때문이다(.next/server/app/broadcast/livenow.html로 확인).
// 경계를 좁힌 이득은 AuthGuard가 서버에서도 그릴 수 있게 되는 시점에 생긴다.
// 이 주석을 LCP·SEO 근거로 쓰지 말 것.
/** 출입증을 만료 몇 분 전에 다시 받을지 */
const PLAYBACK_ACCESS_MARGIN_MS = 5 * 60_000;
/** 출입증 실패 뒤 다시 받기까지 */
const PLAYBACK_ACCESS_RETRY_MS = 60_000;
/** 만료 시각을 모를 때 다시 받는 간격 — 운영 쿠키 수명 60분보다 짧게 */
const PLAYBACK_ACCESS_DEFAULT_RENEW_MS = 50 * 60_000;

function LivePlayer({
  stream,
  viewersNote,
  broadcastStatus,
  displayStatus,
  startedAt,
  endedAt,
  ingestStreamId,
  uptimeSeconds,
  controllerRef,
  chatPanelOpen,
  onToggleChatPanel,
  startAtSeconds,
  onRecordedReady,
}: {
  stream: LiveStream;
  viewersNote: string;
  /** 명부가 답한 방송 상태. 녹화를 찾고 영상 주소를 고르는 데만 쓴다 */
  broadcastStatus: BroadcastStatus;
  /**
   * 플레이어 문구(배지·자리 표시)의 상태. 지난 방송 주소면 명부 답 전에도 끝난 방송이다(리뷰 3라운드). 녹화 찾기에 이것을 쓰면
   * 시작·종료 시각 없이 찾아 같은 스트림키의 앞뒤 방송 녹화가 섞인다
   */
  displayStatus: BroadcastStatus;
  /** 방송 시작·종료(epoch ms) — 같은 스트림키의 앞뒤 방송 녹화를 거른다 */
  startedAt: number | null;
  endedAt: number | null;
  /** 영상 경로의 키(POK-233). 모르면 null이고 방송 번호가 곧 영상 경로다 */
  ingestStreamId: string | null;
  uptimeSeconds: number | null;
  controllerRef: Ref<GlassPlayerController>;
  chatPanelOpen: boolean;
  onToggleChatPanel: () => void;
  /** 녹화가 서면 이 자리(방송 기준 초)부터 튼다. 녹화가 서기 전에 고른 시점이다 */
  startAtSeconds: number | null;
  /** 녹화 플레이어가 섰다. 시작 위치를 받았으니 부른 쪽이 비운다 */
  onRecordedReady: () => void;
}) {
  // env 미설정이면 null → GlassPlayer가 「영상 신호 없음」 자리 표시를 그린다.
  // 주소에 ?stream= 이 없으면 clip 명부에서 고른 방송(liveDataStore)의 영상을 튼다.
  const { streamId: liveStreamId, originMs } = useLiveData();
  // 라이브·녹화 주소는 영상 경로의 키로 만든다(POK-233) — 방송 번호가 회차 번호면 그 이름의 경로가 없다. 출입증·채팅은 방송 번호
  const mediaKey = ingestStreamId ?? liveStreamId;
  const src = useMediaSource(mediaKey);
  // 끝난 방송은 LL-HLS 주소가 닫힌다 — 녹화 재생 서버에서 다시보기를 튼다(있을 때만).
  const [recorded, setRecorded] = useState<RecordedSource | null>(null);
  useEffect(() => {
    setRecorded(null);
    publishLiveData({ timeBaseMs: null });
    if (broadcastStatus !== 'ended' || !mediaKey) return undefined;
    let alive = true;
    // 첫 구간 하나만 쓰면 송출이 한 번 끊긴 방송은 그 뒤를 못 보고, 앞 방송 녹화를 잡을 수도 있다(POK-253)
    void fetchRecordingSpans(mediaKey).then((spans) => {
      const found = recordingTimeline(spans, startedAt, endedAt);
      if (alive && found) {
        // 재생기의 0초를 서버 기준점(카드·조각 위치의 0초, POK-255)에 세운다 — 녹화 시작에 세우면 두 시작의 차만큼 카드를
        // 누른 자리와 영상이 어긋난다(POK-253). 서버가 모르면(null) 녹화 시작
        const timeline = originMs === null ? found : rebaseTimeline(found, originMs);
        setRecorded({ streamId: mediaKey, ...timeline });
        // 영상의 0초가 곧 카드 시점의 0초다 — 채팅도 이 기준으로 시점을 잡게 알린다
        publishLiveData({ timeBaseMs: timeline.startMs });
      }
    });
    return () => {
      alive = false;
    };
  }, [broadcastStatus, mediaKey, startedAt, endedAt, originMs]);
  useEffect(() => {
    if (recorded !== null) onRecordedReady();
  }, [recorded, onRecordedReady]);
  // 영상 출입증(POK-122) — 방송을 열 때 받고, 만료 5분 전에 다시 받는다. 쿠키 수명(운영 60분)이 지나면 새 조각이
  // 403이 되어 재생이 멈춘다(PR #200 codex P1). 실패하면 1분 뒤 다시 받는다. 서명 재료가 없는 서버(503)는
  // 출입증이 필요 없는 곳이라(로컬·dev) 다시 묻지 않는다. 실패해도 재생은 시도한다(로컬 media는 쿠키를 안 본다).
  useEffect(() => {
    if (!liveStreamId) return undefined;
    let alive = true;
    let timer: number | undefined;
    const ask = () => {
      void requestPlaybackAccess(liveStreamId).then((result) => {
        if (!alive || result.kind === 'not_needed') return;
        const wait =
          result.kind === 'failed'
            ? PLAYBACK_ACCESS_RETRY_MS
            : result.expiresAtMs === null
              ? PLAYBACK_ACCESS_DEFAULT_RENEW_MS
              : Math.max(
                  PLAYBACK_ACCESS_RETRY_MS,
                  result.expiresAtMs - Date.now() - PLAYBACK_ACCESS_MARGIN_MS,
                );
        timer = window.setTimeout(ask, wait);
      });
    };
    ask();
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [liveStreamId]);
  return (
    <GlassPlayer
      // 실재생 훅은 경과 시드를 마운트 때 한 번만 읽는다 — startedAt이 도착하면 한 번 다시 세운다
      key={uptimeSeconds === null ? 'clock-pending' : 'clock-known'}
      channelName={stream.channelName}
      viewersNote={viewersNote}
      broadcastStatus={displayStatus}
      // 라이브 주소는 방송 중일 때만 — 끝난 방송의 LL-HLS는 닫혀 있어 검은 화면과 오류만 남는다(PR #200 codex).
      // 끝난 방송은 녹화가 있으면 recorded로, 없으면 「지난 방송 영상」 자리를 그린다
      src={broadcastStatus === 'live' ? src : null}
      recorded={recorded}
      startAtSeconds={startAtSeconds}
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
  // 저장소는 화면 하나의 것이다. 다른 방송을 보던 화면에서 넘어오면 앞 방송의 제목·시청자 수·그래프가 남아 첫 그리기에 칠해지고,
  // 그 그리기의 효과들이 앞 방송 번호로 요청을 보냈다(리뷰 3·4라운드). 그래서 비운 뒤에야 그린다: 레이아웃 효과에서 바꾼 상태는
  // 칠하기 전에 다시 그려져 빈 첫 그림은 보이지 않는다. 같은 화면 안에서 방송이 바뀔 때는 저장소가 스스로 비운다
  const [storeCleared, setStoreCleared] = useState(false);
  useLayoutEffect(() => {
    resetLiveData();
    setStoreCleared(true);
  }, []);
  return storeCleared ? <LiveScreenBody /> : null;
}

function LiveScreenBody() {
  const { status } = useLiveStatusMockState();
  const selection = useLiveStreamSelection();
  const offline = status === 'offline' || (selection.resolved && selection.streamId === '');
  return offline ? <LiveOfflineScreen /> : <LiveDashboard />;
}

function LiveDashboard() {
  // 주소는 usePathname으로 읽는다: 넘어온 직후 첫 그리기의 window.location은 옛 주소다
  const vodRouteId = vodStreamIdFromPathname(usePathname() ?? '');
  const pastRoute = vodRouteId !== null;
  const { stream, highlights, chatVolume, chatWarning } = useLiveMockState(pastRoute);
  const { streamMeta, cardVisuals } = useLiveDetailsMockState();
  const { streamId, info } = useLiveData();
  const clock = useBroadcastClock(streamId);
  // 지난 방송 상세 주소로 들어왔는데 그 방송이 명부에 없으면(없는 번호·권한 회수) 404 화면으로 보낸다 —
  // 빈 대시보드를 보여 주지 않는다(PR #200 codex, 404 계약: README 「404 계약」)
  if (
    clock.missingStreamId !== null &&
    clock.missingStreamId === streamId &&
    vodRouteId === streamId
  )
    notFound();
  const playerRef = useRef<GlassPlayerController>(null);
  const { toast } = useToast();
  // 지난 방송 주소면 명부가 방송 중이라고 답하기 전까지 끝난 방송이다. 방송 번호를 정하기 전(offline)이나 명부 조회가
  // 늦거나 실패한 동안(unknown) 라이브 문구로 깜박이지 않는다(QA 2026-10-11 리뷰). 문구를 내는 곳은 전부 이것을 받는다
  const viewStatus = pastRoute && clock.status !== 'live' ? 'ended' : clock.status;
  const ended = viewStatus === 'ended';

  const uptimeSeconds = clock.uptimeSeconds;
  const uptimeLabel = uptimeSeconds === null ? null : formatUptime(uptimeSeconds);
  // 서버에 남길 길이 생길 때까지 준비 중이라고만 말한다(POK-257). 카드를 만들지 않는다
  const marking = useManualMarking();
  // 접으면 패널 자체가 사라지므로 되살릴 통로는 플레이어 상단 오버레이의 여는 버튼이다
  const [chatPanelOpen, setChatPanelOpen] = useState(true);
  const toggleChatPanel = useCallback(() => setChatPanelOpen((open) => !open), []);
  // 수집이 끊겼으면 새 채팅도 멈춘다 — 「수집 끊김」이라면서 메시지가 계속 쌓이면
  // 화면이 스스로 모순된다(ADR-011: 끊기면 자동 탐지를 정직하게 비활성화한다).
  const chat = useChatPanelMockState(chatPanelOpen && !chatWarning, pastRoute);
  const stats = useLiveStatsMockState(ended);

  // 시청자 수는 broadcast-info의 series에서 마지막으로 잰 값이다(수집기 PR-C 전에는 없다). 수집기는 목록에서 채널을 못 찾은
  // 회차를 빈 값으로 남기므로 마지막 관측이 비어도 그 앞의 값을 쓴다: 통계의 최고·평균과 같은 규칙이다(리뷰 3라운드). 끝난 방송은
  // 지금 보는 사람이 아니라 끝날 때 값이고(QA 2026-10-11 리뷰: 며칠 전 방송을 지금 1,842명이 보는 것처럼 읽혔다), 방송 정보를
  // 받기 전이면 「기록 없음」이라 단정하지 않는다. 끝난 방송은 방송 창으로 읽은 기록만 믿는다: 명부 답 전에 최근 창으로 읽은
  // 빈 기록은 끝난 방송의 기록이 아니다(리뷰 4라운드)
  const viewersLabel = useMemo(() => {
    const v = info?.series?.findLast((p) => p.viewers !== null)?.viewers ?? null;
    if (ended) {
      if (info?.scope !== 'broadcast') return '시청자 수 확인 중';
      return v === null ? '시청자 수 기록 없음' : `끝날 때 ${v.toLocaleString()}명 시청`;
    }
    return v === null ? '시청자 수 수집 전' : `${v.toLocaleString()}명 시청 중`;
  }, [info, ended]);

  // 카드의 타임라인 위치(posPercent)는 방송 경과 대비다 — 실경과를 넣어야 눈금이 맞는다
  const streamForCards = useMemo<LiveStream>(
    () => ({ ...stream, uptimeSeconds: uptimeSeconds ?? 0 }),
    [stream, uptimeSeconds],
  );

  // 필터 개수도 통계의 하이라이트 줄도 같은 카드 목록에서 센다. 어느 한쪽을 따로 세면 두 표기가 언젠가 어긋난다.
  //
  // 경과 표기 때문에 이 컴포넌트는 매초 다시 그려진다. 요약이 매번 새 객체면
  // 아래 패널들의 memo가 통째로 무력해지므로 여기서 정체성을 붙잡아 둔다.
  const highlightSummary = useMemo(() => {
    const manual = highlights.filter((card) => card.source === 'manual').length;
    return { total: highlights.length, auto: highlights.length - manual, manual };
  }, [highlights]);

  // 녹화는 방송을 연 뒤에 찾는다. 그 전에 고른 시점은 옮길 플레이어가 없어(자리 표시) 그냥 사라지고, 뒤늦게 선 녹화는
  // 처음부터 튼다. 그래서 플레이어가 못 옮긴 시점만 남겨 두었다가 녹화의 시작 위치로 넘긴다(QA 2026-10-11 실기동).
  // 녹화가 받으면 비운다: 남겨 두면 시크바로 옮긴 뒤 녹화가 다시 설 때 오래전 카드로 튄다
  const [pendingStart, setPendingStart] = useState<number | null>(null);
  // 라이브에서 고른 시점은 남기지 않는다. 라이브 영상(HLS)은 시작 위치를 읽지 않고 라이브 채팅은 재생 위치를 따르지 않아,
  // 남겨 두면 방송이 끝난 뒤 녹화는 처음부터인데 채팅만 그 카드에 묶이거나 오래전 카드로 튄다(리뷰 2·3라운드).
  // 명부 답 전(상태를 모름)에 고른 시점은 남긴다: 끝난 방송으로 밝혀지면 녹화가 그 시점부터 선다(리뷰 3라운드)
  const live = clock.status === 'live';
  /** @returns 플레이어가 그 자리로 옮겼나(영상이 없으면 false) */
  const seekTo = useCallback(
    (seconds: number) => {
      const moved = playerRef.current?.seekToUptime(seconds) ?? false;
      if (live) return moved;
      // 영상이 없어도 채팅 패널이 그 시점을 보여준다(지난 방송)
      publishLiveData({ playheadMs: seconds * 1000 });
      setPendingStart(moved ? null : seconds);
      return moved;
    },
    [live],
  );
  // 방송 중으로 밝혀지면 모르는 동안 고른 시점을 버린다(위와 같은 이유)
  useEffect(() => {
    if (!live) return;
    setPendingStart(null);
    publishLiveData({ playheadMs: null });
  }, [live]);
  const handleRecordedReady = useCallback(() => setPendingStart(null), []);

  // 홈 「최근 하이라이트」가 주소에 실은 카드(?card=). 카드가 오는 대로 한 번 그 시점으로 옮긴다. 사람이 먼저 고르면 그쪽이다.
  // window.location이 아니라 useSearchParams로 읽는다: Next는 다른 화면에서 넘어올 때 새 화면을 먼저 그리고 주소창은 그 뒤에
  // 바꿔서, 첫 그리기 때 주소창을 읽으면 옛 주소다(실기동에서 찾음)
  const linkedCardId = useSearchParams().get('card');
  /** 이미 옮겨 준(또는 사람이 다른 카드를 골라 접은) 주소의 카드 번호 */
  const linkedDoneRef = useRef<string | null>(null);
  useEffect(() => {
    if (linkedCardId === null || linkedDoneRef.current === linkedCardId) return;
    const card = highlights.find((h) => h.id === `card-${linkedCardId}`);
    const seconds = card ? parseClockLabel(card.timestamp) : null;
    if (seconds === null) return;
    linkedDoneRef.current = linkedCardId;
    seekTo(seconds);
  }, [highlights, linkedCardId, seekTo]);

  const handleSeek = useCallback(
    (timestamp: string) => {
      const seconds = parseClockLabel(timestamp);
      if (seconds === null) return;
      linkedDoneRef.current = linkedCardId;
      // 사람이 누른 것만 알린다. 주소의 카드로 옮길 때는 녹화를 찾는 중일 뿐이라 말하지 않는다(리뷰).
      // 채팅이 그 시점으로 가는 것은 지난 방송뿐이다: 라이브 채팅은 최신 그대로라 옮겼다고 하면 거짓이다(리뷰 2라운드)
      if (!seekTo(seconds))
        toast({
          tone: 'info',
          title: chat.followsPlayhead
            ? '영상은 아직 없어요 · 채팅을 그 시점으로 옮겼어요'
            : '영상은 아직 없어요',
        });
    },
    [linkedCardId, seekTo, toast, chat.followsPlayhead],
  );

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
                displayStatus={viewStatus}
                startedAt={clock.startedAt}
                endedAt={clock.endedAt}
                ingestStreamId={clock.ingestStreamId}
                uptimeSeconds={uptimeSeconds}
                controllerRef={playerRef}
                chatPanelOpen={chatPanelOpen}
                onToggleChatPanel={toggleChatPanel}
                startAtSeconds={pendingStart}
                onRecordedReady={handleRecordedReady}
              />
            </Suspense>
          </div>
          <StreamInfoBar
            stream={stream}
            meta={streamMeta}
            viewersLabel={viewersLabel}
            uptimeLabel={uptimeLabel}
            uptimeNote={
              ended
                ? '방송함'
                : viewStatus === 'live'
                  ? '스트리밍 중'
                  : viewStatus === 'unknown'
                    ? '확인 중'
                    : '방송 중이 아니에요'
            }
            metaNote={
              !info ? '방송 정보 확인 중' : ended ? '방송 정보 기록 없음' : '방송 정보 수집 전'
            }
            // 만드는 중 자리는 수동 마킹이 서버에 남을 때(POK-257) 쓴다
            pendingLabel={null}
            onMark={marking.mark}
          />
          <HighlightCardPanel
            highlights={highlights}
            stream={streamForCards}
            visuals={cardVisuals}
            pendingLabel={null}
            detectionPaused={chatWarning}
            ended={ended}
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
              mode={viewStatus}
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
        ended={ended}
      />
    </main>
  );
}
