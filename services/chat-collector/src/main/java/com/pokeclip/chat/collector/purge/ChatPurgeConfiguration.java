package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.archive.ArchiveProperties;
import com.pokeclip.chat.collector.archive.S3Clients;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.soop.SoopWatch;
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
                                ChatPurgeProperties properties, SoopWatch soop) {
        // 그 (플랫폼, 채널)을 걷는 세션을 닫는다(탈퇴 시각 전에 시작한 것만). 늦게 온 시작 편지는 auth가 연동을 닫아 다시 못 연다.
        return new ChannelPurger(store, archive, stopCollecting(sessions, soop), properties, Instant::now);
    }

    /**
     * 세션 등록부와 SOOP 지켜보기를 같이 닫는다(POK-397 계획 검증 M3). 지켜보기는 세션 자리 밖이라 등록부만 닫으면 탈퇴 뒤에도 SOOP 토큰이
     * 만료(약 8시간)까지 살아 정보 줄을 쓴다(SOOP에는 폐기 API가 없다). 그 줄은 정리 창 뒤라 60일 보관으로만 지워진다.
     */
    static ChannelPurger.SessionCloser stopCollecting(SessionRegistry sessions, SoopWatch soop) {
        return (platform, channelId, startedBefore) -> {
            sessions.closeChannel(platform, channelId, startedBefore);
            soop.unwatchChannel(platform, channelId, startedBefore);
        };
    }

    @Bean
    ChatPurgeSweepers chatPurgeSweepers(ChatPurgeStore store, ChannelPurger purger, ChatPurgeProperties properties) {
        return new ChatPurgeSweepers(store, purger, properties, Instant::now);
    }
}
