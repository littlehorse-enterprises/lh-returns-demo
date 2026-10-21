package io.littlehorse.returns;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;

public final class ReturnsHttpServer implements AutoCloseable {
  public interface WorkflowGateway {
    void review(String id, boolean approved);

    String status(String id);
  }

  private final CaseStore store;
  private final CarrierScheduler.Publisher publisher;
  private final WorkflowGateway gateway;
  private final String dashboard;
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(8);

  public ReturnsHttpServer(
      int port,
      CaseStore store,
      CarrierScheduler.Publisher publisher,
      WorkflowGateway gateway,
      String dashboard)
      throws IOException {
    this.store = store;
    this.publisher = publisher;
    this.gateway = gateway;
    this.dashboard = dashboard;
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange x) throws IOException {
    try {
      String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
      if (path.equals("/mcp")) {
        mcp(x);
        return;
      }
      if (path.equals("/api/health") && method.equals("GET")) {
        json(
            x,
            200,
            Map.of(
                "status",
                "ready",
                "agent",
                "real lh-agent-connector",
                "businessServices",
                "simulated persistent fixtures"));
        return;
      }
      if (path.equals("/api/orders") && method.equals("GET")) {
        json(x, 200, Map.of("orders", CaseStore.orders(), "dashboardUrl", dashboard));
        return;
      }
      if (path.equals("/api/cases") && method.equals("GET")) {
        List<Map<String, Object>> cases = store.list();
        cases.forEach(
            c -> {
              c.put("dashboardUrl", dashboard + "/default/wfRun/" + c.get("wfRunId"));
            });
        json(x, 200, Map.of("cases", cases));
        return;
      }
      if (path.equals("/api/requests") && method.equals("POST")) {
        create(x);
        return;
      }
      if (path.startsWith("/api/cases/")) {
        String tail = path.substring("/api/cases/".length());
        String id = tail.split("/", -1)[0];
        if (!CaseStore.validId(id)) {
          json(x, 400, Map.of("error", "Invalid request id"));
          return;
        }
        Map<String, Object> c = store.get(id);
        if (c == null) {
          json(x, 404, Map.of("error", "Case not found"));
          return;
        }
        if (tail.equals(id) && method.equals("GET")) {
          enrich(c);
          json(x, 200, c);
          return;
        }
        if (tail.equals(id + "/replay") && method.equals("POST")) {
          if (!Boolean.TRUE.equals(c.get("requestPublished")))
            throw new Conflict("Replay requires an acknowledged customer request");
          // Replay recorded facts only: an active request must not fabricate delivery.
          Set<String> topics = new LinkedHashSet<>();
          topics.add("customer-requests");
          if (Boolean.TRUE.equals(c.get("carrierPublished"))) topics.add("fulfillment-events");
          for (int i = 0; i < 2; i++) {
            publishRequest(c);
            if (topics.contains("fulfillment-events")) publisher.publish("fulfillment-events", id, "true");
          }
          Map<String, Long> offsets = new LinkedHashMap<>(publisher.acknowledgedOffsets());
          offsets.keySet().retainAll(topics);
          json(x, 200, Map.of("requestId", id, "recordsPerTopic", 2, "simulated", true,
              "topics", topics, "targetOffsets", offsets));
          return;
        }
        if (tail.equals(id + "/review") && method.equals("POST")) {
          review(x, id);
          return;
        }
      }
      if (path.startsWith("/api/")) {
        json(x, 404, Map.of("error", "Unknown API endpoint"));
        return;
      }
      if (!method.equals("GET")) {
        json(x, 405, Map.of("error", "Method not allowed"));
        return;
      }
      String asset = switch (path) {
        case "/", "/index.html" -> "index.html";
        case "/shop/store.css" -> "store.css";
        case "/shop/store.js" -> "store.js";
        case "/shop/grinder.svg" -> "grinder.svg";
        case "/shop/espresso.svg" -> "espresso.svg";
        case "/shop/jedi-saber.svg" -> "jedi-saber.svg";
        case "/shop/sith-saber.svg" -> "sith-saber.svg";
        case "/shop/manrope.ttf" -> "manrope.ttf";
        default -> null;
      };
      if (asset == null) {
        json(x, 404, Map.of("error", "Not found"));
        return;
      }
      try (InputStream in = getClass().getResourceAsStream("/shop/" + asset)) {
        if (in == null) {
          json(x, 503, Map.of("error", "Storefront unavailable"));
          return;
        }
        String type = asset.endsWith(".css") ? "text/css; charset=utf-8"
            : asset.endsWith(".js") ? "text/javascript; charset=utf-8"
            : asset.endsWith(".svg") ? "image/svg+xml"
            : asset.endsWith(".ttf") ? "font/ttf" : "text/html; charset=utf-8";
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        send(x, 200, in.readAllBytes());
      }
    } catch (CaseStore.RequestConflict e) {
      json(x, 409, Map.of("error", e.getMessage()));
    } catch (IllegalArgumentException e) {
      json(x, 400, Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()));
    } catch (Conflict e) {
      json(x, 409, Map.of("error", e.getMessage()));
    } catch (Exception e) {
      System.err.println("HTTP operation failed: " + e.getClass().getSimpleName());
      json(
          x,
          503,
          Map.of("error", "Service temporarily unavailable; persisted case remains inspectable"));
    } finally {
      x.close();
    }
  }

  private void create(HttpExchange x) throws Exception {
    JsonNode body = body(x);
    String scenario = body.path("scenario").asText("normal");
    if (!Set.of("normal", "review", "recovery", "carrier-restart").contains(scenario))
      throw new IllegalArgumentException("Unknown scenario");
    if (body.has("message") && !body.get("message").isTextual())
      throw new IllegalArgumentException("Message must be text");
    String message = body.has("message") ? body.get("message").asText() : null;
    if (message != null && message.length() > 8000)
      throw new IllegalArgumentException("Message is too long");
    if (body.has("requestId") && !body.get("requestId").isTextual())
      throw new IllegalArgumentException("requestId must be text");
    Map<String, Object> c = body.has("requestId")
        ? store.create(scenario, message, body.get("requestId").asText())
        : store.create(scenario, message);
    String id = (String) c.get("requestId");
    if (Boolean.TRUE.equals(c.get("requestPublished"))) {
      enrich(c);
      json(x, 201, c);
      return;
    }
    try {
      publishRequest(c);
      c = store.update(id, current -> {
        current.put("requestPublished", true);
        if ("INGRESS_FAILED".equals(current.get("status"))) current.put("status", "QUEUED");
        CaseStore.event(current, "KAFKA_INGRESS", "Customer-request record acknowledged by Kafka; connector starts workflow.");
        return current;
      });
    } catch (Exception e) {
      store.update(id, current -> {
        // A failed acknowledgement does not prove Kafka rejected the record. Preserve progress.
        if (!Boolean.TRUE.equals(current.get("validated"))) current.put("status", "INGRESS_FAILED");
        CaseStore.event(current, "INGRESS_UNCONFIRMED", "Kafka acknowledgement unavailable; retry the same request ID.");
        return current;
      });
      throw e;
    }
    c.put("dashboardUrl", dashboard + "/default/wfRun/" + id);
    json(x, 201, c);
  }

  private void publishRequest(Map<String, Object> c) throws Exception {
    String id = String.valueOf(c.get("requestId"));
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("request-id", id);
    record.put("customer-id", c.get("customerId"));
    record.put("order-id", c.get("orderId"));
    record.put("message", c.get("message"));
    record.put("scenario", c.get("scenario"));
    publisher.publish("customer-requests", id, CaseStore.JSON.writeValueAsString(record));
  }

  private void review(HttpExchange x, String id) throws Exception {
    JsonNode body = body(x);
    if (!body.path("approved").isBoolean())
      throw new IllegalArgumentException("approved must be boolean");
    boolean approved = body.get("approved").asBoolean();
    // Persist the first decision before attempting delivery. A lost acknowledgement permits
    // retrying that same decision, never replacing an event that may already be in flight.
    store.update(id, current -> {
      if (!"WAITING_REVIEW".equals(current.get("status"))
          || current.containsKey("reviewApproved"))
        throw new Conflict("Case is not waiting for review");
      if (current.containsKey("reviewIntent")
          && !Objects.equals(current.get("reviewIntent"), approved))
        throw new Conflict("Review decision already submitted; retry the same decision");
      if (!current.containsKey("reviewIntent")) {
        current.put("reviewIntent", approved);
        current.put("reviewPublication", "PENDING");
        CaseStore.event(current, "REVIEW_REQUESTED", "Demo review callback requested: " + approved);
      }
      return current;
    });
    try {
      // Network I/O must not hold the process-wide or cross-process case store lock.
      // Concurrent retries can deliver the same event more than once; the workflow consumes
      // the review gate once. Only that consumed event grants replacement permission.
      gateway.review(id, approved);
    } catch (Exception e) {
      store.update(id, current -> {
        if (!"CONFIRMED".equals(current.get("reviewPublication"))) {
          current.put("reviewPublication", "UNCONFIRMED");
          CaseStore.event(current, "REVIEW_UNCONFIRMED",
              "Review acknowledgement unavailable; retry the same decision.");
        }
        return current;
      });
      throw e;
    }
    Map<String, Object> c = store.update(id, current -> {
      if (!"CONFIRMED".equals(current.get("reviewPublication"))) {
        current.put("reviewPublication", "CONFIRMED");
        CaseStore.event(current, "REVIEW_SIGNALLED", "Demo review callback submitted: " + approved);
      }
      // Workers can consume the event and finish the case before the RPC returns.
      if ("WAITING_REVIEW".equals(current.get("status"))
          && !current.containsKey("reviewApproved"))
        current.put("status", "REVIEW_SIGNALLED");
      return current;
    });
    c.put("dashboardUrl", dashboard + "/default/wfRun/" + id);
    json(x, 200, c);
  }

  private void enrich(Map<String, Object> c) {
    c.put("order", CaseStore.orderForCase(c));
    c.put("dashboardUrl", dashboard + "/default/wfRun/" + c.get("wfRunId"));
    try {
      c.put("lhStatus", gateway.status(String.valueOf(c.get("wfRunId"))));
      c.put("lhAvailable", true);
    } catch (Exception e) {
      c.put("lhAvailable", false);
      c.put("lhStatus", "UNAVAILABLE");
    }
  }

  private void mcp(HttpExchange x) throws IOException {
    if (!x.getRequestMethod().equals("POST")) {
      x.getResponseHeaders().set("Allow", "POST");
      json(x, 405, Map.of("error", "Stateless MCP accepts POST only"));
      return;
    }
    JsonNode request;
    try {
      request = body(x);
    } catch (Exception e) {
      json(x, 200, rpcError(null, -32700, "Invalid JSON"));
      return;
    }
    JsonNode id = request.get("id");
    String method = request.path("method").asText();
    if (!"2.0".equals(request.path("jsonrpc").asText()) || method.isBlank()) {
      json(x, 200, rpcError(id, -32600, "Invalid JSON-RPC request"));
      return;
    }
    if (id == null) {
      x.sendResponseHeaders(202, -1);
      return;
    }
    Object result;
    switch (method) {
      case "initialize" ->
          result =
              Map.of(
                  "protocolVersion",
                  "2025-03-26",
                  "capabilities",
                  Map.of("tools", Map.of()),
                  "serverInfo",
                  Map.of("name", "returns-read-only-fixtures", "version", "1.0.0"),
                  "instructions",
                  "Tools read validated fixture case facts only. No tool can mutate orders, issue replacements or approve cases.");
      case "ping" -> result = Map.of();
      case "tools/list" ->
          result =
              Map.of(
                  "tools",
                  List.of(
                      tool(
                          "get_order",
                          "Read trusted order and ownership facts for a validated request."),
                      tool(
                          "get_policy",
                          "Read replacement eligibility and review limits for a validated request."),
                      tool(
                          "get_fulfillment_options",
                          "Read available fulfillment options without performing any action.")));
      case "tools/call" -> {
        JsonNode params = request.path("params");
        String name = params.path("name").asText();
        String requestId = params.path("arguments").path("request_id").asText();
        if (!Set.of("get_order", "get_policy", "get_fulfillment_options").contains(name)) {
          json(x, 200, rpcError(id, -32602, "Unknown read-only tool"));
          return;
        }
        Map<String, Object> c = CaseStore.validId(requestId) ? store.get(requestId) : null;
        if (c == null || !Boolean.TRUE.equals(c.get("validated")))
          result =
              Map.of(
                  "isError",
                  true,
                  "content",
                  List.of(
                      Map.of(
                          "type",
                          "text",
                          "text",
                          "Request not found or not validated; do not infer customer data.")));
        else {
          Object facts =
              switch (name) {
                case "get_order" -> CaseStore.orderForCase(c);
                case "get_policy" ->
                    Map.of(
                        "replacementEligible",
                        true,
                        "requiresReportedDamage",
                        true,
                        "autoApprovalLimit",
                        100,
                        "currency",
                        "USD",
                        "aboveLimit",
                        "REVIEW",
                        "agentCanAct",
                        false,
                        "allowedProposals",
                        List.of("REPLACE", "ESCALATE", "SEND_INFO"));
                default ->
                    Map.of(
                        "replacementInStock",
                        true,
                        "options",
                        List.of("REPLACE", "ESCALATE", "SEND_INFO"),
                        "delivery",
                        "simulated carrier confirmation",
                        "agentCanAct",
                        false);
              };
          store.update(
              requestId,
              current -> {
                @SuppressWarnings("unchecked")
                List<Object> reads =
                    (List<Object>) current.computeIfAbsent("toolReads", k -> new ArrayList<>());
                reads.add(Map.of("at", java.time.Instant.now().toString(), "tool", name));
                CaseStore.event(
                    current,
                    "TOOL_READ",
                    "Agent read fixture facts with " + name + "; no business action capability.");
                return current;
              });
          result =
              Map.of(
                  "isError",
                  false,
                  "content",
                  List.of(
                      Map.of("type", "text", "text", CaseStore.JSON.writeValueAsString(facts))));
        }
      }
      default -> {
        json(x, 200, rpcError(id, -32601, "Method not found"));
        return;
      }
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("jsonrpc", "2.0");
    envelope.put("id", id);
    envelope.put("result", result);
    json(x, 200, envelope);
  }

  private static Map<String, Object> tool(String name, String description) {
    return Map.of(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "request_id",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "Exact request ID supplied in the agent input")),
            "required",
            List.of("request_id"),
            "additionalProperties",
            false),
        "annotations",
        Map.of(
            "readOnlyHint",
            true,
            "destructiveHint",
            false,
            "idempotentHint",
            true,
            "openWorldHint",
            false));
  }

  private static Map<String, Object> rpcError(JsonNode id, int code, String message) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("jsonrpc", "2.0");
    e.put("id", id);
    e.put("error", Map.of("code", code, "message", message));
    return e;
  }

  private static JsonNode body(HttpExchange x) throws IOException {
    byte[] bytes = x.getRequestBody().readNBytes(65537);
    if (bytes.length > 65536) throw new IllegalArgumentException("Request body is too large");
    JsonNode node = CaseStore.JSON.readTree(bytes);
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("JSON object required");
    return node;
  }

  private static void json(HttpExchange x, int status, Object value) throws IOException {
    x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    x.getResponseHeaders().set("Cache-Control", "no-store");
    send(x, status, CaseStore.JSON.writeValueAsBytes(value));
  }

  private static void send(HttpExchange x, int status, byte[] bytes) throws IOException {
    x.sendResponseHeaders(status, bytes.length);
    x.getResponseBody().write(bytes);
  }

  private static final class Conflict extends RuntimeException {
    Conflict(String message) {
      super(message);
    }
  }

  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
