package com.pokeclip.clip.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 실제 HTTP로 SSE를 열고 줄을 백그라운드로 읽어 모은다.
 *
 * <p>MockMvc로는 이걸 못 잰다 — 비동기 응답을 끝까지 흘려보내지 않는다. 「3초 안에 도착」·
 * 「연결이 닫힌다」·「자리가 반납된다」는 진짜 소켓이 있어야 보인다.
 */
public final class SseReader implements AutoCloseable {

    /**
     * {@code comment}는 하트비트({@code : ping})처럼 이름 없는 줄이다.
     * {@code receivedAt}은 <b>클라이언트가 받은 시각</b>이다 — 「3초 안에 도착」을 재려면
     * 「3초 안에 await가 통과했다」가 아니라 실제 시각차를 봐야 한다(문항 4(나)).
     */
    public record Event(String name, String id, String data, String comment, Instant receivedAt) {
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    /** 오류 응답은 SSE가 아니라 JSON 한 줄이다 — 이벤트로 안 잡히므로 원문도 따로 모은다. */
    private final List<String> rawLines = Collections.synchronizedList(new ArrayList<>());
    private final HttpResponse<Stream<String>> response;
    private final Thread reader;
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile CountDownLatch wanted = new CountDownLatch(0);

    public SseReader(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(builder::header);
        try {
            this.response = client.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        this.reader = new Thread(this::readLoop, "sse-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    private void readLoop() {
        String name = null;
        String id = null;
        StringBuilder data = new StringBuilder();
        try {
            for (String line : (Iterable<String>) response.body()::iterator) {
                rawLines.add(line);
                if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.startsWith(":")) {
                    // 주석(하트비트). 그 자체가 하나의 이벤트다.
                    add(new Event(null, null, null, line.substring(1).trim(), Instant.now()));
                } else if (line.isEmpty()) {
                    // 🔴 <b>data 버퍼가 비면 이벤트를 만들지 않는다.</b> WHATWG HTML 9.2.6
                    // 「dispatch the event」 2단계가 "If the data buffer is an empty string, set the
                    // data buffer and the event type buffer to the empty string and return"이라
                    // MessageEvent를 만드는 4단계에 도달하지 못한다 — 브라우저는 그 이벤트를 버린다.
                    //
                    // 전에는 「name이 있으면」도 이벤트로 셌고, 그래서 data 없이 나가던
                    // event:ended\n\n 를 <b>도착한 것으로 세어</b> 결함을 통째로 가렸다
                    // (2026-08-23 재현: 같은 바이트를 Chrome 148과 undici는 둘 다 버렸다).
                    // 시험 파서가 실물보다 관대하면 시험이 초록인 것은 아무 뜻이 없다.
                    if (data.length() > 0) {
                        add(new Event(name, id, data.toString(), null, Instant.now()));
                    }
                    name = null;
                    // 규약은 last event ID 버퍼를 비우지 않는다("The buffer does not get reset").
                    // 여기서 비우는 것은 의도된 차이다 — 시험이 보는 것은 「그 이벤트에 붙어 온 id」라
                    // 앞 이벤트의 id가 흘러들면 순서 시험이 거짓으로 통과한다.
                    id = null;
                    data = new StringBuilder();
                }
            }
        } catch (RuntimeException ignored) {
            // 서버가 끊었다. closed()가 그것을 말한다.
        } finally {
            closed.countDown();
        }
    }

    private void add(Event event) {
        events.add(event);
        wanted.countDown();
    }

    /** {@code count}개가 모일 때까지 기다린다. 상한은 성공 기준과 같게 부른 쪽이 정한다. */
    public boolean await(int count, Duration timeout) {
        CountDownLatch latch = new CountDownLatch(Math.max(0, count - events.size()));
        wanted = latch;
        // 기다리기 직전에 이미 다 왔을 수 있다.
        for (int i = events.size(); i > 0 && latch.getCount() > 0; i--) {
            latch.countDown();
        }
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || events.size() >= count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 서버가 스트림을 닫을 때까지 기다린다. */
    public boolean awaitClosed(Duration timeout) {
        try {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean closed() {
        return closed.getCount() == 0;
    }

    /** {@code index}번째 이벤트를 받은 시각. */
    public Instant receivedAt(int index) {
        return events().get(index).receivedAt();
    }

    /** 이름이 {@code name}인 이벤트가 올 때까지 기다린다. {@code ended}처럼 종류가 중요한 갈래에 쓴다. */
    public boolean awaitName(String name, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (events().stream().anyMatch(e -> name.equals(e.name()))) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return events().stream().anyMatch(e -> name.equals(e.name()));
    }

    /** 이름이 있는 이벤트만. 주석(하트비트·연결 확인용 ok)을 뺀 것이다. */
    public List<Event> named() {
        return events().stream().filter(e -> e.name() != null).toList();
    }

    /** 이름 있는 이벤트가 {@code count}개 모일 때까지. 주석은 안 센다. */
    public boolean awaitNamed(int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (named().size() >= count) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return named().size() >= count;
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /** 응답 본문 원문. 200이 아닌 응답의 JSON을 볼 때 쓴다. */
    public String body() {
        awaitClosed(Duration.ofSeconds(3));
        synchronized (rawLines) {
            return String.join("\n", rawLines);
        }
    }

    public int statusCode() {
        return response.statusCode();
    }

    public HttpHeaders headers() {
        return response.headers();
    }

    /**
     * {@code client.close()}를 쓰지 않는다 — 그것은 <b>진행 중인 요청이 끝날 때까지 기다린다</b>.
     * SSE는 서버가 끊기 전엔 안 끝나므로 시험이 통째로 멈춘다(실측: 전수 실행이 2분 넘게 hang).
     * {@code shutdownNow()}가 연결을 즉시 닫아 서버 쪽 자리도 다음 쓰기에서 회수된다.
     */
    @Override
    public void close() {
        client.shutdownNow();
        reader.interrupt();
    }
}
