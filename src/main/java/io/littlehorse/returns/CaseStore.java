package io.littlehorse.returns;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Atomic durable fixtures; the lock also coordinates independent serve/worker JVMs. */
public final class CaseStore {
  public static final ObjectMapper JSON = new ObjectMapper();
  private final Path directory;
  private static final Object JVM_LOCK = new Object();

  public CaseStore(Path directory) {
    this.directory = directory.toAbsolutePath();
  }

  public static boolean validId(String id) {
    return id != null && id.matches("[a-zA-Z0-9_-]{1,100}");
  }

  public static Map<String, Object> order(String scenario) {
    boolean review = "review".equals(scenario);
    return new LinkedHashMap<>(
        Map.of(
            "orderId",
            review ? "order-sith-299" : "order-jedi-49",
            "customerId",
            "customer-demo",
            "item",
            review ? "Sith Lord Lightsaber" : "Jedi Training Lightsaber",
            "price",
            review ? 299 : 49,
            "delivered",
            true,
            "replacementEligible",
            true,
            "currency",
            "USD"));
  }

  public static List<Map<String, Object>> orders() {
    return List.of(order("normal"), order("review"));
  }

  /** Preserve the actual product facts for historical runs when demo inventory changes. */
  public static Map<String, Object> orderForCase(Map<String, Object> c) {
    if (c.get("order") instanceof Map<?, ?> snapshot) {
      Map<String, Object> result = new LinkedHashMap<>();
      snapshot.forEach((key, value) -> result.put(String.valueOf(key), value));
      return result;
    }
    String id = String.valueOf(c.get("orderId"));
    Map<String, Object> result = order(String.valueOf(c.get("scenario")));
    if (Set.of("order-49", "order-299").contains(id)) {
      result.put("orderId", id);
      result.put("item", id.equals("order-299") ? "Espresso machine" : "Coffee grinder");
      result.put("price", id.equals("order-299") ? 299 : 49);
    }
    return result;
  }

  public Map<String, Object> create(String scenario, String message) {
    if (!Set.of("normal", "review", "recovery", "carrier-restart").contains(scenario))
      throw new IllegalArgumentException("Unknown scenario");
    return create(scenario, message, UUID.randomUUID().toString().replace("-", ""));
  }

  /** A browser retries its stable request ID; conflicting content cannot reuse that identity. */
  public Map<String, Object> create(String scenario, String message, String id) {
    if (!Set.of("normal", "review", "recovery", "carrier-restart").contains(scenario))
      throw new IllegalArgumentException("Unknown scenario");
    if (!validId(id)) throw new IllegalArgumentException("Invalid request id");
    Map<String, Object> o = order(scenario);
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("requestId", id);
    c.put("wfRunId", id);
    c.put("customerId", o.get("customerId"));
    c.put("orderId", o.get("orderId"));
    c.put("order", o);
    c.put(
        "message",
        message == null || message.isBlank()
            ? "My " + o.get("item") + " arrived damaged. Please send a replacement."
            : message);
    c.put("scenario", scenario);
    c.put("status", "QUEUED");
    c.put("events", new ArrayList<>());
    event(c, "REQUEST_CREATED", "Customer request stored; awaiting Kafka publication.");
    return update(id, existing -> {
      if (existing == null) return c;
      if (!Objects.equals(existing.get("scenario"), scenario)
          || !Objects.equals(existing.get("message"), c.get("message")))
        throw new RequestConflict("Request ID already belongs to different request content");
      return existing;
    });
  }

  public Map<String, Object> get(String id) {
    return locked(() -> read(id));
  }

  public List<Map<String, Object>> list() {
    return locked(
        () -> {
          List<Map<String, Object>> result = new ArrayList<>();
          try (var files = Files.list(directory)) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
              result.add(read(p.getFileName().toString().replace(".json", "")));
          }
          result.sort(Comparator.comparing(c -> String.valueOf(c.get("requestId"))));
          return result;
        });
  }

  public Map<String, Object> update(
      String id, Function<Map<String, Object>, Map<String, Object>> change) {
    if (!validId(id)) throw new IllegalArgumentException("Invalid request id");
    return locked(
        () -> {
          Map<String, Object> c = change.apply(read(id));
          if (c == null) throw new IllegalArgumentException("Case not found");
          Path file = directory.resolve(id + ".json"),
              temp = Files.createTempFile(directory, id, ".tmp");
          try {
            byte[] bytes = JSON.writeValueAsBytes(c);
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
              java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
              while (buffer.hasRemaining()) out.write(buffer);
              out.force(true);
            }
            Files.move(
                temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
              dir.force(true);
            } catch (IOException ignored) {
            }
            return c;
          } finally {
            Files.deleteIfExists(temp);
          }
        });
  }

  @SuppressWarnings("unchecked")
  public static void event(Map<String, Object> c, String type, String message) {
    ((List<Object>) c.computeIfAbsent("events", k -> new ArrayList<>()))
        .add(Map.of("at", Instant.now().toString(), "type", type, "message", message));
  }

  /** Preserve late carrier evidence without rewriting a failed workflow's business outcome. */
  public static void recordLateDelivery(Map<String, Object> c) {
    if ("ESCALATED".equals(c.get("status")) && c.get("replacement") != null
        && Boolean.TRUE.equals(c.get("carrierPublished")) && c.get("lateDelivery") == null) {
      c.put("followUpRequired", true);
      c.put("lateDelivery", Map.of("delivered", true, "simulated", true,
          "eventId", c.get("carrierEventId")));
      event(c, "LATE_DELIVERY_RECORDED",
          "Simulated carrier confirmation recorded for an escalated request; follow-up remains required.");
    }
  }

  public static final class RequestConflict extends IllegalArgumentException {
    public RequestConflict(String message) { super(message); }
  }

  private Map<String, Object> read(String id) throws IOException {
    if (!validId(id)) return null;
    Path p = directory.resolve(id + ".json");
    return Files.exists(p)
        ? JSON.readValue(p.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {})
        : null;
  }

  private interface IO<T> {
    T run() throws IOException;
  }

  private <T> T locked(IO<T> work) {
    synchronized (JVM_LOCK) {
      try {
        Files.createDirectories(directory);
        try (FileChannel channel =
                FileChannel.open(
                    directory.resolve(".lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock = channel.lock()) {
          return work.run();
        }
      } catch (IOException e) {
        throw new IllegalStateException("Persistent case storage unavailable", e);
      }
    }
  }
}
