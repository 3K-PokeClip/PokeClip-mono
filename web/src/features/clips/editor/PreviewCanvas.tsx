import { useRef, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';
import { Image as ImageIcon } from 'lucide-react';
import { Segmented } from './Segmented';
import { CROP_KEY_STEP } from './cropMath';
import styles from './editorShared.module.css';
import type { ClipEditorMockState, EditorSource } from './useClipEditorMockState';

// 시안 1d 가운데 — 레이아웃 세그먼트 + 9:16/1:1/상하분할 미리보기.
// 크롭 위치 드래그·소스 교체는 목업이라 실제 픽셀을 옮기지 않는다.
//
// 소스가 있으면 media 표식이 붙은 칸에 실제 <video>가 들어간다. 표식이 pane 객체에 붙어 있어
// 상하 자리바꿈을 하면 영상도 따라 옮겨간다. 크롭 좌표는 POK-109 몫이라 지금은 중앙 고정이다.

/**
 * 영상을 끌어 크롭 위치를 정하는 판 (E5).
 *
 * 소스가 가로인데 내보내는 것은 세로라, 「어느 부분을 쓸지」를 손으로 고르는 자리다.
 * 마우스와 키보드가 같은 문(state.dragCrop / state.nudgeCrop)으로 들어간다 —
 * 구간 핸들이 쓰는 규약과 같다.
 *
 * `role="slider"`인 이유: 우리 비율(9:16·1:1·상하분할)은 전부 소스보다 홀쭉해서 세로를 다 쓰고
 * 가로만 남는다 — 즉 실제로 움직이는 축이 하나다. 2차원이 되는 소스가 오면 cropAxis 가 'y'를 준다.
 */
function CropSurface({
  state,
  source,
  children,
}: {
  state: ClipEditorMockState;
  source: EditorSource;
  children: ReactNode;
}) {
  const surfaceRef = useRef<HTMLDivElement>(null);
  const lastPointer = useRef<{ x: number; y: number } | null>(null);
  const axis = source.cropAxis ?? null;
  const crop = source.crop;

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (axis === null) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    lastPointer.current = { x: event.clientX, y: event.clientY };
    // 드래그 한 번이 실행취소 한 칸이다 — 포인터가 움직일 때마다 쌓으면 상한을 넘겨 이전 편집이 밀린다
    state.beginGesture();
  };

  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const last = lastPointer.current;
    if (last === null || !event.currentTarget.hasPointerCapture(event.pointerId)) return;
    const box = surfaceRef.current?.getBoundingClientRect();
    if (box === undefined) return;
    // 직전 위치와의 차이를 넘긴다 — 시작점 기준으로 보내면 중심이 가장자리에서 잘린 뒤
    // 손을 되돌려도 잘린 만큼 헛돈다
    state.dragCrop(
      source.id,
      { x: event.clientX - last.x, y: event.clientY - last.y },
      { width: box.width, height: box.height },
    );
    lastPointer.current = { x: event.clientX, y: event.clientY };
  };

  // 취소·캡처 상실도 끝으로 친다 — 놓치면 이후 편집이 계속 같은 히스토리 항목을 덮어쓴다
  const onPointerUp = () => {
    if (lastPointer.current === null) return;
    lastPointer.current = null;
    state.endGesture();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (axis === null || event.repeat) return;
    const back = axis === 'x' ? 'ArrowLeft' : 'ArrowUp';
    const forward = axis === 'x' ? 'ArrowRight' : 'ArrowDown';
    if (event.key !== back && event.key !== forward) return;
    event.preventDefault();
    const step = event.key === back ? -CROP_KEY_STEP : CROP_KEY_STEP;
    state.nudgeCrop(source.id, axis === 'x' ? { x: step, y: 0 } : { x: 0, y: step });
  };

  // 소스 전체를 쓰는 칸은 고를 것이 없다 — 조작할 수 없는 컨트롤을 두지 않는다
  if (axis === null || crop === undefined) return <>{children}</>;

  const free = axis === 'x' ? 1 - crop.w : 1 - crop.h;
  const now = axis === 'x' ? crop.x : crop.y;
  const percent = Math.round((now / free) * 100);

  return (
    <div
      ref={surfaceRef}
      className={styles.cropSurface}
      role="slider"
      tabIndex={0}
      aria-label={`${source.badge} 크롭 위치`}
      aria-orientation={axis === 'x' ? 'horizontal' : 'vertical'}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      aria-valuetext={`${axis === 'x' ? '가로' : '세로'} ${percent}%`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      onLostPointerCapture={onPointerUp}
      onKeyDown={onKeyDown}
    >
      {children}
    </div>
  );
}

