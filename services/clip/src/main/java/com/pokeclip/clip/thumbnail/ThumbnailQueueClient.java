package com.pokeclip.clip.thumbnail;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** 사진 주문줄에 닿는 유일한 자리. 렌더·업로드 클라이언트와 타입을 따로 두는 이유는 그쪽 주석과 같다(빈 주입이 갈리지 않게). */
public class ThumbnailQueueClient {

    private final SqsClient sqs;
    private final String queueUrl;

    ThumbnailQueueClient(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    public void send(String body) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    }
}
