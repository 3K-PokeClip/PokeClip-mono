'use client';

import { useCallback, useRef, useState } from 'react';
import clsx from 'clsx';
import { useToast } from '@/ui';
import styles from './GlassPlayer.module.css';
import { PlayerChatOverlay } from './PlayerChatOverlay';
import { PlayerControls } from './PlayerControls';
import { PlayerSeekBar } from './PlayerSeekBar';
import { PlayerTopOverlay } from './PlayerTopOverlay';
import { usePlayerSimulation, type PlayerSimulationOptions } from './usePlayerSimulation';
import { useSimulatedChat } from './useSimulatedChat';

// 리퀴드 글래스 라이브 플레이어 (시안 "영상 플레이어 글래스") — UI 목업.
// 실재생(hls.js·LL-HLS DVR)은 POK-23: usePlayerSimulation 내부와 videoSlot만 교체한다.
export interface GlassPlayerProps {
  channelName: string;
  title: string;
  viewersNote: string;
  /** 화면 안에 꽉 채워 넣는 모드 — 라운드·외곽 여백 제거 (1b 라이브 대시보드) */
  embed?: boolean;
  /** 테스트용 시뮬레이션 초기값 */
  simulationOptions?: PlayerSimulationOptions;
}

export function GlassPlayer({
  channelName,
  title,
  viewersNote,
  embed = false,
  simulationOptions,
}: GlassPlayerProps) {
  const sim = usePlayerSimulation(simulationOptions);
  const [chatOn, setChatOn] = useState(true);
  // 설정 팝오버는 Portal로 플레이어 밖에 뜬다 — 포커스가 넘어가면 :has(:focus-visible)
  // 보호가 닿지 않으므로, 열림 상태를 여기서 알고 그동안 컨트롤 숨김을 유보한다.
  const [settingsOpen, setSettingsOpen] = useState(false);
  const chat = useSimulatedChat(chatOn);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);

  const controlsShown = sim.controlsVisible || !sim.playing || settingsOpen;

  const handleClip = useCallback(() => {
    sim.markClip();
    toast({ title: '최근 30초 클립이 저장되었습니다', variant: 'success' });
  }, [sim, toast]);

  const handlePip = useCallback(() => {
    toast({ title: '미니 플레이어는 준비 중이에요' });
  }, [toast]);

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
      onPointerDown={sim.wake}
    >
      {/* POK-23: 이 자리에 <video>가 들어간다 */}
      <div className={styles.videoSlot} aria-hidden>
        <span className={styles.videoLabel}>라이브 방송 화면</span>
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
          clipMarked={sim.clipMarked}
          onSeekToFraction={sim.seekToFraction}
          onSeekBy={sim.seekBy}
          onReturnToLive={sim.returnToLive}
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
