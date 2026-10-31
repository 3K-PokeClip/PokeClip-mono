'use client';

import {
  memo,
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
import type { PlayerMark } from '@/features/player/PlayerControls';
import { useMediaSource } from '@/features/player/mediaSource';
import { formatUptime, parseClockLabel } from '@/features/player/playerMath';
import { ChatPanel } from './ChatPanel';
import { HighlightCardPanel } from './HighlightCardPanel';
import { LinkLockAnnouncer, LockedChatPanel, LockedStatsPanel } from './LinkLockedPanels';
import { LiveOfflineScreen } from './LiveOfflineScreen';
import { NewBroadcastBand } from './NewBroadcastBand';
import { LiveScreenSkeleton } from './LiveScreenSkeleton';
import { LiveStatsPanel, type TimelineCard } from './LiveStatsPanel';
import { MARK_HOTKEY } from './markKey';
import { StreamInfoBar } from './StreamInfoBar';
import { notFound, usePathname, useSearchParams } from 'next/navigation';
import { ensureLiveAccess, takePlaybackDrop } from '@/api/playbackAccess';
import { track } from '@/lib/analytics';
import { useToast } from '@/ui';
import { vodStreamIdFromPathname } from '@/features/broadcast/streamSelection';
import { lookupRecordingSpans, playbackConfigured } from '@/api/mediaPlayback';
import type { LivePosition } from '@/features/player/useHlsPlayback';
import type { RecordedSource } from '@/features/player/useRecordedPlayback';
import { rebaseTimeline, recordingTimeline } from '@/features/player/recordingTimeline';
import { nextLiveFollow } from './chatReplay';
import {
  missingWord,
  publishLiveData,
  resetLiveData,
  timeBaseOf,
  useLiveField,
} from './liveDataStore';
import { useBroadcastClock, type BroadcastStatus } from './useBroadcastClock';
import { chatFollowsPlayhead, useChatPanelMockState } from './useChatPanelMockState';
import { useLiveDetailsMockState } from './useLiveDetailsMockState';
import { useLiveMockState, type LiveStream } from './useLiveMockState';
import { useLiveStatsMockState } from './useLiveStatsMockState';
import { useLiveStatusMockState } from './useLiveStatusMockState';
import { useLiveStreamSelection, type Reselect } from './useLiveStreamSelection';
import { useLiveSession } from './useLiveSession';
import { LINK_LOCK_WORD, useLinkLock, type LinkLock } from './linkLock';
import { useManualMarking } from './useManualMarking';
import { infoWaitOver } from '../infoWait';

/** 카드 눈금에 넘기는 라이브 경과의 단위(초) */
const CARD_UPTIME_STEP_S = 15;

// 디자인 1b — 라이브 대시보드. 시안은 페이지 헤더 없이 콘텐츠부터 시작하고,
// 방송 정보를 영상 아래 줄에 둔다. 세로로 길게 쌓아 문서 스크롤로 내려가는 화면이라
// 높이를 뷰포트에 맞추지 않는다 — 채팅만 예외로 화면 높이에 sticky(시안 갱신, POK-239).
//
// 시계의 주인은 플레이어가 아니라 clip의 방송 명부다(useBroadcastClock, POK-251). 영상이 없어도
// 경과·마킹 시각이 흘러야 해서, 경과는 방송 시작 시각(startedAt)에서 센다.

// useSearchParams(?stream=)는 프리렌더에서 가장 가까운 Suspense 경계까지 CSR로 전환한다 —
// 화면 전체가 아니라 플레이어만 빠지도록 여기서 분리하고 경계는 playerFrame 안에 둔다.
// 단, 화면 맨 위의 오프라인 목업 토글(?mock=offline)과 대시보드의 ?card=(주소로 고른 카드, QA 2026-10-11)도
// 같은 훅을 읽어 라이브 page.tsx의 경계까지 화면 전체가 빠진다. 이 경계로 좁히려면 둘 다 훅 밖으로 옮겨야 한다.
// 지난 방송 상세(vod/[streamId])는 동적 경로라 프리렌더가 없어 그 page.tsx에는 경계가 없다. 그 경로를 정적으로
// 바꾸면(generateStaticParams) 거기에도 경계가 필요하다(리뷰 2라운드).
//
// 다만 지금 이 라우트의 프리렌더 HTML은 어차피 비어 있다 — AuthGuard가 hydrate 전에
// (dock) 서브트리를 통째로 null로 만들기 때문이다(.next/server/app/broadcast/livenow.html로 확인).
// 경계를 좁힌 이득은 AuthGuard가 서버에서도 그릴 수 있게 되는 시점에 생긴다.
// 이 주석을 LCP·SEO 근거로 쓰지 말 것.
/** 경과 뒤에 붙는 말. 경과를 모르면 이 말만 선다(지난 방송 주소는 명부 답 전에도 「방송함」이다) */
const UPTIME_NOTE: Record<BroadcastStatus, string> = {
  live: '스트리밍 중',
  ended: '방송함',
  unknown: '확인 중',
  offline: '방송 중이 아니에요',
};

/**
 * 녹화를 찾지 못했을 때 다시 묻기까지. 지난 방송 목록이 녹화를 다시 확인하는 주기(LIST_POLL_MS)와 같고, 목록처럼 「있음」만
 * 굳힌다: 녹화가 늦게 잡히거나 재생 서버가 녹화 폴더를 못 읽어도(400) 「없음」으로 오는데, 굳히면 목록은 「다시 보기 가능」이
 * 된 방송을 이 화면만 계속 「찾지 못했어요」라 했다(리뷰 7라운드)
 */
const RECORDING_RETRY_MS = 60_000;
/**
 * 지난 방송 채팅이 녹화를 찾는 동안 기다리는 한도. 넘으면 방송 끝을 보이고, 늦게 찾으면 그 재생 위치를 따라간다. 녹화 조회 자체는
 * 느린 재생 서버도 기다리게 더 오래 둔다(lookupRecordingSpans 30초): 조회를 10초에 끊었을 때는 느리게라도 답하는 서버를 늘
 * 묻지 못한 것으로 봐 녹화를 못 틀었다(PR #230)
 */
const CHAT_WAIT_MS = 10_000;

/** 녹화를 찾은 결과: 확인 중 · 없음 · 물을 수 없음(재생 서버가 없거나 조회 실패) · 찾음 */
type RecordingLookup =
  | { kind: 'pending' }
  | { kind: 'none' }
  | { kind: 'unavailable' }
  | { kind: 'found'; source: RecordedSource };

/**
 * 녹화가 서서 재생 위치를 알려 올 수 있나(찾는 중이거나 찾았다). 영상 자리 아랫줄(「확인하고 있어요」)과 지난 방송 채팅의 기다림이
 * 이 판정 하나를 쓴다: 따로 쓰면 조회 결과가 하나 늘 때 둘이 갈린다(리뷰 2라운드). 채팅은 찾는 중을 CHAT_WAIT_MS까지만 기다린다
 */
function recordingMayPlay(kind: RecordingLookup['kind']): boolean {
  return kind === 'pending' || kind === 'found';
}

/**
 * 출입증을 이 간격으로 다시 본다. 받아 둔 것이 쓸 만하면 요청이 없고(만료 5분 전에 새로 받는 것은 playbackAccess가 정한다), 실패했거나
 * 쿠키가 지워져(403) 버려졌으면 이 간격 안에 다시 받는다
 */
const PLAYBACK_ACCESS_CHECK_MS = 60_000;

// memo: 대시보드는 경과 표기 때문에 매초 다시 그려진다. 받는 값이 그대로면(대시보드가 붙잡아 둔 mark · 콜백) 플레이어까지 내려가지 않는다
const LivePlayer = memo(function LivePlayer({
  stream,
  viewersNote,
  infoPending,
  broadcastStatus,
  displayStatus,
  startedAt,
  endedAt,
  ingestStreamId,
  uptimeSeconds,
  controllerRef,
  chatPanelOpen,
  onToggleChatPanel,
  mark,
  startAtSeconds,
  onRecordedReady,
  onRecordingLookup,
  onRecordingLookupStart,
  onPlaybackPosition,
  onLivePosition,
  onSeek,
  onMarkableChange,
  reselect,
}: {
  stream: LiveStream;
  viewersNote: string;
  /** 방송 정보를 받아 오는 중 — 상단 필의 빈 칸을 회색 막대로 둔다(POK-449) */
  infoPending: boolean;
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
  /** 전체 화면의 수동 마킹. 영상 아래 줄이 안 보이는 자리라 재생 막대가 받는다 */
  mark: PlayerMark;
  /** 녹화가 서면 이 자리(방송 기준 초)부터 튼다. 녹화가 서기 전에 고른 시점이다 */
  startAtSeconds: number | null;
  /** 녹화 플레이어가 섰다. 시작 위치를 받았으니 부른 쪽이 비운다 */
  onRecordedReady: () => void;
  /**
   * 녹화 조회 결과가 바뀌었다. 카드를 누를 때의 알림이 플레이어와 같은 말을 하고(없음), 지난 방송 채팅이 녹화가 자리를 알려 올
   * 때까지 기다리게(확인 중·찾음) 부른 쪽에 알린다
   */
  onRecordingLookup: (kind: RecordingLookup['kind']) => void;
  /**
   * 녹화 조회를 새로 시작했다(끝난 방송으로 밝혀졌거나 끝 시각·기준점·영상 키가 바뀜). 조회 결과는 확인 중 그대로라 onRecordingLookup이
   * 다시 불리지 않는다: 지난 방송 채팅의 기다림(CHAT_WAIT_MS)을 이 조회부터 다시 센다
   */
  onRecordingLookupStart: () => void;
  /** 녹화의 재생 위치(방송 기준 초)가 바뀌었다. 지난 방송 채팅이 그 시각까지의 줄만 보인다 */
  onPlaybackPosition: (seconds: number) => void;
  /** 라이브 재생기의 자리(실제 시각·실시간에서 몇 초 뒤)가 바뀌었다. 되감으면 라이브 채팅이 그 시각까지의 줄만 보인다(POK-326) */
  onLivePosition: (position: LivePosition | null) => void;
  /**
   * 사람이 옮겼다(시크바·키(화살표·PageUp·PageDown·Home)·끝으로·카드·다 본 뒤 처음부터). 지난 방송 채팅이 그 시점(맨 아래)을
   * 따라간다. 라이브 재생기도 부른다(실시간으로 포함)
   */
  onSeek: () => void;
  /**
   * 지난 방송에서 「이 시점 카드 추가」를 할 수 있나(POK-392): 녹화가 서 있고 그 0초가 서버 기준점이다. 재생기가 위치를 셀 때 쓰는
   * 녹화 소스 하나에서 읽는다(계획 검증 m11): 녹화 조회 결과와 기준점을 따로 보면 둘이 갈리는 그리기가 생긴다
   */
  onMarkableChange: (markable: boolean) => void;
  /** 재생 보유 중의 다시 고르기(useLiveStreamSelection — POK-200 M5). 보던 방송과 다른 답이면 「새 방송」 띠다 */
  reselect: Reselect;
}) {
  // env 미설정이면 null → GlassPlayer가 「영상 신호 없음」 자리 표시를 그린다.
  // 주소에 ?stream= 이 없으면 clip 명부에서 고른 방송(liveDataStore)의 영상을 튼다.
  const liveStreamId = useLiveField('streamId');
  const originMs = useLiveField('originMs');
  // 라이브·녹화 주소는 영상 경로의 키로 만든다(POK-233) — 방송 번호가 회차 번호면 그 이름의 경로가 없다. 출입증·채팅은 방송 번호
  const mediaKey = ingestStreamId ?? liveStreamId;
  const src = useMediaSource(mediaKey);
  // 끝난 방송은 LL-HLS 주소가 닫힌다 — 녹화 재생 서버에서 다시보기를 튼다(있을 때만).
  // 출입증을 받았거나 필요 없다는 답이 온 (방송 번호, 영상 키)(아래 출입증 효과가 채운다). 라이브 재생과 녹화 조회가 이것을
  // 기다린다(POK-317). 영상 키까지 같이 보는 이유: 키가 늦게 오거나 바뀌면 옛 키 쿠키는 새 주소에 안 실려, 방송 번호만 보면
  // 새 키의 출입증 전에 쿠키 없이 부르고 403을 받았다(PR #332 codex P1)
  const accessKey = liveStreamId ? `${liveStreamId}|${mediaKey ?? liveStreamId}` : null;
  const [accessReadyFor, setAccessReadyFor] = useState<string | null>(null);
  // 보관 기간(60일)이 지나 출입증을 안 주는 방송(410, POK-335). 녹화를 찾지 않고 그렇다고 알린다
  const [accessExpiredFor, setAccessExpiredFor] = useState<string | null>(null);
  const [lookup, setLookup] = useState<RecordingLookup>({ kind: 'pending' });
  const accessReady = accessKey === null || accessReadyFor === accessKey;
  // 재생 세션(POK-200 M5) — 라이브 · 되감기 · 끝남 대기. 녹화 조회는 세션이 녹화로 닫히거나(그 자리의 끝 시각으로 고정) 세션을
  // 연 적 없는 화면이 끝난 방송일 때만 묻는다: 명부는 정산 15분 뒤에야 ended 라 「명부 ended 단독」 조건을 쓰지 않는다(설계 5.5절)
  const live = useLiveSession({
    liveStreamId,
    mediaKey,
    accessKey,
    accessReady,
    broadcastStatus,
    startedAt,
    endedAt,
    liveUrl: src,
    reselect,
  });
  const request = live.recordedRequest;
  const playerStatus: BroadcastStatus = live.closedRecorded ? 'ended' : displayStatus;
  // 재생기 훅이 이 값의 정체성을 의존성으로 본다: 매 그리기 새 객체면 그때마다 다른 값으로 읽는다
  const simulationOptions = useMemo(
    () => ({ initialUptimeSeconds: uptimeSeconds ?? 0 }),
    [uptimeSeconds],
  );
  useEffect(() => {
    setLookup({ kind: 'pending' });
    publishLiveData({ timeBaseMs: null });
    // CDN 뒤 녹화 목록은 출입증 쿠키가 있어야 열린다: 출입증 답 뒤에 묻는다(POK-317)
    if (request === null || !mediaKey || !accessReady) return undefined;
    let alive = true;
    let retry: number | undefined;
    // 시작 시각을 모르는 방송(자리표시)은 그래프와 같은 끝 12시간 안에서만 찾는다: 하한이 없으면 같은 송출 키로 한 다른 날 방송의
    // 녹화를 틀고, 채팅도 그 날짜를 물었다(리뷰 5라운드). 지난 방송 목록도 같은 하한이고(recordingSearchFrom), 지난 방송 채팅은 이
    // 하한에서 보정 여유(60초)만큼 앞부터 읽는다(POK-324)
    const { fromMs, untilMs } = request;
    onRecordingLookupStart();
    const ask = () => {
      // 첫 구간 하나만 쓰면 송출이 한 번 끊긴 방송은 그 뒤를 못 보고, 앞 방송 녹화를 잡을 수도 있다(POK-253)
      void lookupRecordingSpans(mediaKey).then((spans) => {
        if (!alive) return;
        const found = spans === null ? null : recordingTimeline(spans, fromMs, untilMs);
        if (!found) {
          // 묻지 못했으면(재생 서버 없음·조회 실패) 녹화가 없다는 뜻이 아니라 「물을 수 없음」이다: 조회 실패를 빈 목록으로 접어
          // 「찾지 못했어요」로 굳혔다(리뷰 6라운드). 어느 쪽이든 서버가 있으면 다시 묻는다. 같은 답이면 상태를 그대로 둔다: 새 값을
          // 넣으면 다시 물을 때마다 플레이어를 다시 그렸다(리뷰 7라운드)
          const kind = spans === null ? 'unavailable' : 'none';
          setLookup((prev) => (prev.kind === kind ? prev : { kind }));
          if (playbackConfigured()) retry = window.setTimeout(ask, RECORDING_RETRY_MS);
          return;
        }
        // 재생기의 0초를 서버 기준점(카드·조각 위치의 0초, POK-255)에 세운다 — 녹화 시작에 세우면 두 시작의 차만큼 카드를
        // 누른 자리와 영상이 어긋난다(POK-253). 서버가 모르면(null) 녹화 시작
        const timeline = originMs === null ? found : rebaseTimeline(found, originMs);
        setLookup({
          kind: 'found',
          source: { streamId: mediaKey, ...timeline, originAxis: originMs !== null },
        });
        // 영상의 0초가 곧 카드 시점의 0초다 — 채팅도 이 기준으로 시점을 잡게 알린다
        publishLiveData({ timeBaseMs: timeline.startMs });
      });
    };
    ask();
    return () => {
      alive = false;
      window.clearTimeout(retry);
    };
  }, [request, mediaKey, accessReady, originMs, onRecordingLookupStart]);
  const recorded = lookup.kind === 'found' ? lookup.source : null;
  const markable = recorded?.originAxis === true;
  useEffect(() => {
    onMarkableChange(markable);
  }, [markable, onMarkableChange]);
  useEffect(() => {
    if (recorded !== null) onRecordedReady();
  }, [recorded, onRecordedReady]);
  useEffect(() => {
    onRecordingLookup(lookup.kind);
  }, [lookup.kind, onRecordingLookup]);
  // 출입증을 아직 한 번도 못 받아 라이브를 열지 못한 출입증 키(방송|영상 키, 아래 출입증 효과). 키로 두어 방송이나 영상 키가
  // 바뀌면 앞 방송의 실패를 말하지 않는다(로컬 리뷰 1라운드)
  const [accessFailedFor, setAccessFailedFor] = useState<string | null>(null);
  // 출입증을 실제로 받은(쿠키를 실은) 출입증 키. 이 키의 라이브만 403을 쿠키 탓으로 보고 출입증을 버린다: 「필요 없음」을 받은
  // 곳(로컬·dev)은 다시 받을 길이 없어 「다시 받고 있어요」가 거짓말이 된다(로컬 리뷰 1라운드)
  const [cookieFor, setCookieFor] = useState<string | null>(null);
  // 끝난 방송의 영상 자리 아랫줄. 확인하는 동안 「확인하고 있어요」, 없으면 「찾지 못했어요」다(「아직 연결되지 않았어요」는
  // 기다리면 나올 것처럼 읽혔다, 리뷰 4라운드 실기동). 재생 서버가 없으면 플레이어의 기본 말 「영상 서버에 아직 연결되지
  // 않았어요」가 맞다: 그때 「찾지 못했어요」라 하면 녹화가 지워진 것처럼 읽혔다(dev·운영은 재생 서버 주소가 비어 있다, 리뷰
  // 6라운드). 서버는 있는데 묻지 못했으면(오류·시한·출입증 쿠키 없음 403) 그렇다고 알린다: 1분 뒤 다시 묻는다(POK-337)
  const noSignalNote =
    playerStatus === 'ended' && accessKey !== null && accessExpiredFor === accessKey
      ? '보관 기간(60일)이 지나 녹화 영상을 볼 수 없어요'
      : playerStatus === 'live'
        ? accessKey !== null && accessFailedFor === accessKey
          ? '영상 출입증을 받지 못했어요. 1분 뒤 다시 받아요'
          : undefined
        : playerStatus !== 'ended' || !playbackConfigured()
          ? undefined
          : recordingMayPlay(lookup.kind)
            ? '녹화 영상을 확인하고 있어요'
            : lookup.kind === 'none'
              ? '녹화 영상을 찾지 못했어요'
              : '녹화 영상을 불러오지 못했어요. 1분 뒤 다시 확인해요';
  // 영상 출입증(POK-122). 방송을 열 때 받고, 1분마다 다시 본다(playbackAccess 한 곳에서 받는다: 만료 5분 전이면 새로 받고,
  // 실패했거나 다른 화면이 403으로 버렸으면 다시 받는다, POK-317). 쿠키 수명(운영 60분)이 지나면 새 조각이 403이 되어 재생이
  // 멈춘다(PR #200 codex P1). 서명 재료가 없는 서버(503)는 출입증이 필요 없는 곳이라(로컬·dev) 다시 묻지 않는다.
  // 출입증을 받거나(받음) 필요 없다는 답(로컬·dev의 503)이 오기 전에는 라이브 주소를 넘기지 않는다(POK-317). 먼저 넘기면 CDN 뒤 첫
  // 요청이 쿠키 없이 403이고 hls.js는 한 번만 다시 시도한 뒤 멈춘다. 실패는 열지 않고 1분 뒤 다시 받은 답을 기다린다(PR #320 codex).
  // 로컬·dev는 503이 곧바로 와서 재생 시작이 왕복 한 번만큼만 늦다
  // 열린 뒤 갱신이 실패하다가, 또는 쿠키가 지워져(403) 버린 뒤 다시 받아진 횟수. 그 사이 멈춘 hls.js는 새 쿠키로 저절로 안
  // 살아나서 재생기를 새로 세운다(PR #320 · #332 codex). 정상 갱신에는 안 늘어 재생이 끊기지 않는다
  const [accessRecoveries, setAccessRecoveries] = useState(0);
  // 403(출입증을 버림)으로 재생기를 새로 세운 출입증 키. 새 재생기가 또 403이면 더 받지 않고 멈춘다(POK-419). 키로 두어 방송이나
  // 영상 키가 바뀌면 앞 방송의 403을 다음 방송에 들고 가지 않는다(LivePlayer는 방송마다 새로 서지 않는다, 로컬 리뷰 1라운드)
  const [afterForbiddenFor, setAfterForbiddenFor] = useState<string | null>(null);
  useEffect(() => {
    if (!liveStreamId) return undefined;
    let alive = true;
    let timer: number | undefined;
    let opened = false;
    let failedSinceOpen = false;
    const key = `${liveStreamId}|${mediaKey ?? liveStreamId}`;
    const ask = () => {
      void ensureLiveAccess(liveStreamId, mediaKey ?? liveStreamId).then((result) => {
        if (!alive) return;
        if (result.kind === 'expired') {
          // 다시 받아도 같다: 더 묻지 않는다. 보던 녹화가 있으면 내린다: 준비 표시를 비우면 녹화 효과가 찾은 녹화를 치운다
          // (남기면 녹화가 안내보다 앞서 쿠키가 끝날 때까지 계속 돈다, PR #348 codex)
          setAccessExpiredFor(key);
          setAccessReadyFor(null);
          setCookieFor(null);
          return;
        }
        if (result.kind === 'failed') {
          failedSinceOpen = opened;
          // 열린 뒤의 갱신 실패는 알리지 않는다: 받아 둔 쿠키가 아직 살아 있어 재생은 이어진다
          if (!opened) setAccessFailedFor(key);
        } else {
          setAccessFailedFor(null);
          setCookieFor(result.kind === 'granted' ? key : null);
          // 버려진 것은 한 번 읽으면 지워진다: 열기 전이어도 읽어 둔다
          const droppedBefore = takePlaybackDrop(liveStreamId);
          if (opened && (failedSinceOpen || droppedBefore)) {
            setAccessRecoveries((n) => n + 1);
            setAfterForbiddenFor(droppedBefore ? key : null);
          }
          failedSinceOpen = false;
          opened = true;
          setAccessReadyFor(key);
        }
        if (result.kind === 'not_needed') return;
        timer = window.setTimeout(ask, PLAYBACK_ACCESS_CHECK_MS);
      });
    };
    ask();
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [liveStreamId, mediaKey]);
  return (
    <>
      {live.band !== null ? <NewBroadcastBand onGo={live.onBand} /> : null}
      <GlassPlayer
        // 실재생 훅은 경과 시드를 마운트 때 한 번만 읽는다 — startedAt이 도착하면 한 번 다시 세운다
        // 출입증 갱신이 실패하다가 다시 받아져도 다시 세운다(accessRecoveries)
        key={`${uptimeSeconds === null ? 'clock-pending' : 'clock-known'}-${accessRecoveries}`}
        channelName={stream.channelName}
        viewersNote={viewersNote}
        infoPending={infoPending}
        broadcastStatus={playerStatus}
        noSignalNote={noSignalNote}
        mediaKey={liveStreamId && cookieFor === accessKey ? (mediaKey ?? liveStreamId) : undefined}
        afterForbidden={accessKey !== null && afterForbiddenFor === accessKey}
        // 사용 통계 「방송마다 한 번」은 방송 번호로 기억한다 — 영상 키는 스트리머마다 같다(3K-PokeClip/pokeclip-mono#382 2차 리뷰)
        broadcastId={liveStreamId || undefined}
        // 라이브 주소는 방송 중일 때만 — 끝난 방송의 LL-HLS는 닫혀 있어 검은 화면과 오류만 남는다
        // (3K-PokeClip/pokeclip-mono#200 codex). 끝난 방송은 녹화가 있으면 recorded로, 없으면 「지난 방송 영상」 자리를
        // 그린다. 세션이 있으면 세션이 소스를 정하고, 녹화로 닫힌 세션은 닫힌 라이브 주소로 다시 붙지 않는다(설계 5.5절)
        session={live.session}
        src={broadcastStatus === 'live' && accessReady && !live.closedRecorded ? src : null}
        recorded={recorded}
        startAtSeconds={startAtSeconds}
        onPlaybackPosition={onPlaybackPosition}
        onLivePosition={onLivePosition}
        onSeek={onSeek}
        embed
        simulationOptions={simulationOptions}
        controllerRef={controllerRef}
        chatPanelOpen={chatPanelOpen}
        onToggleChatPanel={onToggleChatPanel}
        mark={mark}
      />
    </>
  );
});

// 방송 상태로 먼저 가른다 — 오프라인에서는 대시보드 훅(F8 수동 마킹 리스너·채팅 타이머)이
// 아예 돌지 않아야 하므로, 분기를 그 훅들보다 위인 컴포넌트 경계에서 한다.
// 꺼짐은 둘이다: 시안 확인용 토글(?mock=offline, 개발 전용)과 실제로 방송 중인 것이 없을 때.
export function LiveScreen() {
  // 저장소는 화면 하나의 것이다. 다른 방송을 보던 화면에서 넘어오면 앞 방송의 제목·시청자 수·그래프가 남아 첫 그리기에 칠해지고,
  // 그 그리기의 효과들이 앞 방송 번호로 요청을 보냈다(리뷰 3·4라운드). 그래서 비운 뒤에야 그린다: 레이아웃 효과에서 바꾼 상태는
  // 칠하기 전에 다시 그려져 빈 첫 그림은 보이지 않는다. 같은 화면 안에서 방송이 바뀔 때는 저장소가 스스로 비운다.
  // 그 첫 그림도 빈칸(null)이 아니라 빈 상자다: Next 16.3 스크롤 처리기는 잡을 화면이 없으면 건너뛰고 다음 그림에는 다시 돌지
  // 않아, 맨 위로 올리라는 뜻이 남았다(리뷰 5라운드)
  const [storeCleared, setStoreCleared] = useState(false);
  useLayoutEffect(() => {
    resetLiveData();
    setStoreCleared(true);
  }, []);
  return storeCleared ? <LiveScreenBody /> : <div />;
}

function LiveScreenBody() {
  const { status } = useLiveStatusMockState();
  const selection = useLiveStreamSelection();
  // 대시보드의 훅들이 보는 방송 번호. 고른 번호는 효과에서 저장소에 오르므로(useLiveStreamSelection) 정한 직후 한 그리기는 비어 있다
  const streamId = useLiveField('streamId');
  const offline = status === 'offline' || (selection.resolved && selection.streamId === '');
  if (offline) return <LiveOfflineScreen />;
  // 방송을 정하기 전, 그리고 정한 번호가 저장소에 오르기 전에는 대시보드를 세우지 않고 같은 모양의 스켈레톤을 둔다(POK-445). 빈
  // 번호로 세우면 시계(useBroadcastClock)가 「꺼짐」을 퍼뜨려 정보 바에 「방송 중이 아니에요」가 먼저 떴다가 바뀌었다. 방송 목록을
  // 못 읽으면 10초마다 다시 묻고(LIVE_RESELECT_MS) 답이 오면 선다. 한 번 선 뒤 방송이 바뀌면(A → B) 저장소가 비지 않으니 내리지 않는다
  if (!selection.resolved || streamId === '') return <LiveScreenSkeleton />;
  return <LiveDashboard reselect={selection} />;
}

/**
 * 채팅 칸. 채팅 훅의 줄 상태(중계 조각마다 바뀐다)와 재생 위치 구독을 이 칸 안에 가둔다: 대시보드(영상 · 정보 바 · 카드 · 통계)는
 * 채팅 줄이 늘어도 다시 그려지지 않는다. 훅은 칸이 서 있는 동안만 돈다(접으면 칸째 빠지므로 읽기도 멈춘다 — 전에는 enabled 거짓이었다).
 * 수집이 끊겼으면 새 채팅도 멈춘다 — 「수집 끊김」이라면서 메시지가 계속 쌓이면 화면이 스스로 모순된다(ADR-011: 끊기면 자동 탐지를
 * 정직하게 비활성화한다). 잠근 채팅도 묻지 않는다
 */
function LiveChatColumn({
  linkLock,
  chatWarning,
  ended,
  awaitPlayhead,
  mode,
  followJump,
  onCollapse,
}: {
  linkLock: LinkLock;
  chatWarning: boolean;
  ended: boolean;
  awaitPlayhead: boolean;
  mode: BroadcastStatus;
  followJump: number;
  onCollapse: () => void;
}) {
  const chat = useChatPanelMockState(!chatWarning && linkLock === null, ended, awaitPlayhead);
  if (linkLock !== null) return <LockedChatPanel reason={linkLock} onCollapse={onCollapse} />;
  return (
    <ChatPanel
      surges={chat.surges}
      messages={chat.messages}
      ratePerMinute={chat.ratePerMinute}
      mode={mode}
      replayClock={chat.replayClock}
      replayWaiting={chat.replayWaiting}
      statusNote={chat.statusNote}
      followJump={followJump}
      collectionWarning={chatWarning}
      placeholder={chat.placeholder}
      onCollapse={onCollapse}
    />
  );
}

function LiveDashboard({ reselect }: { reselect: Reselect }) {
  // 주소는 usePathname으로 읽는다: 넘어온 직후 첫 그리기의 window.location은 옛 주소다
  const vodRouteId = vodStreamIdFromPathname(usePathname() ?? '');
  const pastRoute = vodRouteId !== null;
  // 방송 채널 연동이 없으면 채팅 · 통계는 올 곳이 없다: 두 패널을 잠그고 채널 연동 화면으로 가는 길을 준다(linkLock). 제목 · 카테고리 ·
  // 태그 · 시청자 수도 수집기가 걷는 값이라 오지 않는다: 그 빈칸은 「수집 전」 대신 「연동 필요」이고, 받아 오는 중 막대도 세우지 않는다.
  // 자동 하이라이트도 채팅으로 찾으니 「자동 감지 중」이라 하지 않는다. 방송 정보 훅보다 먼저 판정한다: 잠긴 동안 그 훅이 차트를 읽지 않는다
  const linkLock = useLinkLock();
  const lockWord = linkLock === null ? null : LINK_LOCK_WORD[linkLock];
  const { stream, highlights, hiddenHighlights, cardsStatus, chatWarning, mergeCard } =
    useLiveMockState(pastRoute, linkLock !== null);
  const { streamMeta, cardVisuals } = useLiveDetailsMockState();
  // 쓰는 칸만 듣는다: 저장소 전체를 들으면 재생 위치(playheadMs, 초당 몇 번)가 바뀔 때마다 이 대시보드가 통째로 다시 그려졌다.
  // 기준점(timeBaseOf)이 읽는 세 칸(timeBaseMs · originMs · startedAt)도 따로 듣는다
  const streamId = useLiveField('streamId');
  const info = useLiveField('info');
  const storeStatus = useLiveField('status');
  const storeEndedAt = useLiveField('endedAt');
  const relation = useLiveField('relation');
  const timeBaseMs = useLiveField('timeBaseMs');
  const originMs = useLiveField('originMs');
  const startedAtMs = useLiveField('startedAt');
  /** 카드 시각의 0초(카드 목록과 같은 기준점, timeBaseOf). 라이브 카드를 그 실제 시각으로 옮길 때 쓴다(POK-326) */
  const cardBase = timeBaseOf({ timeBaseMs, originMs, startedAt: startedAtMs });
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
  // 수동 마킹(POK-392). 라이브는 서버 시각, 지난 방송은 녹화 재생 위치로 카드를 만든다. 갈래는 명부가 답한 상태로만 고른다:
  // 지난 방송 주소라도 상태를 모르는 동안(viewStatus는 그때 이미 ended로 그린다)에는 어느 갈래도 보내지 않는다(감사 1회차)
  const [pastMarkable, setPastMarkable] = useState(false);
  const markMode = clock.status === 'live' ? 'live' : clock.status === 'ended' ? 'past' : 'unknown';
  const marking = useManualMarking({
    streamId,
    mode: markMode,
    clockLabel: uptimeLabel,
    positionMs: () => playerRef.current?.currentPositionMs?.() ?? null,
    onCard: mergeCard,
    onExistingCard: (card) => {
      seekTo(card.streamTimestampMs / 1000);
    },
  });
  const markDisabled = markMode === 'unknown' || (markMode === 'past' && !pastMarkable);
  // 같은 값이면 같은 객체: 플레이어(memo)가 대시보드가 다시 그려질 때마다 새 mark를 받아 같이 다시 그려지지 않는다
  const markStatus = marking.pendingLabel
    ? `${marking.pendingLabel} 마킹됨 · 카드 생성 중`
    : marking.notice;
  const playerMark = useMemo<PlayerMark>(
    () => ({
      mode: ended ? 'past' : 'live',
      disabled: markDisabled,
      status: markStatus,
      hotkey: MARK_HOTKEY,
      onMark: marking.mark,
    }),
    [ended, markDisabled, markStatus, marking.mark],
  );
  // 접으면 패널 자체가 사라지므로 되살릴 통로는 플레이어 상단 오버레이의 여는 버튼이다
  const [chatPanelOpen, setChatPanelOpen] = useState(true);
  const toggleChatPanel = useCallback(() => setChatPanelOpen((open) => !open), []);
  /** 녹화 조회 결과(플레이어가 알린다). 없다고 확인되면 카드를 누를 때 「아직」이라 하지 않는다 */
  const [recordingLookup, setRecordingLookup] = useState<RecordingLookup['kind']>('pending');
  const recordingMissing = recordingLookup === 'none';
  // 재생 서버가 있고 녹화를 찾는 중이면 곧 재생기가 자리를 알려 온다(찾으면 재생기가 서는 그리기에서 알린다). 그때까지 지난 방송
  // 채팅은 기다린다: 방송 끝을 먼저 읽어 끝 채팅이 잠깐 보였다가 0초 빈 창으로 바뀌었다(POK-324 리뷰 1라운드). 찾는 중이 CHAT_WAIT_MS를
  // 넘으면 기다리지 않고 방송 끝을 보인다(재생 서버가 답하지 않거나 느려도 채팅을 비워 두지 않는다, 리뷰 2라운드·PR #230)
  // 기다림은 녹화 조회 하나마다 센다: 화면을 연 때부터 세면 명부가 늦게 답한 화면은 조회를 시작하자마자 방송 끝을 보였다(PR #230
  // codex). 조회를 새로 시작해도 결과는 확인 중 그대로라 시작 횟수로 다시 센다
  const [chatWaitOver, setChatWaitOver] = useState(false);
  const [lookupStarts, setLookupStarts] = useState(0);
  const handleRecordingLookupStart = useCallback(() => setLookupStarts((n) => n + 1), []);
  useEffect(() => {
    setChatWaitOver(false);
    if (recordingLookup !== 'pending') return undefined;
    const timer = window.setTimeout(() => setChatWaitOver(true), CHAT_WAIT_MS);
    return () => window.clearTimeout(timer);
  }, [recordingLookup, lookupStarts]);
  const awaitPlayhead =
    playbackConfigured() &&
    recordingMayPlay(recordingLookup) &&
    !(recordingLookup === 'pending' && chatWaitOver);
  // 채팅 창이 그 시점(맨 아래)을 따라가는 횟수. 사람이 옮긴 것(영상 없이 누른 카드, 녹화의 시크바·키(화살표·PageUp·PageDown·Home)·끝으로·카드·다 본 뒤
  // 처음부터, 채팅이 따르는 동안의 라이브 시크)과 라이브 채팅이 영상을 따르기 시작하거나 멈춘 것이다(POK-326): 재생으로 흐르거나
  // 녹화 틈을 넘는 것은 옮긴 것이 아니다(커서가 튀었는지로 짐작해 틈을 넘을 때도 읽던 자리를 잃었다, 리뷰 1라운드)
  const [chatJump, setChatJump] = useState(0);
  const bumpChatJump = useCallback(() => setChatJump((n) => n + 1), []);
  // 녹화를 찾으면 채팅이 그 녹화의 재생 위치로 통째로 바뀐다(방송 끝 줄 → 0초 줄). 위로 올려 읽던 자리가 남으면 엉뚱한 줄이
  // 보이므로 그 시점(맨 아래)을 따라간다: 「묻지 못함」·「없음」 뒤 1분 재조회로 찾을 때 그랬다(리뷰 3라운드)
  useEffect(() => {
    if (recordingLookup === 'found') bumpChatJump();
  }, [recordingLookup, bumpChatJump]);
  // 재생 위치가 녹화가 알린 자리인가(영상 없이 누른 카드 시각이 아니라). 끝 시각·기준점이 고쳐져 녹화를 다시 찾으면 그 녹화가
  // 알린 자리는 버린다: 남기면 찾는 동안 옛 녹화의 자리를 보이고, 끝내 못 찾으면 방송 끝으로 돌아가지 않고 거기에 멈췄다
  // (PR #230 codex). 카드 시각은 남긴다: 영상이 없으면 채팅은 누른 카드 시각까지에서 멈춘다
  const playheadFromRecording = useRef(false);
  useEffect(() => {
    if (recordingLookup === 'found' || !playheadFromRecording.current) return;
    playheadFromRecording.current = false;
    publishLiveData({ playheadMs: null });
  }, [recordingLookup]);
  // 채팅 훅(useChatPanelMockState)은 채팅 칸(LiveChatColumn)이 부른다: 훅의 줄 상태가 이 대시보드에 있으면 중계 조각이 올 때마다 영상 ·
  // 카드 · 통계까지 통째로 다시 그려졌다
  const stats = useLiveStatsMockState(viewStatus);

  // 시청자 수는 broadcast-info의 series에서 마지막으로 잰 값이다(수집기 PR-C 전에는 없다). 수집기는 목록에서 채널을 못 찾은
  // 회차를 빈 값으로 남기므로 마지막 관측이 비어도 그 앞의 값을 쓴다: 통계의 최고·평균과 같은 규칙이다(리뷰 3라운드). 끝난 방송은
  // 지금 보는 사람이 아니라 끝날 때 값이고(QA 2026-10-11 리뷰: 며칠 전 방송을 지금 1,842명이 보는 것처럼 읽혔다), 방송 정보를
  // 받기 전이면 「기록 없음」이라 단정하지 않는다. 어떤 기록을 믿고(끝난 방송은 방송 창으로 읽은 것만, 리뷰 4라운드) 값이 없을 때
  // 무엇이라 쓰는지는 통계와 같은 함수 하나가 정한다(리뷰 5라운드). 값과 말은 통계 훅이 센 것을 그대로 쓴다: 두 곳에서 따로
  // 세면 한쪽 인자만 바뀌어도 다시 갈린다(리뷰 6라운드)
  const v = stats.lastViewers;
  // 방송 정보를 받아 오는 중(POK-449): 방송 중이거나 아직 상태를 모르고, 방송 시작에서 2분이 안 지났다. 이때 빈 칸(채널 이름 · 시청자
  // 수 · 제목 · 카테고리)은 회색 막대다. 그 뒤로는 지금처럼 까닭(확인 중 · 수집 전)을 글자로 적는다
  // 시작 시각을 모르면(명부 답 전 · 실패, 시작 시각 없는 방송) 이 화면이 그 방송을 처음 본 때부터 센다: 안 세면 명부 조회가 계속
  // 실패하는 동안 막대가 끝없이 번쩍였다(로컬 리뷰 1라운드)
  // 방송을 바꾸면 다시 센다(렌더 중 상태 고침: React의 「이전 값에 맞춰 상태 고치기」 방식)
  const [firstSeen, setFirstSeen] = useState(() => ({ streamId, at: Date.now() }));
  if (firstSeen.streamId !== streamId) setFirstSeen({ streamId, at: Date.now() });
  const infoPending =
    lockWord === null &&
    (viewStatus === 'live' || viewStatus === 'unknown') &&
    !infoWaitOver(clock.startedAt ?? firstSeen.at, Date.now());
  const viewersLabel =
    v === null
      ? infoPending
        ? ''
        : `시청자 수 ${lockWord ?? stats.noViewers}`
      : ended
        ? `끝날 때 ${v.toLocaleString('ko-KR')}명 시청`
        : `${v.toLocaleString('ko-KR')}명 시청 중`;

  // 카드의 타임라인 위치(posPercent)는 방송 경과 대비다 — 실경과를 넣어야 눈금이 맞는다. 라이브는 경과가 매초 늘어 그대로 넘기면
  // 카드 전부가 매초 다시 그려진다. 눈금은 1% 단위라 CARD_UPTIME_STEP_S로 올려 묶어도 보이는 차이가 없다
  const cardUptime =
    uptimeSeconds === null
      ? 0
      : ended
        ? uptimeSeconds
        : Math.ceil(uptimeSeconds / CARD_UPTIME_STEP_S) * CARD_UPTIME_STEP_S;
  const streamForCards = useMemo<LiveStream>(
    () => ({ ...stream, uptimeSeconds: cardUptime }),
    [stream, cardUptime],
  );

  // 필터 개수도 통계의 하이라이트 줄도 같은 카드 목록에서 센다. 어느 한쪽을 따로 세면 두 표기가 언젠가 어긋난다.
  //
  // 경과 표기 때문에 이 컴포넌트는 매초 다시 그려진다. 요약이 매번 새 객체면
  // 아래 패널들의 memo가 통째로 무력해지므로 여기서 정체성을 붙잡아 둔다. highlights 자체가 경과 표기(「n초 전」) 때문에 매초 새
  // 배열이라, 그것에 묶지 않고 센 수에 묶는다(PR #316 리뷰)
  const manualCount = highlights.filter((card) => card.source === 'manual').length;
  const highlightSummary = useMemo(
    () => ({
      total: highlights.length,
      auto: highlights.length - manualCount,
      manual: manualCount,
    }),
    [highlights.length, manualCount],
  );
  // 통계 그래프의 핀도 같은 카드 목록이다(숨긴 카드는 없다). 자동 카드는 제목의 번호(채팅이 튄 순간 #12)로, 수동 카드는 제목에
  // 번호가 없어 「수동」으로 선다. 누르면 카드를 누른 것과 같이 그 시점으로 간다(handleSeek). 핀에 쓰는 값이 그대로면 앞 배열을
  // 그대로 돌려준다 — 매초 새 배열이면 그래프(memo)가 매초 칸 묶기·선 보간·막대 수백 개를 다시 그렸다(PR #316 리뷰)
  const pinCache = useRef<{ key: string; cards: TimelineCard[] }>({ key: '', cards: [] });
  const timelineCards = useMemo(() => {
    const next = highlights.flatMap((card): TimelineCard[] =>
      card.atMs === undefined
        ? []
        : [
            {
              id: card.id,
              atMs: card.atMs,
              timestamp: card.timestamp,
              label: card.source === 'manual' ? '수동' : `#${card.id.replace('card-', '')}`,
              manual: card.source === 'manual',
              reason: card.reason ?? card.title,
            },
          ],
    );
    const key = next
      .map((c) => `${c.id}|${c.atMs}|${c.timestamp}|${c.label}|${c.reason}`)
      .join('\n');
    if (key !== pinCache.current.key) pinCache.current = { key, cards: next };
    return pinCache.current.cards;
  }, [highlights]);

  // 녹화는 방송을 연 뒤에 찾는다. 그 전에 고른 시점은 옮길 플레이어가 없어(자리 표시) 그냥 사라지고, 뒤늦게 선 녹화는
  // 처음부터 튼다. 그래서 플레이어가 못 옮긴 시점만 남겨 두었다가 녹화의 시작 위치로 넘긴다(QA 2026-10-11 실기동).
  // 녹화가 받으면 비운다: 남겨 두면 시크바로 옮긴 뒤 녹화가 다시 설 때 오래전 카드로 튄다
  const [pendingStart, setPendingStart] = useState<number | null>(null);
  // 라이브에서 고른 시점은 남기지 않는다. 라이브 영상(HLS)은 시작 위치를 읽지 않고 라이브 채팅은 고른 시점이 아니라 재생기가
  // 알린 자리(되감은 영상의 시각, POK-326)를 따라, 남겨 두면 방송이 끝난 뒤 녹화는 처음부터인데 채팅만 그 카드에 묶이거나
  // 오래전 카드로 튄다(리뷰 2·3라운드).
  // 명부 답 전(상태를 모름)에 고른 시점은 남긴다: 끝난 방송으로 밝혀지면 녹화가 그 시점부터 선다(리뷰 3라운드)
  const live = clock.status === 'live';
  // 채팅이 고른 시점을 따라가나: 채팅 훅(useChatPanelMockState)과 같은 함수로 센다
  const followsPlayhead = chatFollowsPlayhead(storeStatus, storeEndedAt, cardBase);
  /**
   * @returns 'moved' 플레이어가 그 자리로 옮겼다 · 'outside' 라이브에서 되감을 수 있는 범위 밖이라 안 옮겼다 · 'none' 영상이 없다
   */
  const seekTo = useCallback(
    (seconds: number): 'moved' | 'outside' | 'none' => {
      // 라이브는 카드의 실제 시각(기준점 + 카드 초)으로 옮긴다: 재생기가 조각의 시각 표시로 영상 시각을 센다(POK-326). 방송 경과
      // 시계로 옮기면 숨은 탭에서 그 시계의 타이머가 늦춰져 카드보다 몇 분 늦은 자리(심하면 실시간 끝)로 갔고, 채팅도 그 자리를
      // 따랐다(리뷰 1라운드). 시각 표시가 없으면 경과 시계로 옮긴다. 범위 밖이면 경과 시계로도 옮기지 않는다: 그 길은 창 맨 앞으로
      // 잘라 옮겨 「옮겼다」고 답한다(POK-498)
      const player = playerRef.current;
      const byTime =
        live && cardBase !== null
          ? (player?.seekToTime?.(cardBase + seconds * 1000) ?? 'unknown')
          : 'unknown';
      if (byTime === 'outside') return 'outside';
      const moved = byTime === 'moved' || (player?.seekToUptime(seconds) ?? false);
      if (live) return moved ? 'moved' : 'none';
      // 녹화가 옮겼으면 재생기가 옮긴 자리와 옮겼다는 것을 알린다(handlePlaybackPosition·onSeek: 틈 안 카드면 다음 구간 시작이다).
      // 여기서도 카드 시각을 쓰면 재생기가 같은 초에 머물 때 바로잡히지 않아 채팅만 카드 시각에 남았다(리뷰 1라운드). 영상이 없으면
      // 채팅이 그 시점까지에서 멈추고 그 시점(맨 아래)을 따라간다(지난 방송, POK-324). 채팅이 영상 시각을 따르지 않는 방송(기준점이
      // 없는 자리표시)이면 채팅이 그대로이니 위로 올려 읽던 자리도 그대로 둔다(리뷰 2라운드)
      if (!moved) {
        playheadFromRecording.current = false;
        publishLiveData({ playheadMs: seconds * 1000 });
        if (followsPlayhead) bumpChatJump();
      }
      setPendingStart(moved ? null : seconds);
      return moved ? 'moved' : 'none';
    },
    [live, cardBase, followsPlayhead, bumpChatJump],
  );
  // 녹화가 흐르는 대로 채팅에 알린다: 지난 방송 채팅은 영상 시각까지의 줄만 보이고, 재생하면 그 시각의 줄이 붙는다(POK-324).
  // 녹화는 끝난 방송에만 서므로 라이브 채팅은 영향이 없다
  const handlePlaybackPosition = useCallback((seconds: number) => {
    playheadFromRecording.current = true;
    publishLiveData({ playheadMs: seconds * 1000 });
  }, []);
  // 방송 중으로 밝혀지면 모르는 동안 고른 시점을 버린다(위와 같은 이유)
  useEffect(() => {
    if (!live) return;
    setPendingStart(null);
    publishLiveData({ playheadMs: null });
  }, [live]);
  const handleRecordedReady = useCallback(() => setPendingStart(null), []);
  // 라이브 되감기(POK-326): 재생기가 실시간에서 10초 이상 뒤면 채팅이 그 자리(영상 시각)까지의 줄만 보이고, 재생기의 「실시간」으로
  // 돌아오면 최신 채팅이다. 그 사이는 앞 상태 그대로다(nextLiveFollow). 따르기 시작하거나 멈추면 채팅 창이 맨 아래를 따라간다:
  // 채팅이 통째로 바뀌어(최신 ↔ 되감은 자리) 위로 올려 읽던 자리가 남으면 엉뚱한 줄이 보인다
  // 시작 시각을 모르는 방송은 채팅이 따르지 못한다(읽을 하한이 없다, 채팅 훅과 같은 조건): 판정도 켜지 않는다. 켜면 채팅은 그대로인데
  // 판정이 바뀔 때마다 위로 올려 읽던 자리를 맨 아래로 끌어 내렸다(리뷰 1라운드)
  const liveFollowRef = useRef(false);
  const canFollowRef = useRef(false);
  canFollowRef.current = clock.startedAt !== null;
  const handleLivePosition = useCallback(
    (position: LivePosition | null) => {
      const follow =
        position !== null &&
        canFollowRef.current &&
        nextLiveFollow(liveFollowRef.current, position.behindSeconds);
      if (follow !== liveFollowRef.current) {
        liveFollowRef.current = follow;
        bumpChatJump();
      }
      publishLiveData({ liveRewind: follow ? position : null });
    },
    [bumpChatJump],
  );
  // 사람이 옮겼다. 녹화는 늘 채팅 창이 그 시점을 따라간다(POK-324). 라이브는 채팅이 영상을 따르는 동안만이다: 따르지
  // 않으면(실시간 근처, 시작 시각을 모름, 목록에 시각 표시가 없음) 채팅은 그대로라 위로 올려 읽던 자리를 지킨다(리뷰 2라운드).
  // 따르기가 켜지거나 꺼지는 시크는 handleLivePosition이 끈다
  const handlePlayerSeek = useCallback(() => {
    if (!live || liveFollowRef.current) bumpChatJump();
  }, [live, bumpChatJump]);

  // 주소에 실린 카드(?card=). 카드가 오는 대로 한 번 그 시점으로 옮긴다. 사람이 먼저 고르면 그쪽이다.
  // window.location이 아니라 useSearchParams로 읽는다: Next는 다른 화면에서 넘어올 때 새 화면을 먼저 그리고 주소창은 그 뒤에
  // 바꿔서, 첫 그리기 때 주소창을 읽으면 옛 주소다(실기동에서 찾음)
  const linkedCardId = useSearchParams().get('card');
  /** 이미 옮겨 준(또는 사람이 다른 카드를 골라 접은) 주소의 카드 번호 */
  const linkedDoneRef = useRef<string | null>(null);
  useEffect(() => {
    if (linkedCardId === null || linkedDoneRef.current === linkedCardId) return;
    // 가린 카드도 찾는다(POK-463): 주소를 받은 뒤 누가 가렸어도 그 시점으로는 간다
    const card = [...highlights, ...hiddenHighlights].find((h) => h.id === `card-${linkedCardId}`);
    const seconds = card ? parseClockLabel(card.timestamp) : null;
    if (seconds === null) return;
    linkedDoneRef.current = linkedCardId;
    seekTo(seconds);
  }, [highlights, hiddenHighlights, linkedCardId, seekTo]);

  const handleSeek = useCallback(
    (timestamp: string, from: 'card' | 'stats_pin') => {
      const seconds = parseClockLabel(timestamp);
      if (seconds === null) return;
      // 사람이 누른 이동만 센다(주소의 카드로 옮기는 것은 위 효과) — 카드 번호·시각은 싣지 않는다 (POK-357)
      track('highlight_card_seeked', { from, mode: live ? 'live' : 'past' });
      linkedDoneRef.current = linkedCardId;
      // 사람이 누른 것만 알린다. 주소의 카드로 옮길 때는 녹화를 찾는 중일 뿐이라 말하지 않는다(리뷰).
      // 영상 없이 채팅만 그 시점으로 가는 것은 지난 방송뿐이다: 라이브 채팅은 옮긴 영상의 시각을 따르므로(POK-326) 영상이 못 옮기면
      // 최신 그대로라 옮겼다고 하면 거짓이다(리뷰 2라운드)
      const result = seekTo(seconds);
      // 라이브 영상은 있지만 그 시점까지 되감을 수 없다(라이브 창 28초 · 되감기 목록 준비 전이거나 그보다 오래된 카드). 되감기가
      // 준비되면(10초 폴링) 1시간 안 카드는 갈 수 있고, 끝나면 녹화로 간다(POK-498)
      if (result === 'outside')
        toast({
          tone: 'info',
          title: '되감을 수 있는 범위 밖이라 그 시점으로 갈 수 없어요',
          description: '되감기가 준비되거나 방송이 끝나면 다시 눌러 보세요.',
        });
      else if (result === 'none')
        toast({
          tone: 'info',
          // 녹화가 없다고 확인된 방송은 플레이어처럼 없다고 말한다: 「아직 없어요」는 기다리면 나올 것처럼 읽혀 플레이어의 「찾지
          // 못했어요」와 한 화면에서 어긋났다(리뷰 6라운드)
          title: `${recordingMissing ? '녹화 영상이 없어요' : '영상은 아직 없어요'}${
            followsPlayhead ? ' · 채팅을 그 시점으로 옮겼어요' : ''
          }`,
        });
    },
    [linkedCardId, seekTo, toast, followsPlayhead, recordingMissing, live],
  );
  /** 하이라이트 카드 목록에서 누른 이동 */
  const handleCardSeek = useCallback(
    (timestamp: string) => handleSeek(timestamp, 'card'),
    [handleSeek],
  );
  /** 실시간 통계의 카드 핀에서 누른 이동 */
  const handlePinSeek = useCallback(
    (timestamp: string) => handleSeek(timestamp, 'stats_pin'),
    [handleSeek],
  );

  return (
    <main className={styles.container}>
      {/* 잠금이 걸리고 풀리는 것을 듣는 사람에게도 알린다 — 패널은 통째로 바뀌어 낭독되지 않는다 */}
      <LinkLockAnnouncer
        lock={linkLock}
        liveOwner={storeStatus === 'live' && relation === 'OWNER'}
      />
      <div className={clsx(styles.grid, !chatPanelOpen && styles.gridSolo)}>
        <div className={styles.mainCol}>
          <div className={styles.playerFrame}>
            {/* 폴백은 플레이어와 같은 16:9 빈 블록 — 서스펜드 중에도 레이아웃이 흔들리지 않는다 */}
            <Suspense fallback={<div className={styles.playerFallback} aria-hidden />}>
              <LivePlayer
                stream={stream}
                viewersNote={viewersLabel}
                infoPending={infoPending}
                broadcastStatus={clock.status}
                displayStatus={viewStatus}
                startedAt={clock.startedAt}
                endedAt={clock.endedAt}
                ingestStreamId={clock.ingestStreamId}
                uptimeSeconds={uptimeSeconds}
                controllerRef={playerRef}
                chatPanelOpen={chatPanelOpen}
                onToggleChatPanel={toggleChatPanel}
                mark={playerMark}
                startAtSeconds={pendingStart}
                onRecordedReady={handleRecordedReady}
                onRecordingLookup={setRecordingLookup}
                onRecordingLookupStart={handleRecordingLookupStart}
                onPlaybackPosition={handlePlaybackPosition}
                onLivePosition={handleLivePosition}
                onSeek={handlePlayerSeek}
                onMarkableChange={setPastMarkable}
                reselect={reselect}
              />
            </Suspense>
          </div>
          <StreamInfoBar
            stream={stream}
            meta={streamMeta}
            viewersLabel={viewersLabel}
            infoPending={infoPending}
            titleNote={`제목 ${lockWord ?? missingWord(viewStatus, info !== null)}`}
            uptimeLabel={uptimeLabel}
            uptimeNote={UPTIME_NOTE[viewStatus]}
            metaNote={`방송 정보 ${lockWord ?? missingWord(viewStatus, info !== null)}`}
            pendingLabel={marking.pendingLabel}
            markMode={ended ? 'past' : 'live'}
            markDisabled={markDisabled}
            onMark={marking.mark}
          />
          <HighlightCardPanel
            highlights={highlights}
            hiddenHighlights={hiddenHighlights}
            stream={streamForCards}
            visuals={cardVisuals}
            pendingLabel={marking.pendingLabel}
            detectionPaused={chatWarning}
            detectionLocked={linkLock !== null}
            ended={ended}
            listStatus={cardsStatus}
            onSeek={handleCardSeek}
            onCardChange={mergeCard}
          />
        </div>
        {/* 칸이 행 높이를 받고 그 안에서 채팅이 sticky로 붙는다 — 칸의 역할은 CSS .chatCol 주석.
            접으면 칸째 빠진다(.gridSolo가 트랙도 걷는다). */}
        {chatPanelOpen ? (
          <div className={styles.chatCol}>
            <LiveChatColumn
              linkLock={linkLock}
              chatWarning={chatWarning}
              ended={ended}
              awaitPlayhead={awaitPlayhead}
              mode={viewStatus}
              followJump={chatJump}
              onCollapse={toggleChatPanel}
            />
          </div>
        ) : null}
      </div>
      {/* 전폭 — 스크롤로 내려와 만나는 자리다 */}
      {/* 방송이 바뀌면 처음부터 다시 그린다 — 스크롤 위치·끝 따라가기를 앞 방송에서 이어받지 않는다 */}
      {linkLock !== null ? (
        <LockedStatsPanel reason={linkLock} />
      ) : (
        <LiveStatsPanel
          key={streamId}
          stats={stats}
          cards={timelineCards}
          highlightSummary={highlightSummary}
          live={viewStatus === 'live'}
          ended={ended}
          onSeekCard={handlePinSeek}
        />
      )}
    </main>
  );
}
