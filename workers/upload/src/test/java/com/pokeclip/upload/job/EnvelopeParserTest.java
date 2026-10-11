package com.pokeclip.upload.job;

import com.pokeclip.upload.job.UploadEnvelope.Thumbnail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문서 해석(POK-291 명세 §4). 판은 1 그대로이고 새 칸(태그·아동용·썸네일)은 <b>너그럽게</b> 읽는다: 모양이 틀려도 기본값으로
 * 떨어뜨린다. 🔴 새 칸 때문에 {@link EnvelopeParser.Unreadable}을 던지면 일꾼이 쪽지를 지우고 clip 줄이 queued로 영원히 남는다.
 */
class EnvelopeParserTest {

    private final EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());

    @Test
    void 새_칸을_모두_읽는다() {
        UploadEnvelope job = parser.parse(주문서("""
                "privacyStatus":"unlisted","tags":["롤","펜타 킬"],"madeForKids":true""", """
                {"source":"scene","offsetMs":12000}"""));

        assertThat(job.privacyStatus()).isEqualTo("unlisted");
        assertThat(job.tags()).containsExactly("롤", "펜타 킬");
        assertThat(job.madeForKids()).isTrue();
        assertThat(job.thumbnail()).isEqualTo(new Thumbnail.Scene(12000));
    }

    @Test
    void 파일_썸네일을_읽는다() {
        UploadEnvelope job = parser.parse(주문서("\"privacyStatus\":\"public\"", """
                {"source":"file","bucket":"b2","s3Key":"upload-thumbnails/9/a.jpg","contentType":"image/jpeg"}"""));

        assertThat(job.privacyStatus()).isEqualTo("public");
        assertThat(job.thumbnail()).isEqualTo(new Thumbnail.File("b2", "upload-thumbnails/9/a.jpg", "image/jpeg"));
    }

    /** 옛 주문서(새 칸 없음): 태그 없음·아동용 아님·썸네일 없음·비공개. */
    @Test
    void 옛_주문서는_기본값이다() {
        UploadEnvelope job = parser.parse("""
                {"schemaVersion":1,"jobType":"UPLOAD","uploadId":"41","channelOwnerUserId":"9",
                 "source":{"bucket":"b","s3Key":"k"},"video":{"title":"t"}}""");

        assertThat(job.tags()).isEmpty();
        assertThat(job.madeForKids()).isFalse();
        assertThat(job.thumbnail()).isNull();
        assertThat(job.privacyStatus()).isEqualTo("private");
        assertThat(job.description()).isEmpty();
    }

    @Test
    void 모양이_틀린_새_칸은_기본값으로_떨어진다() {
        UploadEnvelope job = parser.parse(주문서("""
                "privacyStatus":"secret","tags":"롤","madeForKids":"yes\"""", "\"scene\""));

        assertThat(job.privacyStatus()).isEqualTo("private");
        assertThat(job.tags()).isEmpty();
        assertThat(job.madeForKids()).isFalse();
        assertThat(job.thumbnail()).isNull();
    }

    /** 태그 배열 안의 문자열이 아닌 것은 버리고 나머지는 둔다. */
    @Test
    void 태그_배열의_문자열이_아닌_것은_버린다() {
        UploadEnvelope job = parser.parse(주문서("\"tags\":[\"롤\",3,null,{\"a\":1},\"하이라이트\"]", "null"));

        assertThat(job.tags()).containsExactly("롤", "하이라이트");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "{\"source\":\"none\"}",
            "{\"source\":\"scene\"}",
            "{\"source\":\"scene\",\"offsetMs\":-1}",
            "{\"source\":\"scene\",\"offsetMs\":12.5}",
            "{\"source\":\"scene\",\"offsetMs\":\"12000\"}",
            "{\"source\":\"file\",\"bucket\":\"b\",\"s3Key\":\"k\"}",
            "{\"source\":\"file\",\"bucket\":\"b\",\"s3Key\":\"\",\"contentType\":\"image/png\"}",
            "{\"source\":\"file\",\"bucket\":\"b\",\"s3Key\":\"k\",\"contentType\":\"image/gif\"}",
            "{\"source\":\"video\"}",
            "[1,2]",
    })
    void 모양이_틀린_썸네일은_없음이고_지우지_않는다(String thumbnail) {
        UploadEnvelope job = parser.parse(주문서("\"privacyStatus\":\"private\"", thumbnail));

        assertThat(job.thumbnail()).isNull();
        assertThat(job.uploadId()).isEqualTo(41);
    }

    /** 판·종류·필수 칸이 틀린 것은 지금처럼 못 읽는 쪽지다(새 칸 너그러움이 이것까지 넓히지 않는다). */
    @Test
    void 판이_다르면_여전히_못_읽는다() {
        assertThatThrownBy(() -> parser.parse(주문서("", "null").replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(EnvelopeParser.Unreadable.class);
    }

    private static String 주문서(String videoExtra, String thumbnail) {
        String extra = videoExtra.isBlank() ? "\"privacyStatus\":\"private\"" : videoExtra;
        return """
                {"schemaVersion":1,"jobType":"UPLOAD","uploadId":"41","channelOwnerUserId":"9",
                 "source":{"bucket":"b","s3Key":"k"},"video":{"title":"t","description":"d",%s},
                 "thumbnail":%s}""".formatted(extra, thumbnail);
    }
}
