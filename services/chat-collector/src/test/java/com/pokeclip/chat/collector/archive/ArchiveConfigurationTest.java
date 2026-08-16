package com.pokeclip.chat.collector.archive;

import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 스프링 없이 팩토리 메서드만 — 켜짐/꺼짐 갈림은 bucket 하나다. */
class ArchiveConfigurationTest {

    @Test
    void bucket이_비면_NONE이고_disabled_로그가_한_줄_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            ChatArchive archive = new ArchiveConfiguration().chatArchive(
                    new ArchiveProperties("", "ap-northeast-2", "", false, 60, 10_000));
            assertThat(archive).isSameAs(ChatArchive.NONE);
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.archive.disabled"));
        }
    }

    @Test
    void bucket이_있으면_ChatArchiver가_만들어지고_runId가_있다() {
        ChatArchive archive = new ArchiveConfiguration().chatArchive(
                new ArchiveProperties("some-bucket", "ap-northeast-2", "http://127.0.0.1:1", true, 60, 10_000));
        assertThat(archive).isInstanceOf(ChatArchiver.class);
        assertThat(archive.counters().runId()).hasSize(8).matches("[0-9a-f]{8}");
        archive.beginClose();
        archive.awaitClosed(Duration.ofSeconds(1));   // 스레드 정리
    }
}
