package io.littlehorse.returns;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.littlehorse.sdk.worker.LHTaskMethod;
import io.littlehorse.sdk.worker.WorkerContext;
import java.util.*;

public final class ReturnsTasks {
  private final CaseStore store;
  private final long deliveryDelayMs;
  private final long recoveryPauseMs;

  public ReturnsTasks(CaseStore store, long deliveryDelayMs) {
    this(store, deliveryDelayMs, 0);
  }

  public ReturnsTasks(CaseStore store, long deliveryDelayMs, long recoveryPauseMs) {
    this.store = store;
    this.deliveryDelayMs = deliveryDelayMs;
    this.recoveryPauseMs = recoveryPauseMs;
  }

  @LHTaskMethod("returns-validate")
  public boolean validate(
      String requestId,
      String customerId,
      String orderId,
      String message,
      String scenario,
      WorkerContext context) {
    return validateCase(
        requestId, customerId, orderId, message, scenario, context.getWfRunId().getId());
  }

  public boolean validateCase(
      String id, String customer, String order, String message, String scenario, String runId) {
    if (!CaseStore.validId(id)) return false;
    Map<String, Object> existing = store.get(id);
    Map<String, Object> trustedOrder = existing == null ? CaseStore.order(scenario) : CaseStore.orderForCase(existing);
    boolean valid =
        id.equals(runId)
            && Set.of("normal", "review", "recovery", "carrier-restart").contains(scenario)
            && "customer-demo".equals(customer)
            && trustedOrder.get("orderId").equals(order)
            && message != null
            && !message.isBlank()
            && message.length() <= 8000;
    if (existing != null
        && (!Objects.equals(existing.get("customerId"), customer)
            || !Objects.equals(existing.get("orderId"), order)
            || !Objects.equals(existing.get("message"), message)
            || !Objects.equals(existing.get("scenario"), scenario))) valid = false;
    final boolean result = valid;
    store.update(
        id,
        c -> {
          if (c == null) {
            c = new LinkedHashMap<>();
            c.put("requestId", id);
            c.put("wfRunId", runId);
            c.put("customerId", customer);
            c.put("orderId", order);
            c.put("message", message);
            c.put("scenario", scenario);
          }
          if (!Boolean.TRUE.equals(c.get("validated"))) {
            c.put("validated", result);
            c.put("status", result ? "AGENT_INVESTIGATING" : "REJECTED");
            CaseStore.event(
                c,
                result ? "VALIDATED" : "VALIDATION_REJECTED",
                result
                    ? "Trusted order ownership confirmed; agent may read business fixtures."
                    : "Request identity, order ownership or input was invalid.");
          }
          return c;
        });
    return result;
  }

  @LHTaskMethod("returns-policy")
  public String policy(String requestId, String proposal) {
    return applyPolicy(requestId, proposal);
  }

  public String applyPolicy(String id, String proposal) {
    final String[] route = {"ESCALATE"};
    store.update(
        id,
        c -> {
          requireValidated(c);
          String action = "INVALID", reason = "Unsupported or malformed model proposal";
          c.remove("customerAnswer");
          try {
            JsonNode node =
                CaseStore.JSON.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(proposal);
            if (node.isObject()
                && node.path("action").isTextual()
                && node.path("reason").isTextual()
                && node.get("reason").asText().length() <= 2000
                && !node.get("reason").asText().isBlank()) {
              String candidate = node.get("action").asText();
              if (Set.of("REPLACE", "ESCALATE", "SEND_INFO").contains(candidate)) {
                action = candidate;
                reason = node.get("reason").asText();
                if ("SEND_INFO".equals(action)) {
                  JsonNode answer = node.path("answer");
                  if (answer.isTextual() && !answer.asText().isBlank() && answer.asText().length() <= 4000)
                    c.put("customerAnswer", answer.asText().strip());
                  else {
                    action = "ESCALATE";
                    reason = "Information proposal has no valid customer answer; follow-up required.";
                  }
                }
              }
            }
          } catch (Exception ignored) {
          }
          Map<String, Object> order = CaseStore.orderForCase(c);
          if ("REPLACE".equals(action) && Boolean.TRUE.equals(order.get("replacementEligible")))
            route[0] = ((Number) order.get("price")).intValue() <= 100 ? "AUTO" : "REVIEW";
          else if ("SEND_INFO".equals(action)) route[0] = "INFO";
          c.put("decision", action);
          c.put("reason", reason);
          c.put("policy", route[0]);
          c.put("status", "REVIEW".equals(route[0]) ? "WAITING_REVIEW" : "POLICY_CHECKED");
          CaseStore.event(
              c,
              "POLICY_CHECKED",
              "Proposed " + action + "; trusted policy route " + route[0] + ".");
          return c;
        });
    return route[0];
  }

