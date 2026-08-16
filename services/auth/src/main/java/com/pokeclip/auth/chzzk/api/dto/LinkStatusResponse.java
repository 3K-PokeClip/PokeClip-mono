package com.pokeclip.auth.chzzk.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.LinkStatus;

import java.time.Instant;

/**
 * 행 없음 → {linked:false}. 있으면 linked = status ∈ {ACTIVE, EXPIRED}. BROKEN·UNLINKED도 linked:false지만
 * status·channelName은 준다 — 화면이 "끊겼다"를 보여줄 수 있게. {@code {}}에 넣지 않는다 — channelId.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkStatusResponse(boolean linked, String channelId, String channelName, LinkStatus status,
                                 Instant linkedAt, Instant lastRefreshedAt, Instant accessExpiresAt) {

    public static LinkStatusResponse none() {
        return new LinkStatusResponse(false, null, null, null, null, null, null);
    }

    public static LinkStatusResponse of(ChzzkChannelLink link, Instant now) {
        LinkStatus status = link.status(now);
        boolean linked = status == LinkStatus.ACTIVE || status == LinkStatus.EXPIRED;
        return new LinkStatusResponse(linked, link.getChannelId(), link.getChannelName(), status,
                link.getCreatedAt(), link.getLastRefreshedAt(), link.getAccessExpiresAt());
    }
}
