package com.pokeclip.chat.detector.metrics;

/** 집계 한 줄. 표의 칸과 이름을 맞춘다. */
public record MetricRow(String streamId,
                        long windowSizeMs,
                        long windowStartMs,
                        int messageCount,
                        int chatterCount) {
}
