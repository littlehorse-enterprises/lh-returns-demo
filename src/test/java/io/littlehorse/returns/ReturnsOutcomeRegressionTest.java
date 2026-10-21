package io.littlehorse.returns;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReturnsOutcomeRegressionTest {
  @TempDir Path state;
  CaseStore store;
  ReturnsTasks tasks;
  final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void setup() {
    store = new CaseStore(state);
    tasks = new ReturnsTasks(store, 0);
  }

  private String validated(String scenario) {
    Map<String, Object> c = store.create(scenario, null);
    String id = (String) c.get("requestId");
    assertTrue(tasks.validateCase(id, (String) c.get("customerId"),
        (String) c.get("orderId"), (String) c.get("message"), scenario, id));
    return id;
  }

  private String proposal(Object answer) throws Exception {
    return CaseStore.JSON.writeValueAsString(Map.of(
        "action", "SEND_INFO", "reason", "Customer asked for order information", "answer", answer));
  }

  @Test
  void informationResolutionPersistsTheAnswerBeforeCompletion() throws Exception {
    String id = validated("normal");
    String answer = "Your Jedi Training Lightsaber order was delivered. Contact support for help.";
    assertEquals("INFO", tasks.applyPolicy(id, proposal(answer)));
    assertEquals(answer, store.get(id).get("customerAnswer"));
    assertEquals("INFORMATION_SENT", tasks.finish(id, "INFORMATION_SENT"));
    assertEquals(answer, new CaseStore(state).get(id).get("customerAnswer"));
    assertNull(store.get(id).get("replacement"));
  }

  @Test
  void informationAnswerHasAnExplicitLengthBoundary() throws Exception {
    String id = validated("normal");
    assertEquals("INFO", tasks.applyPolicy(id, proposal("a".repeat(4000))));
    assertEquals("a".repeat(4000), store.get(id).get("customerAnswer"));
    assertEquals("ESCALATE", tasks.applyPolicy(id, proposal("a".repeat(4001))));
    assertNull(store.get(id).get("customerAnswer"));
  }

  @Test
  void missingOrMalformedInformationCannotReuseAnEarlierAnswer() throws Exception {
    String id = validated("normal");
    List<String> invalid = List.of(
        "{\"action\":\"SEND_INFO\",\"reason\":\"Order information\"}",
        "{\"action\":\"SEND_INFO\",\"reason\":\"Order information\",\"answer\":null}",
        proposal(""), proposal(" \n\t "), proposal(7), proposal(List.of("answer")));
    for (String output : invalid) {
      assertEquals("INFO", tasks.applyPolicy(id, proposal("An earlier valid answer")));
      assertEquals("ESCALATE", tasks.applyPolicy(id, output), output);
      assertNull(store.get(id).get("customerAnswer"), output);
      assertThrows(IllegalStateException.class, () -> tasks.finish(id, "INFORMATION_SENT"));
      assertNull(store.get(id).get("replacement"));
    }
  }

  @Test
  void finishCannotClaimInformationWasSentWithoutAnAnswer() {
    String id = validated("normal");
    assertThrows(IllegalStateException.class, () -> tasks.finish(id, "INFORMATION_SENT"));
    store.update(id, c -> { c.put("customerAnswer", " \t "); return c; });
    assertThrows(IllegalStateException.class, () -> tasks.finish(id, "INFORMATION_SENT"));
    assertNotEquals("INFORMATION_SENT", store.get(id).get("status"));
  }

  @Test
  void lateDeliveryDrainsTheOutboxWithoutRewritingTheEscalatedOutcome() {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    tasks.escalate(id, "Carrier confirmation deadline elapsed");
    Object receipt = store.get(id).get("replacement");
    AtomicInteger published = new AtomicInteger();
    try (CarrierScheduler carrier = new CarrierScheduler(new CaseStore(state), (topic, key, value) -> {
      assertEquals("fulfillment-events", topic);
      assertEquals(id, key);
      assertEquals("true", value);
      published.incrementAndGet();
    })) {
      carrier.tick(Long.MAX_VALUE);
      Map<String, Object> late = store.get(id);
      assertEquals("ESCALATED", late.get("status"));
      assertEquals(true, late.get("carrierPublished"));
      assertEquals(true, late.get("followUpRequired"));
      assertInstanceOf(Map.class, late.get("lateDelivery"));
      Map<?, ?> delivery = (Map<?, ?>) late.get("lateDelivery");
      assertEquals(true, delivery.get("delivered"));
      assertEquals(true, delivery.get("simulated"));
      assertNull(late.get("delivery"));
      assertEquals(receipt, late.get("replacement"));
      assertEquals(1, ((Map<?, ?>) late.get("replacement")).get("effectCount"));
      carrier.tick(Long.MAX_VALUE);
      assertEquals(1, published.get());
      assertEquals(late, store.get(id), "Repeated ticks must not change the late-delivery audit");
    }
  }

  @Test
  void escalationReconcilesCarrierAcknowledgementAlreadyOnFile() {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    try (CarrierScheduler carrier = new CarrierScheduler(store, (topic, key, value) -> {})) {
      carrier.tick(Long.MAX_VALUE);
      assertNull(store.get(id).get("lateDelivery"));
      tasks.escalate(id, "Native confirmation deadline elapsed before correlation");
      assertInstanceOf(Map.class, store.get(id).get("lateDelivery"));
      assertEquals("ESCALATED", store.get(id).get("status"));
      assertNull(store.get(id).get("delivery"));
      // Historical records can have an acknowledged carrier fact but no follow-up marker.
      store.update(id, c -> { c.remove("lateDelivery"); c.remove("followUpRequired"); return c; });
      carrier.tick(Long.MAX_VALUE);
      assertEquals(true, store.get(id).get("followUpRequired"));
      assertInstanceOf(Map.class, store.get(id).get("lateDelivery"));
    }
  }

  @Test
  void escalationDuringCarrierPublicationRetainsTheSingleReceipt() throws Exception {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    Object receipt = store.get(id).get("replacement");
    CountDownLatch publishing = new CountDownLatch(1), release = new CountDownLatch(1);
    try (CarrierScheduler carrier = new CarrierScheduler(store, (topic, key, value) -> {
      publishing.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline");
    }); ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> tick = executor.submit(() -> carrier.tick(Long.MAX_VALUE));
      try {
        assertTrue(publishing.await(3, TimeUnit.SECONDS));
        tasks.escalate(id, "Native wait failed while carrier acknowledgement was pending");
        assertEquals(false, store.get(id).get("carrierPublished"));
      } finally { release.countDown(); }
      tick.get(3, TimeUnit.SECONDS);
      Map<String, Object> late = store.get(id);
      assertEquals("ESCALATED", late.get("status"));
      assertInstanceOf(Map.class, late.get("lateDelivery"));
      assertEquals(receipt, late.get("replacement"));
      assertNull(late.get("delivery"));
    }
  }

  private ReturnsHttpServer server(CarrierScheduler.Publisher publisher,
      BiConsumer<String, Boolean> review) throws Exception {
    ReturnsHttpServer server = new ReturnsHttpServer(0, store, publisher,
        new ReturnsHttpServer.WorkflowGateway() {
          public void review(String id, boolean approved) { review.accept(id, approved); }
          public String status(String id) { return "RUNNING"; }
        }, "http://localhost:8080");
    server.start();
    return server;
  }

  private CompletableFuture<HttpResponse<String>> request(ReturnsHttpServer server, String path,
      String body) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(
        URI.create("http://localhost:" + server.port() + path)).timeout(Duration.ofSeconds(8));
    if (body != null) builder.header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void replayOfActiveRequestsNeverInventsAnUnpublishedDelivery() throws Exception {
    List<String> topics = new ArrayList<>();
    CarrierScheduler.Publisher publisher = new CarrierScheduler.Publisher() {
      public void publish(String topic, String key, String value) { topics.add(topic); }
      public Map<String, Long> acknowledgedOffsets() {
        // A producer thread may retain an offset from an earlier, unrelated operation.
        return Map.of("customer-requests", 8L, "fulfillment-events", 99L);
      }
    };
    try (ReturnsHttpServer server = server(publisher, (id, approved) -> {})) {
      for (String status : List.of("WAITING_REVIEW", "WAITING_DELIVERY")) {
        String id = validated("review");
        store.update(id, c -> {
          c.put("requestPublished", true);
          c.put("status", status);
          c.put("carrierPublished", false);
          return c;
        });
        topics.clear();
        var response = request(server, "/api/cases/" + id + "/replay", "{}")
            .get(3, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(List.of("customer-requests", "customer-requests"), topics);
        JsonNode offsets = CaseStore.JSON.readTree(response.body()).path("targetOffsets");
        assertEquals(Set.of("customer-requests"), fieldNames(offsets));
        assertEquals(8, offsets.path("customer-requests").asLong());
        assertEquals(status, store.get(id).get("status"));
        assertNull(store.get(id).get("delivery"));
        assertEquals(false, store.get(id).get("carrierPublished"));
      }
    }
  }

  private Set<String> fieldNames(JsonNode node) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }

  @Test
  void replayIncludesDeliveryOnlyAfterItsOriginalKafkaPublication() throws Exception {
    String id = validated("normal");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    tasks.replaceOnce(id);
    store.update(id, c -> {
      c.put("requestPublished", true);
      c.put("carrierPublished", true);
      return c;
    });
    List<String> topics = new ArrayList<>();
    CarrierScheduler.Publisher publisher = new CarrierScheduler.Publisher() {
      public void publish(String topic, String key, String value) {
        assertEquals(id, key);
        if (topic.equals("fulfillment-events")) assertEquals("true", value);
        topics.add(topic);
      }
      public Map<String, Long> acknowledgedOffsets() {
        return Map.of("customer-requests", 8L, "fulfillment-events", 9L);
      }
    };
    try (ReturnsHttpServer server = server(publisher, (request, approved) -> {})) {
      var response = request(server, "/api/cases/" + id + "/replay", "{}")
          .get(3, TimeUnit.SECONDS);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(2, Collections.frequency(topics, "customer-requests"));
      assertEquals(2, Collections.frequency(topics, "fulfillment-events"));
      assertEquals(Set.of("customer-requests", "fulfillment-events"),
          fieldNames(CaseStore.JSON.readTree(response.body()).path("targetOffsets")));
      assertEquals("WAITING_DELIVERY", store.get(id).get("status"));
      assertNull(store.get(id).get("delivery"), "Transport replay must not record business completion");
    }
  }

  @Test
  void concurrentReviewKeepsFirstIntentAndLeavesReadsAvailable() throws Exception {
    String id = validated("review");
    tasks.applyPolicy(id, "{\"action\":\"REPLACE\",\"reason\":\"damaged\"}");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try (ReturnsHttpServer server = server((topic, key, value) -> {}, (request, approved) -> {
      assertTrue(approved, "Opposing permission must never reach the gateway");
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        try {
          if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("blocked gateway");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    })) {
      String path = "/api/cases/" + id;
      var first = request(server, path + "/review", "{\"approved\":true}");
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(200, request(server, path, null).get(2, TimeUnit.SECONDS).statusCode());
        assertEquals(true, store.get(id).get("reviewIntent"));
        assertEquals(409, request(server, path + "/review", "{\"approved\":false}")
            .get(2, TimeUnit.SECONDS).statusCode());
        assertEquals(200, request(server, path + "/review", "{\"approved\":true}")
            .get(2, TimeUnit.SECONDS).statusCode());
        assertNull(store.get(id).get("reviewApproved"));
        assertThrows(IllegalStateException.class, () -> tasks.replaceOnce(id));
      } finally { release.countDown(); }
      assertEquals(200, first.get(3, TimeUnit.SECONDS).statusCode());
      assertEquals(2, calls.get());
      assertEquals(true, store.get(id).get("reviewIntent"));
      assertNull(store.get(id).get("replacement"));
    }
  }
}
