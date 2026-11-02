package com.pokeclip.clip.render;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

/**
 * 주문줄·실패 큐에 닿는 유일한 자리. {@code SqsClient}를 <b>감싼다</b> — 빈 타입이 {@code SqsClient}이면
 * 방송 편지 수신({@code IntakeConfiguration})의 클라이언트와 타입이 같아져 그쪽 {@code ObjectProvider}가
 * 둘 중 무엇을 고를지 몰라 부팅이 죽는다(둘 다 켜는 운영 배포에서만 드러난다). 타입을 갈라 두면 그 경합이 없다.
 */
public class RenderQueueClient {

    private final SqsClient sqs;
    private final String queueUrl;
    private final String dlqUrl;

    RenderQueueClient(SqsClient sqs, String queueUrl, String dlqUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
    }

    /** @return SQS가 준 메시지 번호. 예외가 올라오면 안 실린 것이다 — 부르는 쪽이 미발행으로 남긴다 */
    public String send(String body) {
        return sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build())
                .messageId();
    }

    /** 실패 큐에서 최대 열 통. 롱폴링을 안 한다 — 정리기는 주기적으로 돌고 비어 있으면 바로 돌아간다. */
    public List<Message> receiveDeadLetters() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(dlqUrl).maxNumberOfMessages(10).waitTimeSeconds(0).build()).messages();
    }

    public void deleteDeadLetter(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(receiptHandle).build());
    }
}
