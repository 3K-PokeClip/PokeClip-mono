import type { RegionPlacement } from './editorLayout';
import styles from './editorShared.module.css';

// 잡은 영역이 결과 화면의 어디에 어떤 모양으로 놓이는지 보여주는 칸 (POK-109).
//
// 아직 그림은 없다 — 실제 영상은 미디어 경로가 이어지면 이 칸에 크롭 좌표대로 잘라 그린다.
// 지금은 자리(placement)·여백 채우기·테두리만 시안대로 그리고 안에는 라벨을 둔다.

export function CropResult({
  label,
  placement,
}: {
  label: string;
  /** 결과 화면에서 이 영역이 놓이는 방식 — 레이아웃이 정한다 */
  placement: RegionPlacement;
}) {
  // 배치는 레이아웃이 정한다 — 꽉 채우거나, 지분만큼 쌓이거나, 비율을 지켜 가운데 놓이거나,
  // 다른 영역 위에 얹힌다(크롭 모드의 작은 화면).
  const style =
    placement.kind === 'stack'
      ? { flex: placement.flex }
      : placement.kind === 'overlay'
        ? {
            position: 'absolute' as const,
            left: `${placement.left * 100}%`,
            top: `${placement.top * 100}%`,
            width: `${placement.width * 100}%`,
            aspectRatio: placement.aspect,
            zIndex: 2,
            // 시안: 1px 라인 + 그림자 + 4px 라운드. 설정으로 끄면 맨 그림이다
            ...(placement.border.on
              ? {
                  border: `calc(${placement.border.width} * var(--pc-u)) solid ${placement.border.color}`,
                  borderRadius: 'calc(4 * var(--pc-u))',
                  boxShadow: 'var(--pc-shadow-md)',
                  overflow: 'hidden' as const,
                }
              : {}),
          }
        : { flex: 1 };

  // 중앙 모드 — 시안: 블러 배경(flex 1) / 원본 16:9 그대로(폭 100%) / 블러 배경(flex 1).
  if (placement.kind === 'contain') {
    const fill = placement.fill;
    return (
      <div
        className={styles.resultPane}
        data-placement="contain"
        // 단색이면 바닥색으로, 블러면 강도를 CSS 변수로 넘긴다. 지금은 흐릴 그림이 없어 이 변수를
        // 읽는 CSS 가 없다 — 실재생이 오면 뒤에 까는 배경 캔버스가 이 값으로 blur() 를 건다
        style={
          fill.kind === 'color'
            ? { ...style, background: fill.color }
            : { ...style, ['--pc-blur' as string]: String(fill.strength / 100) }
        }
      >
        <div className={styles.resultContain} style={{ aspectRatio: placement.aspect }}>
          <span className={styles.sourcePlaceholder}>{label}</span>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.resultPane} data-placement={placement.kind} style={style}>
      <span className={styles.sourcePlaceholder}>{label}</span>
    </div>
  );
}
