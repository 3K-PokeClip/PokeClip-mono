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
import { useToast } from '@/ui';
import styles from './GlassPlayer.module.css';
import { PlayerChatOverlay } from './PlayerChatOverlay';
import { PlayerControls } from './PlayerControls';
import { PlayerSeekBar } from './PlayerSeekBar';
import { PlayerTopOverlay } from './PlayerTopOverlay';
import { seekIntentForKey } from './playerKeys';
import { progressFraction } from './playerMath';
import { useHlsPlayback } from './useHlsPlayback';
import {
  usePlayerSimulation,
  type PlayerSimulation,
  type PlayerSimulationOptions,
} from './usePlayerSimulation';
import { useSimulatedChat } from './useSimulatedChat';

// 리퀴드 글래스 라이브 플레이어 (시안 "영상 플레이어 글래스").
// src가 있으면 hls.js 실재생(useHlsPlayback), 없으면 목업(usePlayerSimulation) —
// 훅 규칙상 조건부 호출이 안 되므로 컴포넌트 단위로 갈라 태운다. Body는 어느 쪽인지 모른다.
//
// 플레이어 안 채팅(오버레이·토글)은 전체 화면 전용이다 — 시안은 항상 그리지만 제품 결정으로
// 다르게 간다(POK-239). 기본 상태에선 옆 채팅 패널이 채팅을 맡고, 전체 화면에선 그 패널이
// 안 보이니 그때만 오버레이가 선다. 바깥 패널을 다시 여는 버튼(상단 오버레이 우측)은 별개다 —
// 패널이 접혀 있는 동안 플레이어 안에 남는 유일한 복귀 통로다.
/** 바깥에서 플레이어에 내리는 명령 — sim이 Body 안에서 생겨 상태로는 끌어올릴 수 없다 */
export interface GlassPlayerController {
  /** 방송 경과 시각(초)으로 이동 — 되감기 창 밖은 가장 오래된 지점으로 클램프 */
  seekToUptime: (uptimeSeconds: number) => void;
}

export interface GlassPlayerProps {
  channelName: string;
  /** 상단 필의 아래 줄 — 라이브는 「1,842명 시청 중」 (시안은 여기에 제목을 두지 않는다) */
  viewersNote: string;
  /** HLS 재생 소스(m3u8) — 없으면 목업 시뮬레이션으로 동작한다 */
  src?: string | null;
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
  /** 카드 클릭 → 시점 이동 같은 바깥 명령의 통로 */
  controllerRef?: Ref<GlassPlayerController>;
  /**
   * 방송 경과가 흐를 때마다 알린다 — 시계의 주인은 플레이어다.
   * 바깥에서 따로 재면 두 시계가 어긋나, 「지금」 찍은 표시가 플레이어의 지금과 달라진다.
   */
  onUptimeChange?: (uptimeSeconds: number) => void;
}

export function GlassPlayer(props: GlassPlayerProps) {
  return props.src ? (
    <HlsGlassPlayer {...props} src={props.src} />
  ) : (
    <SimulatedGlassPlayer {...props} />
  );
}

function SimulatedGlassPlayer(props: GlassPlayerProps) {
  const sim = usePlayerSimulation(props.simulationOptions);
  return <GlassPlayerBody {...props} sim={sim} videoNode={null} />;
}

