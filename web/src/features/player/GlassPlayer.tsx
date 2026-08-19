'use client';

import { useCallback, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import clsx from 'clsx';
import { useToast } from '@/ui';
import styles from './GlassPlayer.module.css';
import { PlayerChatOverlay } from './PlayerChatOverlay';
import { PlayerControls } from './PlayerControls';
import { PlayerSeekBar } from './PlayerSeekBar';
import { PlayerTopOverlay } from './PlayerTopOverlay';
import { seekIntentForKey } from './playerKeys';
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
export interface GlassPlayerProps {
  channelName: string;
  title: string;
  viewersNote: string;
  /** HLS 재생 소스(m3u8) — 없으면 목업 시뮬레이션으로 동작한다 */
  src?: string | null;
  /** 화면 안에 꽉 채워 넣는 모드 — 라운드·외곽 여백 제거 (1b 라이브 대시보드) */
  embed?: boolean;
  /** 테스트용 시뮬레이션 초기값 */
  simulationOptions?: PlayerSimulationOptions;
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
  title,
  viewersNote,
  embed = false,
  sim,
  videoNode,
}: GlassPlayerBodyProps) {
  const [chatOn, setChatOn] = useState(true);
  // 설정 팝오버는 Portal로 플레이어 밖에 뜬다 — 포커스가 넘어가면 :has(:focus-visible)
  // 보호가 닿지 않으므로, 열림 상태를 여기서 알고 그동안 컨트롤 숨김을 유보한다.
  const [settingsOpen, setSettingsOpen] = useState(false);
  // 드래그 중 포인터가 플레이어 밖으로 나가면 onMouseLeave가 컨트롤을 숨겨 시크바가 사라진다.
  // 마우스 드래그는 :focus-visible이 아니라 CSS의 포커스 예외절도 안 걸린다 — 팝오버(settingsOpen)와
  // 같은 방식으로 드래그 동안 숨김을 유보한다.
  const [seeking, setSeeking] = useState(false);
  const chat = useSimulatedChat(chatOn);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);

  const controlsShown = sim.controlsVisible || !sim.playing || settingsOpen || seeking;

  const handleClip = useCallback(() => {
    sim.markClip();
    toast({ title: '최근 30초 클립이 저장되었습니다', variant: 'success' });
  }, [sim, toast]);

  const handlePip = useCallback(() => {
    toast({ title: '미니 플레이어는 준비 중이에요' });
  }, [toast]);

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
      sim.wake();
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
      <PlayerTopOverlay
        channelName={channelName}
        title={title}
        viewersNote={viewersNote}
        uptimeSeconds={sim.uptimeSeconds}
      />
      {chatOn ? <PlayerChatOverlay messages={chat} /> : null}
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
          chatOn={chatOn}
          onToggleChat={() => setChatOn((on) => !on)}
          onClip={handleClip}
          onPip={handlePip}
          onFullscreen={handleFullscreen}
          onSettingsOpenChange={setSettingsOpen}
        />
      </div>
    </div>
  );
}
