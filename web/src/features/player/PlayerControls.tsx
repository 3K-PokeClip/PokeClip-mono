'use client';

import {
  CirclePlus,
  Maximize,
  MessageSquare,
  Pause,
  PictureInPicture2,
  Play,
  RotateCcw,
  Volume1,
  Volume2,
  VolumeX,
  Zap,
} from 'lucide-react';
import type { MouseEvent, Ref } from 'react';
import clsx from 'clsx';
import styles from './GlassPlayer.module.css';
import { PlayerControlHint } from './PlayerControlHint';
import { PLAYER_KEY_LABELS } from './playerKeys';
import { LIVE_EDGE_WORDS, formatBehind, volumeLevel } from './playerMath';
import { PlayerSettingsPopover } from './PlayerSettingsPopover';
import { ReplayTimeButton } from './ReplayTimeButton';
import type { PlayerSimulation } from './usePlayerSimulation';

const VOLUME_ICONS = { silent: VolumeX, low: Volume1, high: Volume2 } as const;

/**
 * 전체 화면의 수동 마킹. 평소엔 영상 아래 줄의 버튼이 맡아 재생 막대에 두지 않고, 그 줄이 안 보이는 전체 화면에서만 세운다.
 * 없으면(마킹을 못 주는 화면) 전체 화면에도 버튼이 없다
 */
export interface PlayerMark {
  /** 영상 아래 줄과 같은 이름이다: 라이브는 「수동 마킹」, 지난 방송은 「이 시점 카드 추가」 */
  mode: 'live' | 'past';
  disabled: boolean;
  /** 마킹 진행 · 결과 한 줄. 토스트도 안 보이는 자리라 플레이어 안에 띄운다 */
  status: string | null;
  onMark: () => void;
}

/**
 * 마우스로 누른 단추에 포커스가 남으면 다음 Space가 재생 대신 그 단추를 또 누른다. 편집기 재생 막대와 같은 처리다(POK-490).
 * 슬라이더는 끌어야 해서, 메뉴를 여는 단추는 포커스를 넘겨야 해서 뺀다
 */
function keepFocus(event: MouseEvent<HTMLDivElement>) {
  if (event.target instanceof Element && event.target.closest('button:not([aria-haspopup])')) {
    event.preventDefault();
  }
}

