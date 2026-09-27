package com.pokeclip.render.recipe;

import java.util.List;

/**
 * 계약6 레시피 한 벌. 렌더 입력의 전부다. 시각은 전부 방송 절대축(UTC epoch ms)이다.
 * 검증을 통과한 것만 이 모양이 된다({@link RecipeParser}). 렌더 주문에 실린 레시피라 {@code cut}은 늘 있다.
 */
public record Recipe(String streamId, Cut cut, List<Output> outputs, List<AudioTrack> tracks, Subtitles subtitles) {

    /** 반개 구간 {@code [inAtMs, outAtMs)}. */
    public record Cut(long inAtMs, long outAtMs) {
        public long durationMs() {
            return outAtMs - inAtMs;
        }
    }

    /** 원본 화면에서 자를 영역. 표시 평면의 정규화 좌표(0~1, 좌상단 원점). */
    public record Crop(double x, double y, double w, double h) {
    }

    /**
     * 출력 한 벌. 결과 화면을 {@code background} 위에 {@code layers}를 차례로 얹고 {@code dividers}를 긋는 것으로 적는다(계약6 v2).
     * v1의 {@code crop} 하나짜리 출력은 「원본을 잘라 결과를 꽉 채우는 층 하나」로 읽는다({@link #single}).
     *
     * @param background null이면 검정
     */
    public record Output(String outputId, Aspect aspect, Background background, List<Layer> layers,
                         List<Divider> dividers) {

        /** v1 출력. 층 하나가 결과 전체를 채운다. */
        public static Output single(String outputId, Aspect aspect, Crop crop) {
            return new Output(outputId, aspect, null, List.of(new Layer(crop, Box.FULL, null)), List.of());
        }

        /** 꾸밈 없이 층 하나가 결과를 꽉 채우는가. 그렇다면 v1과 같은 한 줄 필터로 만든다. */
        public boolean isPlainSingle() {
            return background == null && dividers.isEmpty() && layers.size() == 1
                    && layers.getFirst().frame() == null && layers.getFirst().box().equals(Box.FULL);
        }
    }

    /** 결과 화면 안의 자리. 결과의 정규화 좌표(0~1, 좌상단 원점). */
    public record Box(double x, double y, double w, double h) {
        public static final Box FULL = new Box(0, 0, 1, 1);
    }

    /**
     * 원본의 {@code crop}을 잘라 결과의 {@code box}에 놓는다. 둘의 픽셀 종횡비는 같아야 한다(±1%).
     *
     * @param frame null이면 테두리·둥근 모서리·그림자 없음
     */
    public record Layer(Crop crop, Box box, Frame frame) {
    }

    /**
     * 층의 둘레. 길이는 전부 <b>결과 폭에 대한 비</b>다(1080 폭이면 0.01 = 10.8px). 화면(편집기 미리보기)이 같은 비로 그린다.
     *
     * @param color {@code #RRGGBB}
     */
    public record Frame(double width, String color, double radius, boolean shadow) {
    }

    /** 층 뒤를 채우는 것. */
    public sealed interface Background {
        /** 원본 전체를 결과에 꽉 차게 키워 흐리게 깐다. {@code strength} 0~100. */
        record Blur(int strength) implements Background {
        }

        /** 단색. {@code #RRGGBB}. */
        record Color(String color) implements Background {
        }
    }

    /**
     * 가로 구분선. {@code y}(결과 높이에 대한 비)에서 아래로 {@code thickness}(결과 폭에 대한 비)만큼 칠한다.
     * 층들 위에 긋는다.
     */
    public record Divider(double y, double thickness, String color) {
    }

    /** trackId 0 = 최종 믹스, 1~5 = 소스별(ADR-017). gain은 선형 0.0~2.0. */
    public record AudioTrack(int trackId, double gain) {
    }

    /** @param position null이면 v1 자리(아래 가운데, 렌더 기본 글꼴 크기) */
    public record Subtitles(SubtitleMode mode, List<SubtitleSegment> segments, SubtitlePosition position) {
    }

    /**
     * 번인 자막 글 덩어리의 자리. {@code anchor} 쪽 가장자리(TOP=윗변, BOTTOM=아랫변, MIDDLE=가운데)가 결과 높이의 {@code y}에 온다.
     */
    public record SubtitlePosition(Anchor anchor, double y) {
    }

    public enum Anchor {
        TOP, MIDDLE, BOTTOM
    }

    /** 반개 구간 {@code [startAtMs, endAtMs)}. */
    public record SubtitleSegment(long startAtMs, long endAtMs, String text) {
    }

    /** 출력 비율. v1은 Shorts에 올릴 수 있는 둘뿐이다(계약6 2절). 해상도는 레시피가 아니라 렌더 내부 결정이다. */
    public enum Aspect {
        VERT_9_16(9, 16, 1080, 1920),
        SQUARE_1_1(1, 1, 1080, 1080);

        private final int ratioW;
        private final int ratioH;
        private final int width;
        private final int height;

        Aspect(int ratioW, int ratioH, int width, int height) {
            this.ratioW = ratioW;
            this.ratioH = ratioH;
            this.width = width;
            this.height = height;
        }

        public double ratio() {
            return (double) ratioW / ratioH;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    public enum SubtitleMode {
        BURN_AND_CC(true, true),
        BURN_ONLY(true, false),
        CC_ONLY(false, true);

        private final boolean burns;
        private final boolean cc;

        SubtitleMode(boolean burns, boolean cc) {
            this.burns = burns;
            this.cc = cc;
        }

        public boolean burns() {
            return burns;
        }

        public boolean cc() {
            return cc;
        }
    }
}
