package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkResolution;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;

/**
 * 편지 하나를 <b>열쇠 조회 → 세션 열기</b>로 잇는다. 이 카드에서 부품이 하나로 물리는 자리다.
 *
 * <p><b>여기서 재시도하지 않는다.</b> 재시도는 편지를 큐에 남기는 것이고, 그 판단을
 * {@link ProcessResult}로 돌려주면 러너가 한다. 여기서 자체 재시도를 돌리면 그동안
 * <b>다른 방송의 시작 편지도 같이 멈춘다</b> — 편지를 꺼내는 스레드가 하나다.
 */
public class LinkedSessionStarter implements BroadcastSessions {

    private final ChzzkLinkClient link;
    private final SessionRegistry registry;

    public LinkedSessionStarter(ChzzkLinkClient link, SessionRegistry registry) {
        this.link = link;
        this.registry = registry;
    }

    /**
     * <p><b>열쇠부터 받는다.</b> 등록부에 먼저 자리를 잡고 열쇠를 받으러 가면, 못 받았을 때
     * 그 자리를 되돌리는 동안 그 스트리머의 다음 편지가 「이미 열림」으로 걸린다.
     *
     * <p><b>{@code open()}의 {@code false}는 두 가지 뜻이라 그대로 판정에 쓸 수 없다</b> —
     * 「이미 그 방송이 열려 있다」(재전송이므로 지운다)와 「수립에 실패했다」(다시 오면 될 수
     * 있으므로 남긴다)가 같은 값이다. 그래서 false를 받으면 <b>그 스트리머가 지금 하고 있는
     * 방송이 이 편지의 방송인지</b>를 되묻는다. 그것이 곧 「이 편지는 더 볼 일 없다」의 정의다.
     *
     * <p><b>되묻는 사이의 창은 인정한다</b>: 세션이 그 찰나에 스스로 멈추면(REVOKED) 답이
     * null이라 {@code RETRY_LATER}가 나가고 편지가 다시 온다. 그때 auth는 대개 연동이 끊겼다고
     * 답하므로({@code UNLINKED}) 다음 회차에서 지워진다 — <b>재현해 보지 않았다.</b>
     * 반대 방향(이미 열려 있는데 {@code RETRY_LATER})이 훨씬 비싸다: 같은 회차가 영원히 반복되고
     * FIFO라 그 방송의 뒤 편지가 전부 막힌다.
     */
    @Override
    public ProcessResult start(LifecycleEnvelope envelope, StreamerId streamer) {
        LinkResolution resolution = link.resolve(streamer.value());
        if (!resolution.usable()) {
            // 사유를 여기서 다시 해석하지 않는다 — 어떤 사유가 영구인지는 auth 계약을 아는
            // ChzzkLinkClient가 이미 판정했다. 두 곳에서 보면 한쪽만 낡는다.
            return resolution.retryable() ? ProcessResult.RETRY_LATER : ProcessResult.PROCESSED;
        }
        SessionKey key = new SessionKey(envelope.streamId(), streamer.value(), resolution.channelId());
        if (registry.open(key, resolution.accessToken())) {
            return ProcessResult.PROCESSED;
        }
        return envelope.streamId().equals(registry.currentStreamIdOf(streamer.value()))
                ? ProcessResult.PROCESSED
                : ProcessResult.RETRY_LATER;
    }

    @Override
    public boolean stop(String streamId) {
        return registry.close(streamId);
    }
}
