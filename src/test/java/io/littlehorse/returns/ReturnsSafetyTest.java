package io.littlehorse.returns;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReturnsSafetyTest {
  @TempDir Path state;
  CaseStore store;
  ReturnsTasks tasks;

  @BeforeEach
  void setup() {
    store = new CaseStore(state);
    tasks = new ReturnsTasks(store, 0);
  }

  String validated(String scenario) {
    Map<String, Object> c = store.create(scenario, null);
    String id = (String) c.get("requestId");
    assertTrue(
        tasks.validateCase(
            id,
            (String) c.get("customerId"),
            (String) c.get("orderId"),
            (String) c.get("message"),
            scenario,
            id));
    return id;
  }

  @Test
  void inventoryThemeKeepsHistoricalOrderFactsAndNewSnapshots() {
    Map<String, Object> legacy = new LinkedHashMap<>(Map.of("orderId", "order-49", "scenario", "normal"));
    assertEquals("Coffee grinder", CaseStore.orderForCase(legacy).get("item"));
    assertEquals("order-49", CaseStore.orderForCase(legacy).get("orderId"));
    Map<String, Object> current = store.create("normal", null);
    assertEquals("order-jedi-49", current.get("orderId"));
    assertEquals("Jedi Training Lightsaber", CaseStore.orderForCase(current).get("item"));
    assertEquals("Sith Lord Lightsaber", CaseStore.order("review").get("item"));
    String id = String.valueOf(current.get("requestId"));
    assertTrue(tasks.validateCase(id, String.valueOf(current.get("customerId")),
        String.valueOf(current.get("orderId")), String.valueOf(current.get("message")), "normal", id));
  }

  @Test
  void identityMustMatchOrderAndWorkflowRun() {
    Map<String, Object> c = store.create("normal", null);
    String id = (String) c.get("requestId");
    assertFalse(
        tasks.validateCase(id, "attacker", "order-49", "Replace it", "normal", "different-run"));
    assertThrows(
        IllegalStateException.class,
        () -> tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"yes\"}"));
    assertNull(store.get(id).get("replacement"));
  }

  @Test
  void invalidModelOutputNeverCreatesEffect() {
    String id = validated("normal");
    for (String output :
        List.of(
            "DISPATCH",
            "```json\n{}\n```",
            "{\"action\":\"REFUND\",\"reason\":\"yes\"}",
            "{\"action\":\"REPLACE\",\"reason\":4}",
            "{\"action\":\"REPLACE\",\"reason\":\"\"}")) {
      assertEquals("ESCALATE", tasks.applyPolicy(id, output));
      assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
    }
    assertNull(store.get(id).get("replacement"));
  }

  @Test
  void trailingModelContentNeverAuthorizesReplacement() {
    String id = validated("normal");
    for (String suffix : List.of(" trailing explanation", " {}", " true")) {
      assertEquals(
          "ESCALATE",
          tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}" + suffix));
      assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
    }
    assertNull(store.get(id).get("replacement"));
  }

  @Test
  void reviewCallbackNeedsPendingGateAndConsumedApproval() throws Exception {
    List<Boolean> signals = new ArrayList<>();
    ReturnsHttpServer.WorkflowGateway gateway =
        new ReturnsHttpServer.WorkflowGateway() {
          public void review(String id, boolean approved) { signals.add(approved); }
          public String status(String id) { return "RUNNING"; }
        };
    try (ReturnsHttpServer server =
        new ReturnsHttpServer(0, store, (topic, key, value) -> {}, gateway, "http://localhost:8080")) {
      server.start();
      String base = "http://localhost:" + server.port();
      for (boolean approved : List.of(true, false)) {
        String id = validated("review");
        String endpoint = base + "/api/cases/" + id + "/review";
        assertEquals(409, post(endpoint, "{\"approved\":true}").statusCode());
        assertEquals("REVIEW", tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}"));
        assertEquals(400, post(endpoint, "{\"approved\":\"true\"}").statusCode());
        assertEquals("WAITING_REVIEW", store.get(id).get("status"));
        HttpResponse<String> response = post(endpoint, "{\"approved\":" + approved + "}");
        assertEquals(200, response.statusCode());
        var body = CaseStore.JSON.readTree(response.body());
        assertEquals("REVIEW_SIGNALLED", body.path("status").asText());
        assertEquals("http://localhost:8080/default/wfRun/" + id, body.path("dashboardUrl").asText());
        assertNull(store.get(id).get("reviewApproved"));
        assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
        assertEquals(409, post(endpoint, "{\"approved\":true}").statusCode());
        tasks.review(id, approved);
        if (approved) {
          assertEquals("replacement-" + id, tasks.replaceOnce(id));
        } else {
          tasks.reject(id);
          assertEquals("REJECTED", store.get(id).get("status"));
          assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
          assertNull(store.get(id).get("replacement"));
        }
      }
      assertEquals(List.of(true, false), signals);
    }
  }

  @Test
  void failedReviewCallbackLeavesPendingGateRetryable() throws Exception {
    String id = validated("review");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    AtomicInteger attempts = new AtomicInteger();
    ReturnsHttpServer.WorkflowGateway gateway =
        new ReturnsHttpServer.WorkflowGateway() {
          public void review(String requestId, boolean approved) {
            assertEquals(id, requestId);
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("LH unavailable");
          }
          public String status(String requestId) { return "RUNNING"; }
        };
    try (ReturnsHttpServer server =
        new ReturnsHttpServer(0, store, (topic, key, value) -> {}, gateway, "http://localhost:8080")) {
      server.start();
      String endpoint = "http://localhost:" + server.port() + "/api/cases/" + id + "/review";
      assertEquals(503, post(endpoint, "{\"approved\":true}").statusCode());
      assertEquals("WAITING_REVIEW", store.get(id).get("status"));
      assertNull(store.get(id).get("reviewApproved"));
      assertNull(store.get(id).get("replacement"));
      assertEquals(200, post(endpoint, "{\"approved\":true}").statusCode());
      assertEquals(2, attempts.get());
      assertEquals("REVIEW_SIGNALLED", store.get(id).get("status"));
    }
  }

  @Test
  void trustedPriceAndConsumedReviewGateOverrideAgentAmounts() {
    String id = validated("review");
    assertEquals(
        "REVIEW",
        tasks.applyPolicy(
            id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\",\"price\":1,\"approved\":true}"));
    assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
    tasks.review(id, false);
    assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
    tasks.review(id, true);
    assertEquals("replacement-" + id, tasks.replaceOnce(id));
  }

  @Test
  void postEffectFailureAndRestartDoNotDuplicateReplacement() {
    String id = validated("recovery");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
    assertTrue((Boolean) store.get(id).get("recoveryFailureInjected"));
    ReturnsTasks restarted = new ReturnsTasks(new CaseStore(state), 0);
    assertEquals("replacement-" + id, restarted.replaceOnce(id));
    restarted.replaceOnce(id);
    assertEquals(
        1, ((Map<?, ?>) new CaseStore(state).get(id).get("replacement")).get("effectCount"));
  }

  @Test
  void carrierOutboxSurvivesRestartAndRetriesFailedPublish() {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    try (CarrierScheduler failed =
        new CarrierScheduler(
            new CaseStore(state),
            (topic, key, value) -> {
              throw new RuntimeException("Kafka down");
            })) {
      assertThrows(IllegalStateException.class, () -> failed.tick(Long.MAX_VALUE));
    }
    assertFalse((Boolean) store.get(id).get("carrierPublished"));
    AtomicInteger calls = new AtomicInteger();
    try (CarrierScheduler restarted =
        new CarrierScheduler(
            new CaseStore(state),
            (topic, key, value) -> {
              assertEquals("fulfillment-events", topic);
              assertEquals(id, key);
              assertEquals("true", value);
              calls.incrementAndGet();
            })) {
      restarted.tick(Long.MAX_VALUE);
      restarted.tick(Long.MAX_VALUE);
    }
    assertEquals(1, calls.get());
    assertTrue((Boolean) store.get(id).get("carrierPublished"));
  }

  @Test
  void httpUsesKafkaIngressAndMcpIsReadOnlyAndScoped() throws Exception {
    List<Map<String, String>> published = new ArrayList<>();
    ReturnsHttpServer.WorkflowGateway gateway =
        new ReturnsHttpServer.WorkflowGateway() {
          public void review(String id, boolean approved) {}

          public String status(String id) {
            throw new IllegalStateException("LH down");
          }
        };
    try (ReturnsHttpServer server =
        new ReturnsHttpServer(
            0,
            store,
            (topic, key, value) ->
                published.add(Map.of("topic", topic, "key", key, "value", value)),
            gateway,
            "http://localhost:8080")) {
      server.start();
      String url = "http://localhost:" + server.port();
      for (String removedPage : List.of("/app.js", "/styles.css", "/shop/../AGENTS.md")) {
        var removed = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url + removedPage)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(404, removed.statusCode(), removedPage);
      }
      var storefront = HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(url + "/")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, storefront.statusCode());
      assertTrue(storefront.body().contains("Request replacement"));
      assertFalse(storefront.body().contains("case-list"));
      for (String asset : List.of("store.js", "store.css", "grinder.svg", "espresso.svg", "jedi-saber.svg", "sith-saber.svg", "manrope.ttf")) {
        var responseAsset = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url + "/shop/" + asset)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, responseAsset.statusCode(), asset);
        assertTrue(responseAsset.body().length > 0, asset);
        assertEquals("nosniff", responseAsset.headers().firstValue("X-Content-Type-Options").orElse(""));
      }
      HttpResponse<String> response = post(url + "/api/requests", "{\"scenario\":\"normal\"}");
      assertEquals(201, response.statusCode());
      String id = CaseStore.JSON.readTree(response.body()).path("requestId").asText();
      assertEquals("customer-requests", published.get(0).get("topic"));
      assertEquals(id, published.get(0).get("key"));
      assertEquals(
          id, CaseStore.JSON.readTree(published.get(0).get("value")).path("request-id").asText());
      assertEquals(
          409, post(url + "/api/cases/" + id + "/review", "{\"approved\":true}").statusCode());
      String rpc =
          "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_order\",\"arguments\":{\"request_id\":\""
              + id
              + "\"}}}";
      assertTrue(
          CaseStore.JSON
              .readTree(post(url + "/mcp", rpc).body())
              .path("result")
              .path("isError")
              .asBoolean());
      Map<String, Object> c = store.get(id);
      tasks.validateCase(
          id,
          (String) c.get("customerId"),
          (String) c.get("orderId"),
          (String) c.get("message"),
          "normal",
          id);
      var before = store.get(id);
      var read = CaseStore.JSON.readTree(post(url + "/mcp", rpc).body());
      assertFalse(read.path("result").path("isError").asBoolean());
      assertEquals(before.get("status"), store.get(id).get("status"));
      assertNull(store.get(id).get("replacement"));
      assertEquals(1, ((List<?>) store.get(id).get("toolReads")).size());
      assertEquals(
          -32602,
          CaseStore.JSON
              .readTree(post(url + "/mcp", rpc.replace("get_order", "replace_order")).body())
              .path("error")
              .path("code")
              .asInt());
      var caseResponse =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url + "/api/cases/" + id)).GET().build(),
                  HttpResponse.BodyHandlers.ofString());
      assertFalse(CaseStore.JSON.readTree(caseResponse.body()).path("lhAvailable").asBoolean());
      assertEquals(
          "UNAVAILABLE", CaseStore.JSON.readTree(caseResponse.body()).path("lhStatus").asText());
    }
  }

  @Test
  void repeatedHttpSubmissionReusesIdentityAndRejectsChangedContent() throws Exception {
    AtomicInteger published = new AtomicInteger();
    ReturnsHttpServer.WorkflowGateway gateway = new ReturnsHttpServer.WorkflowGateway() {
      public void review(String id, boolean approved) {}
      public String status(String id) { return "RUNNING"; }
    };
    try (ReturnsHttpServer server = new ReturnsHttpServer(0, store,
        (topic, key, value) -> published.incrementAndGet(), gateway, "http://localhost:8080")) {
      server.start();
      String url = "http://localhost:" + server.port() + "/api/requests";
      String payload = "{\"requestId\":\"retry-stable-id\",\"scenario\":\"normal\",\"message\":\"Damaged item\"}";
      assertEquals(201, post(url, payload).statusCode());
      assertEquals(201, post(url, payload).statusCode());
      assertEquals(1, published.get());
      assertEquals(1, store.list().size());
      assertEquals(409, post(url, payload.replace("Damaged item", "Different request")).statusCode());
      assertEquals(400, post(url, payload.replace("retry-stable-id", "../invalid")).statusCode());
      assertEquals(1, published.get());
    }
  }

  @Test
  void uncertainKafkaAcknowledgementCanRetrySamePersistedRequest() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    ReturnsHttpServer.WorkflowGateway gateway = new ReturnsHttpServer.WorkflowGateway() {
      public void review(String id, boolean approved) {}
      public String status(String id) { return "RUNNING"; }
    };
    try (ReturnsHttpServer server = new ReturnsHttpServer(0, store, (topic, key, value) -> {
      if (attempts.incrementAndGet() == 1) throw new IllegalStateException("uncertain acknowledgement");
    }, gateway, "http://localhost:8080")) {
      server.start();
      String url = "http://localhost:" + server.port() + "/api/requests";
      String payload = "{\"requestId\":\"retry-unconfirmed\",\"scenario\":\"normal\"}";
      assertEquals(503, post(url, payload).statusCode());
      assertEquals("INGRESS_FAILED", store.get("retry-unconfirmed").get("status"));
      assertEquals(201, post(url, payload).statusCode());
      assertEquals("QUEUED", store.get("retry-unconfirmed").get("status"));
      assertEquals(1, store.list().size());
    }
  }

  @Test
  void blockedCarrierPublishDoesNotLockCustomerOrWorkerState() throws Exception {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try (CarrierScheduler carrier = new CarrierScheduler(store, (topic, key, value) -> {
      entered.countDown();
      if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
    })) {
      var publishing = pool.submit(() -> carrier.tick(Long.MAX_VALUE));
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      try {
        assertEquals(id, pool.submit(() -> store.get(id)).get(1, java.util.concurrent.TimeUnit.SECONDS).get("requestId"));
      } finally { release.countDown(); }
      publishing.get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue((Boolean) store.get(id).get("carrierPublished"));
    } finally { release.countDown(); pool.shutdownNow(); }
  }

  @Test
  void workflowGraphCompilesWithActualAgentAndCorrelatedWaits() {
    var spec = ReturnsWorkflow.build(30).compileWorkflow();
    String graph = spec.toString();
    assertTrue(graph.contains("returns-decision-agent"));
    assertTrue(graph.contains("replacement-approved"));
    assertTrue(graph.contains("replacement-delivered"));
    assertTrue(graph.contains("returns-review"));
  }

  static HttpResponse<String> post(String url, String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
