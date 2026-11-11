package com.pokeclip.render.support;

import com.pokeclip.render.support.SharedTimelineCases.AudioJoinCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공유 시험 표({@code services/ledger-timeline/src/test/resources/ledger-timeline-cases.json})를 렌더 몫으로 읽는다(PDT 설계 r7
 * 3.5 · r6 3.4). 렌더 몫 갈래는 {@code audioJoin} 이고, 사례마다 소리 이음 판정은 렌더 소리 이음 시험이 이 읽개로 대조한다.
 *
 * <p>표는 services · 렌더 · 웹 세 곳이 읽는 계약이라 읽는 쪽은 fail-closed 다. 아래 「거절한다」 시험이 그 규칙이다.
 */
class SharedTimelineCasesTest {

    private static final String AUDIO_JOIN = """
            {"id": "a-v2", "kind": "audioJoin", "timingVersion": 2,
             "prev": {"videoStartMs": 0, "videoEndMs": 4000, "tracks": [{"startMs": 0, "endMs": 4000, "sampleRate": 48000}]},
             "next": {"videoStartMs": 0, "tracks": [{"startMs": 300}]},
             "applies": true, "expectSilenceMs": [300]}""";

    @Test
    void 저장소_표를_상대_경로로_읽어_audioJoin_사례를_모두_낸다() {
        List<AudioJoinCase> cases = SharedTimelineCases.load().audioJoin();

        assertThat(cases).isNotEmpty();
        assertThat(cases).extracting(AudioJoinCase::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(c -> assertThat(c.expectSilenceMs()).hasSameSizeAs(c.next().tracks()));
    }

    /** 판으로 가르지 않는다(r6 3.4) — 판 2 사례마다 같은 값의 판 NULL 짝이 있어야 표가 그 결함을 잡는다. */
    @Test
    void audioJoin_사례는_판_2_와_판_NULL_이_같은_값으로_짝지어_있다() {
        List<AudioJoinCase> cases = SharedTimelineCases.load().audioJoin();

        for (AudioJoinCase v2 : cases.stream().filter(c -> Objects.equals(c.timingVersion(), 2)).toList()) {
            assertThat(cases).as(v2.id()).anySatisfy(other -> {
                assertThat(other.timingVersion()).isNull();
                assertThat(other.withoutIdAndVersion()).isEqualTo(v2.withoutIdAndVersion());
            });
        }
        assertThat(cases).anySatisfy(c -> assertThat(c.timingVersion()).isEqualTo(2));
    }

    @Test
    void 영상이_없는_쪽이_있는_사례는_판정하지_않고_무음_0_이다() {
        String noNextVideo = AUDIO_JOIN
                .replace("\"videoStartMs\": 0, \"tracks\": [{\"startMs\": 300}]", "\"tracks\": [{\"startMs\": 300}]")
                .replace("\"applies\": true, \"expectSilenceMs\": [300]", "\"applies\": false, \"expectSilenceMs\": [0]");

        AudioJoinCase c = SharedTimelineCases.parse(table(noNextVideo)).audioJoin().getFirst();

        assertThat(c.applies()).isFalse();
        assertThat(c.next().videoStartMs()).isNull();
        assertThat(c.prev().videoEndMs()).isEqualTo(4000L);
    }

    @Test
    void 표가_없으면_건너뛰지_않고_실패한다(@TempDir Path dir) {
        assertThatThrownBy(() -> SharedTimelineCases.load(dir.resolve("없음.json")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 표_판이_다르면_거절한다() {
        assertThatThrownBy(() -> SharedTimelineCases.parse(table(AUDIO_JOIN).replace("\"version\": 2", "\"version\": 3")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
    }

    /** 판은 정수 2 하나다 — 2.9 가 잘려 2 로 읽히거나 글자 "2" 가 통과하지 않는다. */
    @Test
    void 표_판이_정수_2_가_아니면_거절한다() {
        for (String version : new String[] {"2.9", "2.0", "\"2\"", "null"}) {
            String bad = table(AUDIO_JOIN).replace("\"version\": 2", "\"version\": " + version);
            assertThatThrownBy(() -> SharedTimelineCases.parse(bad)).as(version)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("version");
        }
    }

    /** 사례 목록은 배열이다 — 객체로 오면 그 값들을 사례로 돌지 않는다. */
    @Test
    void 사례_목록이_배열이_아니면_거절한다() {
        String object = "{\"version\": 2, \"cases\": {\"a\": " + AUDIO_JOIN + "}}";
        String missing = "{\"version\": 2}";

        for (String bad : List.of(object, missing)) {
            assertThatThrownBy(() -> SharedTimelineCases.parse(bad)).as(bad)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("cases");
        }
    }

    @Test
    void 모르는_갈래가_있으면_거절한다() {
        String unknown = "{\"id\": \"x\", \"kind\": \"resultOffset\"}";

        assertThatThrownBy(() -> SharedTimelineCases.parse(table(AUDIO_JOIN, unknown)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("kind");
    }

    @Test
    void audioJoin_사례가_하나도_없으면_거절한다() {
        String f = "{\"id\": \"f\", \"kind\": \"F\"}";

        assertThatThrownBy(() -> SharedTimelineCases.parse(table(f)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("audioJoin");
    }

    @Test
    void 사례_id_가_겹치면_거절한다() {
        assertThatThrownBy(() -> SharedTimelineCases.parse(table(AUDIO_JOIN, AUDIO_JOIN)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
    }

    @Test
    void audioJoin_사례의_모양이_틀리면_거절한다() {
        String sizes = AUDIO_JOIN.replace("\"expectSilenceMs\": [300]", "\"expectSilenceMs\": [300, 0]");
        String noVersion = AUDIO_JOIN.replace("\"timingVersion\": 2,", "");
        String badVersion = AUDIO_JOIN.replace("\"timingVersion\": 2", "\"timingVersion\": 1");
        String halfVideo = AUDIO_JOIN.replace("\"videoStartMs\": 0, \"videoEndMs\": 4000", "\"videoStartMs\": 0");
        String notZero = AUDIO_JOIN.replace("\"videoStartMs\": 0, \"tracks\": [{\"startMs\": 300}]",
                "\"tracks\": [{\"startMs\": 300}]").replace("\"applies\": true", "\"applies\": false");

        for (String bad : List.of(sizes, noVersion, badVersion, halfVideo, notZero)) {
            assertThatThrownBy(() -> SharedTimelineCases.parse(table(bad))).as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 읽는_길은_렌더_일꾼_폴더_기준_상대_경로다() {
        assertThat(SharedTimelineCases.PATH).isEqualTo(
                Path.of("../../services/ledger-timeline/src/test/resources/ledger-timeline-cases.json"));
        assertThat(Files.isRegularFile(SharedTimelineCases.PATH)).isTrue();
    }

    private static String table(String... cases) {
        return "{\"version\": 2, \"cases\": [" + String.join(",", cases) + "]}";
    }
}
