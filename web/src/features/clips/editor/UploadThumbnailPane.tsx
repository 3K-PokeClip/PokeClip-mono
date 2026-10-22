'use client';

import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { RadioGroup, Text } from '@/ui';
import type { ThumbnailSource } from '@/api/clipEditor';
import {
  SCENE_STEP_MS,
  sceneLabel,
  THUMBNAIL_FILE_HINT,
  thumbnailFileProblem,
  type ScenePick,
} from './uploadInfo';
import styles from './UploadInfoDialog.module.css';

// 업로드 창의 썸네일 탭(POK-381, 시안 T2 「펼치는 목록」). 영상 장면 · 내 이미지 · 유튜브에 맡기기를 설명과 함께 위아래로 두고
// 고른 것만 펼친다. 장면은 그림으로 고른다: 추천 장면 타일 한 줄 + 끌어서 0.25초까지 맞추는 장면 띠.

/** 장면 띠의 칸 수 */
export const STRIP_TILES = 12;

/** 영상 안(0 ~ 길이−1)의 눈금 자리로 */
export function snapScene(ms: number, cutLengthMs: number): number {
  const max = Math.max(0, cutLengthMs - 1);
  return Math.min(max, Math.max(0, Math.round(ms / SCENE_STEP_MS) * SCENE_STEP_MS));
}

/** 장면 띠 칸마다 보여 줄 시각(칸 가운데) */
export function stripTileTimes(cutLengthMs: number): number[] {
  return Array.from({ length: STRIP_TILES }, (_, i) =>
    snapScene(((i + 0.5) * cutLengthMs) / STRIP_TILES, cutLengthMs),
  );
}

const OPTIONS: { value: ThumbnailSource; title: string }[] = [
  { value: 'scene', title: '영상 장면' },
  { value: 'file', title: '내 이미지' },
  { value: 'none', title: '유튜브에 맡기기' },
];

export function UploadThumbnailPane({
  source,
  sceneOffsetMs,
  file,
  previewUrl,
  cutLengthMs,
  picks,
  frameOf,
  error,
  onSource,
  onScene,
  onFile,
  onFileProblem,
  onHover,
}: {
  source: ThumbnailSource;
  sceneOffsetMs: number;
  file: File | null;
  /** 고른 이미지의 로컬 주소 */
  previewUrl: string | null;
  cutLengthMs: number;
  picks: readonly ScenePick[];
  /** 장면 그림. 아직 못 뽑았거나 녹화가 없으면 null(시각만 보인다) */
  frameOf: (offsetMs: number) => string | null;
  error: string | undefined;
  onSource: (source: ThumbnailSource) => void;
  onScene: (offsetMs: number) => void;
  onFile: (file: File) => void;
  onFileProblem: (problem: string) => void;
  /** 띠 위에 올린 자리(그 장면부터 뽑게). 벗어나면 null */
  onHover: (offsetMs: number | null) => void;
}) {
  const pick = picks.find((p) => p.offsetMs === sceneOffsetMs);
  const summary: Record<ThumbnailSource, string> = {
    scene: `${pick?.label ?? '직접 고름'} · ${sceneLabel(sceneOffsetMs)}`,
    file: file?.name ?? THUMBNAIL_FILE_HINT,
    none: '유튜브가 영상에서 한 장면을 골라요',
  };
  const mini: Record<ThumbnailSource, string | null> = {
    scene: frameOf(sceneOffsetMs),
    file: previewUrl,
    none: null,
  };

  return (
    <RadioGroup
      value={source}
      onValueChange={(v) => onSource(v as ThumbnailSource)}
      aria-label="썸네일"
      className={styles.thumbList}
    >
      {OPTIONS.map((option) => {
        const on = source === option.value;
        const image = on ? null : mini[option.value];
        return (
          <div key={option.value} className={styles.thumbItem} data-on={on || undefined}>
            <RadioGroup.Item
              value={option.value}
              className={styles.thumbHead}
              label={
                <span className={styles.thumbHeadText}>
                  <span className={styles.thumbTitle}>
                    <b>{option.title}</b>
                    <span>{summary[option.value]}</span>
                  </span>
                  {image !== null ? (
                    // 뽑은 장면(data:)·고른 파일(blob:)이라 next/image가 다룰 대상이 아니다
                    // eslint-disable-next-line @next/next/no-img-element
                    <img className={styles.thumbMini} src={image} alt="" />
                  ) : null}
                </span>
              }
            />
            {on && option.value === 'scene' ? (
              <div className={styles.thumbBody}>
                <div
                  className={styles.picks}
                  style={{ gridTemplateColumns: `repeat(${picks.length}, 1fr)` }}
                >
                  {picks.map((p) => (
                    <PickTile
                      key={p.id}
                      pick={p}
                      selected={p.offsetMs === sceneOffsetMs}
                      image={frameOf(p.offsetMs)}
                      onPick={() => onScene(p.offsetMs)}
                    />
                  ))}
                </div>
                <div>
                  <span className={styles.label}>직접 고르기 · {sceneLabel(sceneOffsetMs)}</span>
                  <SceneStrip
                    valueMs={sceneOffsetMs}
                    cutLengthMs={cutLengthMs}
                    picks={picks}
                    frameOf={frameOf}
                    onChange={onScene}
                    onHover={onHover}
                  />
                  <Text as="p" size="xs" tone="muted" className={styles.stripHint}>
                    끌거나 ← → 로 0.25초씩, Shift를 누르면 1초씩 옮겨요
                  </Text>
                </div>
              </div>
            ) : null}
            {on && option.value === 'file' ? (
              <div className={styles.thumbBody}>
                <ImageDrop previewUrl={previewUrl} onFile={onFile} onProblem={onFileProblem} />
              </div>
            ) : null}
          </div>
        );
      })}
      {error !== undefined ? (
        <Text as="p" size="sm" tone="danger" role="alert">
          {error}
        </Text>
      ) : null}
    </RadioGroup>
  );
}

