package com.pokeclip.auth.youtube;

/**
 * 연동 상태. 치지직({@code chzzk/LinkStatus})과 달리 EXPIRED가 없다 —
 * 구글 access는 1시간이라 만료가 일상이고 갱신으로 항상 해소된다.
 * 화면에 「만료」가 깜빡이면 사용자가 할 일이 있는 것처럼 보이는데 실제로는 없다.
 */
public enum LinkStatus {
    ACTIVE,
    BROKEN,
    UNLINKED
}
