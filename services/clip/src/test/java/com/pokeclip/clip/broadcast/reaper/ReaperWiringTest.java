package com.pokeclip.clip.broadcast.reaper;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 켜면 치우개 빈이 실제로 뜨고, {@code @Scheduled}가 읽는 키가 yml에 있는 키와 같은지 잰다.
 * 기본 시험 컨텍스트(꺼짐)에서 빈이 없는 것은 {@code StaleBroadcastReaperTest}가 손으로 만드는 것이 증거다.
 */
@TestPropertySource(properties = "pokeclip.broadcast.reaper.enabled=true")
class ReaperWiringTest extends IntegrationTestSupport {

    private final ApplicationContext context;
    private final JdbcTemplate jdbc;

    ReaperWiringTest(ApplicationContext context, JdbcTemplate jdbc) {
        this.context = context;
        this.jdbc = jdbc;
    }

    /** 켜져 있는데 1번 장부 표가 없으면 부팅 거부 — 조용히 죽는 안전망을 막는다(codex P2). 있으면 통과. */
    @Test
    void 장부_표가_없으면_켤_수_없다() {
        ReaperConfiguration.requireSegmentLedger(jdbc);   // 시드가 만든 표가 있다 — 통과

        JdbcTemplate 표_없음 = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.BDDMockito.given(표_없음.queryForObject(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class))).willReturn(null);
        assertThatThrownBy(() -> ReaperConfiguration.requireSegmentLedger(표_없음))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream_segments");
    }

    @Test
    void 켜면_치우개가_뜬다() {
        assertThat(context.getBeanNamesForType(StaleBroadcastReaper.class)).hasSize(1);
    }

    @Test
    void 스케줄_키가_yml의_키와_같다() throws Exception {
        Scheduled scheduled = StaleBroadcastReaper.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${pokeclip.broadcast.reaper.interval}");
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("interval: ${BROADCAST_REAPER_INTERVAL:").contains("grace: ${BROADCAST_REAPER_GRACE:");
    }
}
