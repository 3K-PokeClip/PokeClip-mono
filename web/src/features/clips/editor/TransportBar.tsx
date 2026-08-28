import { Pause, Play, RotateCcw, RotateCw } from 'lucide-react';
import { IconButton, Switch } from '@/ui';
import { Segmented } from './Segmented';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 미리보기 아래 — 재생 이동·현재 시각·배속·구간 반복.

export function TransportBar({
  state,
  showRangeLength = false,
}: {
  state: ClipEditorMockState;
  /** 시안에서 스튜디오형만 "/ 구간 12.4초"를 함께 적는다 */
  showRangeLength?: boolean;
}) {
  return (
    <div className={styles.transport}>
      <IconButton variant="ghost" size="sm" aria-label="5초 뒤로" onClick={() => state.seekBy(-5)}>
        <RotateCcw size={17} aria-hidden />
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="1초 뒤로" onClick={() => state.seekBy(-1)}>
        <RotateCcw size={15} aria-hidden />
      </IconButton>
      <IconButton
        variant="ghost"
        size="md"
        aria-label={state.playing ? '일시정지' : '재생'}
        onClick={state.togglePlay}
      >
        {state.playing ? <Pause size={22} aria-hidden /> : <Play size={22} aria-hidden />}
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="1초 앞으로" onClick={() => state.seekBy(1)}>
        <RotateCw size={15} aria-hidden />
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="5초 앞으로" onClick={() => state.seekBy(5)}>
        <RotateCw size={17} aria-hidden />
      </IconButton>
      <span className={styles.transportTime}>{state.playheadLabel}</span>
      {showRangeLength ? (
        <span className={styles.transportNote}>/ 구간 {state.rangeLengthLabel}</span>
      ) : null}
      <div className={styles.transportRight}>
        <span className={styles.transportNote}>배속</span>
        <Segmented
          label="재생 속도"
          size="sm"
          options={state.speedOptions.map((speed) => ({ value: speed, label: `${speed}×` }))}
          value={state.speed}
          onChange={state.setSpeed}
        />
        <span className={styles.transportDivider} aria-hidden />
        <Switch
          size="sm"
          label="구간 반복"
          checked={state.loop}
          onChange={state.toggleLoop}
        />
      </div>
    </div>
  );
}
