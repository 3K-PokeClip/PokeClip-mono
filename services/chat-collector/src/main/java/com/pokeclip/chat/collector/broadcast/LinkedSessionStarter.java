package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkResolution;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;

import java.time.Instant;

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
     * <p><b>편지가 아니라 값 셋을 받는다</b> — 재부착도 이 문을 쓴다(POK-219).
     *
     * <p>🔴 <b>{@code PROCESSED}는 「세션이 서 있다」만 뜻한다.</b> auth가 영구히 거절해
     * <b>세션이 서 보지도 못한</b> 갈래는 {@link ProcessResult#LINK_REFUSED}로 따로 나간다.
     * 러너에게는 둘 다 「지운다」로 같지만, 재부착에게는 정반대다 — 한 값으로 뭉쳐 두었더니
     * 재부착이 안 붙은 방송에 공백을 찍고 있었다(감사 라운드 3 H1, 주입으로 재현).
     *
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
    public ProcessResult start(String streamId, StreamerId streamer, Instant startedAt) {
        // <b>이미 그 방송을 걷고 있으면 auth에 묻지 않는다.</b> SQS는 at-least-once라
        // 같은 시작 편지가 두 번 온다. 그때 열쇠부터 물으면 <b>auth가 아픈 동안 그 중복이
        // RETRY_LATER가 되어 그 방송의 FIFO 그룹 앞을 막고</b>, 뒤따르는 종료 편지가
        // 배달되지 못해 이미 끝난 방송의 세션이 auth가 나을 때까지 열려 있는다
        // (codex P2, 재현함 — auth 503에서 registry.open이 호출조차 안 됐다).
        //
        // 아래 registry.open도 같은 판정을 하지만 그것은 열쇠를 받아 온 <b>뒤</b>다.
        // 여기서 먼저 거르는 것은 「열쇠를 물을 필요조차 없다」를 가르는 것이라 층이 다르다.
        if (streamId.equals(registry.currentStreamIdOf(streamer.value()))) {
            return ProcessResult.PROCESSED;
        }
        LinkResolution resolution = link.resolve(streamer.value());
        if (!resolution.usable()) {
            // 사유를 여기서 다시 해석하지 않는다 — 어떤 사유가 영구인지는 auth 계약을 아는
            // ChzzkLinkClient가 이미 판정했다. 두 곳에서 보면 한쪽만 낡는다.
            if (resolution.retryable()) {
                return ProcessResult.RETRY_LATER;
            }
            // 🔴 <b>여기서 포기 메모를 남기지 않는다 — 부르는 쪽이 남긴다</b>(POK-219 감사
            // 라운드 3, PRD 결정). 그 메모의 목적은 <b>「편지를 지우기 전에 남긴다」</b>였다:
            // 편지가 그 방송의 유일한 트리거라 메모 없이 지우면 창구가 영원히 unknown을
            // 답한다. 그런데 이 문을 <b>재부착도 쓴다</b>(POK-219). 재부착에는 지울 편지가
            // 없고, 여기서 메모를 남기면 <b>재부착이 만든 메모 때문에 다음 회차부터
            // 재부착 자신이 그 방송을 24시간 건너뛴다</b> — 이 카드의 목적을 무력화한다.
            //
            // <b>증폭 갈래</b>: ChzzkLinkClient는 auth의 계약 위반(chat.link.incomplete)도
            // 영구 거절로 친다. auth가 한 번 잘못 답하면 <b>한 회차로 살아 있는 방송
            // 전부에</b> 메모가 박힌다. 전에는 알림 경로만 있어 새 방송에만 번졌다.
            //
            // 그래서 「무엇을 했나」만 돌려주고, 「편지를 지우기 전에 남길지」는 편지를
            // 아는 층(BroadcastEventProcessor)이 정한다. <b>사유 넷은 여기서도 저기서도
            // 안 가른다</b> — LINK_UNAVAILABLE의 주석에 근거를 적었다.
            return ProcessResult.LINK_REFUSED;
        }
        SessionKey key = new SessionKey(streamId, streamer.value(),
                resolution.channelId(), startedAt);
        if (registry.open(key, resolution.accessToken())) {
            return ProcessResult.PROCESSED;
        }
        if (streamId.equals(registry.currentStreamIdOf(streamer.value()))) {
            return ProcessResult.PROCESSED;
        }
        // <b>낡은 시작은 지운다.</b> 지금 걷는 방송이 이 편지보다 나중에 시작했으면
        // 다시 물어도 답이 안 바뀐다 — 「나중에 다시」로 두면 그 방송의 FIFO 그룹 앞을
        // 영원히 막는다. 자리를 못 얻은 다른 이유(그 사이 세션이 죽음)는 재시도가 맞다.
        if (registry.isStaleStart(streamer.value(), startedAt)) {
            return ProcessResult.IGNORED_STALE;
        }
        return ProcessResult.RETRY_LATER;
    }

    @Override
    public boolean stop(String streamId) {
        return registry.close(streamId);
    }
}
