'use client';

import {
  Maximize,
  MessageSquare,
  Pause,
  PictureInPicture2,
  Play,
  Scissors,
  Volume2,
  VolumeX,
} from 'lucide-react';
import type { Ref } from 'react';
import clsx from 'clsx';
import styles from './GlassPlayer.module.css';
import { formatBehind } from './playerMath';
import { PlayerSettingsPopover } from './PlayerSettingsPopover';
import type { PlayerSimulation } from './usePlayerSimulation';

// 하단 버튼줄 — 재생·볼륨·LIVE 복귀(계약3 4절) / 클립·(채팅)·미니·설정·전체 화면.
export function PlayerControls({
  sim,
  chatToggle,
  fullscreenButtonRef,
  onClip,
  onPip,
  onFullscreen,
  onSettingsOpenChange,
}: {
  sim: PlayerSimulation;
  /**
   * 플레이어 안 채팅 오버레이 토글 — 없으면 버튼을 그리지 않는다.
   * GlassPlayer는 전체 화면일 때만 넘긴다: 기본 상태에선 옆 채팅 패널이 채팅을 맡아 중복이고,
   * 전체 화면에선 그 패널이 안 보여 오버레이가 유일한 채팅 창구다(POK-239, 시안과 다른 제품 결정).
   * 바깥 패널을 여는 버튼(PlayerTopOverlay)과는 다른 것이다.
   *
   * ref는 GlassPlayer가 「전체 화면을 나가는 순간 이 버튼이 포커스를 들고 있었나」를 알기 위한 것이다.
   */
  chatToggle?: { on: boolean; onToggle: () => void; ref?: Ref<HTMLButtonElement> };
  /** 사라지는 채팅 토글의 포커스를 받아 줄 자리 — GlassPlayer가 여기로 되돌린다 */
  fullscreenButtonRef?: Ref<HTMLButtonElement>;
  onClip: () => void;
  onPip: () => void;
  onFullscreen: () => void;
  /** 설정 팝오버 열림 알림 — 열려 있는 동안 GlassPlayer가 컨트롤 숨김을 유보한다 */
  onSettingsOpenChange?: (open: boolean) => void;
}) {
  return (
    <div className={styles.buttonRow}>
      <div className={styles.buttonGroup}>
        <button
          type="button"
          className={styles.glassBtn}
          aria-label={sim.playing ? '일시정지' : '재생'}
          onClick={sim.togglePlay}
        >
          {sim.playing ? (
            <Pause size={19} aria-hidden fill="currentColor" strokeWidth={0} />
          ) : (
            <Play size={19} aria-hidden fill="currentColor" strokeWidth={0} />
          )}
        </button>
        <div className={styles.volumeGroup}>
          <button
            type="button"
            className={styles.volumeBtn}
            aria-label={sim.muted ? '음소거 해제' : '음소거'}
            onClick={sim.toggleMute}
          >
            {sim.muted ? <VolumeX size={19} aria-hidden /> : <Volume2 size={19} aria-hidden />}
          </button>
          <input
            type="range"
            className={styles.volumeSlider}
            aria-label="볼륨"
            min={0}
            max={100}
            value={sim.muted ? 0 : sim.volume}
            onChange={(event) => sim.setVolume(Number(event.target.value))}
          />
        </div>
        {/* 명시적 LIVE 복귀 버튼 — 계약3 4절: 시차는 -MM:SS, 상한 1:00:00 */}
        <button
          type="button"
          className={styles.livePillBtn}
          aria-label="실시간으로 이동"
          onClick={sim.returnToLive}
        >
          <span className={styles.livePillDot} data-at-edge={sim.atEdge || undefined} aria-hidden />
          {sim.atEdge ? '실시간' : `${formatBehind(sim.behindSeconds)} · 실시간으로`}
        </button>
      </div>
      <div className={styles.buttonGroup}>
        <button type="button" className={styles.glassBtn} aria-label="클립 만들기" onClick={onClip}>
          <Scissors size={19} aria-hidden />
        </button>
        {chatToggle ? (
          <button
            ref={chatToggle.ref}
            type="button"
            className={clsx(styles.glassBtn, chatToggle.on && styles.glassBtnActive)}
            aria-label="채팅 오버레이"
            aria-pressed={chatToggle.on}
            onClick={chatToggle.onToggle}
          >
            <MessageSquare size={19} aria-hidden />
          </button>
        ) : null}
        <button
          type="button"
          className={styles.glassBtn}
          aria-label="미니 플레이어"
          onClick={onPip}
        >
          <PictureInPicture2 size={19} aria-hidden />
        </button>
        <PlayerSettingsPopover
          quality={sim.quality}
          onQualityChange={sim.setQuality}
          lowLatency={sim.lowLatency}
          onToggleLowLatency={sim.toggleLowLatency}
          onOpenChange={onSettingsOpenChange}
        />
        <button
          ref={fullscreenButtonRef}
          type="button"
          className={styles.glassBtn}
          aria-label="전체 화면"
          onClick={onFullscreen}
        >
          <Maximize size={19} aria-hidden />
        </button>
      </div>
    </div>
  );
}
