package com.pokeclip.clip.jumpcard.stream;

import tools.jackson.databind.JsonNode;

/**
 * 수집기가 민 채팅·후원 한 건(POK-234 PR-B). clip은 <b>{@code seq}·{@code kind}만 안다</b> — 나머지 칸은
 * {@code raw}에 통째로 들고 SSE {@code data}로 그대로 내보낸다(계획 검증 F8). PR-C가 방송 정보 칸을 더해도
 * clip이 칸을 새로 알 필요가 없다.
 *
 * @param seq  방송마다 1부터. SSE {@code id}가 된다. 순서·빈틈 감지용이지 중복 판정 열쇠가 아니다(F3)
 * @param kind SSE {@code event} 이름({@code chat}·{@code donation}, PR-C에서 {@code broadcast-info})
 * @param raw  받은 이벤트 객체 원문 전체({@code seq}·{@code kind} 포함)
 */
public record ChatEvent(long seq, String kind, JsonNode raw) {
}
