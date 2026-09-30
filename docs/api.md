# LedgerX API guide

This guide summarizes the implemented HTTP controllers. The full request and response schemas are in the local [OpenAPI document](http://127.0.0.1:8080/api-docs) and [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). Both are disabled in the `prod` profile. The API prefix is `/api/v1`.

## Identity and request conventions

- **Production:** Send `Authorization: Bearer <JWT_TOKEN>`. Keycloak issues the token through Authorization Code with PKCE. LedgerX validates its issuer and `ledgerx-api` audience. The signed `ledgerx_owner_id` claim selects the pre-provisioned wallet owner. `ledgerx-operator` is required for `/operations/**`.
- **Local/test only:** Owner endpoints accept `X-LedgerX-Owner-Id: <OWNER_ID>`. This header is forgeable and is not authentication. Local mode denies `/operations/**`; use the isolated real-Keycloak browser stack or the production-profile operator workbench to provision owners.
- **Financial commands:** Send `Idempotency-Key` with a fresh client key for a new transfer, payment, refund, or local demo top-up. Reusing the same owner, key, and canonical request replays the durable result. Reusing a key for a different request returns a conflict.
- **Money:** Use a positive decimal string and the explicit currency `USD`, for example `{"amount":"25.00","currency":"USD"}`. Wallet and record IDs are UUIDs.
- **Errors:** Validation, ownership, missing-resource, conflict, and business-rule failures use structured API errors. Owner-scoped reads may return 404 for an unrelated record.

Local base URL: `http://127.0.0.1:8080/api/v1`. Production base URL: `https://<LEDGERX_HOST>/api/v1`. The examples below use synthetic placeholders, not real tokens, IDs, or credentials.

## Endpoint groups

| Group | Implemented endpoints | Access |
| --- | --- | --- |
| Workspace and activity | `GET /me`; `GET /activity` with optional `walletId`, `limit`, and `page` query parameters | Owner |
| Transfers and journal | `POST /transfers`; `GET /transfers/{transferId}`; `GET /ledger-transactions/{transactionId}` | Participating owner |
| Merchant payments | `POST /payments`; `GET /payments/{paymentId}` | Payer for creation; participating owner for reads |
| Refunds | `POST /payments/{paymentId}/refunds`; `GET /payments/{paymentId}/refunds`; `GET /payments/{paymentId}/refunds/{refundId}`; `GET /refunds/{refundId}` | Original merchant for creation; participating owner for reads |
| Payer risk review | `GET /payment-risk-cases/{caseId}` | Payer owner |
| Merchant webhooks | `POST /webhook-endpoints`; `GET /webhook-endpoints`; `GET /webhook-endpoints/{endpointId}`; `POST /webhook-endpoints/{endpointId}/disable`; `POST /webhook-endpoints/{endpointId}/rotate-secret`; `GET /webhook-endpoints/{endpointId}/deliveries`; `GET /webhook-endpoints/{endpointId}/deliveries/{deliveryId}`; `GET /webhook-endpoints/{endpointId}/deliveries/{deliveryId}/attempts`; `POST /webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay` | Merchant owner |
| Operator provisioning and evidence | `GET/POST /operations/owners`; `GET /operations/owners/{ownerId}`; `POST /operations/owners/{ownerId}/wallets`; `POST /operations/owners/{ownerId}/suspend`; `POST /operations/wallets/{walletId}/suspend`; `POST /operations/wallets/{walletId}/close`; `GET /operations/summary`; `GET /operations/reconciliation-runs`; `GET /operations/reconciliation-runs/{runId}/findings`; `GET /operations/ledger-transactions/{transactionId}` | Operator role |
| Operator risk and recovery | `GET /operations/risk/policy`; `POST /operations/risk/policies`; `GET /operations/risk/cases`; `GET /operations/risk/cases/{caseId}`; `GET /operations/risk/cases/{caseId}/actions`; `POST /operations/risk/cases/{caseId}/approve`; `POST /operations/risk/cases/{caseId}/decline`; `POST /operations/outbox-events/{eventId}/replay`; `POST /operations/webhook-keys/reencrypt` | Operator role |
| Local demo funding | `POST /demo/wallets/{walletId}/fundings` | Owner, local/test profile only |

The activity endpoint is paged and can filter by an owned wallet. Reconciliation reads report stored findings; the workbench does not edit journals. Webhook delivery is at least once, so merchant receivers should deduplicate by the stable event ID. [Event operations](event-operations.md) describes replay and key rotation.

## Representative requests

Replace each angle-bracket placeholder. The examples show production Bearer authentication; when exploring the local owner API, replace the `Authorization` header with `X-LedgerX-Owner-Id: <OWNER_ID>`.

### Read the owner's wallets

```http
GET /api/v1/me HTTP/1.1
Authorization: Bearer <JWT_TOKEN>
```

```json
{
  "ownerId": "<OWNER_ID>",
  "ownerType": "PERSON",
  "status": "ACTIVE",
  "wallets": [
    {
      "walletId": "<SOURCE_WALLET_ID>",
      "currency": "USD",
      "status": "ACTIVE",
      "balance": { "amount": "100.00", "currency": "USD" }
    }
  ]
}
```

`balance` is derived from posted journal entries. No endpoint sets it directly.

### Transfer between wallets

```http
POST /api/v1/transfers HTTP/1.1
Authorization: Bearer <JWT_TOKEN>
Idempotency-Key: transfer-example-001
Content-Type: application/json

{
  "sourceWalletId": "<SOURCE_WALLET_ID>",
  "destinationWalletId": "<DESTINATION_WALLET_ID>",
  "money": { "amount": "25.00", "currency": "USD" }
}
```

The caller must own the source wallet. A new transfer returns `201 Created` with a `Location` header; an identical replay returns `200 OK` and the same transfer. Example response:

```json
{
  "transferId": "<TRANSFER_ID>",
  "sourceWalletId": "<SOURCE_WALLET_ID>",
  "destinationWalletId": "<DESTINATION_WALLET_ID>",
  "money": { "amount": "25.00", "currency": "USD" },
  "status": "COMPLETED",
  "ledgerTransactionId": "<JOURNAL_ID>",
  "completedAt": "2030-01-02T03:04:05Z"
}
```

### Pay a merchant

```http
POST /api/v1/payments HTTP/1.1
Authorization: Bearer <PAYER_JWT_TOKEN>
Idempotency-Key: payment-example-001
Content-Type: application/json

{
  "payerWalletId": "<PERSON_WALLET_ID>",
  "merchantWalletId": "<MERCHANT_WALLET_ID>",
  "money": { "amount": "7.50", "currency": "USD" }
}
```

The payer must own an active PERSON wallet and the destination must be an active MERCHANT wallet. An allowed new payment returns `201 Created`; an identical replay returns `200 OK`. Example completed response:

```json
{
  "paymentId": "<PAYMENT_ID>",
  "payerWalletId": "<PERSON_WALLET_ID>",
  "merchantWalletId": "<MERCHANT_WALLET_ID>",
  "money": { "amount": "7.50", "currency": "USD" },
  "status": "COMPLETED",
  "refundedMoney": { "amount": "0.00", "currency": "USD" },
  "remainingRefundableMoney": { "amount": "7.50", "currency": "USD" },
  "ledgerTransactionId": "<JOURNAL_ID>",
  "completedAt": "2030-01-02T03:04:05Z"
}
```

With an enabled risk policy, `202 Accepted` means review is required and **no money moved**; a blocked decision returns `422` without posting. The original payer may retry an approved review with the same key and request.

### Issue a partial refund

```http
POST /api/v1/payments/<PAYMENT_ID>/refunds HTTP/1.1
Authorization: Bearer <MERCHANT_JWT_TOKEN>
Idempotency-Key: refund-example-001
Content-Type: application/json

{ "money": { "amount": "2.50", "currency": "USD" } }
```

Only the original merchant can refund. `201 Created` returns a refund ID and compensating journal ID; an identical replay returns `200 OK`. Example response:

```json
{
  "refundId": "<REFUND_ID>",
  "paymentId": "<PAYMENT_ID>",
  "merchantWalletId": "<MERCHANT_WALLET_ID>",
  "payerWalletId": "<PERSON_WALLET_ID>",
  "money": { "amount": "2.50", "currency": "USD" },
  "ledgerTransactionId": "<REFUND_JOURNAL_ID>",
  "completedAt": "2030-01-02T03:05:05Z"
}
```

The cumulative refunded amount cannot exceed the payment amount. Use `GET /api/v1/payments/<PAYMENT_ID>` to see the derived payment refund status and remaining refundable amount.

## OpenAPI and workbench

Start the local Compose stack, then open `/swagger-ui/index.html` for interactive schemas and editable examples or `/api-docs` for the OpenAPI JSON. [The API reference screenshot](images/api-reference.png) shows the local UI. For real Keycloak authentication, the same-origin workbench at `/` performs PKCE sign-in; the [deployment guide](production-deployment.md) explains client and owner-claim setup. Production intentionally disables Swagger and OpenAPI routes.
