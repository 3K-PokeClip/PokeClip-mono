import { Check, Gamepad2, Mic, Music } from 'lucide-react';
import type { ComponentType } from 'react';
import styles from './StudioScreen.module.css';
import { TrackWaveform } from './TrackWaveform';
import type { EditorTrack, EditorTrackKind } from '../useClipEditorMockState';

// 방송 소리 트랙 한 줄(POK-389): 우리만 가진 「트랙별 소리」를 타임라인의 주인공으로 둔다.
// 머리: 아이콘 · 이름 · 넣기 단추 · 음량 %. 뺀 줄은 흐리게 「안 넣음」. 음량 막대는 오디오 메뉴에 있다.
// 파형은 미리 듣기로 풀어 둔 이 트랙 소리의 실제 크기다(음량을 반영, 무음은 평평, 뺀 줄은 흐리게). 목업은 모양만 그린다.

const TRACK_ICONS: Record<EditorTrackKind, ComponentType<{ size?: number }>> = {
  mic: Mic,
  game: Gamepad2,
  bgm: Music,
};

export function TimelineTrackRow({
  track,
  onToggleMute,
  levels,
  view,
}: {
  track: EditorTrack;
  onToggleMute: (trackId: string) => void;
  /** 소리 크기. undefined면 목업(모양만), null이면 아직·못 불러 빈 줄 */
  levels: { values: Float32Array; startSeconds: number; stepSeconds: number } | null | undefined;
  view: { startSeconds: number; endSeconds: number };
}) {
  const Icon = TRACK_ICONS[track.kind];

  return (
    <div
      className={styles.trackRow}
      data-track-row={track.id}
      data-muted={track.muted || undefined}
    >
      <span className={styles.trackLabel}>
        <Icon size={12} />
        <span data-row-label>{track.label}</span>
        <span className={styles.trackControls}>
          <button
            type="button"
            className={styles.trackToggle}
            aria-pressed={!track.muted}
            aria-label={`${track.label} 넣기`}
            onClick={() => onToggleMute(track.id)}
          >
            {track.muted ? null : <Check size={10} aria-hidden />}
          </button>
          <span className={styles.trackValue}>{track.muted ? '안 넣음' : `${track.volume}%`}</span>
        </span>
      </span>

      <div className={styles.lane} data-kind={track.kind}>
        {levels === undefined ? (
          <div
            className={`${styles.laneWave} ${track.muted ? styles.laneMuted : ''}`}
            aria-hidden
          />
        ) : levels !== null ? (
          <TrackWaveform
            levels={levels.values}
            startSeconds={levels.startSeconds}
            stepSeconds={levels.stepSeconds}
            // 뺀 줄도 소리 모양은 보인다(흐리게): 넣기 전에 어느 트랙에 소리가 있는지 알 수 있게
            gain={(track.volume ?? 100) / 100}
            view={view}
          />
        ) : null}
      </div>
    </div>
  );
}
