package com.pokeclip.clip.render;

import java.time.Instant;
import java.util.List;

/**
 * 완성 영상 한 벌의 파일 주소들(POK-247). 주소 하나가 곧 출입증이다: {@code expiresAt}까지 누구든 그 주소로 받는다.
 *
 * @param files 일꾼이 보고한 순서 그대로(계약1 result)
 */
public record ClipFileAccess(long clipId, Instant expiresAt, List<File> files) {

    /**
     * @param kind     {@code video} | {@code srt}(계약1 result.kind)
     * @param fileName 받을 때 이름. 주소가 이 이름을 창고에 실어 보내 브라우저가 그대로 쓴다
     * @param url      미리서명 주소. 같은 주소로 {@code <video>} 재생도 된다(브라우저는 재생 요청에 받는 이름을 안 본다)
     */
    public record File(String outputId, String kind, String fileName, String url) {
    }
}