// 하단 버튼줄 — 재생·볼륨·LIVE 복귀(계약3 4절, 지난 방송은 시간 표기) / 클립·(채팅)·미니·설정·전체 화면.
export function PlayerControls({
  sim,
  liveHidden = false,
  chatToggle,
  fullscreen = false,
  fullscreenButtonRef,
  mark,
  onPip,
  onFullscreen,
  onToggleSound,
  onVolumeTouch,
  onSettingsOpenChange,
}: {
  sim: PlayerSimulation;
  /**
   * LIVE 복귀 버튼을 숨긴다(재생 세션의 끝난 방송 다시보기). 시간 표기도 세우지 않는다: 세션 축은 1시간에서 잘려 1시간 넘는
   * 방송의 총 시간이 틀린다(playbackEdge)
   */
  liveHidden?: boolean;
  /**
   * 플레이어 안 채팅 오버레이 토글 — 없으면 버튼을 그리지 않는다.
   * GlassPlayer는 전체 화면일 때만 넘긴다: 기본 상태에선 옆 채팅 패널이 채팅을 맡아 중복이고,
   * 전체 화면에선 그 패널이 안 보여 오버레이가 유일한 채팅 창구다(POK-239, 시안과 다른 제품 결정).
   * 바깥 패널을 여는 버튼(PlayerTopOverlay)과는 다른 것이다.
   *
   * ref는 GlassPlayer가 「전체 화면을 나가는 순간 이 버튼이 포커스를 들고 있었나」를 알기 위한 것이다.
   */
  chatToggle?: { on: boolean; onToggle: () => void; ref?: Ref<HTMLButtonElement> };
  /** 플레이어가 전체 화면인가 — 버튼 이름이 갈린다 */
  fullscreen?: boolean;
  /** 사라지는 채팅 토글의 포커스를 받아 줄 자리 — GlassPlayer가 여기로 되돌린다 */
  fullscreenButtonRef?: Ref<HTMLButtonElement>;
  mark?: PlayerMark;
  onPip: () => void;
  onFullscreen: () => void;
  /** 소리 켜고 끄기 — m 단축키와 같은 동작이다(useSoundToggle) */
  onToggleSound: () => void;
  /** 음량 슬라이더를 만지기 시작했다 — 되살릴 음량을 기억한다(useSoundToggle) */
  onVolumeTouch: () => void;
  /** 설정 팝오버 열림 알림 — 열려 있는 동안 GlassPlayer가 컨트롤 숨김을 유보한다 */
  onSettingsOpenChange?: (open: boolean) => void;
}) {
  const playName = sim.ended ? '처음부터' : sim.playing ? '일시정지' : '재생';
  const level = volumeLevel(sim.muted, sim.volume);
  const VolumeIcon = VOLUME_ICONS[level];
  const soundName = level === 'silent' ? '음소거 해제' : '음소거';
  const fullscreenName = fullscreen ? '전체 화면 종료' : '전체 화면';
  const markName = mark?.mode === 'past' ? '이 시점 카드 추가' : '수동 마킹';
  return (
    <div className={styles.buttonRow} onMouseDown={keepFocus}>
      <div className={styles.buttonGroup}>
        <PlayerControlHint label={playName} keys={PLAYER_KEY_LABELS.play} align="start">
          <button
            type="button"
            className={styles.glassBtn}
            aria-label={playName}
            aria-keyshortcuts="Space k"
            onClick={sim.togglePlay}
          >
            {sim.ended ? (
              <RotateCcw size={19} aria-hidden />
            ) : sim.playing ? (
              <Pause size={19} aria-hidden fill="currentColor" strokeWidth={0} />
            ) : (
              <Play size={19} aria-hidden fill="currentColor" strokeWidth={0} />
            )}
          </button>
        </PlayerControlHint>
        <div className={styles.volumeGroup}>
          <PlayerControlHint label={soundName} keys={PLAYER_KEY_LABELS.sound}>
            <button
              type="button"
              className={styles.volumeBtn}
              aria-label={soundName}
              aria-keyshortcuts="m"
              onClick={onToggleSound}
            >
              <VolumeIcon size={19} aria-hidden />
            </button>
          </PlayerControlHint>
          <input
            type="range"
            className={styles.volumeSlider}
            aria-label="볼륨"
            min={0}
            max={100}
            value={sim.muted ? 0 : sim.volume}
            onPointerDown={onVolumeTouch}
            onKeyDown={onVolumeTouch}
            onChange={(event) => sim.setVolume(Number(event.target.value))}
          />
        </div>
        {/* 명시적 LIVE 복귀 버튼 — 계약3 4절: 시차는 -MM:SS, 상한 1:00:00. 지난 방송은 끝으로 갈 일이 없어 시간을 적는다(POK-518) */}
        {liveHidden ? null : sim.replay ? (
          <ReplayTimeButton behindSeconds={sim.behindSeconds} windowSeconds={sim.windowSeconds} />
        ) : (
          <PlayerControlHint label={LIVE_EDGE_WORDS.goToLabel} keys={PLAYER_KEY_LABELS.live}>
            <button
              type="button"
              className={styles.livePillBtn}
              aria-label={LIVE_EDGE_WORDS.goToLabel}
              aria-keyshortcuts="End"
              onClick={sim.returnToLive}
            >
              <span
                className={styles.livePillDot}
                data-at-edge={sim.atEdge || undefined}
                aria-hidden
              />
              {sim.atEdge
                ? LIVE_EDGE_WORDS.atEdge
                : `${formatBehind(sim.behindSeconds)} · ${LIVE_EDGE_WORDS.goTo}`}
            </button>
          </PlayerControlHint>
        )}
      </div>
      <div className={styles.buttonGroup}>
        {fullscreen && mark ? (
          <PlayerControlHint label={markName} keys={PLAYER_KEY_LABELS.mark}>
            <button
              type="button"
              className={styles.glassBtn}
              aria-label={markName}
              aria-keyshortcuts={PLAYER_KEY_LABELS.mark}
              disabled={mark.disabled}
              onClick={mark.onMark}
            >
              {mark.mode === 'past' ? (
                <CirclePlus size={19} aria-hidden />
              ) : (
                <Zap size={19} aria-hidden />
              )}
            </button>
          </PlayerControlHint>
        ) : null}
        {chatToggle ? (
          <PlayerControlHint label="채팅 오버레이">
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
          </PlayerControlHint>
        ) : null}
        <PlayerControlHint label="미니 플레이어">
          <button
            type="button"
            className={styles.glassBtn}
            aria-label="미니 플레이어"
            onClick={onPip}
          >
            <PictureInPicture2 size={19} aria-hidden />
          </button>
        </PlayerControlHint>
        {/* 실재생은 고를 화질이 없다(렌디션 하나) — 거짓 선택지를 안 보인다(POK-253) */}
        {sim.settingsMenu && (
          <PlayerControlHint label="설정">
            <PlayerSettingsPopover
              quality={sim.quality}
              onQualityChange={sim.setQuality}
              lowLatency={sim.lowLatency}
              onToggleLowLatency={sim.toggleLowLatency}
              onOpenChange={onSettingsOpenChange}
            />
          </PlayerControlHint>
        )}
        <PlayerControlHint label={fullscreenName} keys={PLAYER_KEY_LABELS.fullscreen} align="end">
          <button
            ref={fullscreenButtonRef}
            type="button"
            className={styles.glassBtn}
            aria-label={fullscreenName}
            aria-keyshortcuts="f"
            onClick={onFullscreen}
          >
            <Maximize size={19} aria-hidden />
          </button>
        </PlayerControlHint>
      </div>
    </div>
  );
}
