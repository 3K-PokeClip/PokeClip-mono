import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { Volume2, VolumeX } from 'lucide-react';
import { Popover, Skeleton, Slider } from '@/ui';
import styles from './StudioScreen.module.css';
import { TrackWaveform } from './TrackWaveform';
import type { ClipEditorMockState, EditorTrack } from '../useClipEditorMockState';

// 방송 소리 트랙 한 줄(POK-389): 우리만 가진 「트랙별 소리」를 타임라인의 주인공으로 둔다.
// 머리: 넣기(스피커) · 이름 · 음량 %. 음량 숫자는 누르면 막대가 뜨고, 좌우로 끌면 바로 바뀐다(편집 프로그램 방식).
// 뺀 줄은 숫자를 감추고 이름만 한 단계 흐리게 둔다(못 누르는 줄처럼 보이지 않게). 오디오 메뉴에는 음량이 없다.
// 파형은 미리 듣기로 풀어 둔 이 트랙 소리의 실제 크기다(음량을 반영, 무음은 평평, 뺀 줄은 흐리게). 목업은 모양만 그린다.

/** 저장 계약의 음량 상한(%) */
const MAX_VOLUME = 200;
/** 음량 막대 아래 바로 고르는 값 */
const VOLUME_PRESETS = [50, 100, 150] as const;
/** 이만큼 움직여야 누르기가 아니라 끌기다(px) — 손떨림으로 막대가 안 뜨는 일을 막는다 */
const DRAG_THRESHOLD_PX = 3;
/** ← → 한 번에 바뀌는 음량(%) */
const KEY_STEP = 5;

type VolumeControls = Pick<ClipEditorMockState, 'setTrackVolume' | 'gestureHandlers' | 'reloading'>;

/** 음량 숫자: 누르면 막대 · 50/100/150%가 뜨고, 좌우로 끌면 1px = 1%로 바뀐다. 초점이 있으면 ← → 로 5%씩 */
function VolumeValue({ track, controls }: { track: EditorTrack; controls: VolumeControls }) {
  const [open, setOpen] = useState(false);
  // 다른 편집 대상을 읽기 시작하면 막대 창을 닫는다(PR #377 2차 리뷰): 창은 body로 포털돼 타임라인의 inert가 닿지 않아, 열어
  // 두면 곧 사라질 화면의 음량을 계속 바꿀 수 있다(업로드 창을 닫는 것과 같은 이유)
  if (controls.reloading && open) setOpen(false);
  const drag = useRef<{ x: number; start: number; moved: boolean } | null>(null);
  // 끈 뒤 따라오는 click은 막대를 열지 않는다. click이 안 오면(밖에서 놓음) 다음 틱에 풀어 다음 누르기를 먹지 않게 한다
  const swallowClick = useRef(false);
  const value = track.volume ?? 100;
  const setVolume = (next: number) =>
    controls.setTrackVolume(track.id, Math.round(Math.min(MAX_VOLUME, Math.max(0, next))));

  const endDrag = () => {
    if (drag.current === null) return;
    if (drag.current.moved) {
      swallowClick.current = true;
      window.setTimeout(() => {
        swallowClick.current = false;
      }, 0);
    }
    drag.current = null;
    controls.gestureHandlers.onPointerUp();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const step =
      event.key === 'ArrowRight' || event.key === 'ArrowUp'
        ? KEY_STEP
        : event.key === 'ArrowLeft' || event.key === 'ArrowDown'
          ? -KEY_STEP
          : 0;
    if (step === 0) return;
    // 화살표는 이 숫자의 것이다: 편집기 전체의 재생 위치 이동으로 새지 않게 막는다.
    // 누르고 있는 자동반복은 실행취소 칸을 잔뜩 쌓으므로 걸러 낸다(음량 막대와 같은 규약)
    event.preventDefault();
    event.stopPropagation();
    if (!event.repeat) setVolume(value + step);
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        if (swallowClick.current) return;
        setOpen(next);
      }}
      side="right"
      align="center"
    >
      <Popover.Trigger>
        <button
          type="button"
          className={styles.trackVolume}
          aria-label={`${track.label} 음량 ${value}%`}
          title="눌러서 막대 · 좌우로 끌어 조절"
          onPointerDown={(event: PointerEvent<HTMLButtonElement>) => {
            if (event.button !== 0) return;
            event.currentTarget.setPointerCapture?.(event.pointerId);
            drag.current = { x: event.clientX, start: value, moved: false };
            controls.gestureHandlers.onPointerDownCapture();
          }}
          onPointerMove={(event) => {
            const current = drag.current;
            if (current === null) return;
            const dx = event.clientX - current.x;
            if (!current.moved && Math.abs(dx) < DRAG_THRESHOLD_PX) return;
            current.moved = true;
            setVolume(current.start + dx);
          }}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
          // 캡처를 잃어도 끝이다: up·cancel 없이 끝나면 제스처가 열린 채 남아 다음 편집들이 한 실행취소 칸에 섞인다(PR #341 claude)
          onLostPointerCapture={endDrag}
          onKeyDown={onKeyDown}
        >
          {value}%
        </button>
      </Popover.Trigger>
      <Popover.Content>
        <div className={styles.volumePopover}>
          <span className={styles.volumePopoverHead}>
            {track.label}
            <b>{value}%</b>
          </span>
          <Slider
            label={`${track.label} 볼륨`}
            value={value}
            max={MAX_VOLUME}
            {...controls.gestureHandlers}
            onValueChange={setVolume}
          />
          <span className={styles.volumePresets}>
            {VOLUME_PRESETS.map((preset) => (
              <button
                key={preset}
                type="button"
                className={styles.volumePreset}
                aria-pressed={value === preset}
                onClick={() => setVolume(preset)}
              >
                {preset}%
              </button>
            ))}
          </span>
        </div>
      </Popover.Content>
    </Popover>
  );
}

