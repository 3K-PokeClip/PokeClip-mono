// 시안 1d-a 레일 하단 「패널 위치」 아이콘 — 화면 상자 안에서 패널이 붙은 쪽을 칠한다.
// 지금 어느 쪽인지를 그림으로 말하고, 누르면 갈 곳은 버튼의 이름이 말한다.

export function PanelSideIcon({ side }: { side: 'left' | 'right' }) {
  const filledX = side === 'left' ? 3 : 13.5;
  const dividerX = side === 'left' ? 10.5 : 13.5;
  return (
    <svg
      viewBox="0 0 24 24"
      style={{ width: 'calc(17 * var(--pc-u))', height: 'calc(17 * var(--pc-u))' }}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.9}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <rect x="3" y="4" width="18" height="16" rx="2.5" />
      <rect
        x={filledX}
        y="4"
        width="7.5"
        height="16"
        rx="2.5"
        fill="currentColor"
        stroke="none"
        opacity="0.32"
      />
      <path d={`M${dividerX} 4v16`} />
    </svg>
  );
}
