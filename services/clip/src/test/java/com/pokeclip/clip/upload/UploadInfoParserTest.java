package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadErrors.InvalidUploadRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「영상 만들기」 업로드 정보 검사(POK-291). 스프링 없이 규칙만 잰다. 웹이 같은 식(태그 합계 500)을 쓰므로 경계를 숫자로 못박는다.
 */
class UploadInfoParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long 컷 = 45_000;

    @Test
    void 비어_있는_칸은_기본값이다() {
        UploadInfo info = parse("{\"title\":\"  펜타킬  \"}");

        assertThat(info.title()).isEqualTo("펜타킬");
        assertThat(info.description()).isEmpty();
        assertThat(info.tags()).isEmpty();
        assertThat(info.privacyStatus()).isEqualTo("private");
        assertThat(info.madeForKids()).isFalse();
        assertThat(info.thumbnail().source()).isEqualTo("none");
    }

    @Test
    void 고른_값은_그대로_싣는다() {
        UploadInfo info = parse("""
                {"title":"t","description":"d","tags":[" 롤 ","펜타킬","롤"],"privacyStatus":"unlisted","madeForKids":true,
                 "thumbnail":{"source":"scene","offsetMs":12000}}""");

        assertThat(info.tags()).as("앞뒤 공백을 걷고 똑같은 태그는 하나로, 순서는 유지").containsExactly("롤", "펜타킬");
        assertThat(info.privacyStatus()).isEqualTo("unlisted");
        assertThat(info.madeForKids()).isTrue();
        assertThat(info.thumbnail().source()).isEqualTo("scene");
        assertThat(info.thumbnail().offsetMs()).isEqualTo(12_000L);
    }

    @Test
    void 칸마다_틀리면_그_칸_이름으로_400이다() {
        거절("{}", "title");
        거절("{\"title\":5}", "title");
        거절("{\"title\":\"t\",\"description\":\"<b>\"}", "description");
        거절("{\"title\":\"t\",\"tags\":\"롤\"}", "tags");
        거절("{\"title\":\"t\",\"tags\":[1]}", "tags");
        거절("{\"title\":\"t\",\"tags\":[\"  \"]}", "tags");
        거절("{\"title\":\"t\",\"tags\":[\"a,b\"]}", "tags");
        거절("{\"title\":\"t\",\"tags\":[\"<a>\"]}", "tags");
        거절("{\"title\":\"t\",\"privacyStatus\":\"secret\"}", "privacyStatus");
        거절("{\"title\":\"t\",\"madeForKids\":\"true\"}", "madeForKids");
        거절("{\"title\":\"t\",\"thumbnail\":\"scene\"}", "thumbnail");
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"gif\"}}", "thumbnail");
        assertThatThrownBy(() -> UploadInfoParser.parse(MAPPER.readTree("[]"), 컷, false))
                .isInstanceOf(InvalidUploadRequestException.class)
                .extracting(e -> ((InvalidUploadRequestException) e).field()).isEqualTo("upload");
    }

    /**
     * 제어 문자(NUL 등)는 그 칸 이름으로 400이다(POK-291 로컬 리뷰 1라운드). 안 막으면 검사·연결 확인·그림 저장을 다 지나 DB가
     * 거절하고(VARCHAR의 0x00은 22021, jsonb 안의 NUL 이스케이프는 22P05) 처리기가 없어 500이 된다. 설명만 탭·줄바꿈을 받는다(여러 줄 설명).
     */
    @Test
    void 제어_문자는_그_칸_이름으로_거절하고_설명의_탭과_줄바꿈은_받는다() {
        거절("{\"title\":\"a\\u0000b\"}", "title");
        거절("{\"title\":\"a\\nb\"}", "title");
        거절("{\"title\":\"a\\u001Bb\"}", "title");
        거절("{\"title\":\"t\",\"description\":\"a\\u0000b\"}", "description");
        거절("{\"title\":\"t\",\"description\":\"a\\u007Fb\"}", "description");
        거절("{\"title\":\"t\",\"tags\":[\"a\\u0000b\"]}", "tags");
        거절("{\"title\":\"t\",\"tags\":[\"a\\tb\"]}", "tags");

        assertThat(parse("{\"title\":\"t\",\"description\":\"첫 줄\\n\\t둘째 줄\\r\\n셋째\"}").description())
                .isEqualTo("첫 줄\n\t둘째 줄\r\n셋째");
    }

    /** 합계 = Σ(글자 + 공백이 든 태그면 따옴표 2) + 쉼표(개수 − 1). 정확히 500은 통과, 501은 거절. */
    @Test
    void 태그_합계는_따옴표와_쉼표까지_세어_500자다() {
        // 9자 태그 49개(441) + 마지막 태그 하나 + 쉼표 49. 마지막을 공백 든 태그로 두면 따옴표 2가 경계를 가른다.
        List<String> 쉰 = new java.util.ArrayList<>(Arrays.asList(태그들(49, 9)));
        쉰.add("a b c d");      // 7 + 2 → 499
        assertThat(UploadInfoParser.tags(쉰)).hasSize(50);
        쉰.set(49, "a b c de");  // 8 + 2 → 500: 정확히 상한은 통과
        assertThat(UploadInfoParser.tags(쉰)).hasSize(50);
        // 9 + 2 → 501. 따옴표를 안 세면 499로 보여 통과한다: 이 거절이 따옴표 계산을 잰다.
        쉰.set(49, "a b c def");
        assertThatThrownBy(() -> UploadInfoParser.tags(쉰)).isInstanceOf(InvalidUploadRequestException.class);
        // 9자 태그 51개(459) + 쉼표 50 = 509 → 거절. 쉼표를 안 세면 459로 보여 통과한다.
        assertThat(UploadInfoParser.tags(Arrays.asList(태그들(45, 9)))).hasSize(45);   // 405 + 44 = 449
        assertThatThrownBy(() -> UploadInfoParser.tags(Arrays.asList(태그들(51, 9))))
                .isInstanceOf(InvalidUploadRequestException.class);
    }

    /** 장면은 정수 ms, 0 ≤ offsetMs < 컷 길이. 소수는 잭슨이 조용히 접으므로 따로 막는다. 컷이 없으면(템플릿) 고를 수 없다. */
    @Test
    void 장면_시각은_컷_안의_정수만_받는다() {
        assertThat(parse("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":0}}").thumbnail().offsetMs()).isZero();
        assertThat(parse("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":44999}}").thumbnail().offsetMs())
                .isEqualTo(44_999L);
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":45000}}", "thumbnail.offsetMs");
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":-1}}", "thumbnail.offsetMs");
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":12.5}}", "thumbnail.offsetMs");
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":\"12\"}}", "thumbnail.offsetMs");
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\"}}", "thumbnail.offsetMs");
        assertThatThrownBy(() -> UploadInfoParser.parse(
                MAPPER.readTree("{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":1}}"), null, false))
                .extracting(e -> ((InvalidUploadRequestException) e).field()).isEqualTo("thumbnail.offsetMs");
    }

    /** 그림 파트는 file일 때만 필수이고, 그 밖에 오면 400이다. JSON(파트 없음)으로 file을 고르면 400이다. */
    @Test
    void 그림_파트는_file일_때만_받는다() {
        assertThat(UploadInfoParser.parse(json("{\"title\":\"t\",\"thumbnail\":{\"source\":\"file\"}}"), 컷, true)
                .thumbnail().isFile()).isTrue();
        거절("{\"title\":\"t\",\"thumbnail\":{\"source\":\"file\"}}", "thumbnail");
        for (String 고르기 : List.of("{\"title\":\"t\"}", "{\"title\":\"t\",\"thumbnail\":{\"source\":\"none\"}}",
                "{\"title\":\"t\",\"thumbnail\":{\"source\":\"scene\",\"offsetMs\":1}}")) {
            assertThatThrownBy(() -> UploadInfoParser.parse(json(고르기), 컷, true))
                    .extracting(e -> ((InvalidUploadRequestException) e).field()).isEqualTo("thumbnail");
        }
    }

    private static String[] 태그들(int count, int length) {
        String[] tags = new String[count];
        for (int i = 0; i < count; i++) {
            tags[i] = String.format("%0" + length + "d", i);
        }
        return tags;
    }

    private static UploadInfo parse(String upload) {
        return UploadInfoParser.parse(json(upload), 컷, false);
    }

    private static void 거절(String upload, String field) {
        assertThatThrownBy(() -> parse(upload))
                .as(upload)
                .isInstanceOf(InvalidUploadRequestException.class)
                .extracting(e -> ((InvalidUploadRequestException) e).field()).isEqualTo(field);
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
