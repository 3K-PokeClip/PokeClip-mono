package com.pokeclip.chat.collector.broadcast.reattach;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 🔴 <b>떼기는 재부착 없이 켤 수 없다</b>(봇 리뷰 1판 codex P2). 떼기는 재부착 회차 안에서 돌므로
 * {@code CHAT_REATTACH_ENABLED=false}인 채 {@code CHAT_DETACH_ENABLED=true}만 주면 {@code ReattachConfiguration}이
 * 아예 안 만들어져 <b>스위치가 조용히 아무것도 안 한다</b> — 이 서버가 반복해서 데인 「설정은 켰는데 그 기능만
 * 조용히 죽어 있고 health는 초록」의 모양이다. {@code ReattachConfiguration}은 조건부라 그 안에서 못 막으므로
 * 항상 뜨는 이 클래스가 부팅에서 거부한다.
 */
@Configuration
@EnableConfigurationProperties(ReattachProperties.class)
public class DetachSwitchGuard {

    public DetachSwitchGuard(ReattachProperties reattach) {
        if (reattach.detachEnabled() && !reattach.enabled()) {
            throw new IllegalStateException(
                    "pokeclip.reattach.detach-enabled=true는 pokeclip.reattach.enabled=true 없이 켤 수 없다. "
                    + "떼기는 재부착 회차 안에서 도는 갈래라 재부착이 꺼져 있으면 한 번도 안 돈다 — "
                    + "CHAT_REATTACH_ENABLED=true를 같이 주거나 CHAT_DETACH_ENABLED=false로 꺼라.");
        }
    }
}
