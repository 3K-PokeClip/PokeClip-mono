package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SOOP 채팅 받기 배선(POK-397 PR4). 켜지면 세션들 + 화면 없는 브라우저, 꺼지면 {@link SoopChat#NONE}. 꺼져도 빈을 두는 이유는
 * 지켜보기가 켜짐을 모르고 타입 하나만 받게 하려는 것이다({@code SoopWatchConfiguration}과 같다).
 *
 * <p>🔴 채팅은 SOOP 방송 지켜보기가 방송 중으로 본 방송에만 붙는다. 그래서 채팅만 켜고 지켜보기를 끄면 아무 데도 안 붙으면서 브라우저 의존만
 * 생긴다: 부팅을 거부한다.
 */
@Configuration
@EnableConfigurationProperties(SoopChatProperties.class)
public class SoopChatConfiguration {

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.soop.chat", name = "enabled", havingValue = "true")
    public static class Enabled {

        @Bean
        public SoopChatRuntime soopChatRuntime(SoopChatProperties properties, Environment environment, ChatBuffer buffer,
                                               ChatArchive archive, RelaySink relay) {
            properties.validate();
            if (!environment.getProperty("pokeclip.soop.watch-enabled", Boolean.class, false)) {
                throw new IllegalStateException(
                        "pokeclip.soop.chat.enabled=true인데 pokeclip.soop.watch-enabled가 꺼져 있다. 채팅은 지켜보기가 본 방송에만 붙는다.");
            }
            SoopChatSessions sessions = new SoopChatSessions(properties, buffer, archive, relay, System::currentTimeMillis);
            SoopChatBrowser browser = new SoopChatBrowser(properties, sessions);
            sessions.attach(new SoopChatSessions.Tabs() {
                @Override
                public void open(String streamId, String token) {
                    browser.open(streamId, token);
                }

                @Override
                public void close(String streamId) {
                    browser.close(streamId);
                }
            });
            return new SoopChatRuntime(sessions, browser);
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "pokeclip.soop.chat", name = "enabled", havingValue = "false", matchIfMissing = true)
    public static class Disabled {

        @Bean
        public SoopChat soopChat() {
            return SoopChat.NONE;
        }
    }

    /** 켜졌을 때의 한 벌. 이것이 곧 {@link SoopChat}이고, 끌 때 다시 붙기 예약 · 브라우저를 닫는다. */
    public static class SoopChatRuntime implements SoopChat, DisposableBean {

        private final SoopChatSessions sessions;
        private final SoopChatBrowser browser;

        SoopChatRuntime(SoopChatSessions sessions, SoopChatBrowser browser) {
            this.sessions = sessions;
            this.browser = browser;
        }

        @Override
        public void ensure(String streamId, String channelId, String token) {
            sessions.ensure(streamId, channelId, token);
        }

        @Override
        public void close(String streamId) {
            sessions.close(streamId);
        }

        /** 끄는 순서: 세션 떼기 → 탭 닫기 → 브라우저 닫기({@link SoopChatBrowser#CLOSE_BUDGET} 안에). */
        @Override
        public void destroy() throws InterruptedException {
            sessions.shutdown();
            browser.close();
        }
    }
}
