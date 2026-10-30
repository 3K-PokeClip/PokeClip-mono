'use client';

import {
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
  type Ref,
} from 'react';
import clsx from 'clsx';
import { track } from '@/lib/analytics';
import { useToast } from '@/ui';
import { BufferingOverlay } from './BufferingOverlay';
import styles from './GlassPlayer.module.css';
import { PlayerChatOverlay } from './PlayerChatOverlay';
import { PlayerControls, type PlayerMark } from './PlayerControls';
import { PlayerSeekBar } from './PlayerSeekBar';
import { PlayerTopOverlay, type PlayerBroadcastStatus } from './PlayerTopOverlay';
import { seekIntentForKey } from './playerKeys';
import { progressFraction } from './playerMath';
import { PlayerSessionOverlay } from './PlayerSessionOverlay';
import { useHlsPlayback, type LivePosition } from './useHlsPlayback';
import { useLiveRewindPlayback } from './useLiveRewindPlayback';
import { useMediaBuffering } from './useMediaBuffering';
import type { PlaybackSession } from './usePlaybackSession';
import { usePlayerShortcuts } from './usePlayerShortcuts';
import { useSoundToggle } from './useSoundToggle';
import { useRecordedPlayback, type RecordedSource } from './useRecordedPlayback';
import {
  usePlayerSimulation,
  type DateSeekResult,
  type PlayerSimulation,
  type PlayerSimulationOptions,
} from './usePlayerSimulation';
import { useSimulatedChat } from './useSimulatedChat';

// 리퀴드 글래스 라이브 플레이어 (시안 "영상 플레이어 글래스").
// 고르는 순서: session(라이브 · 되감기 · 끝남 대기 재생 세션, useLiveRewindPlayback — POK-200 M5) → recorded(끝난 방송 녹화)
// → src(hls.js 실재생, useHlsPlayback) → simulate(목업,
// usePlayerSimulation — 스토리북·시험용) → 그 밖엔 「영상 신호 없음」 자리 표시(NoSignalGlassPlayer).
// 실제 화면에서 영상이 없을 때 흐르는 진행바·시간·채팅을 지어내지 않는다(POK-251).
// 훅 규칙상 조건부 호출이 안 되므로 컴포넌트 단위로 갈라 태운다. Body는 어느 쪽인지 모른다.
//
// 플레이어 안 채팅(오버레이·토글)은 전체 화면 전용이다 — 시안은 항상 그리지만 제품 결정으로
// 다르게 간다(POK-239). 기본 상태에선 옆 채팅 패널이 채팅을 맡고, 전체 화면에선 그 패널이
// 안 보이니 그때만 오버레이가 선다. 오버레이 채팅은 아직 흉내(useSimulatedChat)뿐이라 목업
// 모드(simulate)에서만 선다 — 실재생 위에 가짜 채팅을 얹지 않는다. 바깥 패널을 다시 여는 버튼(상단 오버레이 우측)은 별개다 —
// 패널이 접혀 있는 동안 플레이어 안에 남는 유일한 복귀 통로다.
/** 바깥에서 플레이어에 내리는 명령 — sim이 Body 안에서 생겨 상태로는 끌어올릴 수 없다 */
export interface GlassPlayerController {
  /**
   * 방송 경과 시각(초)으로 이동 — 되감기 창 밖은 가장 오래된 지점으로 클램프. 옮길 영상이 없거나(자리 표시) 아직 되감을
   * 구간이 없으면(라이브 영상이 목록을 받기 전) false를 돌려준다: 녹화를 찾는 중일 수도, 아예 없을 수도 있어 무엇을 말할지는
   * 부른 쪽이 정한다(QA 2026-10-11 리뷰)
   */
  seekToUptime: (uptimeSeconds: number) => boolean;
  /**
   * 실제 시각(epoch ms)으로 이동: 라이브 실재생만 있다(조각의 시각 표시(PDT)로 셈한다, POK-326). 목록에 시각 표시가 없으면 'unknown'이라
   * 부른 쪽이 seekToUptime으로 옮긴다. 방송 경과 시계로 옮기면 숨은 탭에서 그 시계가 밀려 카드보다 늦은 자리로 갔다. 되감을 수 있는
   * 범위보다 이르면 옮기지 않고 'outside'다: 부른 쪽이 갈 수 없다고 알린다(POK-498)
   */
  seekToTime?: (epochMs: number) => DateSeekResult;
  /**
   * 녹화의 지금 자리(서버 기준점 축 ms, 반올림)(POK-392). 녹화가 없거나 그 축이 아니면(기준점을 몰라 녹화 시작에 세웠다) null이다:
   * 수동 마킹이 이 값 하나로 보낼지를 정한다. 초를 ms로 바꿀 때 내리지 않고 반올림한다(8.11초 → 8110, 곱이 8109.999…라 내리면 1ms 앞이다)
   */
  currentPositionMs?: () => number | null;
}

