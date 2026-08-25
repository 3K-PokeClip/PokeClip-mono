package com.pokeclip.auth.youtube.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.auth.youtube.LinkStatus;
import com.pokeclip.auth.youtube.YoutubeChannelLink;

import java.time.Instant;

/**
 * 행 없음 → {linked:false}. 있으면 linked = (status == ACTIVE)다 — 치지직과 달리 EXPIRED 갈래가 없다.
 * 구글 access는 1시간짜리라 늘 만료돼 있고 갱신으로 항상 해소되므로 상태가 아니다.
 * BROKEN·UNLINKED도 linked:false지만 status·channelName은 준다 — 화면이 "끊겼다"를 보여줄 수 있게.
 *
 * <p>{@code {}}에 통째로 넣지 않는다 — channelId.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkStatusResponse(boolean linked, String channelId, String channelName, LinkStatus status,
                                 Instant linkedAt, Instant lastRefreshedAt, Instant accessExpiresAt) {

    public static LinkStatusResponse none() {
        return new LinkStatusResponse(false, null, null, null, null, null, null);
    }

    public static LinkStatusResponse of(YoutubeChannelLink link) {
        LinkStatus status = link.status();
        return new LinkStatusResponse(status == LinkStatus.ACTIVE, link.getChannelId(), link.getChannelName(),
                status, link.getCreatedAt(), link.getLastRefreshedAt(), link.getAccessExpiresAt());
    }
}
