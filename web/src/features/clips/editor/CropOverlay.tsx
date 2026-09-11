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
import type { ClipEditorMockState, EditorRegion } from './useClipEditorMockState';

// 소스 위에 얹히는 크롭 사각형 (POK-109).
//
// 편집자는 소스 전체를 보면서 이 사각형을 잡는다 — 사각형 안을 끌면 위치가, 모서리를 끌면 범위가
// 바뀐다. 비율은 내보내는 화면이 정하므로 모서리를 끌어도 안 바뀐다(계약6 종횡비 검증에서
// 흔들리면 렌더가 거부한다).
//
// 마우스와 키보드가 같은 문(state.dragCrop / nudgeCrop / resizeCrop / zoomCrop)으로 들어간다 —
// 구간 핸들이 쓰는 규약과 같다. jsdom 엔 레이아웃이 없어 포인터 경로는 렌더 테스트로 못 재지만,
// 키보드 경로가 같은 액션으로 들어가므로 화면에서도 경계 동작을 확인할 수 있다.

/**
 * 포인터를 정규화할 기준 상자 — 테두리 안쪽(padding box)이다.
 * 절대 배치된 사각형의 %는 테두리 안쪽을 기준으로 놓이는데 `getBoundingClientRect()`는 테두리를
 * 포함하므로, 그대로 쓰면 핸들이 커서보다 테두리 두께만큼 어긋나 따라온다.
 */
function paddingBox(el: HTMLElement) {
  const rect = el.getBoundingClientRect();
  return {
    left: rect.left + el.clientLeft,
    top: rect.top + el.clientTop,
    width: el.clientWidth,
    height: el.clientHeight,
  };
}

/** 방향키 한 걸음 — 영역 프레임과 배치 타깃이 같은 걸음으로 움직인다 */
const MOVE_KEYS: Readonly<Record<string, { x: number; y: number }>> = {
  ArrowLeft: { x: -CROP_KEY_STEP, y: 0 },
  ArrowRight: { x: CROP_KEY_STEP, y: 0 },
  ArrowUp: { x: 0, y: -CROP_KEY_STEP },
  ArrowDown: { x: 0, y: CROP_KEY_STEP },
};

/**
 * 사각형 위의 Space 는 재생 토글이다. 이 버튼들은 role 이 없어 화면의 전역 키 리스너가 Space 를
 * 「버튼이 누르는 키」로 양보하는데, 누를 동작(onClick)이 없어 그냥 죽는다 — 여기서 받아 넘긴다.
 */
function spaceTogglesPlay(event: KeyboardEvent<HTMLElement>, state: ClipEditorMockState): boolean {
  if (event.key !== ' ') return false;
  event.preventDefault();
  event.stopPropagation();
  if (!event.repeat) state.togglePlay();
  return true;
}

/** 사각형의 한 꼭짓점 좌표 */
function cornerPoint(rect: CropRect, corner: CropCorner): { x: number; y: number } {
  return {
    x: corner === 'nw' || corner === 'sw' ? rect.x : rect.x + rect.w,
    y: corner === 'nw' || corner === 'ne' ? rect.y : rect.y + rect.h,
  };
}

function percentStyle(rect: CropRect) {
  return {
    left: `${rect.x * 100}%`,
    top: `${rect.y * 100}%`,
    width: `${rect.w * 100}%`,
    height: `${rect.h * 100}%`,
  };
}

/**
 * 크롭 모드에서 메인 화면 프레임 **안**에 얹히는 작은 화면 타깃 (시안 cropPipTarget).
 * 메인 프레임이 곧 결과 화면이므로, 여기서 잡은 자리가 그대로 결과 안 작은 화면의 자리다.
 * 소스에서 무엇을 잘라낼지는 「작은 화면」 프레임이, 결과의 어디에 둘지는 이 타깃이 정한다.
 */
