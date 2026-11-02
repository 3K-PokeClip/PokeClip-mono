import { RovingProvider, useRovingItem } from '@/ui/primitives';
import styles from './editorShared.module.css';

// 시안 1d의 붙은 버튼 묶음(레이아웃·배속·자막 표시 방식).
// @/ui에 세그먼트 컨트롤이 없어 여기서 만들되, 라디오 그룹 시맨틱을 선언한 이상
// 화살표 이동과 단일 Tab 정지점까지 갖춘다 — 선언만 하고 키를 안 달면
// 스크린리더가 「라디오 1/3」로 예고한 조작이 실제로는 없는 셈이 된다.
// 키 처리는 DS의 RovingProvider가 맡는다 (도구 레일·Tabs와 같은 문).

export interface SegmentedOption<T extends string | number> {
  value: T;
  label: string;
}

function Segment<T extends string | number>({
  option,
  selected,
  onSelect,
  className,
}: {
  option: SegmentedOption<T>;
  selected: boolean;
  onSelect: () => void;
  className: string | undefined;
}) {
  const roving = useRovingItem(String(option.value));
  return (
    <button
      ref={roving.ref}
      type="button"
      role="radio"
      aria-checked={selected}
      tabIndex={roving.tabIndex}
      onKeyDown={roving.onKeyDown}
      onFocus={roving.onFocus}
      className={className}
      onClick={onSelect}
    >
      {option.label}
    </button>
  );
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
    <RovingProvider
      activeValue={String(value)}
      onActiveChange={(next) => {
        const picked = options.find((option) => String(option.value) === next);
        if (picked !== undefined) onChange(picked.value);
      }}
      orientation="horizontal"
    >
      <div className={styles.segmented} role="radiogroup" aria-label={label} data-size={size}>
        {options.map((option) => (
          <Segment
            key={option.value}
            option={option}
            selected={option.value === value}
            onSelect={() => onChange(option.value)}
            className={styles.segment}
          />
        ))}
      </div>
    </RovingProvider>
  );
}