export interface GlassPlayerProps {
  channelName: string;
  /** 상단 필의 아래 줄 — 라이브는 「1,842명 시청 중」 (시안은 여기에 제목을 두지 않는다) */
  viewersNote: string;
  /** 방송 정보를 받아 오는 중 — 상단 필의 빈 채널 이름 · 시청자 줄을 회색 막대로 둔다(POK-449, PlayerTopOverlay) */
  infoPending?: boolean;
  /** HLS 재생 소스(m3u8) — 없으면 simulate에 따라 목업이나 「영상 신호 없음」이다 */
  src?: string | null;
  /**
   * 재생 세션(POK-200 M5) — 있으면 src · recorded 보다 먼저 이것으로 튼다. 세션은 이 컴포넌트 바깥(LiveScreen 층)에 살아 key 로
   * 다시 마운트돼도 같은 자리에서 잇는다(설계 5.5절)
   */
  session?: PlaybackSession | null;
  /** 라이브 영상 키(출입증 쿠키의 경로). 영상 요청이 403이면 그 키의 출입증을 버린다(POK-410) */
  mediaKey?: string;
  /** 이 라이브 재생기는 403으로 출입증을 다시 받은 뒤 새로 섰다. 또 403이면 멈춘다(POK-419, useHlsPlayback) */
  afterForbidden?: boolean;
  /**
   * 방송 번호(회차, POK-233) — 사용 통계 「방송마다 한 번」(`broadcast_playback_started`)의 기억 키. `src`·`mediaKey`·녹화의
   * `streamId`는 물리 키(지금은 스트림 키)라 한 스트리머의 방송이 모두 같아 쓰지 않는다(PR #382 2차 리뷰). 없으면 세지 않는다
   */
  broadcastId?: string;
  /** 끝난 방송의 녹화(재생 서버) — 있으면 HLS 대신 이것으로 다시보기를 튼다 */
  recorded?: RecordedSource | null;
  /** 녹화 다시보기를 이 자리(방송 기준 초)부터 튼다. 녹화 플레이어가 설 때 한 번만 읽는다(useRecordedPlayback) */
  startAtSeconds?: number | null;
  /**
   * 녹화 다시보기의 재생 위치(방송 기준 초, 내림)가 바뀔 때마다 알린다. 지난 방송 채팅이 그 시각까지의 줄만 보인다(POK-324).
   * 녹화를 틀 때만 부른다: 라이브 재생기의 자리는 onLivePosition이 알린다(POK-326)
   */
  onPlaybackPosition?: (seconds: number) => void;
  /**
   * 녹화 다시보기에서 사람이 옮겼다(시크바·키(화살표·PageUp·PageDown·Home)·끝으로·카드·다 본 뒤 처음부터). 재생으로 흐르거나 녹화 틈을 넘을 때는 부르지
   * 않는다: 녹화가 옮긴 자리는 지난 방송 채팅이 이것으로만 따라간다(영상 없이 누른 카드는 화면이 따로 알린다). 라이브 재생기도
   * 사람이 옮기면(실시간으로 포함) 부른다: 라이브 채팅도 맨 아래(되감은 자리, 실시간이면 최신)로 간다(POK-326)
   */
  onSeek?: () => void;
  /**
   * 라이브 재생기의 자리(실제 시각·실시간에서 몇 초 뒤)가 바뀔 때마다 알린다. 영상 목록에 시각 표시(PDT)가 없으면 null이다. 라이브
   * 채팅이 되감은 자리를 따른다(POK-326). 방송이 끝나면 채팅은 이 값을 보지 않는다(지난 방송은 녹화의 재생 위치를 따른다)
   */
  onLivePosition?: (position: LivePosition | null) => void;
  /** 소스가 없을 때 목업 재생으로 시안 동작을 보인다 — 스토리북·시험 전용. 실제 화면은 끈다 */
  simulate?: boolean;
  /** clip의 방송 명부가 말하는 상태 — 상단 배지(LIVE·종료·확인 중)의 근거. 없으면 LIVE */
  broadcastStatus?: PlayerBroadcastStatus;
  /**
   * 영상이 없을 때 자리 표시의 아랫줄. 없으면 상태에 따른 기본 말이다. 끝난 방송은 녹화를 찾는 중인지, 찾았는데 없는지를
   * 바깥(녹화를 찾는 쪽)이 안다
   */
  noSignalNote?: string;
  /** 화면 안에 꽉 채워 넣는 모드 — 라운드·외곽 여백 제거 (1b 라이브 대시보드) */
  embed?: boolean;
  /** 테스트용 시뮬레이션 초기값 */
  simulationOptions?: PlayerSimulationOptions;
  /**
   * 바깥 채팅 패널(1b 대시보드)의 열림 상태 — 플레이어 안 채팅 오버레이(chatOn)와는 다른 것이다.
   * 닫혀 있을 때만 상단 오버레이 우측에 여는 버튼이 선다(시안 영상 플레이어 글래스).
   */
  chatPanelOpen?: boolean;
  onToggleChatPanel?: () => void;
  /** 전체 화면의 수동 마킹 — 없으면 버튼을 세우지 않는다 */
  mark?: PlayerMark;
  /** 카드 클릭 → 시점 이동 같은 바깥 명령의 통로 */
  controllerRef?: Ref<GlassPlayerController>;
  /**
   * 방송 경과가 흐를 때마다 알린다 — 시계의 주인은 플레이어다.
   * 바깥에서 따로 재면 두 시계가 어긋나, 「지금」 찍은 표시가 플레이어의 지금과 달라진다.
   */
  onUptimeChange?: (uptimeSeconds: number) => void;
}

