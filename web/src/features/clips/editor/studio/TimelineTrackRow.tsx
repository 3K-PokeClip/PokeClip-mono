import { Check, Gamepad2, Mic, Music } from 'lucide-react';
import type { ComponentType } from 'react';
import styles from './StudioScreen.module.css';
import type { EditorTrack, EditorTrackKind } from '../useClipEditorMockState';

// 방송 소리 트랙 한 줄(POK-389): 우리만 가진 「트랙별 소리」를 타임라인의 주인공으로 둔다.
// 머리: 아이콘 · 이름 · 넣기 단추 · 음량 %. 뺀 줄은 흐리게 「안 넣음」. 음량 막대는 오디오 메뉴에 있다.
// 파형은 아직 모양만이다: 트랙별 파형 자료가 없다.

const TRACK_ICONS: Record<EditorTrackKind, ComponentType<{ size?: number }>> = {
  mic: Mic,
  game: Gamepad2,
  bgm: Music,
};

export function TimelineTrackRow({
  track,
  onToggleMute,
}: {
  track: EditorTrack;
  onToggleMute: (trackId: string) => void;
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
        <div className={`${styles.laneWave} ${track.muted ? styles.laneMuted : ''}`} aria-hidden />
      </div>
    </div>
  );
}
