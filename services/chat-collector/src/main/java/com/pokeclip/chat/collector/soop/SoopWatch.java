package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.Platform;

import java.time.Instant;
import java.util.Set;

/**
 * SOOP 방송 지켜보기의 입구(POK-397). 편지 처리기·재부착·탈퇴 정리가 부른다. 꺼져 있으면 {@link #NONE}이라 부르는 쪽은 켜짐을 모른다
 * ({@code RelaySink}와 같은 꼴).
 *
 * <p>네 메서드 다 <b>넣고 빼기만 한다</b>(I/O 없음, 던지지 않음). 연동 조회와 상태 확인은 지켜보기의 틱이 한다: 편지 스레드가 auth·SOOP에
 * 붙들리면 다른 방송의 편지가 밀린다.
 */
public interface SoopWatch {

    /** 이 방송을 지켜본다. 이미 지켜보면 무시하고, 같은 스트리머의 방송이면 더 늦게 시작한 쪽이 갈아낀다. */
    void watch(String streamId, long streamerId, Instant startedAt);

    /** 이 방송 지켜보기를 멈춘다(종료 편지). 없으면 무시한다. */
    void unwatch(String streamId);

    /** 그 (플랫폼, 채널)을 지켜보는 것 중 {@code startedBefore} 전에 시작한 것을 멈춘다(탈퇴 정리). SOOP 아이디가 채널이다. */
    void unwatchChannel(Platform platform, String channelId, Instant startedBefore);

    /**
     * clip 방송 중 명부에 없는 방송 중 {@code startedBefore} 전에 시작한 것을 멈춘다(재부착의 떼기 갈래, 명부가 온전할 때만 부른다).
     * 종료 편지를 놓친 방송이 지켜보기 상한을 채우지 않게 한다(계획 검증 L7).
     */
    void retainListed(Set<String> listedStreamIds, Instant startedBefore);

    SoopWatch NONE = new SoopWatch() {
        @Override
        public void watch(String streamId, long streamerId, Instant startedAt) {
        }

        @Override
        public void unwatch(String streamId) {
        }

        @Override
        public void unwatchChannel(Platform platform, String channelId, Instant startedBefore) {
        }

        @Override
        public void retainListed(Set<String> listedStreamIds, Instant startedBefore) {
        }
    };
}
