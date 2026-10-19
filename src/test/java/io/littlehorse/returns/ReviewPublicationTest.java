package io.littlehorse.returns;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewPublicationTest {
  @TempDir Path state;

  private String pending(CaseStore store) {
    String id = (String) store.create("review", null).get("requestId");
    store.update(id, c -> { c.put("status", "WAITING_REVIEW"); return c; });
    return id;
  }

  private ReturnsHttpServer server(CaseStore store, BiConsumer<String, Boolean> review)
      throws Exception {
    ReturnsHttpServer server = new ReturnsHttpServer(0, store, (topic, key, value) -> {},
        new ReturnsHttpServer.WorkflowGateway() {
          public void review(String id, boolean approved) { review.accept(id, approved); }
          public String status(String id) { return "RUNNING"; }
        }, "http://localhost:8080");
    server.start();
    return server;
  }

  private CompletableFuture<HttpResponse<String>> post(ReturnsHttpServer server, String id,
      boolean approved) {
    return HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(
            URI.create("http://localhost:" + server.port() + "/api/cases/" + id + "/review"))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"approved\":" + approved + "}")).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void stalledPublicationAllowsStoreWorkAndDoesNotOverwriteConsumedWorkflowProgress()
      throws Exception {
    CaseStore store = new CaseStore(state);
    String id = pending(store);
    String unrelated = (String) store.create("normal", null).get("requestId");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (ReturnsHttpServer server = server(store, (request, approved) -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }); ExecutorService executor = Executors.newSingleThreadExecutor()) {
      var response = post(server, id, true);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        executor.submit(() -> {
          assertEquals(true, store.get(id).get("reviewIntent"));
          assertNull(store.get(id).get("reviewApproved"));
          assertEquals(2, store.list().size());
          store.update(unrelated, c -> { c.put("status", "VALIDATED"); return c; });
          store.update(id, c -> {
            c.put("reviewApproved", true);
            c.put("status", "DELIVERED");
            return c;
          });
        }).get(2, TimeUnit.SECONDS);
      } finally { release.countDown(); }
      assertEquals(200, response.get(3, TimeUnit.SECONDS).statusCode());
      assertEquals("DELIVERED", store.get(id).get("status"));
      assertEquals("CONFIRMED", store.get(id).get("reviewPublication"));
    }
  }

  @Test
  void ambiguousFailurePersistsDecisionAcrossRestartAndRejectsConflictingRetry() throws Exception {
    CaseStore store = new CaseStore(state);
    String id = pending(store);
    try (ReturnsHttpServer server = server(store, (request, approved) -> {
      throw new IllegalStateException("ack lost");
    })) {
      assertEquals(503, post(server, id, true).get().statusCode());
      assertEquals("WAITING_REVIEW", store.get(id).get("status"));
      assertEquals("UNCONFIRMED", store.get(id).get("reviewPublication"));
      assertNull(store.get(id).get("reviewApproved"));
    }
    AtomicInteger calls = new AtomicInteger();
    CaseStore restarted = new CaseStore(state);
    try (ReturnsHttpServer server = server(restarted, (request, approved) -> {
      assertTrue(approved);
      calls.incrementAndGet();
    })) {
      assertEquals(409, post(server, id, false).get().statusCode());
      assertEquals(0, calls.get());
      assertEquals(200, post(server, id, true).get().statusCode());
      assertEquals(409, post(server, id, true).get().statusCode());
      assertEquals(1, calls.get());
      assertNull(restarted.get(id).get("reviewApproved"));
    }
  }

  @Test
  void lateFailureOfConcurrentSameDecisionCannotUndoSuccessfulPublication() throws Exception {
    CaseStore store = new CaseStore(state);
    String id = pending(store);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    try (ReturnsHttpServer server = server(store, (request, approved) -> {
      assertTrue(approved);
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        try { release.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        throw new IllegalStateException("late lost ack");
      }
    })) {
      var first = post(server, id, true);
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertEquals(409, post(server, id, false).get(3, TimeUnit.SECONDS).statusCode());
        assertEquals(200, post(server, id, true).get(3, TimeUnit.SECONDS).statusCode());
      } finally { release.countDown(); }
      assertEquals(503, first.get(3, TimeUnit.SECONDS).statusCode());
      assertEquals("CONFIRMED", store.get(id).get("reviewPublication"));
      assertEquals("REVIEW_SIGNALLED", store.get(id).get("status"));
      assertNull(store.get(id).get("reviewApproved"));
      assertEquals(2, calls.get());
    }
  }
}