export function GlassPlayer(props: GlassPlayerProps) {
  if (props.session) return <SessionGlassPlayer {...props} session={props.session} />;
  if (props.recorded) return <RecordedGlassPlayer {...props} recorded={props.recorded} />;
  if (props.src) return <HlsGlassPlayer {...props} src={props.src} />;
  return props.simulate ? <SimulatedGlassPlayer {...props} /> : <NoSignalGlassPlayer {...props} />;
}

function SimulatedGlassPlayer(props: GlassPlayerProps) {
  const sim = usePlayerSimulation(props.simulationOptions);
  return <GlassPlayerBody {...props} sim={sim} videoNode={null} />;
}
/**
 * media 서버가 없을 때의 플레이어 — 같은 프레임 안에 사실만 적는다.
 * 움직이는 진행바·재생 버튼·시간 표기가 없다(지어낼 수 없다). 시점 이동 명령은 false로 거절한다(알릴지는 부르는 쪽이 정한다).
 */
function NoSignalGlassPlayer({
  channelName,
  viewersNote,
  infoPending,
  broadcastStatus,
  noSignalNote,
  embed = false,
  chatPanelOpen,
  onToggleChatPanel,
  controllerRef,
}: GlassPlayerProps) {
  useImperativeHandle(controllerRef, () => ({ seekToUptime: () => false }), []);
  return (
    <div className={clsx(styles.player, embed && styles.embed)} data-controls="visible">
      <div className={styles.videoSlot} role="status">
        <div className={styles.noSignal}>
          <span className={styles.noSignalTitle}>
            {broadcastStatus === 'offline'
              ? '지금 방송 중이 아니에요'
              : broadcastStatus === 'ended'
                ? '지난 방송 영상'
                : '영상 신호 없음'}
          </span>
          <span className={styles.noSignalSub}>
            {noSignalNote ??
              (broadcastStatus === 'offline'
                ? '방송을 시작하면 여기에 나타나요'
                : '영상 서버에 아직 연결되지 않았어요')}
          </span>
        </div>
      </div>
      <PlayerTopOverlay
        channelName={channelName}
        viewersNote={viewersNote}
        infoPending={infoPending}
        status={broadcastStatus}
        chatPanelOpen={chatPanelOpen}
        onToggleChatPanel={onToggleChatPanel}
      />
    </div>
  );
}

