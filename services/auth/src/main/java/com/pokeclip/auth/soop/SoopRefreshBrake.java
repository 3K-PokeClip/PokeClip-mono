package com.pokeclip.auth.soop;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 갱신 브레이크 상태 하나. 스케줄러가 세우고 갱신기({@link SoopTokenRefresher})가 같이 본다(감사 2회차 중대).
 *
 * <p>스케줄러에만 두면 브레이크가 선 뒤 미리 갱신하는 주체가 사라져, 수집기의 resolve 즉석 갱신이 같은 앱 설정 오류로
 * 4xx를 받고 방송하는 스트리머마다 연동을 하나씩 파기한다. 그래서 상태를 밖으로 뺐다. 세는 것(연속 거절)은 여전히
 * 스케줄러 틱 안에서만 한다(resolve 거절은 안 센다). 프로세스 수명 동안 서 있고 재시작(재배포)이 푼다.
 */
@Component
public class SoopRefreshBrake {

    private final AtomicBoolean engaged = new AtomicBoolean();

    public boolean engaged() {
        return engaged.get();
    }

    void engage() {
        engaged.set(true);
    }

    /** 시험 전용. 컨텍스트가 공유되므로 세운 시험이 반드시 되돌린다. 운영은 재시작만이 푼다. */
    void release() {
        engaged.set(false);
    }
}
