package com.pokeclip.render.work;

/** 줄에서 꺼낸 메시지 하나를 처리하고 메시지를 어떻게 할지 답한다. 렌더 주문과 사진 주문(POK-277)이 같은 줄 소비자를 쓴다. */
public interface MessageHandler {

    Disposition process(String body);
}