function PickTile({
  pick,
  selected,
  image,
  onPick,
}: {
  pick: ScenePick;
  selected: boolean;
  image: string | null;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      className={styles.pick}
      aria-pressed={selected}
      aria-label={`${pick.label} ${sceneLabel(pick.offsetMs)}`}
      onClick={onPick}
    >
      {image !== null ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={image} alt="" />
      ) : (
        <span className={styles.pickTime}>{sceneLabel(pick.offsetMs)}</span>
      )}
      <span className={styles.pickLabel}>{pick.label}</span>
      {selected ? (
        <span className={styles.pickCheck}>
          <CheckIcon />
        </span>
      ) : null}
    </button>
  );
}

/**
 * 장면 띠. 끌거나 눌러 고르고, 올리면 그 자리 장면이 크게 뜬다. ← → 0.25초 · Shift 1초 · Home/End 처음·끝.
 * 위의 작은 점은 추천 장면 자리다.
 */
function SceneStrip({
  valueMs,
  cutLengthMs,
  picks,
  frameOf,
  onChange,
  onHover,
}: {
  valueMs: number;
  cutLengthMs: number;
  picks: readonly ScenePick[];
  frameOf: (offsetMs: number) => string | null;
  onChange: (offsetMs: number) => void;
  onHover: (offsetMs: number | null) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [dragging, setDragging] = useState(false);
  const max = Math.max(0, cutLengthMs - 1);
  const at = (clientX: number) => {
    const rect = ref.current?.getBoundingClientRect();
    if (!rect || rect.width === 0) return valueMs;
    const x = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    return snapScene(x * cutLengthMs, cutLengthMs);
  };
  const pct = (ms: number) => `${(Math.min(max, ms) / Math.max(1, cutLengthMs)) * 100}%`;
  const showHover = (ms: number | null) => {
    setHover(ms);
    onHover(ms);
  };
  const onKey = (event: KeyboardEvent) => {
    const step = event.shiftKey ? 1000 : SCENE_STEP_MS;
    const next =
      event.key === 'ArrowLeft' || event.key === 'ArrowDown'
        ? valueMs - step
        : event.key === 'ArrowRight' || event.key === 'ArrowUp'
          ? valueMs + step
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? max
              : null;
    if (next === null) return;
    event.preventDefault();
    onChange(next >= max ? max : snapScene(next, cutLengthMs));
  };
  const hoverImage = hover === null ? null : frameOf(hover);
  return (
    <div
      ref={ref}
      className={styles.strip}
      role="slider"
      tabIndex={0}
      aria-label="썸네일 장면"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={valueMs}
      aria-valuetext={sceneLabel(valueMs)}
      onPointerDown={(event: PointerEvent<HTMLDivElement>) => {
        event.currentTarget.setPointerCapture?.(event.pointerId);
        setDragging(true);
        onChange(at(event.clientX));
      }}
      onPointerMove={(event) => {
        const ms = at(event.clientX);
        showHover(ms);
        if (dragging) onChange(ms);
      }}
      onPointerUp={() => setDragging(false)}
      onPointerCancel={() => setDragging(false)}
      onPointerLeave={() => showHover(null)}
      onKeyDown={onKey}
    >
      <div className={styles.stripTiles} aria-hidden>
        {stripTileTimes(cutLengthMs).map((ms, i) => {
          const image = frameOf(ms);
          return image !== null ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img key={i} src={image} alt="" draggable={false} />
          ) : (
            <span key={i} />
          );
        })}
      </div>
      {picks.map((p) => (
        <span
          key={p.id}
          className={styles.stripMark}
          style={{ left: pct(p.offsetMs) }}
          aria-hidden
        />
      ))}
      <span className={styles.stripHandle} style={{ left: pct(valueMs) }} aria-hidden>
        <span className={styles.stripTime}>{sceneLabel(valueMs)}</span>
      </span>
      {hover !== null && !dragging ? (
        <span className={styles.stripHover} style={{ left: pct(hover) }} aria-hidden>
          {hoverImage !== null ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={hoverImage} alt="" />
          ) : (
            <span className={styles.stripHoverEmpty} />
          )}
          <span>{sceneLabel(hover)}</span>
        </span>
      ) : null}
    </div>
  );
}

