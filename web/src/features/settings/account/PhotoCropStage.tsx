'use client';

import {
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type RefObject,
} from 'react';
import { Minus, Plus, RotateCcw } from 'lucide-react';
import { IconButton, Slider } from '@/ui';
import { baseScale, clampOffset, zoomToScale, type CropTransform } from './cropImage';
import styles from './AccountSettingsScreen.module.css';

/** 원본이 아직 디코드되지 않았는지·실패했는지 — 「적용」을 열어 줄지 여기서 갈린다. */
export type CropStatus = 'loading' | 'ready' | 'error';

// 디자인 1p ④ 크롭 — 원형 마스크 안쪽이 잘릴 자리다. 드래그로 위치, 슬라이더로 확대,
// 버튼으로 왼쪽 90도 회전. 여기 미리보기와 cropImage의 캔버스가 같은 식을 쓴다.

export function PhotoCropStage({
  src,
  transform,
  onChange,
  imgRef,
  maskPx,
  onMaskPxChange,
  onStatusChange,
}: {
  src: string;
  transform: CropTransform;
  onChange: (next: CropTransform) => void;
  imgRef: RefObject<HTMLImageElement | null>;
  /** 실측한 마스크 지름 — 내보내기와 나눠 쓰므로 위에서 들고 있다. */
  maskPx: number;
  onMaskPxChange: (px: number) => void;
  onStatusChange: (status: CropStatus) => void;
}) {
  const [natural, setNatural] = useState({ width: 0, height: 0 });
  const maskRef = useRef<HTMLSpanElement>(null);
  // 드래그 시작 지점과 그때의 이동값 — 매 move마다 차이만 더한다
  const drag = useRef<{ pointerX: number; pointerY: number; x: number; y: number } | null>(null);

  const scale =
    baseScale(Math.min(natural.width, natural.height), maskPx) * zoomToScale(transform.zoom);

  /** 어떤 경로로 바뀌든 마스크를 덮는 범위 안으로 잘라서 올린다. */
  function commit(next: CropTransform) {
    onChange(clampOffset(next, natural.width, natural.height, maskPx));
  }

  // 마스크는 --pc-u를 타고 창 폭에 비례해 커진다 — 상수로 두면 창이 넓을 때 미리보기와
  // 잘라낸 결과가 갈린다. 그려진 지름을 재서 양쪽이 같은 값을 쓰게 한다.
  useEffect(() => {
    const el = maskRef.current;
    if (el === null) return;
    const measure = () => {
      // getBoundingClientRect가 아니라 계산된 레이아웃 폭을 쓴다 — 전자는 조상의 transform을
      // 반영해서, 모달 진입 애니메이션(pc-dialog-in의 scale .98) 첫 프레임에 재면 2% 작은
      // 값이 잡히고 ResizeObserver는 transform 변화에 반응하지 않아 그대로 굳는다.
      const px = parseFloat(getComputedStyle(el).width);
      if (px > 0) onMaskPxChange(px); // 0이면 아직 레이아웃 전 — 기준값을 유지한다
    };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [onMaskPxChange]);

  function handlePointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { pointerX: e.clientX, pointerY: e.clientY, x: transform.x, y: transform.y };
  }

  function handlePointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    const start = drag.current;
    if (start === null) return;
    commit({
      ...transform,
      x: start.x + (e.clientX - start.pointerX),
      y: start.y + (e.clientY - start.pointerY),
    });
  }

  function endDrag(e: ReactPointerEvent<HTMLDivElement>) {
    if (drag.current === null) return;
    e.currentTarget.releasePointerCapture(e.pointerId);
    drag.current = null;
  }

  return (
    <>
      <div
        className={styles.cropStage}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        {/* 미리보기는 로컬 data URL이고 크기가 매번 다르다 — next/image가 다룰 대상이 아니다 */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          ref={imgRef}
          src={src}
          alt=""
          draggable={false}
          className={styles.cropImage}
          style={{
            transform: `translate(-50%, -50%) translate(${transform.x}px, ${transform.y}px) rotate(${transform.rotation}deg) scale(${scale})`,
          }}
          onLoad={(e) => {
            const { naturalWidth, naturalHeight } = e.currentTarget;
            setNatural({ width: naturalWidth, height: naturalHeight });
            onStatusChange('ready');
          }}
          // 확장자만 바꾼 HEIC·손상된 JPEG는 MIME 검사를 통과하고 여기서야 드러난다.
          // 신호가 없으면 검은 상자를 「적용」해 디코드 안 되는 data URL이 아바타가 된다.
          onError={() => onStatusChange('error')}
        />
        <span ref={maskRef} className={styles.cropMask} aria-hidden />
        <span className={styles.cropHint}>드래그해서 위치 조정</span>
      </div>

      <div className={styles.cropControls}>
        <Minus aria-hidden className={styles.cropZoomIcon} />
        <Slider
          className={styles.cropSlider}
          label="확대"
          value={transform.zoom}
          onValueChange={(zoom) => commit({ ...transform, zoom })}
        />
        <Plus aria-hidden className={styles.cropZoomIcon} />
        <span className={styles.cropControlDivider} />
        <IconButton
          variant="ghost"
          size="sm"
          aria-label="왼쪽으로 90도 회전"
          onClick={() => commit({ ...transform, rotation: transform.rotation - 90 })}
        >
          <RotateCcw aria-hidden />
        </IconButton>
      </div>
    </>
  );
}
