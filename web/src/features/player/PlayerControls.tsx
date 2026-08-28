'use client';

import {
  Maximize,
  MessageSquare,
  PanelRight,
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
  chatPanelOpen,
  onToggleChatPanel,
  onClip,
  onPip,
  onFullscreen,
  onSettingsOpenChange,
}: {
  sim: PlayerSimulation;
  chatOn: boolean;
  onToggleChat: () => void;
  /** 바깥 채팅 패널(1b) 열림 상태 — 콜백이 있을 때만 토글 버튼이 생긴다 */
  chatPanelOpen?: boolean;
  onToggleChatPanel?: () => void;
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
        <button
          type="button"
          className={clsx(styles.glassBtn, chatOn && styles.glassBtnActive)}
          aria-label="채팅 오버레이"
          aria-pressed={chatOn}
          onClick={onToggleChat}
        >
          <MessageSquare size={19} aria-hidden />
        </button>
        {/* 바깥 패널은 플레이어 밖에 있어 접으면 되살릴 곳이 없다 — 복귀 통로를 여기 둔다 */}
        {onToggleChatPanel ? (
          <button
            type="button"
            className={clsx(styles.glassBtn, chatPanelOpen && styles.glassBtnActive)}
            aria-label="실시간 채팅 패널"
            aria-pressed={chatPanelOpen ?? false}
            onClick={onToggleChatPanel}
          >
            <PanelRight size={19} aria-hidden />
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
