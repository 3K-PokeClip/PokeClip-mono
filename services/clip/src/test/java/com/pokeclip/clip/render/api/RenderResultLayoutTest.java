package com.pokeclip.clip.render.api;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.LocalStackFixture;
import com.pokeclip.clip.thumbnail.ResultPlacements;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 렌더 결과 배치표(POK-196 S-c5 · PDT 설계 r7 3.4 · 8.3 · 예측 P14). 성공 보고 맨 위의 {@code placements} · {@code lengthUs} 를
 * 완성 영상 줄({@code clips.placements})에 적고, 같은 트랜잭션의 업로드 줄이 그 결과 길이로 업로드 장면을 자른다.
 *
 * <p><b>업로드 장면 = 레시피 {@code thumbnail.offsetMs} 그대로</b>(N9 웹 답 2026-11-12 — 편집기가 고른 값은 in 부터 그 장면까지의
 * <i>결과 영상 안</i> 미디어 거리다 · 장부 틈은 0). 그래서 축 변환을 하지 않고 결과 {@code [0, 결과 끝)} 으로 자르기만 한다. 주문서
 * 꼴은 그대로, 저장값(업로드 줄 · 의도)도 그대로다. 결과 길이를 모르면(옛 꼴 · 표가 깨짐) 받은 값 그대로 보내고
 * {@code clip.upload.scene_mapped source=fallback}(WARN)을 남긴다 — 성공 처리를 되감지 않는다.
 */
class RenderResultLayoutTest extends RenderUploadTestSupport {

    /** 컷 45초 = 조각 2–13(조각 4초 · 13 은 1초만). 틈 없이 놓인 배치표. */
    private static final String 배치표_45초 = 배치표(new long[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 45_000_000);

    RenderResultLayoutTest(MockMvc mvc, JdbcTemplate jdbc) {
        super(mvc, jdbc);
    }

    @Test
    void 성공_보고의_배치표를_완성_영상_줄에_적는다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        배치표와_완성시킨다(clipId, 배치표_45초);

        JsonNode stored = MAPPER.readTree(jdbc.queryForObject("SELECT placements::text FROM clips WHERE id = ?",
                String.class, clipId));
        assertThat(stored.get("lengthUs").asLong()).isEqualTo(45_000_000L);
        assertThat(stored.get("placements")).hasSize(12);
        assertThat(stored.at("/placements/2/seq").asLong()).isEqualTo(4);
        assertThat(stored.at("/placements/2/zeroAtUs").asLong()).isEqualTo(8_000_000L);
        assertThat(stored.at("/placements/11/toUs").asLong()).isEqualTo(1_000_000L);
    }

    /** 옛 꼴 보고(배치표 없음)는 칸을 비운다 — 읽는 쪽이 옛 식으로 떨어진다. */
    @Test
    void 배치표_없는_보고는_칸을_비운다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        배치표와_완성시킨다(clipId, null);

