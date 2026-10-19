package io.littlehorse.returns;

import io.littlehorse.sdk.common.config.LHConfig;
import io.littlehorse.sdk.common.proto.*;
import io.littlehorse.sdk.worker.LHTaskWorker;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ReturnsApplication {
  private static String env(String name, String fallback) {
    return System.getenv().getOrDefault(name, fallback);
  }

  public static void main(String[] args) throws Exception {
    String mode = args.length == 0 ? "all" : args[0];
    if (!Set.of("register", "workers", "serve", "all", "carrier").contains(mode))
      throw new IllegalArgumentException("Mode must be register, workers, serve, all or carrier");
    Properties properties = new Properties();
    properties.setProperty("LHC_API_HOST", env("LHC_API_HOST", "localhost"));
    properties.setProperty("LHC_API_PORT", env("LHC_API_PORT", "2023"));
    properties.setProperty("LHC_API_PROTOCOL", env("LHC_API_PROTOCOL", "PLAINTEXT"));
    LHConfig config = new LHConfig(properties);
    var client = config.getBlockingStub();
    CaseStore store = new CaseStore(Path.of(env("STATE_DIR", "./data")));
    ReturnsTasks tasks =
        new ReturnsTasks(store, Long.parseLong(env("DELIVERY_DELAY_SECONDS", "8")) * 1000,
            Long.parseLong(env("RECOVERY_PAUSE_SECONDS", "20")) * 1000);
    List<LHTaskWorker> workers = new ArrayList<>();
    for (String task :
        List.of(
            "returns-validate",
            "returns-policy",
            "returns-review",
            "returns-replace",
            "returns-finish",
            "returns-reject",
            "returns-escalate")) workers.add(new LHTaskWorker(tasks, task, config));
    if (mode.equals("register")) {
      workers.forEach(LHTaskWorker::registerTaskDef);
      registerEvents(client);
      ReturnsWorkflow.build(Integer.parseInt(env("WAIT_TIMEOUT_SECONDS", "300")),
              Integer.parseInt(env("AGENT_TIMEOUT_SECONDS", "300")))
          .registerWfSpec(client);
      System.out.println("Registered returns-resolution and correlated review/carrier metadata.");
      workers.forEach(LHTaskWorker::close);
      return;
    }
    List<AutoCloseable> resources = new ArrayList<>();
    if (mode.equals("workers") || mode.equals("all")) {
      workers.forEach(
          w -> {
            w.registerTaskDef();
            w.start();
            resources.add(w);
          });
    }
    KafkaPublisher kafka = new KafkaPublisher(env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
    resources.add(kafka);
    if (mode.equals("carrier") || Boolean.parseBoolean(env("CARRIER_ENABLED", "true"))) {
      CarrierScheduler scheduler = new CarrierScheduler(store, kafka);
      scheduler.start();
      resources.add(scheduler);
    }
    if (mode.equals("serve") || mode.equals("all")) {
      ReturnsHttpServer server =
          new ReturnsHttpServer(
              Integer.parseInt(env("APP_PORT", "8091")),
              store,
              kafka,
              new ReturnsHttpServer.WorkflowGateway() {
                public void review(String id, boolean approved) {
                  client
                      .withDeadlineAfter(10, TimeUnit.SECONDS)
                      .putCorrelatedEvent(
                          PutCorrelatedEventRequest.newBuilder()
                              .setKey(id)
                              .setExternalEventDefId(
                                  ExternalEventDefId.newBuilder().setName("replacement-approved"))
                              .setContent(VariableValue.newBuilder().setBool(approved))
                              .build());
                }

                public String status(String id) {
                  return client
                      .withDeadlineAfter(3, TimeUnit.SECONDS)
                      .getWfRun(WfRunId.newBuilder().setId(id).build())
                      .getStatus()
                      .name();
                }
              },
              env("DASHBOARD_URL", "http://localhost:8080"));
      server.start();
      resources.add(server);
      System.out.println("Returns HTTP/MCP ready at port " + server.port());
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  Collections.reverse(resources);
                  resources.forEach(
                      r -> {
                        try {
                          r.close();
                        } catch (Exception ignored) {
                        }
                      });
                }));
    new CountDownLatch(1).await();
  }

  static void registerEvents(LittleHorseGrpc.LittleHorseBlockingStub client) {
    for (String event : List.of("replacement-approved", "replacement-delivered"))
      client.putExternalEventDef(
          PutExternalEventDefRequest.newBuilder()
              .setName(event)
              .setContentType(
                  ReturnType.newBuilder()
                      .setReturnType(
                          TypeDefinition.newBuilder().setPrimitiveType(VariableType.BOOL)))
              .setCorrelatedEventConfig(
                  CorrelatedEventConfig.newBuilder().setDeleteAfterFirstCorrelation(false))
              .build());
  }
}
