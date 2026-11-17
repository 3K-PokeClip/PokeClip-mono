package com.pokeclip.render.work;

import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.media.SegmentTiming.TrackRange;
import com.pokeclip.render.support.PlanOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 준비 조각 대조({@link JoinedRenderer#primeMatches}) 중 ffmpeg 없이 손으로 지은 판독으로 보는 몫. */
class PrimeMatchesTest {

    /**
     * 준비 조각의 소리 설정이 머리와 다르면 길이가 맞아도 쓰지 않는다 — concat 스트림 복사는 첫 파일 설정으로 흐름 전체를
     * 디코드한다(설계 7.4 소리 묶음 7). 판독은 손으로 짓고(길이 4000ms = V) 코덱 · 표본율 · 채널 수 · 코덱 설정 바이트를 하나씩만
     * 바꾼다.
     */
    @Test
    void 준비_조각_소리_설정이_머리와_다르면_쓰지_않는다() {
        SegmentTiming owner = soundTiming(new TrackRange(0, 48_000, 0, 9_600, 10, 1, "aac", 48_000, 2, "SHA256:aa"));

        assertThat(primeWith(owner, new TrackRange(0, 48_000, 0, 9_600, 10, 1, "aac", 48_000, 2, "SHA256:aa")))
                .as("같은 설정").isTrue();
        assertThat(primeWith(owner, new TrackRange(0, 48_000, 0, 9_600, 10, 1, "opus", 48_000, 2, "SHA256:aa")))
                .as("코덱").isFalse();
        assertThat(primeWith(owner, new TrackRange(0, 44_100, 0, 8_820, 10, 1, "aac", 44_100, 2, "SHA256:aa")))
                .as("표본율").isFalse();
        assertThat(primeWith(owner, new TrackRange(0, 48_000, 0, 9_600, 10, 1, "aac", 48_000, 1, "SHA256:aa")))
                .as("채널 수").isFalse();
        assertThat(primeWith(owner, new TrackRange(0, 48_000, 0, 9_600, 10, 1, "aac", 48_000, 2, "SHA256:bb")))
                .as("코덱 설정 바이트").isFalse();
    }

    private static boolean primeWith(SegmentTiming owner, TrackRange primeTrack) {
        return JoinedRenderer.primeMatches(soundTiming(primeTrack), PlanOrder.DURATION_MS, owner, List.of(0));
    }

    /** 영상 1/90000 · V = 360,000틱(4000ms · 장부 길이와 VIDEO 대조) · 소리 트랙 하나. */
    private static SegmentTiming soundTiming(TrackRange track) {
        return new SegmentTiming(90_000, 0, 0, new long[] {0, 3_000}, 360_000, 4_000_000, List.of(track));
    }
}
