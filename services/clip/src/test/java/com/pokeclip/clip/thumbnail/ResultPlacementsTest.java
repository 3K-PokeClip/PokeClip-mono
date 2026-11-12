package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.segment.OffsetOrigin;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결과 영상 안 자리(POK-196 S-c5 · PDT 설계 r7 8.3 · 예측 P14). 렌더 배치표 한 벌만 읽어 「조각 seq 의 x 자리가 결과 몇 ms 인가」를
 * 낸다. 식: {@code x′ = offsetMs × 1000 − (FILE_HEAD ? headLeadUs : 0)} → 배치가 있으면 {@code zeroAtUs + clamp(x′, fromUs, toUs)},
 * 없으면 앞 배치 끝(뒤쪽) 또는 첫 배치 시작(앞쪽) → {@code [0, lengthUs]} → ms 내림.
 */
class ResultPlacementsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 조각 셋(3 · 5 · 9). 4 는 결과에 없다(장부 틈이 이어 붙어 사라졌다). 결과 길이 40초. */
    private static final String 셋 = """
            {"placements":[
              {"seq":3,"zeroAtUs":-1500000,"fromUs":1500000,"toUs":4000000,"headLeadUs":21333},
              {"seq":5,"zeroAtUs":2500000,"fromUs":0,"toUs":4000000,"headLeadUs":21333},
              {"seq":9,"zeroAtUs":31000000,"fromUs":0,"toUs":4000000,"headLeadUs":21333}],
             "lengthUs":40000000}""";

    /**
     * cx2-2 반례 — 조각 9 의 1000ms 는 결과 32,000ms 다. 「zeroAtUs + x′ 를 조각 범위로 자름」이면 4,000 이 된다(조각 몫 범위는
     * x′ 축이라 더한 뒤에 자르면 단위가 섞인다).
     */
    @Test
    void cx2_2_반례_조각_자리를_조각_몫으로_자른_뒤_더한다() {
        assertThat(of(셋).resultMsOf(9, 1_000, OffsetOrigin.VIDEO_START)).isEqualTo(32_000);
    }

    /** 판 NULL(FILE_HEAD) 원점은 파일 머리부터라 m(headLeadUs, µs)을 빼고 놓는다 — 31,978.667 → 내림 31,978. */
    @Test
    void 파일_머리_원점은_µs_단위_m_을_빼고_놓는다() {
        assertThat(of(셋).resultMsOf(9, 1_000, OffsetOrigin.FILE_HEAD)).isEqualTo(31_978);
    }

    /** 조각 몫 밖 자리(조각 끝 넘어)는 그 조각 몫 끝에 붙는다. */
    @Test
    void 조각_몫_밖_자리는_몫_끝에_붙는다() {
        assertThat(of(셋).resultMsOf(5, 9_000, OffsetOrigin.VIDEO_START)).isEqualTo(6_500);
    }

    /** Gap — 결과에 없는 조각(4)은 바로 앞 배치(3)의 몫 끝이다(틈은 이어 붙어 사라졌다 · 그 자리는 조각 5 머리와 같다). */
    @Test
    void 배치에_없는_조각은_앞_배치_끝이다() {
        assertThat(of(셋).resultMsOf(4, 100, OffsetOrigin.VIDEO_START)).isEqualTo(2_500);
    }

    /**
     * BeforeFirst — 첫 배치보다 앞 조각은 첫 배치 시작(조각 3 몫의 시작 = 결과 2,000)이다. 그 x 를 첫 배치에 그대로 놓으면(3,000)
     * 다른 조각의 자리를 첫 조각 자리로 읽은 것이다.
     */
    @Test
    void 첫_배치보다_앞_조각은_첫_배치_시작이다() {
        assertThat(of("""
                {"placements":[{"seq":3,"zeroAtUs":0,"fromUs":2000000,"toUs":4000000,"headLeadUs":0}],"lengthUs":10000000}""")
                .resultMsOf(1, 3_000, OffsetOrigin.VIDEO_START)).isEqualTo(2_000);
    }

    /** AfterLast — 마지막 배치보다 뒤 조각은 마지막 몫 끝이고, 결과 길이를 넘으면 결과 끝으로 자른다. */
    @Test
    void 마지막_배치보다_뒤_조각은_결과_끝으로_잘린다() {
        assertThat(of(셋).resultMsOf(12, 0, OffsetOrigin.VIDEO_START)).isEqualTo(35_000);
        assertThat(of("""
                {"placements":[{"seq":1,"zeroAtUs":0,"fromUs":0,"toUs":4000000,"headLeadUs":0}],"lengthUs":3500000}""")
                .resultMsOf(7, 0, OffsetOrigin.VIDEO_START)).isEqualTo(3_500);
    }

    /** 첫 배치의 앞쪽 음수 자리는 0 으로 자른다. */
    @Test
    void 결과_앞쪽은_0으로_자른다() {
        assertThat(of("""
                {"placements":[{"seq":1,"zeroAtUs":-2000000,"fromUs":0,"toUs":4000000,"headLeadUs":0}],"lengthUs":2000000}""")
                .resultMsOf(1, 500, OffsetOrigin.VIDEO_START)).isZero();
    }

    /** 결과 안 [0, 결과 끝) — 업로드 장면이 쓴다(N9 답 · offsetMs 는 이미 결과 영상 ms 다). */
    @Test
    void 결과_안으로_자르면_끝은_결과_끝_바로_앞이다() {
        ResultPlacements r = of(셋);
        assertThat(r.lengthMs()).isEqualTo(40_000);
        assertThat(r.clampToResult(12_000)).isEqualTo(12_000);
        assertThat(r.clampToResult(40_000)).isEqualTo(39_999);
        assertThat(r.clampToResult(99_000)).isEqualTo(39_999);
        assertThat(r.clampToResult(-5)).isZero();
        assertThat(r.middleMs()).isEqualTo(20_000);
    }

    @Test
    void 결과가_1ms_보다_짧으면_자른_자리는_0이다() {
        assertThat(of("""
                {"placements":[{"seq":1,"zeroAtUs":0,"fromUs":0,"toUs":500,"headLeadUs":0}],"lengthUs":500}""")
                .clampToResult(3)).isZero();
    }

    /** 모양이 틀리면 던진다 — 부르는 쪽이 옛 식 · 받은 값으로 떨어진다(성공 처리를 되감지 않는다). */
    @Test
    void 모양이_틀리면_던진다() {
        String one = "{\"seq\":1,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":10,\"headLeadUs\":0}";
        for (String bad : new String[] {
                "\"oops\"",
                "{\"lengthUs\":10}",
                "{\"placements\":[],\"lengthUs\":10}",
                "{\"placements\":\"x\",\"lengthUs\":10}",
                "{\"placements\":[" + one + "]}",
                "{\"placements\":[" + one + "],\"lengthUs\":-1}",
                "{\"placements\":[" + one + "],\"lengthUs\":1.5}",
                "{\"placements\":[{\"seq\":1,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":10}],\"lengthUs\":10}",
                "{\"placements\":[{\"seq\":1,\"zeroAtUs\":\"0\",\"fromUs\":0,\"toUs\":10,\"headLeadUs\":0}],\"lengthUs\":10}",
                "{\"placements\":[{\"seq\":1,\"zeroAtUs\":0,\"fromUs\":11,\"toUs\":10,\"headLeadUs\":0}],\"lengthUs\":10}",
                "{\"placements\":[" + one + "," + one + "],\"lengthUs\":10}",
        }) {
            JsonNode node = MAPPER.readTree(bad);
            assertThatThrownBy(() -> ResultPlacements.parse(node)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ResultPlacements.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 값 범위 · 줄 수 · 칸 묶음도 거절한다(커밋 리뷰 r1 보안 HIGH-1 · MEDIUM-1). 모양만 맞고 값이 넘치는 표가 지나가면 자리 계산의
     * exact 연산이 던져 완성 사진 순회가 그 줄에서 멈춘다. 결과 길이 상한은 렌더 결과 상한 180초, 칸 절댓값 상한은 그 위 60초 여유다.
     */
    @Test
    void 값이_범위를_넘거나_줄이_너무_많거나_모르는_칸이_있으면_던진다() {
        long cap = ResultPlacements.MAX_LENGTH_US;
        long abs = ResultPlacements.MAX_ABS_US;
        for (String bad : new String[] {
                row("9223372036854775807", "0", "1", "0") + ",\"lengthUs\":1000",
                row("0", "0", "1", "-1") + ",\"lengthUs\":1000",
                row("0", "0", "1", String.valueOf(abs + 1)) + ",\"lengthUs\":1000",
                row(String.valueOf(-abs - 1), "0", "1", "0") + ",\"lengthUs\":1000",
                row("0", String.valueOf(-abs - 1), "1", "0") + ",\"lengthUs\":1000",
                row("0", "0", String.valueOf(abs + 1), "0") + ",\"lengthUs\":1000",
                row("0", "0", "1", "0") + ",\"lengthUs\":" + (cap + 1),
                "\"placements\":[{\"seq\":-1,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1,\"headLeadUs\":0}],\"lengthUs\":10",
                "\"placements\":[{\"seq\":1,\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1,\"headLeadUs\":0,\"x\":\"\\u0000\"}],"
                        + "\"lengthUs\":10",
                row("0", "0", "1", "0") + ",\"lengthUs\":10,\"extra\":1",
                rows(ResultPlacements.MAX_ROWS + 1) + ",\"lengthUs\":10",
        }) {
            JsonNode node = MAPPER.readTree("{" + bad + "}");
            assertThatThrownBy(() -> ResultPlacements.parse(node)).as(bad.substring(0, Math.min(bad.length(), 120)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 경계 값 그대로는 받는다 — 결과 길이 180초 · 칸 절댓값 상한 · 줄 수 상한. */
    @Test
    void 상한_경계_값은_받는다() {
        long abs = ResultPlacements.MAX_ABS_US;
        ResultPlacements r = of("{" + row(String.valueOf(-abs), String.valueOf(-abs), String.valueOf(abs),
                String.valueOf(abs)) + ",\"lengthUs\":" + ResultPlacements.MAX_LENGTH_US + "}");
        assertThat(r.lengthMs()).isEqualTo(180_000);
        assertThat(of("{" + rows(ResultPlacements.MAX_ROWS) + ",\"lengthUs\":10}").lengthMs()).isZero();
    }

    /**
     * cx 반례(커밋 리뷰 확인 패스 1 #8) — 재접속으로 생긴 300ms 조각 501개(사이 50ms 공백 500개 · 컷 175.3초 · 결과 150.3초)는
     * 정상 렌더 배치표다. 렌더 · 계획에 조각 수 상한이 없으니 읽는 쪽 줄 수 상한은 주문서 크기에서 나올 수 있는 최대 매체 조각 수
     * 위여야 한다. 조각 250 의 100ms 는 결과 75,100 이다.
     */
    @Test
    void 짧은_재접속_조각_501개_배치표를_받는다() {
        ResultPlacements r = of(짧은_조각_501());
        assertThat(r.lengthMs()).isEqualTo(150_300);
        assertThat(r.resultMsOf(250, 100, OffsetOrigin.VIDEO_START)).isEqualTo(75_100);
    }

    /** 받는 자리는 그 잡 주문의 매체 조각 수를 줄 수 상한으로 준다 — 그보다 많으면 던진다. 같으면 받는다. */
    @Test
    void 주는_줄_수_상한보다_많으면_던진다() {
        JsonNode node = MAPPER.readTree(짧은_조각_501());
        assertThat(ResultPlacements.parse(node, 501).lengthMs()).isEqualTo(150_300);
        assertThatThrownBy(() -> ResultPlacements.parse(node, 500)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 읽는 쪽 줄 수 상한은 주문서 크기 상한(계약1 2절 204,800바이트)을 매체 조각 하나가 주문서에서 차지하는 최소 바이트(빈
     * 창고 · 빈 키 {@code segment} 항목 119 + {@code mediaSeqs} 한 자리 2)로 나눈 수다. 글 길이 상한은 그 줄 수 × 가장 긴 줄(seq
     * 19자리 · 칸 셋 ±240,000,000 · m 240,000,000)이다 — 정상 표가 글 길이로 걸리지 않는다.
     */
    @Test
    void 줄_수_상한과_글_길이_상한은_주문서_크기에서_나온다() {
        String widest = "{\"seq\":9999999999999999999,\"zeroAtUs\":-240000000,\"fromUs\":-240000000,\"toUs\":-240000000,"
                + "\"headLeadUs\":240000000},";
        String emptySegment = "{\"role\":\"segment\",\"bucket\":\"\",\"s3Key\":\"\",\"seq\":0,\"durationMs\":0,"
                + "\"isDiscontinuity\":false,\"followsLedgerPrevious\":false},";
        assertThat(ResultPlacements.MAX_ROWS).isEqualTo(204_800 / (emptySegment.length() + "0,".length()));
        assertThat(ResultPlacements.maxStoredChars(501))
                .isGreaterThanOrEqualTo(501 * widest.length() + "{\"placements\":[],\"lengthUs\":180000000}".length());
        assertThat(짧은_조각_501().length()).isLessThan(ResultPlacements.maxStoredChars(501));
    }

    /** 300ms 조각 501개 · 결과 150.3초(공백 접힘) — cx 반례 배치표. */
    private static String 짧은_조각_501() {
        StringBuilder s = new StringBuilder("{\"placements\":[");
        for (int i = 0; i <= 500; i++) {
            s.append(i == 0 ? "" : ",").append("{\"seq\":").append(i).append(",\"zeroAtUs\":").append(300_000L * i)
                    .append(",\"fromUs\":0,\"toUs\":300000,\"headLeadUs\":0}");
        }
        return s.append("],\"lengthUs\":150300000}").toString();
    }

    private static String row(String zeroAtUs, String fromUs, String toUs, String headLeadUs) {
        return "\"placements\":[{\"seq\":1,\"zeroAtUs\":" + zeroAtUs + ",\"fromUs\":" + fromUs + ",\"toUs\":" + toUs
                + ",\"headLeadUs\":" + headLeadUs + "}]";
    }

    private static String rows(int count) {
        StringBuilder s = new StringBuilder("\"placements\":[");
        for (int i = 0; i < count; i++) {
            s.append(i == 0 ? "" : ",").append("{\"seq\":").append(i)
                    .append(",\"zeroAtUs\":0,\"fromUs\":0,\"toUs\":1,\"headLeadUs\":0}");
        }
        return s.append("]").toString();
    }

    private static ResultPlacements of(String json) {
        return ResultPlacements.parse(MAPPER.readTree(json));
    }
}
