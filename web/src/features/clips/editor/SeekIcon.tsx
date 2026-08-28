// 시안 1d 트랜스포트의 되감기·감기 아이콘 — 원형 화살표 안에 초를 적는다.
// lucide의 RotateCcw/RotateCw만 쓰면 5초와 1초가 같은 그림이라 크기로만 갈리는데,
// 다섯 버튼이 나란히 붙어 있어서 그 차이는 눈에 잡히지 않는다. 숫자가 구분의 본체다.
// (읽어주는 이름은 버튼의 aria-label이 맡으므로 그림 자체는 숨긴다.)

const SHAPES = {
  back: {
    arc: 'M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8',
    tip: 'M3 3v5h5',
    textX: 12.2,
  },
  forward: {
    arc: 'M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8',
    tip: 'M21 3v5h-5',
    textX: 11.8,
  },
} as const;

export function SeekIcon({
  direction,
  seconds,
  size = 17,
}: {
  direction: 'back' | 'forward';
  seconds: number;
  size?: number;
}) {
  const shape = SHAPES[direction];
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d={shape.arc} />
      <path d={shape.tip} />
      <text
        x={shape.textX}
        y="16.2"
        textAnchor="middle"
        fontSize="9"
        fontWeight="700"
        fill="currentColor"
        stroke="none"
      >
        {seconds}
      </text>
    </svg>
  );
}
