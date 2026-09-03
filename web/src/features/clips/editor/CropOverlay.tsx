'use client';

import { useRef, type KeyboardEvent, type PointerEvent, type RefObject } from 'react';
import {
  CROP_CORNERS,
  CROP_CORNER_LABELS,
  CROP_KEY_STEP,
  CROP_ZOOM_STEP,
  normalizePointer,
  type CropCorner,
  type CropRect,
} from './cropMath';
import styles from './editorShared.module.css';
import type { ClipEditorMockState, EditorSource } from './useClipEditorMockState';

// 소스 위에 얹히는 크롭 사각형 (POK-109).
//
// 편집자는 소스 전체를 보면서 이 사각형을 잡는다 — 사각형 안을 끌면 위치가, 모서리를 끌면 범위가
// 바뀐다. 비율은 내보내는 화면이 정하므로 모서리를 끌어도 안 바뀐다(계약6 종횡비 검증에서
// 흔들리면 렌더가 거부한다).
//
// 마우스와 키보드가 같은 문(state.dragCrop / nudgeCrop / resizeCrop / zoomCrop)으로 들어간다 —
// 구간 핸들이 쓰는 규약과 같다. jsdom 엔 레이아웃이 없어 포인터 경로는 렌더 테스트로 못 재지만,
// 키보드 경로가 같은 액션으로 들어가므로 화면에서도 경계 동작을 확인할 수 있다.

function percentStyle(rect: CropRect) {
  return {
    left: `${rect.x * 100}%`,
    top: `${rect.y * 100}%`,
    width: `${rect.w * 100}%`,
    height: `${rect.h * 100}%`,
  };
}

export function CropOverlay({
  state,
  source,
  index,
  panelRef,
}: {
  state: ClipEditorMockState;
  source: EditorSource;
  /** 사각형이 몇 번째인가 — 색을 가른다 */
  index: number;
  /** 소스 판. 포인터 좌표를 정규화하는 기준이다 */
  panelRef: RefObject<HTMLDivElement | null>;
}) {
  const lastPointer = useRef<{ x: number; y: number } | null>(null);
  const draggingCorner = useRef<CropCorner | null>(null);
  const crop = source.crop;

  const beginPointer = (event: PointerEvent<HTMLElement>, corner: CropCorner | null) => {
    // 모서리를 잡았을 때 사각형 본체까지 같이 끌리면 안 된다
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    lastPointer.current = { x: event.clientX, y: event.clientY };
    draggingCorner.current = corner;
    // 드래그 한 번이 실행취소 한 칸이다 — 포인터가 움직일 때마다 쌓으면 상한을 넘겨 이전 편집이 밀린다
    state.beginGesture();
  };

  // 취소·캡처 상실도 끝으로 친다 — 놓치면 이후 편집이 계속 같은 히스토리 항목을 덮어쓴다
  const endPointer = () => {
    if (lastPointer.current === null) return;
    lastPointer.current = null;
    draggingCorner.current = null;
    state.endGesture();
  };

  const onMove = (event: PointerEvent<HTMLElement>) => {
    const last = lastPointer.current;
    if (last === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const box = panelRef.current?.getBoundingClientRect();
    if (box === undefined) return;
    const corner = draggingCorner.current;
    if (corner === null) {
      // 직전 위치와의 차이를 넘긴다 — 시작점 기준으로 보내면 사각형이 가장자리에서 잘린 뒤
      // 손을 되돌려도 잘린 만큼 헛돈다
      state.dragCrop(
        source.id,
        { x: event.clientX - last.x, y: event.clientY - last.y },
        { width: box.width, height: box.height },
      );
    } else {
      // 모서리는 절대 좌표로 넘긴다 — 반대편 모서리를 못 박는 계산이 지금 손 위치를 알아야 한다
      state.resizeCrop(
        source.id,
        corner,
        normalizePointer({ x: event.clientX, y: event.clientY }, box),
      );
    }
    lastPointer.current = { x: event.clientX, y: event.clientY };
  };

  const MOVE_KEYS: Readonly<Record<string, { x: number; y: number }>> = {
    ArrowLeft: { x: -CROP_KEY_STEP, y: 0 },
    ArrowRight: { x: CROP_KEY_STEP, y: 0 },
    ArrowUp: { x: 0, y: -CROP_KEY_STEP },
    ArrowDown: { x: 0, y: CROP_KEY_STEP },
  };

  const onBodyKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    // 자동반복은 버린다 — 누르고 있으면 히스토리 상한을 넘겨 이전 편집이 밀린다
    if (event.repeat) return;
    const delta = MOVE_KEYS[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    state.nudgeCrop(source.id, delta);
  };

  const onCornerKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (event.repeat) return;
    // 모서리에서 방향키의 뜻은 「넓히기/좁히기」다
    const grow = event.key === 'ArrowRight' || event.key === 'ArrowDown';
    const shrink = event.key === 'ArrowLeft' || event.key === 'ArrowUp';
    if (!grow && !shrink) return;
    event.preventDefault();
    state.zoomCrop(source.id, grow ? CROP_ZOOM_STEP : -CROP_ZOOM_STEP);
  };

  if (crop === undefined) return null;

  const zoomPercent = Math.round((source.cropZoom ?? 1) * 100);

  return (
    <div className={styles.cropRect} data-index={index} style={percentStyle(crop)}>
      <button
        type="button"
        className={styles.cropBody}
        aria-label={`${source.badge} 잡을 영역`}
        // 2차원 위치라 slider 로 표현할 수 없다 — 지금 값은 아래 표시로 읽어 준다
        aria-roledescription="크롭 영역"
        onPointerDown={(event) => beginPointer(event, null)}
        onPointerMove={onMove}
        onPointerUp={endPointer}
        onPointerCancel={endPointer}
        onLostPointerCapture={endPointer}
        onKeyDown={onBodyKeyDown}
        onDoubleClick={() => state.resetCrop(source.id)}
      >
        <span className={styles.cropBadge}>{source.badge}</span>
        <span className={styles.cropReading}>
          {Math.round(crop.x * 100)}, {Math.round(crop.y * 100)} · {zoomPercent}%
        </span>
      </button>

      {CROP_CORNERS.map((corner) => (
        <button
          key={corner}
          type="button"
          className={styles.cropHandle}
          data-corner={corner}
          aria-label={`${source.badge} ${CROP_CORNER_LABELS[corner]} 모서리`}
          onPointerDown={(event) => beginPointer(event, corner)}
          onPointerMove={onMove}
          onPointerUp={endPointer}
          onPointerCancel={endPointer}
          onLostPointerCapture={endPointer}
          onKeyDown={onCornerKeyDown}
        />
      ))}
    </div>
  );
}