  @LHTaskMethod("returns-review")
  public boolean review(String id, boolean approved) {
    store.update(
        id,
        c -> {
          requireValidated(c);
          if (!"REVIEW".equals(c.get("policy")))
            throw new IllegalStateException("No review policy gate");
          c.put("reviewApproved", approved);
          CaseStore.event(
              c,
              "REVIEW_APPLIED",
              approved
                  ? "Demo reviewer approved replacement."
                  : "Demo reviewer declined replacement.");
          return c;
        });
    return approved;
  }

  @LHTaskMethod("returns-replace")
  public String replace(String id) {
    return replaceOnce(id);
  }

  public String replaceOnce(String id) {
    final boolean[] fail = {false};
    final String[] receipt = {null};
    store.update(
        id,
        c -> {
          requireValidated(c);
          if (!"REPLACE".equals(c.get("decision"))
              || !("AUTO".equals(c.get("policy"))
                  || ("REVIEW".equals(c.get("policy"))
                      && Boolean.TRUE.equals(c.get("reviewApproved")))))
            throw new IllegalStateException("Replacement denied by trusted policy");
          if (c.get("replacement") == null) {
            String actionId = "replacement-" + id;
            c.put(
                "replacement",
                Map.of(
                    "receiptId",
                    actionId,
                    "item",
                    CaseStore.orderForCase(c).get("item"),
                    "effectCount",
                    1,
                    "simulated",
                    true));
            c.put("carrierDueAt", System.currentTimeMillis()
                + ("carrier-restart".equals(c.get("scenario")) ? Math.max(30000, deliveryDelayMs) : deliveryDelayMs));
            c.put("carrierEventId", "delivered-" + id);
            c.put("carrierPublished", false);
            c.put("status", "WAITING_DELIVERY");
            CaseStore.event(
                c, "REPLACEMENT_CREATED", "Simulated fulfillment receipt persisted exactly once.");
          } else
            CaseStore.event(
                c,
                "REPLACEMENT_REUSED",
                "Retry reused persisted fulfillment receipt; no duplicate replacement.");
          receipt[0] = String.valueOf(((Map<?, ?>) c.get("replacement")).get("receiptId"));
          if ("recovery".equals(c.get("scenario"))
              && !Boolean.TRUE.equals(c.get("recoveryFailureInjected"))) {
            c.put("recoveryFailureInjected", true);
            fail[0] = true;
            CaseStore.event(
                c,
                "RECOVERY_FAILURE",
                "Injected failure after fulfillment effect persisted; workflow retry will reuse it.");
          }
          return c;
        });
    if (fail[0]) {
      // A bounded fixture window lets acceptance kill the process before task acknowledgement.
      try { Thread.sleep(recoveryPauseMs); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      throw new IllegalStateException("Demo failure AFTER persisted replacement effect");
    }
    return receipt[0];
  }

  @LHTaskMethod("returns-finish")
  public String finish(String id, String outcome) {
    store.update(
        id,
        c -> {
          requireValidated(c);
          if ("INFORMATION_SENT".equals(outcome)
              && (!(c.get("customerAnswer") instanceof String answer) || answer.isBlank()))
            throw new IllegalStateException("No customer answer recorded");
          if ("DELIVERED".equals(outcome) && c.get("replacement") == null)
            throw new IllegalStateException("No replacement receipt");
          if (!outcome.equals(c.get("status"))) {
            c.put("status", outcome);
            if ("DELIVERED".equals(outcome))
              c.put(
                  "delivery",
                  Map.of("delivered", true, "eventId", c.get("carrierEventId"), "simulated", true));
            CaseStore.event(
                c,
                "DELIVERED".equals(outcome) ? "DELIVERED" : "INFORMATION_SENT",
                "DELIVERED".equals(outcome)
                    ? "Correlated carrier delivery confirmed; resolution complete."
                    : "Customer answer available on the tracking page; no external delivery claimed.");
          }
          return c;
        });
    return outcome;
  }

  @LHTaskMethod("returns-reject")
  public String reject(String id) {
    return transition(id, "REJECTED", "Trusted validation or demo reviewer rejected replacement.");
  }

  @LHTaskMethod("returns-escalate")
  public String escalate(String id, String reason) {
    return transition(id, "ESCALATED", reason);
  }

  private String transition(String id, String state, String reason) {
    if (!CaseStore.validId(id)) return state;
    store.update(
        id,
        c -> {
          if (c == null) {
            c = new LinkedHashMap<>();
            c.put("requestId", id);
          }
          c.put("status", state);
          if ("ESCALATED".equals(state) && c.get("replacement") != null) {
            c.put("followUpRequired", true);
            CaseStore.recordLateDelivery(c);
          }
          CaseStore.event(c, state, reason);
          return c;
        });
    return state;
  }

  private static void requireValidated(Map<String, Object> c) {
    if (c == null || !Boolean.TRUE.equals(c.get("validated")))
      throw new IllegalStateException("Case is not validated");
  }
}
