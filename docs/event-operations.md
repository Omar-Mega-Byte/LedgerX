# Event and webhook recovery

PostgreSQL remains the financial source of truth. A Kafka replay or webhook replay may repeat an
external effect, so inspect the durable event and delivery history before requesting one. Neither
operation changes a payment, refund, or ledger entry.

## Retention and failure handling

- `LEDGERX_KAFKA_EVENT_RETENTION` defaults to 14 days and
  `LEDGERX_KAFKA_DEAD_LETTER_RETENTION` to 30 days for newly created topics. Keep the Kafka data
  volume and back it up as part of the event recovery plan. Existing topics must have their broker
  retention settings checked explicitly after changing these variables.
- The two consumer groups retry processing three times after the first failure. An exhausted or
  malformed record goes to `<payment-events-topic>.DLT` with Kafka's original-record and exception
  headers. If the DLT publish fails, the source offset is not treated as recovered.
- Inspect the DLT and consumer group offsets before resetting an offset. With the default topic:

  ```powershell
  docker compose --env-file .env -f compose.production.yaml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic ledgerx.payment-events.v1.DLT --from-beginning --property print.headers=true --property print.key=true
  docker compose --env-file .env -f compose.production.yaml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group ledgerx-payment-event-audit-v1
  docker compose --env-file .env -f compose.production.yaml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group ledgerx-webhook-delivery-enqueuer-v1
  ```

  DLT payloads may contain business data; keep their output in the incident record, not a public
  ticket. Fix the consumer or source data issue first. An arbitrary malformed message that has no
  matching outbox row cannot be replayed through LedgerX.

## Replay a published outbox event

1. Confirm the event ID in `ledgerx.outbox_events`, its `PUBLISHED` status, the payload, and the
   affected consumer's `ledgerx.processed_events` receipt. Record the incident reason. Pending
   outbox rows retry automatically and do not need manual replay.
2. An operator calls `POST /api/v1/operations/outbox-events/{eventId}/replay` with a fresh
   `Idempotency-Key`, bearer token, and JSON body such as
   `{"reason":"restore missing Kafka consumer receipt INC-123"}`. The call returns `202`.
3. The existing publisher sends the same immutable event again. The request, operator subject, and
   reason are retained in `outbox_replay_requests`; `outbox_events.replay_count` increments. A retry
   of the same request key returns the original request. A different request while replay is in
   progress is rejected.
4. Check the event returns to `PUBLISHED`, the expected consumer receipt appears, and no duplicate
   webhook delivery was created. A pre-existing receipt makes duplicate processing a no-op.

For a failed merchant delivery, the merchant owner can inspect attempt history and call
`POST /api/v1/webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay` for a `DEAD`
delivery. Verify the receiver is ready first. The same event ID and body are sent again, and the
receiver must deduplicate by `LedgerX-Event-Id`.

## Rotate webhook encryption keys

1. Keep the old key in `LEDGERX_WEBHOOK_ENCRYPTION_KEYS` and add the new version, for example
   `1=<old-base64>,2=<new-base64>`. Set `LEDGERX_WEBHOOK_ENCRYPTION_KEY_VERSION=2` and restart
   LedgerX. The active key must be a base64-encoded 32-byte AES key. The dispatcher checks stored
   key versions at startup and refuses to start if one is missing.
2. An operator repeatedly calls `POST /api/v1/operations/webhook-keys/reencrypt?limit=100` until
   `reencrypted` is zero. Each endpoint update is transactional and has an immutable
   `webhook_secret_reencryptions` record with the operator subject. Merchant HMAC signing material
   remains the same.
3. Verify `SELECT secret_key_version, COUNT(*) FROM ledgerx.webhook_endpoints GROUP BY 1` shows only
   the new version and every running instance is configured to encrypt with it. Only then remove
   the old key from the live key ring. Keep retired keys secured for as long as retained database
   backups may contain old ciphertext.

## Reconciliation evidence

`ledgerx.reconciliation_runs` records completed and failed checks. Findings are append-only and
available to operators at `/api/v1/operations/reconciliation-runs/{runId}/findings` with `limit`
and `page`. Check version 3 covers ledger balance and journal invariants, missing and mismatched
payment/refund events, risk and idempotency links, event sequence/payload and consumer receipts,
and webhook event/attempt/delivery state. Each finding query pages through results. A check with
more than 10,000 findings fails the run rather than reporting a misleading complete result. A
failed run retains its failure status but rolls back partial findings; resolve its cause and rerun
before treating reconciliation as complete. Findings never repair financial data automatically.
