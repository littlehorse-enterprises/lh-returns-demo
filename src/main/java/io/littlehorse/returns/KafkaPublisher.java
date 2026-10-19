package io.littlehorse.returns;

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

public final class KafkaPublisher implements CarrierScheduler.Publisher, AutoCloseable {
  private final KafkaProducer<String, String> producer;
  private final ThreadLocal<java.util.Map<String, Long>> acknowledged =
      ThreadLocal.withInitial(java.util.HashMap::new);

  public KafkaPublisher(String bootstrap) {
    Properties p = new Properties();
    p.put("bootstrap.servers", bootstrap);
    p.put("key.serializer", StringSerializer.class.getName());
    p.put("value.serializer", StringSerializer.class.getName());
    p.put("acks", "all");
    p.put("enable.idempotence", "true");
    p.put("delivery.timeout.ms", "15000");
    p.put("request.timeout.ms", "10000");
    p.put("max.block.ms", "15000");
    producer = new KafkaProducer<>(p);
  }

  public void publish(String topic, String key, String value) throws Exception {
    RecordMetadata receipt = producer.send(new ProducerRecord<>(topic, key, value)).get(20, TimeUnit.SECONDS);
    // This demo provisions one partition per business topic. Offsets are next-to-read positions.
    acknowledged.get().put(topic, receipt.offset() + 1);
  }

  public java.util.Map<String, Long> acknowledgedOffsets() {
    return java.util.Map.copyOf(acknowledged.get());
  }

  public void close() {
    producer.close(java.time.Duration.ofSeconds(5));
  }
}