export function TimelineTrackRow({
  track,
  onToggleMute,
  volumeControls,
  levels,
  view,
  levelsLoading = false,
}: {
  track: EditorTrack;
  onToggleMute: (trackId: string) => void;
  volumeControls: VolumeControls;
  /** 소리 크기. undefined면 목업(모양만), null이면 아직·못 불러 빈 줄(받는 중이면 levelsLoading이 스켈레톤으로 채운다) */
  levels: { values: Float32Array; startSeconds: number; stepSeconds: number } | null | undefined;
  view: { startSeconds: number; endSeconds: number };
  /**
   * 트랙 소리를 받는 중이다(미리 듣기 loading). 아직 파형이 없으면 빈 줄 대신 줄 높이 스켈레톤을 둔다(POK-444).
   * 못 불렀거나(사용 불가 — 미리보기 아래 안내가 말한다) 받을 창이 없으면 빈 줄 그대로다. 앞 창의 파형이 있으면 그것을 둔다
   */
  levelsLoading?: boolean;
}) {
  return (
    <div
      className={styles.trackRow}
      data-track-row={track.id}
      data-muted={track.muted || undefined}
    >
      <span className={styles.trackLabel}>
        <button
          type="button"
          className={styles.trackSpeaker}
          aria-pressed={!track.muted}
          aria-label={`${track.label} 넣기`}
          title={track.muted ? '넣기' : '빼기'}
          onClick={() => onToggleMute(track.id)}
        >
          {track.muted ? <VolumeX size={14} aria-hidden /> : <Volume2 size={14} aria-hidden />}
        </button>
        <span data-row-label>{track.label}</span>
        {track.muted ? null : <VolumeValue track={track} controls={volumeControls} />}
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
        ) : levelsLoading ? (
          // 파형처럼 그림일 뿐이라 낭독할 것이 없다(Skeleton은 aria-hidden) — 줄마다 status를 세우지 않는다
          <Skeleton height="100%" data-wave-loading />
        ) : null}
      </div>
    </div>
  );
}