function PipTarget({
  state,
  frameRef,
}: {
  state: ClipEditorMockState;
  /** 메인 화면 프레임 — 타깃 좌표의 기준 */
  frameRef: RefObject<HTMLDivElement | null>;
}) {
  const lastPointer = useRef<{ x: number; y: number } | null>(null);
  const draggingCorner = useRef<CropCorner | null>(null);
  const pip = state.pipPlacement;

  const begin = (event: PointerEvent<HTMLElement>, corner: CropCorner | null) => {
    // 메인 프레임 본체까지 같이 끌리면 안 된다
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    lastPointer.current = { x: event.clientX, y: event.clientY };
    draggingCorner.current = corner;
    // 타깃은 메인 프레임의 자식이라 메인이 비선택이면 같이 흐려진다 — 잡는 순간 메인을 선택한다
    state.selectRegion('main');
    state.beginGesture();
  };
  const end = () => {
    if (lastPointer.current === null) return;
    lastPointer.current = null;
    draggingCorner.current = null;
    state.endGesture();
  };
  const move = (event: PointerEvent<HTMLElement>) => {
    const last = lastPointer.current;
    if (last === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const frame = frameRef.current;
    if (frame === null) return;
    const box = paddingBox(frame);
    const corner = draggingCorner.current;
    if (corner === null) {
      state.dragPip(
        { x: event.clientX - last.x, y: event.clientY - last.y },
        { width: box.width, height: box.height },
      );
    } else {
      state.resizePip(corner, normalizePointer({ x: event.clientX, y: event.clientY }, box));
    }
    lastPointer.current = { x: event.clientX, y: event.clientY };
  };
  const onBodyKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (spaceTogglesPlay(event, state)) return;
    if (event.repeat) return;
    const delta = MOVE_KEYS[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    event.stopPropagation();
    // 정규화 이동량을 그대로 넘긴다 — 픽셀 환산이 필요 없게 프레임 크기를 1로 준다
    state.dragPip(delta, { width: 1, height: 1 });
  };

  // 모서리의 방향키는 그 꼭짓점을 한 걸음 옮긴다 — 마우스로 끄는 것과 같은 문(resizePip)이다
  const onCornerKeyDown = (corner: CropCorner) => (event: KeyboardEvent<HTMLElement>) => {
    if (spaceTogglesPlay(event, state)) return;
    if (event.repeat || pip === null) return;
    const delta = MOVE_KEYS[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    event.stopPropagation();
    const point = cornerPoint(pip, corner);
    state.resizePip(corner, { x: point.x + delta.x, y: point.y + delta.y });
  };

  if (pip === null) return null;

  return (
    <div
      className={styles.pipTarget}
      style={{
        left: `${pip.x * 100}%`,
        top: `${pip.y * 100}%`,
        width: `${pip.w * 100}%`,
        height: `${pip.h * 100}%`,
      }}
    >
      <button
        type="button"
        className={styles.pipBody}
        aria-label="작은 화면이 놓일 자리"
        aria-roledescription="배치 타깃"
        onPointerDown={(event) => begin(event, null)}
        onPointerMove={move}
        onPointerUp={end}
        onPointerCancel={end}
        onLostPointerCapture={end}
        onKeyDown={onBodyKeyDown}
        onDoubleClick={(event) => {
          event.stopPropagation();
          state.resetPip();
        }}
      >
        <span className={styles.cropBadge} data-tone="point">
          작은 화면 자리
        </span>
      </button>
      {CROP_CORNERS.map((corner) => (
        <button
          key={corner}
          type="button"
          className={styles.cropHandle}
          data-corner={corner}
          data-tone="point"
          aria-label={`작은 화면 자리 ${CROP_CORNER_LABELS[corner]} 모서리`}
          onPointerDown={(event) => begin(event, corner)}
          onPointerMove={move}
          onPointerUp={end}
          onPointerCancel={end}
          onLostPointerCapture={end}
          onKeyDown={onCornerKeyDown(corner)}
        />
      ))}
    </div>
  );
}

export function CropOverlay({
  state,
  region,
  index,
  panelRef,
}: {
  state: ClipEditorMockState;
  region: EditorRegion;
  /** 몇 번째 프레임인가 — 겹칠 때 쌓는 순서에 쓴다 */
  index: number;
  /** 소스 판. 포인터 좌표를 정규화하는 기준이다 */
  panelRef: RefObject<HTMLDivElement | null>;
}) {
  const rectRef = useRef<HTMLDivElement>(null);
  const lastPointer = useRef<{ x: number; y: number } | null>(null);
  const draggingCorner = useRef<CropCorner | null>(null);
  const crop = region.crop;
  const selected = state.selectedRegionId === region.id;
  // 크롭 모드의 메인 프레임에는 작은 화면의 결과 자리를 잡는 타깃이 얹힌다
  const hostsPip = state.layout === 'crop' && region.id === 'main';

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
    const panel = panelRef.current;
    if (panel === null) return;
    const box = paddingBox(panel);
    const corner = draggingCorner.current;
    if (corner === null) {
      // 직전 위치와의 차이를 넘긴다 — 시작점 기준으로 보내면 사각형이 가장자리에서 잘린 뒤
      // 손을 되돌려도 잘린 만큼 헛돈다
      state.dragCrop(
        region.id,
        { x: event.clientX - last.x, y: event.clientY - last.y },
        { width: box.width, height: box.height },
      );
    } else {
      // 모서리는 절대 좌표로 넘긴다 — 반대편 모서리를 못 박는 계산이 지금 손 위치를 알아야 한다
      state.resizeCrop(
        region.id,
        corner,
        normalizePointer({ x: event.clientX, y: event.clientY }, box),
      );
    }
    lastPointer.current = { x: event.clientX, y: event.clientY };
  };

  const onBodyKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (spaceTogglesPlay(event, state)) return;
    // 자동반복은 버린다 — 누르고 있으면 히스토리 상한을 넘겨 이전 편집이 밀린다
    if (event.repeat) return;
    const delta = MOVE_KEYS[event.key];
    if (delta === undefined) return;
    event.preventDefault();
    // 화면의 전역 키 리스너까지 가면 ←→가 시킹까지 일으킨다 — 여기서 끝낸다
    event.stopPropagation();
    state.nudgeCrop(region.id, delta);
  };

  const onCornerKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    if (spaceTogglesPlay(event, state)) return;
    if (event.repeat) return;
    // 모서리에서 방향키의 뜻은 「넓히기/좁히기」다
    const grow = event.key === 'ArrowRight' || event.key === 'ArrowDown';
    const shrink = event.key === 'ArrowLeft' || event.key === 'ArrowUp';
    if (!grow && !shrink) return;
    event.preventDefault();
    event.stopPropagation();
    state.zoomCrop(region.id, grow ? CROP_ZOOM_STEP : -CROP_ZOOM_STEP);
  };

  if (crop === undefined) return null;

  const zoomPercent = Math.round((region.cropZoom ?? 1) * 100);
  // 뒤 영역이 위에 쌓이고 선택하면 그 위로 올라온다. 크롭 모드의 작은 화면 프레임은 늘 맨 위다 —
  // 메인 프레임 안에 겹쳐 있을 때 메인이 선택돼 위에 쌓이면 작은 화면의 꼭짓점을 잡을 길이 없다.
  const layer = region.placement.kind === 'overlay' ? 5 : selected ? 4 : 2 + index;

  return (
    // 시안 규약: 선택 = 2px solid + 바깥 그늘, 비선택 = 2px dashed + 흐리게.
    // 크롭 모드는 두 영역을 함께 보고 배치해야 해서 그늘을 끈다.
    <div
      ref={rectRef}
      className={styles.cropRect}
      data-tone={region.tone}
      data-selected={selected ? 'on' : undefined}
      data-scrim={selected && region.scrim ? 'on' : undefined}
      style={{ ...percentStyle(crop), zIndex: layer }}
    >
      <button
        type="button"
        className={styles.cropBody}
        aria-label={`${region.label} 영역`}
        // 2차원 위치라 slider 로 표현할 수 없다 — 지금 값은 아래 표시로 읽어 준다
        aria-roledescription="크롭 영역"
        onPointerDown={(event) => {
          state.selectRegion(region.id);
          beginPointer(event, null);
        }}
        onFocus={() => state.selectRegion(region.id)}
        onPointerMove={onMove}
        onPointerUp={endPointer}
        onPointerCancel={endPointer}
        onLostPointerCapture={endPointer}
        onKeyDown={onBodyKeyDown}
        onDoubleClick={() => state.resetCrop(region.id)}
      >
        <span className={styles.cropBadge}>{region.label}</span>
        <span className={styles.cropReading}>
          {Math.round(crop.x * 100)}, {Math.round(crop.y * 100)} · {zoomPercent}%
        </span>
      </button>

      {region.resizable
        ? CROP_CORNERS.map((corner) => (
            <button
              key={corner}
              type="button"
              className={styles.cropHandle}
              data-corner={corner}
              aria-label={`${region.label} ${CROP_CORNER_LABELS[corner]} 모서리`}
              onPointerDown={(event) => {
                state.selectRegion(region.id);
                beginPointer(event, corner);
              }}
              onFocus={() => state.selectRegion(region.id)}
              onPointerMove={onMove}
              onPointerUp={endPointer}
              onPointerCancel={endPointer}
              onLostPointerCapture={endPointer}
              onKeyDown={onCornerKeyDown}
            />
          ))
        : null}
      {hostsPip ? <PipTarget state={state} frameRef={rectRef} /> : null}
    </div>
  );
}
