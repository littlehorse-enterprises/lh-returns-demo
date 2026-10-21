# Presenter walkthrough

The customer wants a working lightsaber. The engineering question is what happens after a model makes a recommendation and a distributed system fails.

## The ordinary exchange

Start at the customer storefront. Request a replacement for the $49 Jedi training saber with a damaged-in-transit reason. Keep the customer problem concrete; the sand reference is an optional aside.

Expand **Behind the exchange**, then open the native LittleHorse run. Identify the Kafka business request, the actual agent task and three read-only tool calls. Explain that the agent proposes an action while Java owns policy and fulfillment. Finish on the customer’s confirmed replacement and the native completed run.

## The policy boundary

Use the $299 Sith saber. The higher trusted order value requires review even if the model recommends replacement. Show the waiting node and no replacement receipt. The terminal review scenario submits the labeled local callback; approval resumes the run, while rejection creates no replacement.

Keep business events distinct from engine progress: a task finishing is not a delivery confirmation.

## An answer without a replacement

Choose **Ask about this order**, ask its price or replacement eligibility, and submit. The agent must return an actual answer based on the tool facts. Show the text on the tracking page, the stored `customerAnswer` and the native information branch with no replacement receipt. Run `./scripts/demo.sh information` against the updated live stack before including this in a recording.

## Late delivery after a failed wait

The timeout scenario first shows an escalated request and a failed native run. When the carrier resumes, the tracking page records simulated delivery confirmation while preserving the follow-up requirement, failed native outcome and original receipt. Explain that evidence arriving later does not automatically resolve the failed business process.

## The failure worth showing

Run `./scripts/demo.sh recovery`. The worker persists its replacement receipt, pauses before acknowledgement and is killed. After restart, LittleHorse retries the task, which reuses the receipt. The independently running carrier supplies the correlated delivery event.

Show the retry attempts and receipt output in the native run, then the single persisted replacement effect in the customer tracking details. An optional line: “We restarted the worker. The customer should still get one replacement.”

## Rehearsal checks

Use fresh IDs for each take. Run acceptance on the actual services; prewarm the real model before recording. Confirm the three tool reads, native terminal status and business receipt for each scenario. Check both desktop and phone layouts. Keep downloads, keys, terminal environment output and unrelated windows out of the recording.

The customer, orders, review callback and carrier are simulated. Kafka, LittleHorse, the Agent Connector, model calls and MCP reads are real. Do not present a fixture or a prior receipt as a new live model result.
