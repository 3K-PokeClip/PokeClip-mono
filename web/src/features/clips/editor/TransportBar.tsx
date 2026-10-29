import type { MouseEvent } from 'react';
import { Camera, Check, ChevronDown, Pause, Play, Repeat } from 'lucide-react';
import { DropdownMenu, IconButton } from '@/ui';
import { SeekIcon } from './SeekIcon';
import styles from './editorShared.module.css';
import type { ClipEditorMockState } from './useClipEditorMockState';

// 시안 1d 미리보기 아래 — 재생 이동·쇼츠 안 시각 · 오른쪽은 같은 크기 칩 셋(구간 반복 · 배속 · 썸네일, POK-389).

/**
 * 마우스로 누른 재생 막대 단추는 포커스를 가져가지 않는다(POK-490). 포커스를 쥔 단추 위의 Space는 편집기가 그 단추에
 * 양보한다(StudioScreen KEY_OWNERS) — 「반복」을 눌러 켠 뒤 Space로 재생하려 하면 재생 대신 반복이 다시 꺼지고, 포커스 테두리
 * 때문에 꺼진 칩이 켜진 것처럼 보였다(로컬 실측). Tab으로 온 키보드 사용자는 지금처럼 Space로 그 단추를 누른다.
 * 배속은 눌러 메뉴로 들어가야 해서 뺀다.
 *
 * 포커스를 안 가져가기만 하면 직전에 누른 위젯(트랙 넣기 · 도구 탭 · 스위치)이 포커스를 쥔 채 남아, 그 위젯이 Space를 가져간다
 * — 「재생」을 눌러도 Space가 일시정지 대신 그 위젯을 눌렀다(PR #454 1차 리뷰). 그래서 지금 포커스도 놓아 준다: 포커스는 문서로
 * 돌아가고 Space는 편집기의 재생이 된다(PR 전에는 재생 단추가 포커스를 받아 같은 결과였다).
 */
function keepEditorFocus(event: MouseEvent) {
  event.preventDefault();
  const focused = document.activeElement;
  if (focused instanceof HTMLElement && focused !== document.body) focused.blur();
}

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
      <IconButton
        variant="ghost"
        size="sm"
        aria-label="5초 뒤로"
        onMouseDown={keepEditorFocus}
        onClick={() => state.seekBy(-5)}
      >
        <SeekIcon direction="back" seconds={5} />
      </IconButton>
      <IconButton
        variant="ghost"
        size="sm"
        aria-label="1초 뒤로"
        onMouseDown={keepEditorFocus}
        onClick={() => state.seekBy(-1)}
      >
        <SeekIcon direction="back" seconds={1} />
      </IconButton>
      <IconButton
        variant="ghost"
        size="md"
        aria-label={state.playing ? '일시정지' : '재생'}
        onMouseDown={keepEditorFocus}
        onClick={state.togglePlay}
      >
        {state.playing ? (
          <Pause className={styles.transportPlayIcon} aria-hidden />
        ) : (
          <Play className={styles.transportPlayIcon} aria-hidden />
        )}
      </IconButton>
      <IconButton
        variant="ghost"
        size="sm"
        aria-label="1초 앞으로"
        onMouseDown={keepEditorFocus}
        onClick={() => state.seekBy(1)}
      >
        <SeekIcon direction="forward" seconds={1} />
      </IconButton>
      <IconButton
        variant="ghost"
        size="sm"
        aria-label="5초 앞으로"
        onMouseDown={keepEditorFocus}
        onClick={() => state.seekBy(5)}
      >
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
          onMouseDown={keepEditorFocus}
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
          onMouseDown={keepEditorFocus}
          onClick={state.pickThumbnail}
        >
          <Camera size={14} aria-hidden /> 썸네일
        </button>
      </div>
    </div>
  );
}
