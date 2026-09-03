'use client';

import styles from './LibraryScreen.module.css';

// 시안 1g ③ 제목 인라인 편집 3단계 — 기본은 제목처럼(테두리 없음), 호버에 inset 배경으로
// 입력임을 암시, 포커스에 캐럿과 accent 테두리. 입력 즉시 저장이라 저장 버튼이 없다.
//
// DS Input을 쓰지 않는 이유: Input은 inset 배경·40u 높이의 「폼 칸」이라 19u/800 제목처럼
// 보일 수 없다. 앱에 인라인 편집 선례가 아직 없어 여기 로컬로 둔다 — 두 번째 자리가 생기면
// 그때 @/ui로 올린다(EmptyState 선례).

export function InlineTitleInput({
  value,
  onChange,
}: {
  value: string;
  onChange: (title: string) => void;
}) {
  return (
    <input
      type="text"
      className={styles.titleInput}
      aria-label="클립 제목"
      placeholder="제목 입력"
      spellCheck={false}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      onKeyDown={(event) => {
        // 저장할 것이 따로 없으니 Enter·Escape는 편집을 끝내는 뜻이다
        if (event.key === 'Enter' || event.key === 'Escape') event.currentTarget.blur();
      }}
    />
  );
}