        assertThat(jdbc.queryForObject("SELECT placements IS NULL FROM clips WHERE id = ?", Boolean.class, clipId))
                .isTrue();
    }

    /**
     * 업로드 장면이 결과보다 길면 결과 끝 바로 앞(9,999)으로 자른다. 저장값(업로드 줄의 {@code thumbnail_offset_ms})은 고른 값
     * 그대로다 — 주문서 값만 결과 안 값이다.
     */
    @Test
    void 업로드_장면은_결과_끝을_넘으면_결과_안으로_자른다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();

        try (LogCaptor logs = new LogCaptor()) {
            배치표와_완성시킨다(clipId, 배치표(new long[] {2, 3, 4}, 10_000_000));

            long uploadId = 업로드_번호();
            assertThat(logs.messages()).contains("clip.upload.scene_mapped uploadId=" + uploadId
                    + " offsetMs=12000 resultMs=9999 source=layout");
            assertThat(logs.levelOf("clip.upload.scene_mapped")).isEqualTo(Level.INFO);
        }
        assertThat(주문서_장면()).isEqualTo(9_999L);
        assertThat(jdbc.queryForObject("SELECT thumbnail_offset_ms FROM clip_uploads", Long.class)).isEqualTo(12_000L);
    }

    /**
     * 결과 안이면 고른 값 그대로다. 컷 안 조각 4 를 장부에서 빼 결과가 틈을 건너뛰게 둔다 — 이 시드에서 「컷 시작 PDT + 12,000」 을
     * 장부 축으로 풀면 조각 5 머리(결과 8,000)가 나온다(편집기 값은 이미 결과 영상 안 거리라 축 변환하면 틈만큼 앞당겨진다 · N9).
     */
    @Test
    void 업로드_장면은_결과_안이면_고른_값_그대로이고_장부_축으로_풀지_않는다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ? AND seq = 4", 방송);

        배치표와_완성시킨다(clipId, 배치표(new long[] {2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 41_000_000));

        assertThat(주문서_장면()).isEqualTo(12_000L);
    }

    /** 옛 꼴(배치표 없음)은 결과 길이를 몰라 받은 값 그대로 보내고 WARN 을 남긴다. */
    @Test
    void 결과_길이를_모르면_받은_값_그대로_보내고_경고한다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();

        try (LogCaptor logs = new LogCaptor()) {
            배치표와_완성시킨다(clipId, null);

            assertThat(logs.messages()).contains("clip.upload.scene_mapped uploadId=" + 업로드_번호()
                    + " offsetMs=12000 resultMs=12000 source=fallback reason=no_layout");
            assertThat(logs.levelOf("clip.upload.scene_mapped")).isEqualTo(Level.WARN);
        }
        assertThat(주문서_장면()).isEqualTo(12_000L);
    }

    /**
     * 🔴 배치표가 깨져(계산이 던져도) 성공은 커밋된다 — 완성 · 업로드 줄 · 보고 장부가 모두 남고 장면은 받은 값 그대로다. 계산 예외가
     * 성공 트랜잭션 밖으로 새면 완성까지 되감겨 일꾼이 같은 보고를 되풀이한다(예측 P14 「성공 되감김」).
     */
    @Test
    void 배치표가_깨져도_성공은_커밋되고_장면은_받은_값_그대로다() throws Exception {
        long clipId = json(만들기(편집본, 기본_업로드()).andExpect(status().isCreated())).get("id").asLong();

        try (LogCaptor logs = new LogCaptor()) {
            배치표와_완성시킨다(clipId, "\"placements\":[{\"seq\":\"x\"}],\"lengthUs\":-5");

            assertThat(logs.levelOf("clip.upload.scene_mapped")).isEqualTo(Level.WARN);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, clipId))
                .isEqualTo("rendered");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM render_job_events WHERE event_type = 'SUCCEEDED'",
                Integer.class)).isOne();
        assertThat(업로드_줄_수()).isOne();
        assertThat(주문서_장면()).isEqualTo(12_000L);
    }

    // ── 받는 자리 검사(커밋 리뷰 r1 — 보안 HIGH-1 · MEDIUM-1 · MEDIUM-2 · cx HIGH 1) ─────────────────

    /**
     * 🔴 JSONB 가 받지 못하는 글자(NUL · U+0000)가 든 배치표가 와도 성공은 커밋된다 — 배치표만 버리고(NULL) WARN 을 남긴다. 그대로
     * 적으면 PostgreSQL 이 커밋 때 거절해 완성 · 보고 장부까지 되감기고, 일꾼이 다시 보내도 같은 실패가 난다.
     */
    @Test
    void JSONB_가_못_받는_글자가_든_배치표는_버리고_성공은_커밋된다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        try (LogCaptor logs = new LogCaptor()) {
            배치표와_완성시킨다(clipId, "\"placements\":[{\"seq\":2,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1000,"
                    + "\"headLeadUs\":0,\"note\":\"\\u0000\"}],\"lengthUs\":1000");

            assertThat(logs.levelOf("clip.render.placements_dropped jobId=")).isEqualTo(Level.WARN);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, clipId))
                .isEqualTo("rendered");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM render_job_events WHERE event_type = 'SUCCEEDED'",
                Integer.class)).isOne();
        assertThat(배치표_칸(clipId)).isNull();
    }

    /** 모양은 맞고 값이 넘치는 배치표(zeroAtUs = Long.MAX_VALUE)는 저장 단계에서 버린다 — 읽는 쪽 계산이 넘치지 않게. */
    @Test
    void 값이_넘치는_배치표는_버린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        배치표와_완성시킨다(clipId, "\"placements\":[{\"seq\":2,\"zeroAtUs\":9223372036854775807,\"fromUs\":0,"
                + "\"toUs\":1,\"headLeadUs\":0}],\"lengthUs\":1");

        assertThat(배치표_칸(clipId)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM clips WHERE id = ?", String.class, clipId))
                .isEqualTo("rendered");
    }

    /** 줄 수 상한을 넘는 배치표는 버린다. */
    @Test
    void 줄이_너무_많은_배치표는_버린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i <= ResultPlacements.MAX_ROWS; i++) {
            rows.append(i == 0 ? "" : ",").append("{\"seq\":").append(i + 1)
                    .append(",\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1,\"headLeadUs\":0}");
        }

        배치표와_완성시킨다(clipId, "\"placements\":[" + rows + "],\"lengthUs\":10");

        assertThat(배치표_칸(clipId)).isNull();
    }

    /** 글로 쓴 길이가 상한(64KB)을 넘는 배치표는 칸을 읽기 전에 버린다(사유 too_long). */
    @Test
    void 글이_너무_긴_배치표는_버린다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();

        try (LogCaptor logs = new LogCaptor()) {
            배치표와_완성시킨다(clipId, "\"placements\":[{\"seq\":2,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1000,"
                    + "\"headLeadUs\":0,\"pad\":\"" + "a".repeat(70_000) + "\"}],\"lengthUs\":1000");

            assertThat(logs.messages()).anySatisfy(m -> assertThat(m)
                    .startsWith("clip.render.placements_dropped jobId=").endsWith("reason=too_long"));
        }
        assertThat(배치표_칸(clipId)).isNull();
    }

    /**
     * 이미 저장된 옛 값이 범위를 넘으면(받는 자리 검사 전 · 손으로 고친 줄) 업로드 장면은 받은 값 그대로 보내고, WARN 에 파싱
     * 실패의 이유(예외 이름)를 싣는다 — 「옛 꼴(없음)」 과 「깨짐」 을 가른다(보안 L1).
     */
    @Test
    void 저장된_배치표가_깨졌으면_업로드_경고에_이유를_싣는다() throws Exception {
        long clipId = json(만들기(편집본, null).andExpect(status().isCreated())).get("id").asLong();
        완성시킨다(clipId, 영상_하나());
        비운다(렌더줄);
        jdbc.update("UPDATE clips SET placements = CAST(? AS jsonb) WHERE id = ?", "{\"placements\":[{\"seq\":2,"
                + "\"zeroAtUs\":9223372036854775807,\"fromUs\":0,\"toUs\":1,\"headLeadUs\":0}],\"lengthUs\":1}", clipId);

        try (LogCaptor logs = new LogCaptor()) {
            만들기(편집본, 기본_업로드()).andExpect(status().isOk());

            assertThat(logs.messages()).contains("clip.upload.scene_mapped uploadId=" + 업로드_번호()
                    + " offsetMs=12000 resultMs=12000 source=fallback reason=IllegalArgumentException");
        }
        assertThat(주문서_장면()).isEqualTo(12_000L);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private String 배치표_칸(long clipId) {
        return jdbc.queryForObject("SELECT placements::text FROM clips WHERE id = ?", String.class, clipId);
    }

    /** 일꾼이 잡고 성공을 보고한다. {@code layout} 은 본문 맨 위 칸 조각(없으면 옛 꼴). */
    private void 배치표와_완성시킨다(long clipId, String layout) throws Exception {
        UUID jobId = 잡(clipId);
        String token = MAPPER.readTree(본문(보고(jobId, 이벤트("STARTED", null, null)).andExpect(status().isOk())))
                .get("executionToken").asString();
        String result = "\"result\":" + 영상_하나().replace("{prefix}", "clips/" + clipId + "/" + token);
        보고(jobId, 이벤트("SUCCEEDED", token, layout == null ? result : result + "," + layout))
                .andExpect(status().isOk());
    }

    /** 조각을 4초씩 이어 놓은 배치표 칸 조각. 마지막 조각 몫은 결과 끝까지다. */
    private static String 배치표(long[] seqs, long lengthUs) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < seqs.length; i++) {
            long zero = i * 4_000_000L;
            long to = Math.min(4_000_000L, lengthUs - zero);
            rows.append(i == 0 ? "" : ",").append("{\"seq\":").append(seqs[i]).append(",\"zeroAtUs\":").append(zero)
                    .append(",\"fromUs\":0,\"toUs\":").append(to).append(",\"headLeadUs\":0}");
        }
        return "\"placements\":[" + rows + "],\"lengthUs\":" + lengthUs;
    }

    private long 업로드_번호() {
        return jdbc.queryForObject("SELECT id FROM clip_uploads", Long.class);
    }

    private long 주문서_장면() {
        String 쪽지 = LocalStackFixture.receiveAndDelete(업로드줄.queueUrl());
        assertThat(쪽지).as("업로드 주문서가 실리지 않았다").isNotNull();
        JsonNode 봉투 = MAPPER.readTree(쪽지);
        assertThat(봉투.at("/thumbnail/source").asString()).isEqualTo("scene");
        return 봉투.at("/thumbnail/offsetMs").asLong();
    }
}
