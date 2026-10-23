package com.pokeclip.auth.soop.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.soop.LinkStatus;
import com.pokeclip.auth.soop.SoopChannelLink;

import java.time.Instant;

/**
 * 행 없음 → {linked:false}. 있으면 linked = status ∈ {ACTIVE, EXPIRED}. BROKEN·UNLINKED도 status는 주지만
 * <b>닉네임은 없다</b>: 닫힐 때 파기됐다(약관 제11조 ⑰). 치지직과 달리 아이디 칸이 아예 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkStatusResponse(boolean linked, String nickname, LinkStatus status,
                                 Instant linkedAt, Instant lastRefreshedAt, Instant accessExpiresAt) {

    public static LinkStatusResponse none() {
        return new LinkStatusResponse(false, null, null, null, null, null);
    }

    public static LinkStatusResponse of(SoopChannelLink link, Instant now) {
        LinkStatus status = link.status(now);
        boolean linked = status == LinkStatus.ACTIVE || status == LinkStatus.EXPIRED;
        return new LinkStatusResponse(linked, link.getNickname(), status,
                link.getCreatedAt(), link.getLastRefreshedAt(), link.getAccessExpiresAt());
    }
}
