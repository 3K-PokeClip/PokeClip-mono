import type { EditorPick } from './windowPlacement';

// 업로드 장면(썸네일) 고르기의 꼴(POK-473 설계 prw2 3.3 · 4절). 장면 offset 을 결과 영상 미디어 거리로 셈하는 함수
// sceneOffsetMs 는 c12′ 에서 이 파일에 더한다. 이 커밋은 상태 훅 · 끝점 모델이 함께 쓸 형만 둔다.

/** 썸네일 고르기 — 창 안 자리면 고른 프레임, 창 밖 · 목업이면 표시 초 S */
export type ThumbnailChoice = { kind: 'pick'; pick: EditorPick } | { kind: 's'; s: number };
