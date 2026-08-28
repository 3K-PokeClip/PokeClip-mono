import {
  Film,
  Image as ImageIcon,
  Mic,
  Music,
  SlidersHorizontal,
  Sparkles,
} from 'lucide-react';
import type { ComponentType } from 'react';
import { Slider } from '@/ui';
import styles from './StudioScreen.module.css';
import { TimelineClip } from './TimelineClip';
import type { TimelineView } from '../timelineMath';
import type { EditorTrack, EditorTrackKind } from '../useClipEditorMockState';

// 시안 1d-a 타임라인 한 줄 — 라벨 열(172px) + 레인.
// 레인을 어떻게 그릴지는 트랙 종류가 정한다: 영상은 필름스트립, 마이크·게임은 파형,
// 나머지는 조각. 트랙 목록 자체는 데이터라서(E2) 여기서 늘리거나 줄이지 않는다.

const TRACK_ICONS: Record<EditorTrackKind, ComponentType<{ size?: number }>> = {
  video: Film,
  mic: Mic,
  game: SlidersHorizontal,
  bgm: Music,
  sfx: Sparkles,
  image: ImageIcon,
};

export function TimelineTrackRow({
  track,
  view,
  selectedClipId,
  onSelectClip,
  onVolumeChange,
}: {
  track: EditorTrack;
  view: TimelineView;
  selectedClipId: string | null;
  onSelectClip: (clipId: string) => void;
  onVolumeChange: (trackId: string, volume: number) => void;
}) {
  const Icon = TRACK_ICONS[track.kind];
  const waveform = track.kind === 'mic' || track.kind === 'game';

  return (
    <div className={styles.trackRow}>
      <span className={styles.trackLabel}>
        <Icon size={12} />
        {track.label}
        {track.volume !== null ? (
          <span className={styles.trackControls}>
            <Slider
              className={styles.trackSlider}
              label={`${track.label} 볼륨`}
              value={track.volume}
              onValueChange={(volume) => onVolumeChange(track.id, volume)}
            />
            <span className={styles.trackValue}>{track.volume}%</span>
          </span>
        ) : null}
      </span>

      <div className={styles.lane} data-kind={track.kind}>
        {track.kind === 'video' ? <div className={styles.laneFilmstrip} aria-hidden /> : null}
        {waveform ? (
          <div
            className={`${styles.laneWave} ${track.muted ? styles.laneMuted : ''}`}
            aria-hidden
          />
        ) : null}
        {track.clips.map((clip) => (
          <TimelineClip
            key={clip.id}
            clip={clip}
            kind={track.kind}
            view={view}
            selected={clip.id === selectedClipId}
            onSelect={() => onSelectClip(clip.id)}
          />
        ))}
      </div>
    </div>
  );
}
