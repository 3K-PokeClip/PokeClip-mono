import styles from './editorShared.module.css';

// 시안 1d의 붙은 버튼 묶음(레이아웃·배속). @/ui에 세그먼트 컨트롤이 없어 여기서 만들되
// 라디오 그룹 시맨틱을 지켜 키보드·스크린리더에서 "여러 개 중 하나"로 읽히게 한다.

export interface SegmentedOption<T extends string | number> {
  value: T;
  label: string;
}

export function Segmented<T extends string | number>({
  label,
  options,
  value,
  onChange,
  size,
}: {
  /** 묶음 자체의 이름 — 시안에 눈에 보이는 라벨이 없는 배속 같은 곳에서 쓴다 */
  label: string;
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
  size?: 'sm';
}) {
  return (
    <div className={styles.segmented} role="radiogroup" aria-label={label} data-size={size}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          role="radio"
          aria-checked={option.value === value}
          className={styles.segment}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
