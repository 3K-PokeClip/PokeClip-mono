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
     * 회원 행 락 → 채널 중복 확인 → secrets put 2 → INSERT. 한 커밋.
     *
     * <p>채널 중복은 DB 부분 유니크(uq_chzzk_links_alive_channel)가 최종 방어다 — 앱 락은
     * 인스턴스가 여럿이면 성립하지 않는다. 그런데도 앞서 조회로 한 번 거르는 이유는 로그
     * 위생이다: 유니크 위반이 나면 Hibernate(SqlExceptionHelper)가 "Key (channel_id)=(…)"를
     * 그대로 찍는다 — channelId는 로그에 안 찍는다는 규칙에 걸린다. 조회로 걸러진 경우는
     * ChzzkLinkException으로, 경합으로 조회를 통과한 극히 드문 경우만 DataIntegrityViolationException
     * 으로 나가고 호출부는 둘을 같게(409) 다룬다. 후자에서는 그 Hibernate 한 줄이 남는다.
     *
     * <p>둘 다 이 트랜잭션을 롤백한다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     */
    @Transactional
    public ChzzkChannelLink create(Long userId, ChzzkMe me, ChzzkTokens tokens) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        // 시각은 락 뒤에 잡는다 — 요청 시작 시각(치지직 HTTP 전)을 쓰면 그 사이 다른 경로가 먼저 커밋한 행보다
        // 새 행의 created_at이 앞서, "회원별 최신 행"(GET 상태·resolve NOT_LINKED)이 살아있는 행이 아니게 된다.
        Instant now = Instant.now();
        links.findByChannelIdAndRevokedAtIsNull(me.channelId())
                .filter(other -> !other.getUserId().equals(userId))
                .ifPresent(other -> {
                    throw new ChzzkLinkException(ChzzkLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
                });
        String accessRef = "chzzk-access:" + UUID.randomUUID();
        String refreshRef = "chzzk-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(ChzzkChannelLink.of(userId, me.channelId(), me.channelName(), tokens.scope(),
                accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }
}
