package com.pokeclip.render.report;

import tools.jackson.databind.JsonNode;

/** clip이 보고에 준 답. 4xx도 「확정 응답」이라 예외가 아니라 이 값으로 돌아온다(계약1 4절). */
public record Reply(int status, JsonNode body) {

    public boolean ok() {
        return status / 100 == 2;
    }

    /** 409·400의 {@code reason}. 없으면 빈 문자열. */
    public String reason() {
        return body == null ? "" : body.path("reason").asString("");
    }
}
