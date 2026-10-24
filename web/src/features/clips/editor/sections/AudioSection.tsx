import { Checkbox, Slider } from '@/ui';
import { PendingNote } from './PendingNote';
import styles from './sections.module.css';
import type { ClipEditorMockState } from '../useClipEditorMockState';

// 오디오 도구(POK-389): 우리만 가진 것: 방송 소리를 트랙별로 따로 받는다.
// 위: 방송 소리 트랙마다 넣기·음량 · 아래: 음악·효과음(준비 중).
// 어떤 트랙이 있는지는 데이터가 정한다(스트리머가 적은 트랙 이름, POK-240).

export function AudioSection({ state }: { state: ClipEditorMockState }) {
  const tracks = state.tracks.filter((track) => track.volume !== null);

  return (
    <section className={styles.section} aria-label="오디오">
      <div className={styles.sectionHead}>
        <span className={styles.sectionTitle}>방송 소리</span>
        <span className={styles.hint}>트랙마다 넣기 · 음량</span>
      </div>
      {tracks.map((track) => (
        <div key={track.id} className={styles.trackRow} data-muted={track.muted || undefined}>
          <Checkbox
            size="sm"
            aria-label={`${track.label} 넣기`}
            checked={!track.muted}
            onChange={() => state.toggleTrackMute(track.id)}
          />
          <span className={styles.trackName}>{track.label}</span>
          <Slider
            className={styles.trackSlider}
            label={`${track.label} 볼륨`}
            value={track.volume ?? 0}
            // 저장 계약의 음량은 0~200%다. 100에서 자르면 저장된 큰 음량을 손으로 옮기는 순간 줄어든다
            max={200}
            {...state.gestureHandlers}
            onValueChange={(volume) => state.setTrackVolume(track.id, volume)}
          />
          <span className={styles.trackValue}>{track.muted ? '안 넣음' : `${track.volume}%`}</span>
        </div>
      ))}
      <p className={styles.hint}>켠 소리만 영상에 들어가요</p>

      <div className={styles.divider} />
      <PendingNote title="음악 · 효과음" />
    </section>
  );
}
