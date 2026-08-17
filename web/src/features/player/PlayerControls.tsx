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
import clsx from 'clsx';
import styles from './GlassPlayer.module.css';
import { formatBehind } from './playerMath';
import { PlayerSettingsPopover } from './PlayerSettingsPopover';
import type { PlayerSimulation } from './usePlayerSimulation';

// 하단 버튼줄 — 재생·볼륨·LIVE 복귀(계약3 4절) / 클립·채팅·미니·설정·전체 화면.
export function PlayerControls({
  sim,
  chatOn,
  onToggleChat,
  onClip,
  onPip,
  onFullscreen,
}: {
  sim: PlayerSimulation;
  chatOn: boolean;
  onToggleChat: () => void;
  onClip: () => void;
  onPip: () => void;
  onFullscreen: () => void;
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
        <button
          type="button"
          className={clsx(styles.glassBtn, chatOn && styles.glassBtnActive)}
          aria-label="채팅 오버레이"
          aria-pressed={chatOn}
          onClick={onToggleChat}
        >
          <MessageSquare size={19} aria-hidden />
        </button>
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
        />
        <button
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