export function PreviewCanvas({
  state,
  videoNode = null,
}: {
  state: ClipEditorMockState;
  /** 실재생 <video>. 없으면 자리 표시자만 그린다 */
  videoNode?: ReactNode;
}) {
  const subtitleText =
    state.subtitle.status === 'ready'
      ? (state.subtitle.items.find((item) => item.id === state.selectedSubtitleId)?.text ?? null)
      : null;

  const split = state.layout === 'split';
  // 소스와 그 순서는 훅이 정한다 — 자리바꿈 반영도 저쪽 몫이다
  const panes = split ? state.sources : state.sources.slice(0, 1);
  // 상하분할 경계 위치(%) — 시안 1.5 : 1
  const topShare = (state.splitRatio / (state.splitRatio + 1)) * 100;

  return (
    <div className={styles.preview}>
      <div className={styles.previewToolbar}>
        <span className={styles.previewToolbarLabel}>레이아웃</span>
        <Segmented
          label="레이아웃"
          options={state.layoutOptions}
          value={state.layout}
          onChange={state.setLayout}
        />
        <span className={styles.previewHint}>
          영역 드래그 = 크롭 위치 · 더블클릭 = 소스 교체
        </span>
      </div>

      {/* data-preview-stage: 타임라인 높이 상한을 재는 표식. 미리보기 칸에서 신축하는 건
          이 무대뿐이라, 여기 남은 여유가 곧 타임라인이 더 커질 수 있는 양이다 (POK-237). */}
      <div className={styles.stage} data-preview-stage>
        <div className={styles.frame} data-layout={state.layout}>
          {panes.map((source, index) => {
            const isTop = split && index === 0;
            const isBottom = split && index === 1;
            return (
              <div
                key={source.id}
                className={styles.sourcePane}
                data-position={isTop ? 'top' : undefined}
                style={{ flex: split ? (isTop ? state.splitRatio : 1) : 1 }}
                // 자리바꿈은 소스가 둘일 때만 뜻이 있다 — 단일 모드에서 더블클릭하면
                // 눈에 보이는 변화 없이 실행취소 한 칸만 쌓인다
                onDoubleClick={split ? state.swapSources : undefined}
              >
                {/* 크롭 조작판은 **크롭이 있는 칸**에 선다 — 영상 노드가 넘어왔는지가 아니라
                    데이터가 정한다. 크롭이 없으면 CropSurface 는 그냥 통과시킨다. */}
                <CropSurface state={state} source={source}>
                  {source.media === true && videoNode !== null ? (
                    // aria-hidden: 자막·컨트롤은 화면의 다른 곳이 담당하고, 이 요소는 그림이다.
                    // 안 붙이면 axe 가 자막 트랙 없는 video 를 위반으로 잡는다.
                    <div
                      className={styles.sourceVideo}
                      aria-hidden
                      style={
                        source.objectPosition === undefined
                          ? undefined
                          : {
                              // cover 가 넘치게 그린 것 중 어디를 보여줄지 — 크롭 좌표가 그대로 여기로 온다
                              ['--pc-crop-x' as string]: `${source.objectPosition.x}%`,
                              ['--pc-crop-y' as string]: `${source.objectPosition.y}%`,
                            }
                      }
                    >
                      {videoNode}
                    </div>
                  ) : (
                    <span className={styles.sourcePlaceholder}>{source.placeholder}</span>
                  )}
                </CropSurface>
                <span className={styles.sourceBadge} data-tone={source.tone}>
                  {source.badge}
                </span>
                {index === 0 ? (
                  <span className={styles.overlayChip}>
                    <ImageIcon size={10} aria-hidden />
                    로고.png
                  </span>
                ) : null}
                {/* 자막은 시안에서 아래 소스(캠) 위에 얹힌다 — 분할이 아니면 유일한 소스 위 */}
                {subtitleText !== null && (isBottom || !split) ? (
                  <span className={styles.burnedSubtitle}>“{subtitleText}”</span>
                ) : null}
              </div>
            );
          })}
          {split ? (
            <button
              type="button"
              className={styles.splitHandle}
              style={{ top: `${topShare}%` }}
              // 드래그는 목업이라 두 소스를 맞바꾸는 것으로 대신한다 —
              // 키보드만 쓰는 사용자도 같은 결과에 닿아야 해서 버튼이다.
              onClick={state.swapSources}
              aria-label="상하 소스 자리 바꾸기"
            >
              ⋯
            </button>
          ) : null}
        </div>
        <span className={styles.stageNote}>
          미리보기 · 자막 {state.subtitleModeLabel} · {state.speed}× 배속
        </span>
      </div>
    </div>
  );
}
