package com.pokeclip.auth.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * 지연과 실패를 마음대로 심을 수 있는 가짜 Secrets Manager(POK-272). LocalStack은 「0.8초 뒤 없음」·「응답 없음」·
 * 「스로틀」을 결정적으로 못 만든다. 공유 시한 시험이 이것을 쓴다.
 *
 * <p>JSON 1.1 프로토콜의 네 동작(Create·Put·Get·Delete)만 흉내 낸다. 값은 메모리에 둔다. 동작마다 <b>대본</b>을
 * 줄 세울 수 있고, 대본이 비면 메모리 값대로 정상 응답한다.
 *
 * <p>루프백에 묶는다({@link FakeHttpServer}와 같은 이유: 와일드카드면 남이 같은 번호를 가로챈다). 응답 없음
 * 갈래가 스레드를 오래 쥐므로 요청마다 가상 스레드를 쓴다.
 */
public final class FakeSecretsManagerServer implements AutoCloseable {

    /** 대본 한 줄: {@code delay} 뒤에 {@code errorType}(없으면 정상)으로 답한다. {@code silent}면 답하지 않는다. */
    public record Step(Duration delay, String errorType, int status, boolean silent) {

        public static Step notFoundAfter(Duration delay) {
            return new Step(delay, "ResourceNotFoundException", 400, false);
        }

        public static Step existsAfter(Duration delay) {
            return new Step(delay, "ResourceExistsException", 400, false);
        }

        public static Step error(String type, int status) {
            return new Step(Duration.ZERO, type, status, false);
        }

        public static Step hang() {
            return new Step(Duration.ZERO, null, 0, true);
        }

        public static Step okAfter(Duration delay) {
            return new Step(delay, null, 200, false);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HANG = Duration.ofSeconds(20);

    private final HttpServer server;
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final Map<String, Deque<Step>> scripts = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private FakeSecretsManagerServer(HttpServer server) {
        this.server = server;
    }

    public static FakeSecretsManagerServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeSecretsManagerServer fake = new FakeSecretsManagerServer(server);
            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 동작 이름은 {@code GetSecretValue}·{@code CreateSecret}·{@code PutSecretValue}·{@code DeleteSecret}. */
    public synchronized void script(String operation, Step... steps) {
        scripts.computeIfAbsent(operation, k -> new ArrayDeque<>()).addAll(List.of(steps));
    }

    public void seed(String name, String value) {
        secrets.put(name, value);
    }

    public boolean has(String name) {
        return secrets.containsKey(name);
    }

    /** 받은 요청의 동작 이름을 순서대로. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public long count(String operation) {
        return requests.stream().filter(operation::equals).count();
    }

    /** 남은 대본만 버린다. 저장된 값은 둔다. 장애 뒤 「회복」을 재려면 값이 살아 있어야 한다. */
    public synchronized void clearScripts() {
        scripts.clear();
    }

    public synchronized void reset() {
        secrets.clear();
        scripts.clear();
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
        String operation = target == null ? "" : target.substring(target.indexOf('.') + 1);
        JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        requests.add(operation);

        Step step = nextStep(operation);
        if (step != null) {
            if (step.silent()) {
                sleep(HANG);
                exchange.close();
                return;
            }
            sleep(step.delay());
            if (step.errorType() != null) {
                error(exchange, step.status(), step.errorType());
                return;
            }
        }
        answer(exchange, operation, body);
    }

    private synchronized Step nextStep(String operation) {
        Deque<Step> steps = scripts.get(operation);
        return steps == null ? null : steps.pollFirst();
    }

    private void answer(HttpExchange exchange, String operation, JsonNode body) throws IOException {
        ObjectNode out = MAPPER.createObjectNode();
        switch (operation) {
            case "CreateSecret" -> {
                String name = body.path("Name").asString();
                if (secrets.putIfAbsent(name, body.path("SecretString").asString()) != null) {
                    error(exchange, 400, "ResourceExistsException");
                    return;
                }
                out.put("Name", name).put("ARN", arn(name));
            }
            case "PutSecretValue" -> {
                String id = body.path("SecretId").asString();
                if (!secrets.containsKey(id)) {
                    error(exchange, 400, "ResourceNotFoundException");
                    return;
                }
                secrets.put(id, body.path("SecretString").asString());
                out.put("Name", id).put("ARN", arn(id));
            }
            case "GetSecretValue" -> {
                String id = body.path("SecretId").asString();
                String value = secrets.get(id);
                if (value == null) {
                    error(exchange, 400, "ResourceNotFoundException");
                    return;
                }
                out.put("Name", id).put("ARN", arn(id)).put("SecretString", value);
            }
            case "DeleteSecret" -> {
                String id = body.path("SecretId").asString();
                if (secrets.remove(id) == null) {
                    error(exchange, 400, "ResourceNotFoundException");
                    return;
                }
                out.put("Name", id).put("ARN", arn(id));
            }
            default -> {
                error(exchange, 400, "InvalidRequestException");
                return;
            }
        }
        write(exchange, 200, MAPPER.writeValueAsBytes(out));
    }

    private static void error(HttpExchange exchange, int status, String type) throws IOException {
        exchange.getResponseHeaders().add("x-amzn-ErrorType", type);
        write(exchange, status, ("{\"__type\":\"" + type + "\",\"message\":\"fake\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static void write(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String arn(String name) {
        return "arn:aws:secretsmanager:ap-northeast-2:000000000000:secret:" + name + "-AbCdEf";
    }

    private static void sleep(Duration d) {
        if (d.isZero()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