function HlsGlassPlayer(props: GlassPlayerProps & { src: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const sim = useHlsPlayback(videoRef, props.src, props.simulationOptions);
  return (
    <GlassPlayerBody
      {...props}
      sim={sim}
      // muted 초기값은 훅과 짝 — 자동재생 정책상 소리는 사용자가 켠다
      videoNode={<video ref={videoRef} className={styles.video} playsInline muted />}
    />
  );
}

interface GlassPlayerBodyProps extends GlassPlayerProps {
  sim: PlayerSimulation;
  videoNode: ReactNode;
}

function GlassPlayerBody({
  channelName,
  viewersNote,
  embed = false,
  chatPanelOpen,
  onToggleChatPanel,
  controllerRef,
  onUptimeChange,
  sim,
  videoNode,
}: GlassPlayerBodyProps) {
  // 오버레이는 전체 화면에서만 선다(파일 머리 주석). 켜짐 기본값은 유지 — 들어가면 바로 보인다.
  const [chatOn, setChatOn] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  // 설정 팝오버는 Portal로 플레이어 밖에 뜬다 — 포커스가 넘어가면 :has(:focus-visible)
  // 보호가 닿지 않으므로, 열림 상태를 여기서 알고 그동안 컨트롤 숨김을 유보한다.
  const [settingsOpen, setSettingsOpen] = useState(false);
  // 드래그 중 포인터가 플레이어 밖으로 나가면 onMouseLeave가 컨트롤을 숨겨 시크바가 사라진다.
  // 마우스 드래그는 :focus-visible이 아니라 CSS의 포커스 예외절도 안 걸린다 — 팝오버(settingsOpen)와
  // 같은 방식으로 드래그 동안 숨김을 유보한다.
  const [seeking, setSeeking] = useState(false);
  // 안 보일 땐 시뮬레이션 티커도 돌리지 않는다
  const chat = useSimulatedChat(fullscreen && chatOn);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);

  // 전체 화면 여부는 요청이 아니라 결과로 안다 — ESC·브라우저 UI로 나가도 fullscreenchange가 온다.
  // 이 플레이어가 전체 화면 요소일 때만 참이다(다른 요소의 전체 화면은 남의 일).
  useEffect(() => {
    const sync = () => setFullscreen(document.fullscreenElement === containerRef.current);
    // 리스너가 없던 사이(예: Suspense 재서스펜드)에 바뀐 것은 이벤트로 안 온다 — 붙을 때 한 번 읽는다
    sync();
    document.addEventListener('fullscreenchange', sync);
    return () => document.removeEventListener('fullscreenchange', sync);
  }, []);

  // 전체 화면을 나가면 채팅 토글이 사라진다 — 거기 있던 포커스가 body로 떨어지면 컨테이너 안
  // 포커스를 전제로 하는 화살표 시킹(POK-32)이 죽으므로 컨테이너로 되돌린다.
  const wasFullscreenRef = useRef(false);
  useEffect(() => {
    if (wasFullscreenRef.current && !fullscreen && document.activeElement === document.body) {
      containerRef.current?.focus({ preventScroll: true });
    }
    wasFullscreenRef.current = fullscreen;
  }, [fullscreen]);

  const controlsShown = sim.controlsVisible || !sim.playing || settingsOpen || seeking;

  const handleClip = useCallback(() => {
    sim.markClip();
    toast({ tone: 'success', title: '최근 30초 클립이 저장되었습니다' });
  }, [sim, toast]);

  const handlePip = useCallback(() => {
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
        const behind = Math.min(sim.windowSeconds, Math.max(0, sim.uptimeSeconds - target));
        sim.seekToFraction(progressFraction(behind, sim.windowSeconds));
        sim.wake();
      },
    }),
    [sim],
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
      const intent = seekIntentForKey(event);
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

  return (
    <div
      ref={containerRef}
      className={clsx(styles.player, embed && styles.embed)}
      data-controls={controlsShown ? 'visible' : 'hidden'}
      onMouseMove={sim.wake}
      onMouseLeave={sim.sleep}
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
      {/* 전체 화면에선 바깥 패널이 안 보인다 — 여는 버튼을 눌러도 아무 변화가 없으니 그때는 뺀다 */}
      <PlayerTopOverlay
        channelName={channelName}
        viewersNote={viewersNote}
        chatPanelOpen={chatPanelOpen}
        onToggleChatPanel={fullscreen ? undefined : onToggleChatPanel}
      />
      {fullscreen && chatOn ? <PlayerChatOverlay messages={chat} /> : null}
      <div className={styles.controls}>
        <PlayerSeekBar
          behindSeconds={sim.behindSeconds}
          windowSeconds={sim.windowSeconds}
          clipMarked={sim.clipMarked}
          onSeekToFraction={sim.seekToFraction}
          onSeekBy={sim.seekBy}
          onReturnToLive={sim.returnToLive}
          onSeekingChange={setSeeking}
        />
        <PlayerControls
          sim={sim}
          chatToggle={
            fullscreen ? { on: chatOn, onToggle: () => setChatOn((on) => !on) } : undefined
          }
          onClip={handleClip}
          onPip={handlePip}
          onFullscreen={handleFullscreen}
          onSettingsOpenChange={setSettingsOpen}
        />
      </div>
    </div>
  );
}
