package com.pokeclip.auth.youtube;

/**
 * 연동 상태만 담은 판정(POK-291). 토큰 칸이 아예 없다: clip이 렌더 주문 전에 「유튜브가 연결됐나」만 묻는다.
 *
 * <p>reason: null(연결됨) · NOT_LINKED · UNLINKED · BROKEN. resolve의 REFRESH_UNAVAILABLE은 없다:
 * 그 사유는 갱신을 해 봐야 알 수 있는데 이 판정은 갱신하지 않는다.
 */
public record YoutubeLinkCheck(boolean linked, String reason) {
}
