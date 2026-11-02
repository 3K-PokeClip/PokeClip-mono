package com.pokeclip.clip.upload;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.List;

/**
 * 업로드 주문줄·실패 큐에 닿는 유일한 자리. 렌더의 {@code RenderQueueClient}와 모양이 같지만 <b>타입을 따로 둔다</b> :
 * 같은 타입이면 두 빈 중 무엇을 주입할지 몰라 부팅이 죽는다(렌더 클라이언트 주석과 같은 이유).
 */
public class UploadQueueClient {

    private final SqsClient sqs;
    private final String queueUrl;
    private final String dlqUrl;

    UploadQueueClient(SqsClient sqs, String queueUrl, String dlqUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.dlqUrl = dlqUrl;
    }

    /** @return SQS가 준 메시지 번호. 예외가 올라오면 안 실린 것이다 */
    public String send(String body) {
        return sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build())
                .messageId();
    }

    public List<Message> receiveDeadLetters() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(dlqUrl).maxNumberOfMessages(10).waitTimeSeconds(0).build()).messages();
    }

    public void deleteDeadLetter(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(dlqUrl).receiptHandle(receiptHandle).build());
    }
}
