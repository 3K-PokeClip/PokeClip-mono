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
  const chat = useSimulatedChat(chatOn);
  const { toast } = useToast();
  const containerRef = useRef<HTMLDivElement>(null);

  const controlsShown = sim.controlsVisible || !sim.playing;

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
    try {
      if (document.fullscreenElement) void document.exitFullscreen();
      else void el.requestFullscreen?.();
    } catch {
      // jsdom 등 전체 화면 미지원 환경 — 무시
    }
  }, []);

  return (
    <div
      ref={containerRef}
      className={clsx(styles.player, embed && styles.embed)}
      data-controls={controlsShown ? 'visible' : 'hidden'}
      onMouseMove={sim.wake}
      onMouseLeave={sim.sleep}
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
        />
      </div>
    </div>
  );
}
