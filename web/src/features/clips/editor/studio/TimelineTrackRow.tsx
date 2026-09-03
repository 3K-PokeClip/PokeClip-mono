import {
  AudioLines,
  Film,
  Image as ImageIcon,
  Mic,
  Music,
  SlidersHorizontal,
  Sparkles,
} from 'lucide-react';
import { memo, useMemo, type ComponentType } from 'react';
import { Slider } from '@/ui';
import styles from './StudioScreen.module.css';
import { TimelineClip } from './TimelineClip';
import { filmstripTiles, waveformBars } from '../timelineLaneView';
import type { TimelineView } from '../timelineMath';
import type {
  ClipEditorMockState,
  EditorTrack,
  EditorTrackKind,
} from '../useClipEditorMockState';

// 시안 1d-a 타임라인 한 줄 — 라벨 열(172px) + 레인.
// 레인을 어떻게 그릴지는 트랙 종류가 정한다: 영상은 필름스트립, 소리 트랙은 파형,
// 나머지는 조각. 트랙 목록 자체는 데이터라서(E2) 여기서 늘리거나 줄이지 않는다.
//
// 실데이터(필름스트립 스프라이트·파형)가 있으면 그것을 그리고, 없으면 CSS 자리 표시자로 남는다.
// memo 로 감싼 이유: 재생 중 플레이헤드가 초당 열 번 바뀌며 허브가 리렌더되는데, 이 줄의
// props(트랙·창·핸들러)는 그 사이 그대로다. 파형 막대 수백 개를 매번 다시 만들 이유가 없다.

const TRACK_ICONS: Record<EditorTrackKind, ComponentType<{ size?: number }>> = {
  video: Film,
  mix: AudioLines,
  mic: Mic,
  game: SlidersHorizontal,
  bgm: Music,
  sfx: Sparkles,
  image: ImageIcon,
};

/** 필름스트립 칸 수 — 레인 폭(≈1270px)에서 한 칸이 썸네일 실폭(160px)에 가깝게 */
const FILMSTRIP_COLUMNS = 8;
/** 파형 막대 상한. 이 이상은 1px 미만이라 눈에 안 보이고 DOM 만 늘어난다 */
const WAVEFORM_MAX_BARS = 320;

function TimelineTrackRowImpl({
  track,
  view,
  selectedClipId,
  onSelectClip,
  onVolumeChange,
  gestureHandlers,
}: {
  track: EditorTrack;
  view: TimelineView;
  selectedClipId: string | null;
  onSelectClip: (clipId: string) => void;
  onVolumeChange: (trackId: string, volume: number) => void;
  /** 드래그 한 번을 실행취소 한 칸으로 묶는다 */
  gestureHandlers: ClipEditorMockState['gestureHandlers'];
}) {
  const Icon = TRACK_ICONS[track.kind];
  const isAudio = track.kind === 'mix' || track.kind === 'mic' || track.kind === 'game';

  const tiles = useMemo(
    () =>
      track.filmstrip === undefined ? [] : filmstripTiles(track.filmstrip, view, FILMSTRIP_COLUMNS),
    [track.filmstrip, view],
  );
  const bars = useMemo(
    () => (track.peaks === undefined ? [] : waveformBars(track.peaks, view, WAVEFORM_MAX_BARS)),
    [track.peaks, view],
  );

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
              {...gestureHandlers}
              onValueChange={(volume) => onVolumeChange(track.id, volume)}
            />
            <span className={styles.trackValue}>{track.volume}%</span>
          </span>
        ) : null}
      </span>

      <div className={styles.lane} data-kind={track.kind}>
        {track.kind === 'video' && tiles.length === 0 ? (
          <div className={styles.laneFilmstrip} aria-hidden />
        ) : null}
        {tiles.length > 0 ? (
          <div className={styles.laneTiles} aria-hidden>
            {tiles.map((tile) => (
              <span
                key={tile.left}
                className={styles.laneTile}
                style={{
                  left: `${tile.left * 100}%`,
                  width: `${tile.width * 100}%`,
                  backgroundImage: `url(${tile.sheetUrl})`,
                  backgroundPosition: tile.backgroundPosition,
                  backgroundSize: tile.backgroundSize,
                }}
              />
            ))}
          </div>
        ) : null}
        {isAudio && bars.length === 0 ? (
          <div
            className={`${styles.laneWave} ${track.muted ? styles.laneMuted : ''}`}
            aria-hidden
          />
        ) : null}
        {bars.length > 0 ? (
          // preserveAspectRatio="none" 으로 0..1 좌표를 레인 크기에 그대로 늘린다 —
          // 줌이나 트랙 높이가 바뀌어도 다시 계산할 필요가 없다
          <svg
            className={`${styles.laneWaveSvg} ${track.muted ? styles.laneMuted : ''}`}
            viewBox="0 0 1 1"
            preserveAspectRatio="none"
            aria-hidden
          >
            {bars.map((bar) => (
              <rect
                key={bar.x}
                x={bar.x}
                width={bar.width}
                y={(1 - bar.height) / 2}
                height={bar.height}
              />
            ))}
          </svg>
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

export const TimelineTrackRow = memo(TimelineTrackRowImpl);
