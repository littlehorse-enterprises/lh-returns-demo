package io.littlehorse.returns;

import java.util.Map;
import java.util.concurrent.*;

/** Persistent due-time outbox. Ack-before-mark permits safe transport replay after a crash. */
public final class CarrierScheduler implements AutoCloseable {
  @FunctionalInterface
  public interface Publisher {
    void publish(String topic, String key, String value) throws Exception;

    default Map<String, Long> acknowledgedOffsets() { return Map.of(); }
  }

  private final CaseStore store;
  private final Publisher publisher;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "carrier-outbox");
            t.setDaemon(true);
            return t;
          });

  public CarrierScheduler(CaseStore store, Publisher publisher) {
    this.store = store;
    this.publisher = publisher;
  }

  public void start() {
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            tick(System.currentTimeMillis());
          } catch (Exception e) {
            System.err.println("Carrier outbox retry: " + e.getClass().getSimpleName());
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  public void tick(long now) {
    RuntimeException failure = null;
    for (Map<String, Object> c : store.list()) {
      if ("ESCALATED".equals(c.get("status")) && c.get("replacement") != null
          && Boolean.TRUE.equals(c.get("carrierPublished")) && c.get("lateDelivery") == null) {
        store.update(String.valueOf(c.get("requestId")), current -> {
          CaseStore.recordLateDelivery(current);
          return current;
        });
      }
      if (c.get("carrierDueAt") instanceof Number due && due.longValue() <= now
          && !Boolean.TRUE.equals(c.get("carrierPublished"))
          && c.get("replacement") != null
          && !"REJECTED".equals(c.get("status"))) {
        String id = String.valueOf(c.get("requestId"));
        try {
          publisher.publish("fulfillment-events", id, "true");
          store.update(id, current -> {
            if (!Boolean.TRUE.equals(current.get("carrierPublished"))) {
              current.put("carrierPublished", true);
              CaseStore.event(current, "CARRIER_EVENT_PUBLISHED",
                  "Simulated carrier delivered event acknowledged by Kafka.");
            }
            CaseStore.recordLateDelivery(current);
            return current;
          });
        } catch (Exception e) {
          failure = new IllegalStateException("Carrier Kafka publish failed; outbox remains pending", e);
        }
      }
    }
    if (failure != null) throw failure;
  }

  public void close() {
    executor.shutdownNow();
  }
}
