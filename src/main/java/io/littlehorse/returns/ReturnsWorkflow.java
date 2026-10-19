package io.littlehorse.returns;

import io.littlehorse.sdk.common.proto.*;
import io.littlehorse.sdk.wfsdk.*;

public final class ReturnsWorkflow {
  public static Workflow build(int timeout) {
    return build(timeout, timeout);
  }

  public static Workflow build(int timeout, int agentTimeout) {
    if (timeout <= 0 || agentTimeout <= 0) throw new IllegalArgumentException("Timeouts must be positive");
    UUIDSuffix.VALUE.set(0);
    return Workflow.newWorkflow(
        "returns-resolution",
        wf -> {
          WfRunVariable id = wf.declareStr("request-id").required().searchable();
          WfRunVariable customer = wf.declareStr("customer-id").required();
          WfRunVariable order = wf.declareStr("order-id").required();
          WfRunVariable message = wf.declareStr("message").required();
          WfRunVariable scenario = wf.declareStr("scenario").required();
          WfRunVariable valid = wf.declareBool("request-valid");
          WfRunVariable proposal = wf.declareStr("agent-proposal").searchable();
          WfRunVariable policy = wf.declareStr("policy-route").searchable();
          valid.assign(
              wf.execute("returns-validate", id, customer, order, message, scenario)
                  .withRetries(2));
          wf.doIf(
                  valid.isEqualTo(true),
                  body -> {
                    NodeOutput agent =
                        body.execute(
                                "returns-decision-agent",
                                body.format(
                                    "Request ID: {0}\nCustomer message: {1}\nRead order, policy and fulfillment tools for this request before proposing a resolution. Return the required JSON only.",
                                    id, message))
                            .timeout(agentTimeout);
                    body.handleError(
                        agent,
                        handler -> {
                          handler
                              .execute(
                                  "returns-escalate",
                                  id,
                                  "Agent unavailable or timed out; no action taken.")
                              .withRetries(2);
                          handler.fail("agent-unavailable", "Agent task failed safely.");
                        });
                    proposal.assign(agent);
                    policy.assign(body.execute("returns-policy", id, proposal).withRetries(2));
                    body.doIf(policy.isEqualTo("AUTO"), auto -> fulfillment(auto, id, timeout))
                        .doElseIf(
                            policy.isEqualTo("REVIEW"),
                            review -> {
                              NodeOutput approved =
                                  review
                                      .waitForEvent("replacement-approved")
                                      .withCorrelationId(id)
                                      .withCorrelatedEventConfig(
                                          CorrelatedEventConfig.newBuilder()
                                              .setDeleteAfterFirstCorrelation(false)
                                              .build())
                                      .registeredAs(Boolean.class)
                                      .timeout(timeout);
                              review.handleError(
                                  approved,
                                  LHErrorType.TIMEOUT,
                                  handler -> {
                                    handler
                                        .execute("returns-escalate", id, "Demo review timed out.")
                                        .withRetries(2);
                                    handler.fail(
                                        "review-timeout", "Review did not arrive within deadline.");
                                  });
                              WfRunVariable reviewResult = review.declareBool("review-result");
                              reviewResult.assign(
                                  review.execute("returns-review", id, approved).withRetries(2));
                              review
                                  .doIf(
                                      reviewResult.isEqualTo(true),
                                      yes -> fulfillment(yes, id, timeout))
                                  .doElse(no -> no.execute("returns-reject", id).withRetries(2));
                            })
                        .doElseIf(
                            policy.isEqualTo("INFO"),
                            info ->
                                info.execute("returns-finish", id, "INFORMATION_SENT")
                                    .withRetries(2))
                        .doElse(
                            escalate ->
                                escalate
                                    .execute(
                                        "returns-escalate",
                                        id,
                                        "Model proposal requires a specialist; no fulfillment action taken.")
                                    .withRetries(2));
                  })
              .doElse(rejected -> rejected.execute("returns-reject", id).withRetries(2));
        });
  }

  private static void fulfillment(WorkflowThread wf, WfRunVariable id, int timeout) {
    NodeOutput replacement = wf.execute("returns-replace", id).withRetries(3);
    wf.handleError(
        replacement,
        handler -> {
          handler
              .execute(
                  "returns-escalate",
                  id,
                  "Fulfillment failed after bounded retries; inspect persisted receipt.")
              .withRetries(2);
          handler.fail("fulfillment-failed", "Fulfillment did not acknowledge.");
        });
    NodeOutput delivered =
        wf.waitForEvent("replacement-delivered")
            .withCorrelationId(id)
            .withCorrelatedEventConfig(
                CorrelatedEventConfig.newBuilder().setDeleteAfterFirstCorrelation(false).build())
            .registeredAs(Boolean.class)
            .timeout(timeout);
    wf.handleError(
        delivered,
        LHErrorType.TIMEOUT,
        handler -> {
          handler
              .execute(
                  "returns-escalate",
                  id,
                  "Carrier confirmation timed out; inspect persisted receipt.")
              .withRetries(2);
          handler.fail("carrier-timeout", "Delivery confirmation deadline elapsed.");
        });
    WfRunVariable confirmation = wf.declareBool("delivery-confirmed-" + UUIDSuffix.next());
    confirmation.assign(delivered);
    wf.doIf(
            confirmation.isEqualTo(true),
            yes -> yes.execute("returns-finish", id, "DELIVERED").withRetries(2))
        .doElse(
            no ->
                no.execute("returns-escalate", id, "Carrier reported non-delivery.")
                    .withRetries(2));
  }

  // Stable variable names across the two graph branches, rather than random authoring IDs.
  private static final class UUIDSuffix {
    private static final ThreadLocal<Integer> VALUE = ThreadLocal.withInitial(() -> 0);

    static int next() {
      int v = VALUE.get();
      VALUE.set(v + 1);
      return v;
    }
  }
}
