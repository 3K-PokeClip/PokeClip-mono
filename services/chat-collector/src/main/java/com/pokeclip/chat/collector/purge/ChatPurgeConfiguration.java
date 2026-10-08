package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.archive.ArchiveProperties;
import com.pokeclip.chat.collector.archive.S3Clients;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 원본 창고가 꺼져 있으면(버킷이 비면) 원본 지우기는 아무것도 안 한다. 그 배포에는 원본 파일이 없다.
 * 정리기 둘은 늘 켜져 있다: 꺼 두면 탈퇴한 사람과 60일 지난 채팅이 남는다.
 */
@Configuration
@EnableConfigurationProperties(ChatPurgeProperties.class)
public class ChatPurgeConfiguration {

    @Bean
    ArchivePurge archivePurge(ArchiveProperties archive) {
        return archive.enabled() ? new S3ArchivePurge(S3Clients.create(archive), archive.bucket()) : ArchivePurge.NONE;
    }

    @Bean
    ChannelPurger channelPurger(ChatPurgeStore store, ArchivePurge archive, SessionRegistry sessions,
                                ChatPurgeProperties properties) {
        // 그 채널을 걷는 세션을 닫는다. 늦게 온 시작 편지는 auth가 연동을 닫아 다시 못 연다(연동 조회가 거절된다).
        return new ChannelPurger(store, archive, channelId -> sessions.activeSessions().stream()
                .filter(s -> channelId.equals(s.channelId()))
                .forEach(s -> sessions.close(s.streamId())), properties, Instant::now);
    }

    @Bean
    ChatPurgeSweepers chatPurgeSweepers(ChatPurgeStore store, ChannelPurger purger, ChatPurgeProperties properties) {
        return new ChatPurgeSweepers(store, purger, properties, Instant::now);
    }
}
