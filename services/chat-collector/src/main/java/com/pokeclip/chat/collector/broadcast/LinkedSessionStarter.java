package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkResolution;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;

import java.time.Instant;
import java.util.function.BiConsumer;

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
    /**
     * 포기한 방송을 메모에 남기는 손잡이({@code StoppedStreamRecorder::record}).
     *
     * <p><b>등록부의 {@code onPermanentStop}을 타지 않고 직접 받는다.</b> 그 알림은 세션이
     * 선 뒤에만 울리는데, 여기서 남겨야 하는 것은 <b>세션이 서 보지도 못한</b> 방송이다.
     * 등록부에 그 갈래를 태우려면 열지도 않은 방송을 등록부가 알아야 해서 층이 어긋난다.
     */
    private final BiConsumer<String, StopReason> recorder;

    public LinkedSessionStarter(ChzzkLinkClient link, SessionRegistry registry,
                                BiConsumer<String, StopReason> recorder) {
        this.link = link;
        this.registry = registry;
        this.recorder = recorder;
    }

    /**
     * <p><b>편지가 아니라 값 셋을 받는다</b> — 재부착도 이 문을 쓴다(POK-219).
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
            // <b>지우기 전에 남긴다.</b> 편지가 이 방송의 유일한 트리거라, 메모 없이 지우면
            // 창구가 그 방송에 <b>영원히</b> unknown을 답한다 — 배너를 끄는 값이라
            // 「가장 나쁜 상태가 가장 안전한 답으로 보이는」 틈이고, 여기서는 그 틈이
            // 찰나가 아니라 영구다(되돌아올 편지가 없다). PRD 결정 95가 포기 알림을
            // 「지우기 전」에 둔 이유가 그것이다.
            //
            // <b>사유 넷을 안 가른다</b> — LINK_UNAVAILABLE의 주석에 근거를 적었다.
            //
            // 던지지 않는다: recorder(=StoppedStreamRecorder.record)가 Throwable까지 삼키고
            // 경고만 남긴다. DB가 죽어 메모를 못 남기면 그 방송은 여전히 unknown이다 —
            // 그것은 이 갈래가 아니라 그 클래스의 알려진 한계다.
            recorder.accept(streamId, StopReason.LINK_UNAVAILABLE);
            return ProcessResult.PROCESSED;
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
