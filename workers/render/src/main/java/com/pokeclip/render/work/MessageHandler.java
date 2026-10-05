package com.pokeclip.render.work;

/** 줄에서 꺼낸 메시지 하나를 처리하고 메시지를 어떻게 할지 답한다. 렌더 주문과 사진 주문(POK-277)이 같은 줄 소비자를 쓴다. */
public interface MessageHandler {

    Disposition process(String body);

    /**
     * @param receiveCount SQS가 이 메시지를 몇 번째 내주는가(1부터, 모르면 1). 렌더는 쓰지 않는다(clip의 실행 토큰이 다시 받기를 가른다).
     *                     사진 주문은 다시 받은 라이브 주문을 버리는 데 쓴다
     */
    default Disposition process(String body, int receiveCount) {
        return process(body);
    }
}
