package com.pokeclip.render.report;

/** 재전송(5s·15s·45s)을 다 써도 clip이 답하지 않았다. 부른 쪽은 메시지를 줄에 그대로 둔다. 다시 받으면 판정이 이어진다. */
public class ReportUnavailable extends RuntimeException {

    public ReportUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
