import { Camera, Check, ChevronDown, Pause, Play, Repeat } from 'lucide-react';
import { DropdownMenu, IconButton } from '@/ui';
import { SeekIcon } from './SeekIcon';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 미리보기 아래 — 재생 이동·쇼츠 안 시각 · 오른쪽은 같은 크기 칩 셋(구간 반복 · 배속 · 썸네일, POK-389).

/** 쇼츠 안 시각 m:ss.t — 구간 시작이 0:00.0이다 */
function shortsClock(seconds: number): string {
  const tenths = Math.max(0, Math.round(seconds * 10));
  const m = Math.floor(tenths / 600);
  const s = Math.floor((tenths % 600) / 10);
  return `${m}:${String(s).padStart(2, '0')}.${tenths % 10}`;
}

export function TransportBar({ state }: { state: ClipEditorMockState }) {
  const position = Math.min(
    Math.max(0, state.playheadSeconds - state.range.startSeconds),
    state.rangeLengthSeconds,
  );

  return (
    <div className={styles.transport}>
      <IconButton variant="ghost" size="sm" aria-label="5초 뒤로" onClick={() => state.seekBy(-5)}>
        <SeekIcon direction="back" seconds={5} />
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="1초 뒤로" onClick={() => state.seekBy(-1)}>
        <SeekIcon direction="back" seconds={1} />
      </IconButton>
      <IconButton
        variant="ghost"
        size="md"
        aria-label={state.playing ? '일시정지' : '재생'}
        onClick={state.togglePlay}
      >
        {state.playing ? (
          <Pause className={styles.transportPlayIcon} aria-hidden />
        ) : (
          <Play className={styles.transportPlayIcon} aria-hidden />
        )}
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="1초 앞으로" onClick={() => state.seekBy(1)}>
        <SeekIcon direction="forward" seconds={1} />
      </IconButton>
      <IconButton variant="ghost" size="sm" aria-label="5초 앞으로" onClick={() => state.seekBy(5)}>
        <SeekIcon direction="forward" seconds={5} />
      </IconButton>
      <span className={styles.transportTime}>
        {shortsClock(position)}{' '}
        <span className={styles.transportTotal}>/ {shortsClock(state.rangeLengthSeconds)}</span>
      </span>
      <div className={styles.transportRight}>
        <button
          type="button"
          className={styles.transportChip}
          aria-label="구간 반복"
          aria-pressed={state.loop}
          onClick={state.toggleLoop}
        >
          <Repeat size={14} aria-hidden /> 반복
        </button>
        <DropdownMenu>
          <DropdownMenu.Trigger>
            <button
              type="button"
              className={styles.transportChip}
              aria-label={`재생 속도 ${state.speed}×`}
            >
              {state.speed}×<ChevronDown size={13} aria-hidden />
            </button>
          </DropdownMenu.Trigger>
          <DropdownMenu.Content>
            <DropdownMenu.Label>재생 속도</DropdownMenu.Label>
            {state.speedOptions.map((speed) => (
              <DropdownMenu.Item key={speed} onSelect={() => state.setSpeed(speed)}>
                <span className={styles.transportMenuItem}>
                  {speed}×{speed === state.speed ? <Check size={13} aria-hidden /> : null}
                </span>
              </DropdownMenu.Item>
            ))}
          </DropdownMenu.Content>
        </DropdownMenu>
        {/* 고른 장면은 영상 만들기 창의 「장면 고르기」로 채워진다. 쇼츠 구간 밖에서는 못 고른다(POK-389) */}
        <button
          type="button"
          className={styles.transportChip}
          aria-label="이 장면을 썸네일로"
          title="이 장면을 썸네일로"
          disabled={!state.canPickThumbnail}
          onClick={state.pickThumbnail}
        >
          <Camera size={14} aria-hidden /> 썸네일
        </button>
      </div>
    </div>
  );
}
