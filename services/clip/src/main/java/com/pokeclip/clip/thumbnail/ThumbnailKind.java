package com.pokeclip.clip.thumbnail;

import java.util.Arrays;
import java.util.Optional;

/**
 * 사진이 붙는 자리 셋(POK-277). 지난 방송 썸네일은 따로 찍지 않는다: 그 방송의 최고 점수 카드 사진, 없으면 마지막 라이브 사진을
 * 읽을 때 고른다({@link ThumbnailUrls#ofBroadcasts}).
 */
public enum ThumbnailKind {
    /** 방송 중 최신 화면. 대상 = 방송 번호. 1분마다 덮어쓴다 */
    LIVE("live"),
    /** 하이라이트 카드 시점의 장면. 대상 = 카드 번호 */
    CARD("card"),
    /** 완성 영상의 장면. 대상 = 영상 번호 */
    CLIP("clip");

    private final String value;

    ThumbnailKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ThumbnailKind> fromValue(String value) {
        return Arrays.stream(values()).filter(kind -> kind.value.equals(value)).findFirst();
    }

    /**
     * 사진이 놓이는 키. clip이 정하고 일꾼은 받은 키에 올리기만 한다: 보고에 키를 싣지 않으므로 일꾼이 엉뚱한 키를 적을 길이 없다.
     * 같은 대상은 늘 같은 키라 라이브 사진은 덮어써진다(분마다 새 파일이 쌓이지 않는다). 화면은 목록을 받을 때마다 새로 서명한
     * 주소를 받으므로 덮어쓴 사진이 브라우저 캐시에 막히지 않는다.
     */
    public String keyOf(String targetId) {
        return "thumbnails/" + value + "/" + targetId + ".jpg";
    }
}
