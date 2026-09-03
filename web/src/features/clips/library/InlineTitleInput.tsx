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
        // 한글·일본어 입력기는 글자를 조합하는 동안에도 Enter·Escape를 보낸다 — 조합을
        // 확정하거나 취소하라는 뜻이지 편집을 끝내라는 뜻이 아니다. 조합 중에 blur를 걸면
        // 브라우저가 조합을 강제로 끝내면서 확정된 글자가 한 번 더 들어간다(영어는 조합
        // 단계가 없어 이 자리를 지나가지 않는다). 조합 중 키는 입력기에 넘기고, 편집을
        // 끝내는 것은 조합이 끝난 뒤의 Enter다 — 검색창들이 한글에서 Enter를 두 번 받는 이유다.
        //
        // keyCode 229는 isComposing이 비어 오는 브라우저를 위한 같은 신호다.
        if (event.nativeEvent.isComposing || event.keyCode === 229) return;
        // 저장할 것이 따로 없으니 Enter·Escape는 편집을 끝내는 뜻이다
        if (event.key === 'Enter' || event.key === 'Escape') event.currentTarget.blur();
      }}
    />
  );
}
