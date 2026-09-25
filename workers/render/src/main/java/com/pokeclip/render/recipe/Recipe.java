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

    public record Output(String outputId, Aspect aspect, Crop crop) {
    }

    /** trackId 0 = 최종 믹스, 1~5 = 소스별(ADR-017). gain은 선형 0.0~2.0. */
    public record AudioTrack(int trackId, double gain) {
    }

    public record Subtitles(SubtitleMode mode, List<SubtitleSegment> segments) {
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
