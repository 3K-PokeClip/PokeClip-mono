package com.pokeclip.clip.broadcast;

import java.util.List;

/**
 * 방송별로 지금 라이브 대시보드를 열어 둔 사람의 번호(POK-398). 방송 목록의 「접속 중인 편집자 수」가 읽는다.
 *
 * <p>구현은 연결 보관소({@code CardStreamRegistry}) 하나다. 그래도 규약을 이쪽에 두는 것은 의존 방향 때문이다:
 * 연결 보관소(jumpcard)가 이미 broadcast를 쓰므로({@code EndedListener}와 같은 이유) broadcast가 jumpcard를 쓰면 고리가 생긴다.
 */
public interface ViewerPresence {

    /**
     * 그 방송에 열린 연결마다 회원 번호(JWT {@code sub} 원문) 하나. 같은 사람의 여러 탭은 여러 번 나온다.
     * 중복 제거와 숫자 비교는 부르는 쪽이 한다. 연결이 없으면 빈 목록이다.
     */
    List<String> viewerIdsOf(String streamId);
}
