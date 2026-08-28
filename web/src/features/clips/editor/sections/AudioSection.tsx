import { Slider, Switch } from '@/ui';
import styles from './sections.module.css';
import type { ClipEditorMockState, EditorTrackKind } from '../useClipEditorMockState';

// 시안 1d 오디오 도구 — 트랙별 켜기·볼륨.
// 어떤 트랙이 있는지는 데이터가 정한다 (E2 — RMS 무음 필터를 통과한 트랙만 온다).

export function AudioSection({
  state,
  kinds,
  title,
  hint,
}: {
  state: ClipEditorMockState;
  /** 이 패널이 맡는 트랙 종류 — 오디오 탭과 BGM·효과 탭이 같은 컴포넌트를 나눠 쓴다 */
  kinds: readonly EditorTrackKind[];
  title: string;
  hint?: string;
}) {
  const tracks = state.tracks.filter(
    (track) => kinds.includes(track.kind) && track.volume !== null,
  );

  return (
    <section className={styles.section} aria-label={title}>
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>{title}</span>
        <span className={styles.hint}>트랙별 볼륨</span>
      </div>

      {tracks.map((track) => (
        <div key={track.id} className={styles.trackRow}>
          <Switch
            size="sm"
            aria-label={`${track.label} 사용`}
            checked={!track.muted}
            onChange={() => state.toggleTrackMute(track.id)}
          />
          <span className={styles.trackName}>{track.label}</span>
          <Slider
            className={styles.trackSlider}
            label={`${track.label} 볼륨`}
            value={track.volume ?? 0}
            onValueChange={(volume) => state.setTrackVolume(track.id, volume)}
          />
          <span className={styles.trackValue}>{track.volume}%</span>
        </div>
      ))}

      {hint !== undefined ? <p className={styles.hint}>{hint}</p> : null}
    </section>
  );
}
