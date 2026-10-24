package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 켜고 끄기 · 부팅 거부(POK-397 PR4). 컨텍스트만 띄워 본다(브라우저는 첫 탭에서 뜨므로 여기서는 안 뜬다).
 */
class SoopChatConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SoopChatConfiguration.class)
            .withBean(ChatBuffer.class, ChatBuffer::new)
            .withBean(ChatArchive.class, () -> ChatArchive.NONE)
            .withBean(RelaySink.class, () -> RelaySink.NONE)
            .withPropertyValues("pokeclip.soop.chat.sdk-url=http://127.0.0.1:1/sdk.js",
                    "pokeclip.soop.chat.page-origin=http://localhost", "pokeclip.soop.chat.max-sessions=10",
                    "pokeclip.soop.chat.reconnect-min=PT1S", "pokeclip.soop.chat.reconnect-max=PT60S");

    @Test
    void 꺼져_있으면_NONE이다() {
        runner.run(context -> assertThat(context.getBean(SoopChat.class)).isSameAs(SoopChat.NONE));
    }

    @Test
    void 켜고_앱_값과_지켜보기가_있으면_뜬다() {
        runner.withPropertyValues("pokeclip.soop.chat.enabled=true", "pokeclip.soop.watch-enabled=true",
                        "pokeclip.soop.chat.client-id=id", "pokeclip.soop.chat.client-secret=secret")
                .run(context -> assertThat(context.getBean(SoopChat.class))
                        .isInstanceOf(SoopChatConfiguration.SoopChatRuntime.class));
    }

    @Test
    void 켰는데_앱_값이_비면_부팅을_거부하고_값을_안_싣는다() {
        runner.withPropertyValues("pokeclip.soop.chat.enabled=true", "pokeclip.soop.watch-enabled=true",
                        "pokeclip.soop.chat.client-id=id-visible")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("SOOP_CLIENT_SECRET").hasMessageNotContaining("id-visible"));
    }

    @Test
    void 채팅만_켜고_지켜보기를_끄면_부팅을_거부한다() {
        runner.withPropertyValues("pokeclip.soop.chat.enabled=true", "pokeclip.soop.chat.client-id=id",
                        "pokeclip.soop.chat.client-secret=secret")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("watch-enabled"));
    }

    @Test
    void 다시_붙기_간격은_두_배씩_상한까지() {
        SoopChatProperties p = new SoopChatProperties(true, "i", "s", "u", "o", 1, Duration.ofSeconds(1),
                Duration.ofSeconds(60));
        SoopChatSessions sessions = new SoopChatSessions(p, new ChatBuffer(), ChatArchive.NONE, RelaySink.NONE,
                System::currentTimeMillis);
        try {
            assertThat(sessions.backoff(0)).isEqualTo(Duration.ofSeconds(1));
            assertThat(sessions.backoff(1)).isEqualTo(Duration.ofSeconds(2));
            assertThat(sessions.backoff(5)).isEqualTo(Duration.ofSeconds(32));
            assertThat(sessions.backoff(6)).isEqualTo(Duration.ofSeconds(60));
            assertThat(sessions.backoff(50)).isEqualTo(Duration.ofSeconds(60));
        } finally {
            sessions.shutdown();
        }
    }

    @Test
    void 간격이_말이_안_되면_거부한다() {
        assertThatThrownBy(() -> new SoopChatProperties(true, "i", "s", "u", "o", 1, Duration.ofSeconds(5),
                Duration.ofSeconds(1)).validate()).hasMessageContaining("reconnect");
        assertThatThrownBy(() -> new SoopChatProperties(true, "i", "s", "u", "o", 0, Duration.ofSeconds(1),
                Duration.ofSeconds(1)).validate()).hasMessageContaining("max-sessions");
    }
}
