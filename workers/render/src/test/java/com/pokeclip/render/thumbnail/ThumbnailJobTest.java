package com.pokeclip.render.thumbnail;

import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사진 주문서 읽기. 원점 표지 {@code source.offsetOrigin}(PDT 설계 r7 3.1 · 8.1 — 없으면 {@code FILE_HEAD})과 정수 칸 범위 확인을
 * 본다. 정수 칸은 렌더 주문서와 같은 관례로 「정수이고 담기는가」 를 먼저 보고 변환한다.
 */
class ThumbnailJobTest {

    private static ObjectNode 주문서() {
        ObjectNode root = Fixtures.MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("kind", "card");
        root.put("targetId", "7");
        root.put("capturedAt", "2026-10-04T10:00:00Z");
        root.putObject("source").put("bucket", "segments-test").put("s3Key", "seg/1.m4s").put("offsetMs", 1_000);
        root.putObject("output").put("bucket", "clips-test").put("s3Key", "thumbnails/card/7.jpg");
        return root;
    }

    private static Optional<ThumbnailJob> 읽기(ObjectNode root) {
        return ThumbnailJob.parse(Fixtures.MAPPER, Fixtures.MAPPER.writeValueAsString(root));
    }

    private static ObjectNode 원점(String value) {
        ObjectNode root = 주문서();
        ((ObjectNode) root.get("source")).put("offsetOrigin", value);
        return root;
    }

    @Test
    void 원점_칸이_없으면_파일_머리다() {
        assertThat(읽기(주문서())).get().extracting(ThumbnailJob::offsetOrigin).isEqualTo(OffsetOrigin.FILE_HEAD);
    }

    @Test
    void 원점_칸의_두_값을_읽는다() {
        assertThat(읽기(원점("VIDEO_START"))).get().extracting(ThumbnailJob::offsetOrigin)
                .isEqualTo(OffsetOrigin.VIDEO_START);
        assertThat(읽기(원점("FILE_HEAD"))).get().extracting(ThumbnailJob::offsetOrigin)
                .isEqualTo(OffsetOrigin.FILE_HEAD);
    }

    /** 칸이 있는데 두 값이 아니면 모양이 틀린 주문이다 — 모르는 원점으로 찍으면 다른 장면이 나온다. */
    @Test
    void 원점_칸이_모르는_값이면_모양이_틀리다() {
        assertThat(읽기(원점("video_start"))).as("대소문자").isEmpty();
        assertThat(읽기(원점("PTS"))).isEmpty();
        ObjectNode number = 주문서();
        ((ObjectNode) number.get("source")).put("offsetOrigin", 1);
        assertThat(읽기(number)).as("숫자").isEmpty();
        ObjectNode nul = 주문서();
        ((ObjectNode) nul.get("source")).putNull("offsetOrigin");
        assertThat(읽기(nul)).as("null 은 「없음」 이 아니다").isEmpty();
    }

    /** 정수가 아닌 자리 값(문자열 · 소수)을 정수로 바꿔 받으면 틀린 모양이 지나간다. */
    @Test
    void 자리_값이_정수가_아니면_모양이_틀리다() {
        ObjectNode text = 주문서();
        ((ObjectNode) text.get("source")).put("offsetMs", "1000");
        assertThat(읽기(text)).as("문자열").isEmpty();
        ObjectNode fraction = 주문서();
        ((ObjectNode) fraction.get("source")).put("offsetMs", 1.5);
        assertThat(읽기(fraction)).as("소수").isEmpty();
        ObjectNode negative = 주문서();
        ((ObjectNode) negative.get("source")).put("offsetMs", -1);
        assertThat(읽기(negative)).as("음수").isEmpty();
    }

    /** 범위 밖 정수는 변환에서 잘리거나 예외가 난다. 범위를 먼저 보고 모양이 틀린 주문으로 둔다. */
    @Test
    void 정수_칸이_범위_밖이면_모양이_틀리다() {
        ObjectNode bigOffset = 주문서();
        ((ObjectNode) bigOffset.get("source")).put("offsetMs", new java.math.BigInteger("9223372036854775808"));
        assertThat(읽기(bigOffset)).as("offsetMs 가 long 밖").isEmpty();
        ObjectNode wrapVersion = 주문서();
        wrapVersion.put("schemaVersion", 4_294_967_297L);  // int 로 자르면 1 이 된다
        assertThat(읽기(wrapVersion)).as("schemaVersion 이 int 밖").isEmpty();
    }

    @Test
    void 잘_된_주문서는_칸을_그대로_싣는다() {
        ThumbnailJob job = 읽기(원점("VIDEO_START")).orElseThrow();

        assertThat(job.kind()).isEqualTo("card");
        assertThat(job.offsetMs()).isEqualTo(1_000);
        assertThat(job.sourceKey()).isEqualTo("seg/1.m4s");
        assertThat(job.outputKey()).isEqualTo("thumbnails/card/7.jpg");
    }
}
