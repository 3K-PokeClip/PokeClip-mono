'use client';

import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
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

  // 디코드 대기 상태의 주인은 <img>를 든 이쪽이다. 다이얼로그가 imageSrc 변화로만
  // 리셋하면, 같은 그림으로 재진입할 때(토스트 「편집」·같은 프리셋) 직전 ready가 남아
  // 새 <img>가 읽히기도 전에 「적용」이 열린다.
  //
  // 🔴 useEffect가 아니라 useLayoutEffect다. 데이터 URL 이미지는 삽입 직후 load가 뜬다 —
  // 이 리셋이 그 뒤에 돌면 onLoad의 ready를 loading으로 덮어 「적용」이 영영 잠긴다.
  // 파일을 고른 경로가 정확히 그랬다: FileReader 콜백에서 시작한 갱신은 이산 이벤트가
  // 아니라 passive 이펙트가 뒤로 밀리고, 그 사이 load가 먼저 온다(기본 아바타는 클릭이라
  // 이펙트가 커밋에 동기로 붙어 안 걸렸다). 커밋과 동기로 리셋하고, 그래도 이미 끝나
  // 있으면(캐시·같은 그림) 여기서 바로 판정한다.
  useLayoutEffect(() => {
    onStatusChange('loading');
    const img = imgRef.current;
    if (img !== null && img.complete && img.naturalWidth > 0) {
      setNatural({ width: img.naturalWidth, height: img.naturalHeight });
      onStatusChange('ready');
    }
  }, [src, onStatusChange, imgRef]);

  // ② 클램프는 commit 경로에만 걸려 있어 새는 자리가 둘 있다 — 디코드가 끝나 치수를
  // 처음 알게 될 때, 그리고 창이 줄어 마스크가 작아질 때. 둘 다 여기서 다시 자른다.
  // clampOffset은 바뀐 것이 없으면 같은 객체를 돌려주므로 이 이펙트가 자신을 깨우지 않는다.
  useEffect(() => {
    onChange(clampOffset(transform, natural.width, natural.height, maskPx));
  }, [natural.width, natural.height, maskPx, transform, onChange]);

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

  /** 방향키로도 옮긴다 — 포인터 전용이면 키보드 사용자는 중앙 고정 크롭밖에 못 만든다. */
  function handleKeyDown(e: ReactKeyboardEvent<HTMLDivElement>) {
    const step = e.shiftKey ? 16 : 4; // Shift로 크게 — 큰 사진을 끝까지 옮기려면 필요하다
    const move: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    };
    const delta = move[e.key];
    if (delta === undefined) return;
    e.preventDefault(); // 스테이지가 포커스를 가진 동안 화면이 함께 스크롤되지 않게
    commit({ ...transform, x: transform.x + delta[0], y: transform.y + delta[1] });
  }

  return (
    <>
      <div
        className={styles.cropStage}
        role="group"
        aria-label="사진 위치 조정 — 방향키로 옮기고 Shift를 누르면 크게 움직여요"
        tabIndex={0}
        onKeyDown={handleKeyDown}
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