/** 이미지 놓는 자리: 끌어 놓거나 눌러서 고른다. 규칙(JPG·PNG 10MB)에 어긋나면 받지 않고 까닭을 올린다 */
function ImageDrop({
  previewUrl,
  onFile,
  onProblem,
}: {
  previewUrl: string | null;
  onFile: (file: File) => void;
  onProblem: (problem: string) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const take = (files: FileList | null) => {
    const file = files?.[0];
    if (file === undefined) return;
    const problem = thumbnailFileProblem(file);
    if (problem !== null) onProblem(problem);
    else onFile(file);
  };
  return (
    <div
      className={styles.drop}
      data-over={over || undefined}
      role="button"
      tabIndex={0}
      aria-label={previewUrl === null ? '이미지 고르기' : '다른 이미지 고르기'}
      onClick={() => input.current?.click()}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          input.current?.click();
        }
      }}
      onDragOver={(event) => {
        event.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        event.preventDefault();
        setOver(false);
        take(event.dataTransfer.files);
      }}
    >
      {previewUrl !== null ? (
        // 고른 파일의 로컬 주소라 next/image가 다룰 대상이 아니다(PhotoCropStage 선례)
        // eslint-disable-next-line @next/next/no-img-element
        <img src={previewUrl} alt="고른 썸네일 미리보기" />
      ) : (
        <>
          <UploadIcon />
          <Text size="sm" weight="medium">
            이미지를 끌어 놓거나 눌러서 고르기
          </Text>
          <Text size="xs" tone="muted">
            {THUMBNAIL_FILE_HINT}
          </Text>
        </>
      )}
      {/* 실제 파일 선택기. 위 자리가 대신 누른다 */}
      <input
        ref={input}
        type="file"
        accept="image/jpeg,image/png"
        hidden
        onChange={(event) => {
          take(event.target.files);
          event.target.value = ''; // 같은 파일을 다시 골라도 change가 뜨게
        }}
      />
    </div>
  );
}

function UploadIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="22"
      height="22"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="12"
      height="12"
      fill="none"
      stroke="currentColor"
      strokeWidth="3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M5 12l5 5 9-10" />
    </svg>
  );
}
