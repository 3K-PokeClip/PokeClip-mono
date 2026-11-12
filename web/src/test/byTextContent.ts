/**
 * 여러 요소로 나뉜 문장을 찾는 Testing Library 글자 매처 — `getByText(byTextContent('너구리님, 반가워요'))`.
 * 이름·제목만 세션 녹화 가림 칸(PRIVATE_TEXT_CLASS)으로 감싸면(POK-572) 문장이 `<span>너구리</span>님, …`로 나뉘어
 * 글자 그대로 찾기가 실패한다. 그 문장을 통째로 담은 가장 안쪽 요소 하나만 맞힌다(조상은 자식이 이미 맞으면 뺀다).
 */
export function byTextContent(text: string) {
  return (_content: string, element: Element | null): boolean =>
    element?.textContent === text &&
    Array.from(element.children).every((child) => child.textContent !== text);
}
