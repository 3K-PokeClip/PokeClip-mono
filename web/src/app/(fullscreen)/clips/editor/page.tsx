import { redirect } from 'next/navigation';

// 편집기 진입 — 기본은 스튜디오형이다. 독 없는 (fullscreen) 그룹이라
// 나가는 길은 편집기 헤더의 「보관함으로」가 낸다.
// 시안은 스튜디오형·플로우형 둘 다 채택했고, 어느 쪽으로 열지는 나중에 설정
// (간편/정밀 모드)이 정한다. 그때 바꿀 곳이 여기 한 줄이 되도록 진입 경로를
// 형제 라우트 위에 따로 둔다 — 진입 링크는 계속 /clips/editor를 가리킨다.
export default function ClipEditorPage() {
  redirect('/clips/editor/studio');
}
