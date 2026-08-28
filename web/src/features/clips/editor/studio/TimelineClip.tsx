import styles from './StudioScreen.module.css';
import { secondsToFraction, type TimelineView } from '../timelineMath';
import type { EditorTrackClip, EditorTrackKind } from '../useClipEditorMockState';

// 시안 1d-a 타임라인 위의 BGM·효과음·이미지 조각.
// 트림 핸들은 hover·포커스에서만 보인다 — 트랙이 여섯 줄이라 항상 보이면 눈이 시끄럽다.
// 실제 트림은 배선 티켓 몫이라 여기선 자리와 조작 지점만 보여준다.

export function TimelineClip({
  clip,
  kind,
  view,
  selected,
  onSelect,
}: {
  clip: EditorTrackClip;
  kind: EditorTrackKind;
  view: TimelineView;
  selected: boolean;
  onSelect: () => void;
}) {
  const left = secondsToFraction(clip.startSeconds, view);
  const right = secondsToFraction(clip.endSeconds, view);
  // 창 밖으로 완전히 벗어난 조각은 그리지 않는다
  if (right <= 0 || left >= 1) return null;

  return (
    <button
      type="button"
      className={styles.clip}
      data-kind={kind}
      aria-pressed={selected}
      style={{ left: `${left * 100}%`, width: `${(right - left) * 100}%` }}
      onClick={onSelect}
    >
      <span className={`${styles.clipTrim} ${styles.clipTrimStart}`} aria-hidden />
      {clip.label}
      <span className={`${styles.clipTrim} ${styles.clipTrimEnd}`} aria-hidden />
    </button>
  );
}
