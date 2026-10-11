package com.pokeclip.auth.youtube.api.dto;

import com.pokeclip.auth.youtube.YoutubeLinkCheck;

/**
 * {@code POST /internal/youtube-link/status} 응답. 칸은 둘뿐이고 reason은 연결됐을 때 null로 <b>늘 실린다</b>
 * (명세 모양 {@code {"linked":true,"reason":null}}). 토큰·채널 칸은 없다.
 */
public record YoutubeLinkCheckResponse(boolean linked, String reason) {

    public static YoutubeLinkCheckResponse from(YoutubeLinkCheck c) {
        return new YoutubeLinkCheckResponse(c.linked(), c.reason());
    }
}
