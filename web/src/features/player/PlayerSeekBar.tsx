'use client';

import { useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import styles from './GlassPlayer.module.css';
import { seekIntentForKey } from './playerKeys';
import {
  behindFromSeekFraction,
  formatBehind,
  isAtEdge,
  progressFraction,
  seekFractionFromPointer,
} from './playerMath';

/** 클립 구간 마커 폭 — 진행 비율 기준 % (시안 값: 최근 30초 ≈ 윈도우의 일부를 상징) */
const CLIP_MARK_WIDTH_PCT = 5;

// 커스텀 시크바 — 계약3 4절 2번: 마우스와 키보드가 같은 경로로 시킹하고, 그 뒤에 라이브로
// 맞추는 코드를 호출하지 않는다. 좌표·시차 환산은 playerMath가 맡는다 (jsdom에 레이아웃이
// 없어 여기선 단위 테스트가 안 되는 계산).
//
// 드래그는 놓을 때 한 번만 커밋한다 (POK-32) — pointermove마다 currentTime을 쓰면
// LL-HLS가 매번 버퍼를 비우고 프래그먼트를 다시 받는다. 드래그 중엔 프리뷰만 움직인다.
export function PlayerSeekBar({
  behindSeconds,
  windowSeconds,
  clipMarked,
  onSeekToFraction,
  onSeekBy,
  onReturnToLive,
  onSeekingChange,
}: {
  behindSeconds: number;
  /** 되감기 창 = 시크바 좌측 끝 (계약3 4절 4번) */
  windowSeconds: number;
  clipMarked: boolean;
  onSeekToFraction: (fraction: number) => void;
  onSeekBy: (deltaSeconds: number) => void;
  onReturnToLive: () => void;
  /** 드래그 중 알림 — GlassPlayer가 그동안 컨트롤 숨김을 유보한다 */
  onSeekingChange: (seeking: boolean) => void;
}) {
  // 드래그 중 표시할 위치. null이면 실제 재생 위치(prop)를 그대로 쓴다.
  const [dragFraction, setDragFraction] = useState<number | null>(null);
  // 커밋 값은 state가 아니라 ref다 — pointerdown/up이 한 배치에 묶이면 state는 아직
  // 갱신 전이라 up 핸들러의 클로저가 null을 읽는다.
  const pendingRef = useRef<number | null>(null);
  // 드래그 판정을 setPointerCapture가 아니라 이 ref로 한다 — jsdom엔 그 API가 없어
  // 캡처에 기대면 테스트가 불가능해진다. 캡처는 트랙 밖 추적용으로만 쓴다.
  const dragPointerRef = useRef<number | null>(null);

  // 드래그 중엔 prop을 읽지 않는다 — paint 루프(timeupdate·1초 틱)가 시차를 갱신해도 썸이 튀지 않는다.
  // 프리뷰도 커밋과 같은 함수로 환산해야 엣지 3초 스냅이 양쪽에 똑같이 걸린다
  // (다른 식을 쓰면 "-00:02"를 보여주고 놓으면 "실시간"이 되는 거짓말이 된다).
  const displayBehind =
    dragFraction === null ? behindSeconds : behindFromSeekFraction(dragFraction, windowSeconds);
  const atEdge = isAtEdge(displayBehind);
  const pct = progressFraction(displayBehind, windowSeconds) * 100;
  // 클립 마커는 프리뷰를 따라가지 않는다 — 실제 재생 지점 기준 구간이라 스크럽과 무관하다
  const clipPct = progressFraction(behindSeconds, windowSeconds) * 100;

  const previewFrom = (el: HTMLElement, clientX: number) => {
    const fraction = seekFractionFromPointer(el.getBoundingClientRect(), clientX);
    if (fraction === null) return;
    pendingRef.current = fraction;
    setDragFraction(fraction);
  };

  const handlePointerDown = (event: PointerEvent<HTMLDivElement>) => {
    // 우클릭·가운데 클릭으로 시킹되면 안 된다
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    // 드래그 중 닿은 두 번째 손가락에게 드래그를 넘기지 않는다 — 넘기면 원래 손가락의 move가
    // 전부 무시되고, 그 손가락을 떼도 커밋되지 않은 채 엉뚱한 지점으로 시킹된다
    if (dragPointerRef.current !== null) return;
    dragPointerRef.current = event.pointerId;
    // 트랙 밖으로 나가도 move/up을 계속 받는다. jsdom엔 없는 API라 옵셔널 호출한다.
    event.currentTarget.setPointerCapture?.(event.pointerId);
    onSeekingChange(true);
    previewFrom(event.currentTarget, event.clientX);
  };

  const handlePointerMove = (event: PointerEvent<HTMLDivElement>) => {
    // 다른 손가락의 move는 무시한다 (멀티터치)
    if (dragPointerRef.current !== event.pointerId) return;
    previewFrom(event.currentTarget, event.clientX);
  };

  const endDrag = (event: PointerEvent<HTMLDivElement>, commit: boolean) => {
    if (dragPointerRef.current !== event.pointerId) return;
    dragPointerRef.current = null;
    event.currentTarget.releasePointerCapture?.(event.pointerId);
    const fraction = pendingRef.current;
    pendingRef.current = null;
    setDragFraction(null);
    onSeekingChange(false);
    // 드래그 전체에서 currentTime을 만지는 유일한 지점. 단순 클릭은 "이동 없는 드래그"라
    // 여기서 정확히 한 번 시킹된다 (onClick을 두면 이중 호출이 된다).
    if (commit && fraction !== null) onSeekToFraction(fraction);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const intent = seekIntentForKey(event.key);
    if (!intent) return;
    if (intent.kind === 'by') onSeekBy(intent.seconds);
    else if (intent.kind === 'toFraction') onSeekToFraction(intent.fraction);
    else onReturnToLive();
    // 처리한 키만 막는다 — 화살표의 페이지 스크롤, Home/End의 문서 이동을 삼킨다
    event.preventDefault();
  };

  return (
    <div
      role="slider"
      tabIndex={0}
      aria-label="라이브 탐색"
      aria-valuemin={-windowSeconds}
      aria-valuemax={0}
      aria-valuenow={-Math.min(displayBehind, windowSeconds)}
      aria-valuetext={atEdge ? '실시간' : `실시간에서 ${formatBehind(displayBehind)}`}
      className={styles.seekArea}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={(event) => endDrag(event, true)}
      onPointerCancel={(event) => endDrag(event, false)}
      onKeyDown={handleKeyDown}
    >
      <div className={styles.track}>
        <div className={styles.buffer} />
        <div
          className={styles.progress}
          data-at-edge={atEdge || undefined}
          style={{ width: `${pct}%` }}
        />
        {clipMarked ? (
          <div
            className={styles.clipRange}
            style={{
              left: `${Math.max(0, clipPct - CLIP_MARK_WIDTH_PCT)}%`,
              width: `${CLIP_MARK_WIDTH_PCT}%`,
            }}
          />
        ) : null}
        <div className={styles.thumbDot} style={{ left: `${pct}%` }} />
      </div>
    </div>
  );
}