/** 끝난 방송 다시보기 — 같은 몸통(컨트롤·시크바)에 녹화 재생 훅을 끼운다 */
function RecordedGlassPlayer(props: GlassPlayerProps & { recorded: RecordedSource }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const sim = useRecordedPlayback(
    videoRef,
    props.recorded,
    props.startAtSeconds ?? 0,
    props.onSeek,
    props.broadcastId,
  );
  const buffering = useMediaBuffering(videoRef);
  // 초가 바뀔 때만 알린다: timeupdate는 1초에 네 번쯤 온다. 내린다: 영상 시각을 넘는 줄은 보이지 않는다. 카드는 그 시각에
  // 정확히 서므로(seekToPosition) 카드와 같은 초다
  const second = Math.floor(sim.positionSeconds);
  const { onPlaybackPosition } = props;
  useEffect(() => {
    onPlaybackPosition?.(second);
  }, [second, onPlaybackPosition]);
  return (
    <GlassPlayerBody
      {...props}
      sim={sim}
      buffering={buffering}
      videoNode={<video ref={videoRef} className={styles.video} playsInline muted />}
    />
  );
}

function HlsGlassPlayer(props: GlassPlayerProps & { src: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const sim = useHlsPlayback(
    videoRef,
    { kind: 'live', url: props.src, startAtMs: null, generation: 0 },
    props.simulationOptions,
    props.onSeek,
    props.mediaKey,
    props.afterForbidden,
    props.broadcastId,
  );
  const buffering = useMediaBuffering(videoRef);
  // 초가 바뀌거나 시차가 바뀔 때만 알린다(재생기가 초로 내리고, 같은 값이면 같은 객체를 둔다)
  const { onLivePosition } = props;
  const { livePosition } = sim;
  useEffect(() => {
    onLivePosition?.(livePosition);
  }, [livePosition, onLivePosition]);
  // 내려갈 때 지운다: 방송이 끝나 녹화로 바뀌면 라이브에서 되감은 자리가 저장소에 남는다. 같은 방송이 다시 라이브가 되면 새
  // 재생기가 자리를 알리기 전 그림이 옛 자리로 채팅을 읽었다(리뷰 1라운드)
  useEffect(() => () => onLivePosition?.(null), [onLivePosition]);
  return (
    <GlassPlayerBody
      {...props}
      sim={sim}
      buffering={buffering}
      // muted 초기값은 훅과 짝 — 자동재생 정책상 소리는 사용자가 켠다
      videoNode={<video ref={videoRef} className={styles.video} playsInline muted />}
    />
  );
}

/** 재생 세션으로 튼다 — 라이브 · 되감기 · 끝남 대기 · 다시보기를 한 재생기 자리에서 갈아 끼운다(POK-200 M5) */
function SessionGlassPlayer(props: GlassPlayerProps & { session: PlaybackSession }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const { session } = props;
  const sim = useLiveRewindPlayback(
    videoRef,
    session,
    props.simulationOptions,
    props.onSeek,
    props.mediaKey,
    props.afterForbidden,
    props.broadcastId,
  );
  const buffering = useMediaBuffering(videoRef);
  const { onLivePosition } = props;
  const { livePosition } = sim;
  useEffect(() => {
    onLivePosition?.(livePosition);
  }, [livePosition, onLivePosition]);
  useEffect(() => () => onLivePosition?.(null), [onLivePosition]);
  const { dispatch, view } = session;
  return (
    <GlassPlayerBody
      {...props}
      sim={sim}
      // 덮개가 선 동안은 영상이 끝에서 멎은 것이다: 덮개가 까닭을 말하니 스피너를 겹치지 않는다
      buffering={buffering && view.cover === null}
      seekHidden={view.seekHidden}
      liveHidden={view.liveHidden}
      overlay={
        <PlayerSessionOverlay
          view={view}
          onAction={(which) => dispatch({ type: 'button', which })}
        />
      }
      videoNode={<video ref={videoRef} className={styles.video} playsInline muted />}
    />
  );
}

interface GlassPlayerBodyProps extends GlassPlayerProps {
  sim: PlayerSimulation;
  videoNode: ReactNode;
  /** 영상이 끊겨 기다리는 중 — 가운데에 스피너를 세운다(POK-439) */
  buffering?: boolean;
  /** 영상 위 덮개 · 띠(재생 세션) */
  overlay?: ReactNode;
  /** 받을 목록이 없어 시크바를 숨긴다(재생 세션의 끝남 대기 덮개) */
  seekHidden?: boolean;
  /** LIVE 버튼을 숨긴다(재생 세션의 끝난 방송 다시보기 — 설계 5.3절 vod 표) */
  liveHidden?: boolean;
}

function GlassPlayerBody({
  channelName,
  viewersNote,
  infoPending,
  broadcastStatus,
  simulate = false,
  embed = false,
  chatPanelOpen,
  onToggleChatPanel,
  mark,
  controllerRef,
  onUptimeChange,
  recorded,
  sim,
  videoNode,
  buffering = false,
  overlay,
  seekHidden = false,
  liveHidden = false,
}: GlassPlayerBodyProps) {
  // 오버레이는 전체 화면에서만 선다(파일 머리 주석). 첫 진입은 켜진 채 시작하고, 사용자가 끄면
  // 그 선택이 다음 전체 화면까지 남는다 — 플레이어 토글의 통상 동작이다.
  const [chatOn, setChatOn] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  // 설정 팝오버는 Portal로 플레이어 밖에 뜬다 — 포커스가 넘어가면 :has(:focus-visible)
  // 보호가 닿지 않으므로, 열림 상태를 여기서 알고 그동안 컨트롤 숨김을 유보한다.
  const [settingsOpen, setSettingsOpen] = useState(false);
  // 드래그 중 포인터가 플레이어 밖으로 나가면 onMouseLeave가 컨트롤을 숨겨 시크바가 사라진다.
  // 마우스 드래그는 :focus-visible이 아니라 CSS의 포커스 예외절도 안 걸린다 — 팝오버(settingsOpen)와
  // 같은 방식으로 드래그 동안 숨김을 유보한다.
  const [seeking, setSeeking] = useState(false);
  // 멈춰 있으면 움직임이 없어도 호버하는 동안은 컨트롤을 둔다. 호버하지 않으면 멈춰 있어도 영상을 가리지 않는다(POK-518)
  const [hovering, setHovering] = useState(false);
  // 안 보일 땐 시뮬레이션 티커도 돌리지 않는다
  // 오버레이 채팅은 흉내뿐이라 목업 모드에서만 돈다(파일 머리 주석)
  const overlayChat = simulate;
  const chat = useSimulatedChat(overlayChat && fullscreen && chatOn);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);
  const chatToggleRef = useRef<HTMLButtonElement>(null);
  const fullscreenButtonRef = useRef<HTMLButtonElement>(null);
  /** 전체 화면을 나가는 순간, 곧 사라질 채팅 토글이 포커스를 들고 있었나 */
  const chatToggleHadFocusRef = useRef(false);

  // 전체 화면 여부는 요청이 아니라 결과로 안다 — ESC·브라우저 UI로 나가도 fullscreenchange가 온다.
  // 이 플레이어가 전체 화면 요소일 때만 참이다(다른 요소의 전체 화면은 남의 일).
  useEffect(() => {
    const sync = () => {
      const el = containerRef.current;
      // el을 먼저 따지는 이유 — 언마운트 때 ref 분리가 이 리스너 해제보다 먼저다. 그 사이에 이벤트가
      // 오면 fullscreenElement도 null이라, 그냥 비교하면 null === null로 「전체 화면」이 돼 버린다.
      const next = el !== null && document.fullscreenElement === el;
      // 나가는 이 순간이 마지막 기회다 — 리렌더 전이라 사라질 토글이 아직 포커스를 들고 있다
      if (!next) chatToggleHadFocusRef.current = document.activeElement === chatToggleRef.current;
      setFullscreen(next);
    };
    // 리스너가 없던 사이(예: Suspense 재서스펜드)에 바뀐 것은 이벤트로 안 온다 — 붙을 때 한 번 읽는다
    sync();
    document.addEventListener('fullscreenchange', sync);
    return () => document.removeEventListener('fullscreenchange', sync);
  }, []);

  // 전체 화면을 나가며 채팅 토글이 사라지면 그 포커스가 갈 곳이 없다 — 컨테이너 안 포커스를 전제로
  // 하는 화살표 시킹(POK-32)이 죽는다. 이름 없는 컨테이너(tabIndex −1)로 옮기면 낭독이 끊기므로
  // 바로 옆에 남아 있는 「전체 화면」 버튼으로 되돌린다(버튼 위에서도 화살표 시킹은 먹는다 —
  // handleKeyDown이 button을 일부러 예외에서 뺐다). 토글에 포커스가 없었다면 손대지 않는다:
  // 그때 가져오면 남의 포커스를 뺏어 Tab 순서가 페이지 처음이 아니라 플레이어 다음부터 흐른다.
  useEffect(() => {
    const hadFocus = chatToggleHadFocusRef.current;
    chatToggleHadFocusRef.current = false;
    if (fullscreen || !hadFocus) return;
    fullscreenButtonRef.current?.focus({ preventScroll: true });
    // 재생 중이면 컨트롤이 이미 숨어 있을 수 있다(opacity 0 · pointer-events none). 프로그램적
    // 포커스는 :focus-visible을 얻지 못해 CSS의 유보 절도 안 걸리므로, 보이지 않는 버튼 위에
    // 포커스가 놓이지 않게 컨트롤을 깨워 둔다.
    sim.wake();
  }, [fullscreen, sim]);

  const controlsShown =
    sim.controlsVisible || (!sim.playing && hovering) || settingsOpen || seeking;

  const handlePip = useCallback(() => {
    track('pending_feature_clicked', { feature: 'mini_player' });
    toast({ tone: 'info', title: '미니 플레이어는 준비 중이에요' });
  }, [toast]);

  // 흐르는 경과를 바깥에 알린다 — 화면의 경과 표기와 「지금」 마킹이 이 값을 쓴다.
  useEffect(() => {
    onUptimeChange?.(sim.uptimeSeconds);
  }, [sim.uptimeSeconds, onUptimeChange]);

  // 절대 시각(방송 경과)으로 받는 이유는 시차가 마운트 뒤에도 계속 흐르기 때문이다 —
  // 카드가 든 "1:24:03"은 고정값이라 시차로 바꿔 건네면 누르는 순간마다 어긋난다.
  useImperativeHandle(
    controllerRef,
    () => ({
      seekToUptime: (target: number) => {
        // 옮겼다고 하면 고른 시점이 알림도 없이 사라진다(리뷰 2라운드)
        if (sim.windowSeconds <= 0) return false;
        // 녹화 다시보기는 그 자리로 바로 간다. 비율을 거치면 시차를 초 단위로 반올림해 카드보다 1초 안쪽 앞에 섰다(POK-324)
        if (sim.seekToPosition) sim.seekToPosition(target);
        else {
          const behind = Math.min(sim.windowSeconds, Math.max(0, sim.uptimeSeconds - target));
          sim.seekToFraction(progressFraction(behind, sim.windowSeconds));
        }
        sim.wake();
        return true;
      },
      seekToTime: (epochMs: number) => {
        // seekToUptime과 같은 막이: 되감을 구간이 없으면 옮길 곳이 없다(리뷰 2라운드)
        if (sim.windowSeconds <= 0 || !sim.seekToDate) return 'unknown';
        const result = sim.seekToDate(epochMs);
        if (result === 'moved') sim.wake();
        return result;
      },
      currentPositionMs: () =>
        recorded?.originAxis === true && sim.currentPositionSeconds
          ? Math.round(sim.currentPositionSeconds() * 1000)
          : null,
    }),
    [sim, recorded],
  );

  // 설정 팝오버는 Portal로 document.body에 붙지만, React는 DOM이 아니라 React 트리를 따라
  // 이벤트를 버블링시킨다 — 팝오버 안에서 누른 키·클릭이 여기까지 올라온다. DismissableLayer는
  // 네이티브 document 리스너만 써서 합성 이벤트를 막지 못하므로 DOM 포함 관계로 직접 가른다.
  // 없으면 팝오버가 열린 채 화살표를 눌렀을 때 뒤에서 영상이 시킹된다.
  const isInsidePlayer = (target: EventTarget | null) =>
    target instanceof Node && containerRef.current?.contains(target) === true;

  // 시킹 단축키를 플레이어 전역으로 — 시크바에 Tab 포커스를 넣지 않아도 화살표가 먹는다 (POK-32).
  // 키맵은 시크바와 같은 seekIntentForKey를 쓰므로 둘이 어긋날 수 없다.
  const handleKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      // 포털로 뜬 오버레이(설정 팝오버) 안에서 누른 키는 그쪽 것이다
      if (!isInsidePlayer(event.target)) return;
      // 플레이어 안의 키 입력은 시킹으로 이어지지 않아도 "보고 있다"는 신호다 — onMouseMove와
      // 같은 자리에 둔다. 아래 컨트롤 예외로 빠지는 키까지 깨워야 하는 이유는 CSS 유보가
      // :focus-visible 기준이기 때문이다: 클릭으로 포커스가 들어간 시크바·볼륨 슬라이더는
      // :focus-visible이 아니라, 화살표만 누르는 동안 컨트롤이 사라져 되감기는 계속되는데
      // 시차 표기와 시크바만 없어진다.
      sim.wake();
      // 화살표에 자기 동작이 있는 컨트롤 위에서는 그쪽을 우선한다 — 볼륨 슬라이더의 음량
      // 조절, 그리고 시크바 자신(버블링돼 올라온 키를 여기서 또 처리하면 두 번 시킹된다).
      // button은 일부러 뺐다 — 화살표에 기본 동작이 없고, 재생 버튼을 누른 뒤 그대로
      // 되감으려는 게 자연스럽다 (버튼을 넣으면 클릭 직후 단축키가 죽는다).
      if (
        event.target instanceof Element &&
        event.target.closest('input, select, textarea, [role="slider"], [contenteditable]')
      ) {
        return;
      }
      const intent = seekIntentForKey(event, sim.replay);
      if (!intent) return;
      if (intent.kind === 'by') sim.seekBy(intent.seconds);
      else if (intent.kind === 'toFraction') sim.seekToFraction(intent.fraction);
      else sim.returnToLive();
      event.preventDefault();
    },
    [sim],
  );

  // 화면을 클릭하면 컨테이너가 포커스를 받아 전역 단축키가 먹는다. 버튼·슬라이더를 눌렀을 땐
  // 그쪽 포커스를 뺏지 않는다 — Tab 이동 중 포커스가 튀면 접근성 회귀다.
  const handlePointerDown = useCallback(
    (event: PointerEvent<HTMLDivElement>) => {
      sim.wake();
      // 팝오버 안을 눌렀는데 여기로 포커스를 가져오면 그쪽 포커스 트랩이 깨진다
      if (!isInsidePlayer(event.target)) return;
      if (event.target instanceof Element && event.target.closest('button, input, [role="slider"]'))
        return;
      containerRef.current?.focus({ preventScroll: true });
    },
    [sim],
  );

  const handleFullscreen = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    // 거부는 promise reject로 온다(권한·iframe 정책) — 동기 try/catch로는 못 잡는다.
    // jsdom엔 requestFullscreen 자체가 없어 ?.로 건너뛴다.
    const transition = document.fullscreenElement
      ? document.exitFullscreen()
      : el.requestFullscreen?.();
    transition?.catch(() => {});
  }, []);

  const { rememberVolume, toggleSound } = useSoundToggle(sim);
  usePlayerShortcuts(containerRef, {
    togglePlay: () => {
      sim.togglePlay();
      sim.wake();
    },
    toggleSound: () => {
      toggleSound();
      sim.wake();
    },
    fullscreen: handleFullscreen,
  });

  return (
    <div
      ref={containerRef}
      className={clsx(styles.player, embed && styles.embed)}
      data-controls={controlsShown ? 'visible' : 'hidden'}
      // 들어올 때가 아니라 움직일 때 안다: 마우스가 이미 위에 있는 채로 화면이 열리면 들어오는 사건이 없다
      onMouseMove={() => {
        setHovering(true);
        sim.wake();
      }}
      onMouseLeave={() => {
        setHovering(false);
        sim.sleep();
      }}
      // 키보드 사용자도 컨트롤을 깨울 수 있어야 한다 — CSS의 :has(:focus-visible) 유지와 짝
      onFocus={sim.wake}
      // 탭에 mousemove를 합성하지 않는 터치 환경의 복구 경로 — 숨은 컨트롤은 pointer-events가 없다
      onPointerDown={handlePointerDown}
      // 전역 시킹 단축키 수신용 — Tab 순서엔 넣지 않고 클릭으로만 포커스가 들어온다
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      <div className={styles.videoSlot} aria-hidden>
        {videoNode ?? <span className={styles.videoLabel}>라이브 방송 화면</span>}
      </div>
      {/* 안내가 섰으면 기다리는 것이 아니라 멎은 것이다 */}
      {buffering && !sim.playbackError ? <BufferingOverlay /> : null}
      {/* 재생이 멎은 까닭(POK-337). 컨트롤과 따로 서서 자동 숨김에 안 가려진다 */}
      {sim.playbackError ? (
        <div className={styles.playbackError} role="status">
          {sim.playbackError}
        </div>
      ) : null}
      {/* 전체 화면에선 바깥 패널이 안 보인다 — 여는 버튼을 눌러도 아무 변화가 없으니 그때는 뺀다 */}
      <PlayerTopOverlay
        channelName={channelName}
        viewersNote={viewersNote}
        infoPending={infoPending}
        status={broadcastStatus}
        chatPanelOpen={chatPanelOpen}
        onToggleChatPanel={fullscreen ? undefined : onToggleChatPanel}
      />
      {overlayChat && fullscreen && chatOn ? <PlayerChatOverlay messages={chat} /> : null}
      {/* 평소엔 영상 아래 줄이 알린다. 전체 화면엔 그 줄도 토스트도 안 보인다 */}
      {fullscreen && mark?.status ? (
        <div className={styles.markStatus} role="status">
          {mark.status}
        </div>
      ) : null}
      {overlay}
      <div className={styles.controls}>
        {seekHidden ? null : (
          <PlayerSeekBar
            behindSeconds={sim.behindSeconds}
            windowSeconds={sim.windowSeconds}
            clipMarked={sim.clipMarked}
            onSeekToFraction={sim.seekToFraction}
            onSeekBy={sim.seekBy}
            onReturnToLive={sim.returnToLive}
            onSeekingChange={setSeeking}
            replay={sim.replay}
          />
        )}
        <PlayerControls
          sim={sim}
          liveHidden={liveHidden}
          chatToggle={
            overlayChat && fullscreen
              ? { on: chatOn, onToggle: () => setChatOn((on) => !on), ref: chatToggleRef }
              : undefined
          }
          fullscreen={fullscreen}
          fullscreenButtonRef={fullscreenButtonRef}
          mark={mark}
          onPip={handlePip}
          onFullscreen={handleFullscreen}
          onToggleSound={toggleSound}
          onVolumeTouch={rememberVolume}
          onSettingsOpenChange={setSettingsOpen}
        />
      </div>
    </div>
  );
}
