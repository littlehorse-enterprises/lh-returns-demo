package io.littlehorse.returns;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorefrontConfigurationTest {
  @TempDir Path state;

  @Test
  void ordersExposeConfiguredPublicDashboardWithoutCallingWorkflowService() throws Exception {
    ReturnsHttpServer.WorkflowGateway gateway = new ReturnsHttpServer.WorkflowGateway() {
      public void review(String id, boolean approved) { fail("Unexpected workflow mutation"); }
      public String status(String id) { throw new AssertionError("Unexpected workflow lookup"); }
    };
    try (ReturnsHttpServer server = new ReturnsHttpServer(0, new CaseStore(state),
        (topic, key, value) -> fail("Unexpected Kafka publication"), gateway,
        "http://localhost:18080")) {
      server.start();
      var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
          URI.create("http://localhost:" + server.port() + "/api/orders")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      var body = CaseStore.JSON.readTree(response.body());
      assertEquals("http://localhost:18080", body.path("dashboardUrl").asText());
      assertTrue(body.path("orders").isArray());
      assertFalse(body.path("orders").isEmpty());
    }
  }
}
