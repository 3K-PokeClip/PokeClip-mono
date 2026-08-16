package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 연동 행의 저장만 담당한다. ChzzkLinkService에서 떼어낸 이유는 StreamKeyCreator와 같다 —
 * &#64;Transactional은 프록시로 동작해서 같은 클래스의 메서드를 직접 부르면 무시된다.
 * 서비스는 외부 HTTP(교환·me)를 트랜잭션 밖에서 하고, 저장만 여기서 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class ChzzkLinkWriter {

    private final ChzzkChannelLinkRepository links;
    private final SecretStore secretStore;
    private final UserRepository users;

    /**
     * 회원 행 락 → secrets put 2 → INSERT. 한 커밋.
     *
     * <p>유니크 위반(채널 중복)은 여기서 잡지 않는다 — saveAndFlush로 이 안에서 터뜨리고
     * 호출부가 트랜잭션 밖에서 받는다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     */
    @Transactional
    public ChzzkChannelLink create(Long userId, ChzzkMe me, ChzzkTokens tokens, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        String accessRef = "chzzk-access:" + UUID.randomUUID();
        String refreshRef = "chzzk-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(ChzzkChannelLink.of(userId, me.channelId(), me.channelName(), tokens.scope(),
                accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }
}
