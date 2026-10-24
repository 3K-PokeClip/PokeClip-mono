package com.pokeclip.chat.collector.platforms;

import com.pokeclip.chat.collector.Platform;

import java.time.Instant;
import java.util.Set;

/**
 * clip 플랫폼 묶음에 「이 방송이 이 플랫폼에서 켜져 있다」를 알린다(POK-397). 운영 구현은 {@link ClipPlatformClient}다.
 * 두 메서드 다 던지지 않는 것이 계약이다(부르는 쪽은 방송 정보 틱이고, 알림은 정보 저장보다 덜 중요하다).
 */
public interface PlatformNotifier {

    /** 알린 적 없거나 지난번에 실패했으면 알린다. 이미 받았다고 확인한 (방송, 플랫폼)은 다시 안 보낸다. */
    void seen(String streamId, Platform platform, Instant seenAt);

    /** 그 플랫폼의 메모를 지금 걷는 방송으로 줄인다. 끝난 방송의 메모가 끝없이 쌓이지 않게 틱마다 부른다. */
    void retain(Platform platform, Set<String> streamIds);
}
