'use client';

// 「설치 파일 링크를 누른 적」을 이 탭에 기억한다(POK-613, PR #549 리뷰).
//
// 받은 파일은 화면을 떠나도 남는다 — 설치하는 사이 다른 메뉴에 다녀오거나 새로고침해도 연결 순서 카드의 1번이 끝난
// 것으로 서고 진한 버튼이 2번에 남게 한다. 「다시 켰어요」는 기억하지 않는다(다시 들어오면 OBS가 지금도 켜져 있는지부터
// 본다, ConnectStepsCard). sessionStorage라 탭을 닫으면 잊는다. 계정이 아니라 이 기기에서 한 일이라 계정을 바꿔도
// 지우지 않는다.

import { useSyncExternalStore } from 'react';

const KEY = 'pc-plugin-download-clicked';

function readRemembered(): boolean {
  try {
    return window.sessionStorage.getItem(KEY) !== null;
  } catch {
    return false;
  }
}

/** 설치 파일 링크를 누른 순간 남긴다 */
export function rememberPluginDownloadClicked() {
  try {
    window.sessionStorage.setItem(KEY, '1');
  } catch {
    /* 저장이 막혀도 이번 화면에선 화면 상태가 기억한다(PluginSettingsScreen) */
  }
}

// 이 탭의 sessionStorage는 이 탭만 바꾼다 — 바꾸는 쪽(링크 클릭)이 화면 상태도 같이 바꿔 다시 그리므로 구독할 것이 없다
const subscribe = () => () => {};

/** 이 탭에서 전에 설치 파일 링크를 누른 적이 있나 — 서버 렌더와 첫 수화에선 늘 false라 어긋나지 않는다 */
export function useRememberedPluginDownload(): boolean {
  return useSyncExternalStore(subscribe, readRemembered, () => false);
}
