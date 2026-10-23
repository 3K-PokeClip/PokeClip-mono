'use client';

import { useId, useRef, useState, type CSSProperties, type PointerEvent } from 'react';
import clsx from 'clsx';
import styles from './ModeSegment.module.css';

// 칸 몇 개 중 하나를 고르는 세그먼트 — 휴대폰 컨트롤처럼 흰 손잡이가 고른 칸으로 미끄러져 가고,
// 손잡이를 꾹 잡아 끌어서도 바꾼다.
//
// - 손잡이(고른 칸)를 누르면 살짝 작아지고 손가락을 따라 칸 단위로 옮겨 가며, 놓는 칸으로 정해진다.
// - 다른 칸을 누르면 그 칸만 흐려졌다가 놓을 때 손잡이가 그리로 미끄러진다(누른 채 옆 칸으로 끌면 놓는 칸).
// - 위아래로 CANCEL_SLOP 넘게 벗어나 놓으면 취소. 세로로 밀면 화면 스크롤로 넘긴다(touch-action: pan-y).
// - 키보드는 숨긴 진짜 라디오가 맡는다 — 방향키로 바꿔도 손잡이가 같은 애니메이션으로 옮겨 간다.
//   포인터로 고르면 그 칸의 라디오로 포커스를 옮긴다(아래 onPointerUp) — 안 옮기면 키보드로 와 있던
//   다른 줄에 포커스가 남아, 이어서 누른 방향키가 그 줄을 바꾼다.
//
// 지금은 알림 설정 하나만 쓴다. 다른 화면이 같은 것을 원하면 그때 ui/로 올린다.

export interface ModeOption<T extends string> {
  key: T;
  label: string;
  /** 골라졌을 때 브랜드 색 글자 — 한 컨트롤에 하나만 둔다(상태색은 하나) */
  accent?: boolean;
}

interface Press {
  pointerId: number;
  /** 손잡이(고른 칸)를 잡았나 — 잡았으면 손잡이가 따라오고, 아니면 누른 칸만 흐려진다 */
  grabbed: boolean;
  index: number;
}

// 놓는 자리가 칸에서 이만큼(px) 벗어나면 취소로 본다 — 휴대폰 컨트롤과 같은 너그러움
const CANCEL_SLOP = 40;

export function ModeSegment<T extends string>({
  options,
  value,
  onChange,
  labelledBy,
  describedBy,
  className,
}: {
  options: readonly ModeOption<T>[];
  value: T;
  onChange: (next: T) => void;
  labelledBy: string;
  describedBy?: string;
  className?: string;
}) {
  const name = useId();
  const ref = useRef<HTMLDivElement>(null);
  const inputs = useRef<(HTMLInputElement | null)[]>([]);
  const [press, setPress] = useState<Press | null>(null);

  const selected = options.findIndex((o) => o.key === value);
  const thumbIndex = press?.grabbed ? press.index : selected;

  const indexAt = (clientX: number) => {
    const r = ref.current!.getBoundingClientRect();
    const i = Math.floor(((clientX - r.left) / r.width) * options.length);
    return Math.min(options.length - 1, Math.max(0, i));
  };

  const isOutside = (clientY: number) => {
    const r = ref.current!.getBoundingClientRect();
    return clientY < r.top - CANCEL_SLOP || clientY > r.bottom + CANCEL_SLOP;
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0 || press) return;
    // 끄는 동안 글자가 잡히거나 라벨 클릭으로 값이 먼저 바뀌지 않게
    e.preventDefault();
    // 칸 밖으로 끌어도 이 요소가 이벤트를 계속 받게 — jsdom에는 없어 있을 때만 건다
    e.currentTarget.setPointerCapture?.(e.pointerId);
    const index = indexAt(e.clientX);
    setPress({ pointerId: e.pointerId, grabbed: index === selected, index });
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (!press || e.pointerId !== press.pointerId) return;
    const index = indexAt(e.clientX);
    if (index === press.index) return;
    // 손잡이가 칸을 넘을 때 짧은 진동 — 지원하는 폰(안드로이드)에서만, 나머지는 조용히 넘어간다
    if (press.grabbed) navigator.vibrate?.(8);
    setPress({ ...press, index });
  };

  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    if (!press || e.pointerId !== press.pointerId) return;
    setPress(null);
    if (isOutside(e.clientY)) return;
    const index = indexAt(e.clientX);
    // pointerdown의 preventDefault와 포인터 캡처로 라벨 클릭이 라디오에 닿지 않는다 — 포커스를 직접 옮긴다
    inputs.current[index]?.focus({ preventScroll: true });
    const next = options[index]!;
    if (next.key !== value) onChange(next.key);
  };

  return (
    <div
      ref={ref}
      className={clsx(styles.root, className)}
      role="radiogroup"
      aria-labelledby={labelledBy}
      aria-describedby={describedBy}
      data-pressed={press ? '' : undefined}
      style={{ '--seg-n': options.length, '--seg-i': thumbIndex } as CSSProperties}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={() => setPress(null)}
    >
      <span className={styles.thumb} data-grabbed={press?.grabbed ? '' : undefined} aria-hidden />
      {options.map((o, i) => (
        <label
          key={o.key}
          className={styles.option}
          data-active={i === thumbIndex ? '' : undefined}
          data-accent={o.accent ? '' : undefined}
          data-dimmed={press && !press.grabbed && press.index === i ? '' : undefined}
        >
          <input
            ref={(el) => {
              inputs.current[i] = el;
            }}
            type="radio"
            name={name}
            className={styles.input}
            checked={value === o.key}
            onChange={() => onChange(o.key)}
          />
          {o.label}
        </label>
      ))}
    </div>
  );
}
