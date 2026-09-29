import { createHmac, randomBytes, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { test, expect } from '@playwright/test';

const webOrigin = process.env.E2E_WEB_ORIGIN;
const idpOrigin = process.env.E2E_IDP_ORIGIN;
const project = process.env.E2E_COMPOSE_PROJECT;
const adminUsername = process.env.E2E_KEYCLOAK_ADMIN_USERNAME;
const adminPassword = process.env.E2E_KEYCLOAK_ADMIN_PASSWORD;
const root = resolve(import.meta.dirname, '../..');
const docker = process.platform === 'win32' ? 'docker.exe' : 'docker';
const live = process.env.E2E_LIVE === '1';
const receiverId = process.env.E2E_MERCHANT_RECEIVER_ID;
let liveEndpoint;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

if (live && (process.env.E2E_LIVE_ACK !== 'I_ACCEPT_TEST_DATA_IN_PRODUCTION' ||
    !webOrigin?.startsWith('https://') || !idpOrigin?.startsWith('https://') ||
    !project)) {
  throw new Error('Live acceptance requires explicit acknowledgement and public HTTPS origins.');
}

if (!webOrigin || !idpOrigin || !project || !adminUsername || !adminPassword) {
  throw new Error('Run the browser test with npm run test:e2e, which starts its isolated stack.');
}

test.afterEach(async ({ request }) => {
  if (!live || !receiverId) return;
  try {
    await request.put(`https://webhook.site/token/${receiverId}`, { data: { default_status: 200 } });
  } finally {
    if (liveEndpoint) {
      await request.post(`${webOrigin}/api/v1/webhook-endpoints/${liveEndpoint.id}/disable`, {
        headers: { Authorization: `Bearer ${liveEndpoint.token}` },
      });
      liveEndpoint = undefined;
    }
  }
});

function sql(statement) {
  const compose = live
    ? ['compose', '-p', project, '--env-file', resolve(root, '.env'), '-f', resolve(root, 'compose.production.yaml')]
    : ['compose', '-p', project, '-f', resolve(root, 'compose.e2e.yaml')];
  return execFileSync(docker, [
    ...compose,
    'exec', '-T', 'postgres', 'psql', '-X', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1',
    '-U', process.env.E2E_DB_USERNAME, '-d', 'ledgerx',
  ], { cwd: root, env: process.env, input: `SET search_path TO ledgerx, public;\n${statement}`, encoding: 'utf8' }).trim();
}

function productionCompose(...args) {
  if (!live) throw new Error('Production Compose control is limited to guarded live acceptance.');
  return execFileSync(docker, [
    'compose', '-p', project, '--env-file', resolve(root, '.env'),
    '-f', resolve(root, 'compose.production.yaml'), ...args,
  ], { cwd: root, encoding: 'utf8' }).trim();
}

async function adminAuthorization(request) {
  const response = await request.post(`${idpOrigin}/realms/master/protocol/openid-connect/token`, {
    form: {
      client_id: 'admin-cli',
      grant_type: 'password',
      username: adminUsername,
      password: adminPassword,
    },
  });
  expect(response, 'Keycloak bootstrap administrator can obtain an admin API token').toBeOK();
  return { Authorization: `Bearer ${(await response.json()).access_token}` };
}

async function verifyImportedWebClient(request, headers) {
  const response = await request.get(`${idpOrigin}/admin/realms/ledgerx/clients`, {
    headers, params: { clientId: 'ledgerx-web' },
  });
  expect(response).toBeOK();
  const [client] = await response.json();
  expect(client?.clientId).toBe('ledgerx-web');
  expect(client.redirectUris).toEqual([`${webOrigin}/`]);
  expect(client.webOrigins).toContain(webOrigin);
  expect(client.attributes?.['pkce.code.challenge.method']).toBe('S256');
}

async function createUser(request, headers, label, ownerId, operator = false) {
  const username = `${live ? 'phase-y' : 'e2e'}-${label}-${randomUUID().slice(0, 8)}`;
  const credential = randomBytes(32).toString('base64url');
  const response = await request.post(`${idpOrigin}/admin/realms/ledgerx/users`, {
    headers,
    data: {
      username,
      enabled: true,
      emailVerified: true,
      firstName: 'Browser',
      lastName: label,
      email: `${username}@example.invalid`,
      attributes: ownerId ? { ledgerx_owner_id: [ownerId] } : {},
      credentials: [{ type: 'password', value: credential, temporary: false }],
    },
  });
  expect(response.status()).toBe(201);
  const userId = response.headers().location?.split('/').at(-1);
  expect(userId).toMatch(uuid);
  if (ownerId) {
    const stored = await request.get(`${idpOrigin}/admin/realms/ledgerx/users/${userId}`, { headers });
    expect(stored).toBeOK();
    expect((await stored.json()).attributes?.ledgerx_owner_id).toEqual([ownerId]);
  }
  if (operator) {
    const roleResponse = await request.get(`${idpOrigin}/admin/realms/ledgerx/roles/ledgerx-operator`, { headers });
    expect(roleResponse).toBeOK();
    const assignment = await request.post(`${idpOrigin}/admin/realms/ledgerx/users/${userId}/role-mappings/realm`, {
      headers, data: [await roleResponse.json()],
    });
    expect(assignment.status()).toBe(204);
  }
  return { username, credential };
}

async function signIn(browser, user, section) {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  await page.goto(webOrigin);
  await expect(page.getByRole('heading', { name: 'Open your workbench' })).toBeVisible();
  const authorizeRequest = page.waitForRequest(req => req.url().includes('/protocol/openid-connect/auth?'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  const authorize = new URL((await authorizeRequest).url());
  expect(authorize.searchParams.get('response_type')).toBe('code');
  expect(authorize.searchParams.get('code_challenge_method')).toBe('S256');
  expect(authorize.searchParams.get('code_challenge')).toBeTruthy();
  expect(authorize.searchParams.get('redirect_uri')).toBe(`${webOrigin}/`);
  await expect(page.locator('#username')).toBeVisible({ timeout: 20_000 });
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.credential);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('navigation', { name: 'Workbench sections' }).getByRole('link', { name: section })).toBeVisible();
  const token = await page.evaluate(async () => (await import('/ui/auth.js')).accessToken());
  expect(token).toBeTruthy();
  return { context, page, token };
}

async function provisionOwner(page, ownerType) {
  await page.evaluate(() => { location.hash = '#/operations'; });
  await page.locator('[data-form="owner-create"]').click();
  await page.locator('#owner-type').selectOption(ownerType);
  await page.getByRole('button', { name: 'Review', exact: true }).click();
  const responsePromise = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/operations/owners') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Confirm and submit' }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const owner = await response.json();
  expect(owner.ownerType).toBe(ownerType);
  expect(owner.ownerId).toMatch(uuid);
  expect(owner.wallets[0].walletId).toMatch(uuid);
  await expect(page.locator('#main-content')).toContainText(owner.ownerId);
  return owner;
}

function fundFixture(walletId) {
  expect(walletId).toMatch(uuid);
  const clearingId = randomUUID();
  const transactionId = randomUUID();
  sql(`BEGIN;
INSERT INTO ledgerx.ledger_accounts
  (id, account_kind, account_type, owner_id, system_code, currency, status, created_at)
VALUES ('${clearingId}', 'SYSTEM', 'ASSET', NULL, 'E2E-CLEARING-${clearingId}', 'USD', 'ACTIVE', NOW());
INSERT INTO ledgerx.ledger_transactions (id, currency, description, posted_at)
VALUES ('${transactionId}', 'USD', 'Isolated browser test funding', NOW());
INSERT INTO ledgerx.ledger_entries
  (id, ledger_transaction_id, line_number, ledger_account_id, side, amount, currency)
VALUES
  ('${randomUUID()}', '${transactionId}', 1, '${clearingId}', 'DEBIT', 25.00, 'USD'),
  ('${randomUUID()}', '${transactionId}', 2, '${walletId}', 'CREDIT', 25.00, 'USD');
COMMIT;`);
}

test('real Keycloak PKCE identities complete owner, operator, payment and refund browser flows', async ({ browser, request }) => {
  if (live && process.env.E2E_EXPIRED_TOKEN === '1') test.setTimeout(720_000);
  await test.step('Wait for the imported realm and secure public edge', async () => {
    await expect.poll(async () => {
      try { return (await request.get(`${idpOrigin}/realms/ledgerx/.well-known/openid-configuration`)).status(); }
      catch { return 0; }
    }, { timeout: 120_000 }).toBe(200);
    const config = await request.get(`${webOrigin}/ui-config`);
    expect(config).toBeOK();
    expect(await config.json()).toMatchObject({ mode: 'production', issuerUri: `${idpOrigin}/realms/ledgerx` });
    expect((await request.get(`${webOrigin}/actuator/prometheus`)).status()).toBe(404);
    expect((await request.get(`${webOrigin}/api/v1/me`, {
      headers: { 'X-LedgerX-Owner-Id': randomUUID() },
    })).status()).toBe(401);
  });

  const adminHeaders = await adminAuthorization(request);
  await verifyImportedWebClient(request, adminHeaders);
  const operatorUser = await createUser(request, adminHeaders, 'operator', null, true);

  const operator = await test.step('Operator signs in and provisions two owners', async () => {
    const session = await signIn(browser, operatorUser, 'Operations');
    expect((await request.get(`${webOrigin}/api/v1/operations/summary`, {
      headers: { Authorization: `Bearer ${session.token}` },
    })).status()).toBe(200);
    const person = await provisionOwner(session.page, 'PERSON');
    const merchant = await provisionOwner(session.page, 'MERCHANT');
    await session.page.getByRole('button', { name: 'Sign out' }).click();
    await expect(session.page.getByRole('heading', { name: 'Open your workbench' })).toBeVisible();
    await session.context.close();
    return { person, merchant };
  });

  fundFixture(operator.person.wallets[0].walletId);
  const personUser = await createUser(request, adminHeaders, 'person', operator.person.ownerId);
  const merchantUser = await createUser(request, adminHeaders, 'merchant', operator.merchant.ownerId);
  let webhook;
  if (live && receiverId) {
    webhook = await test.step('Merchant registers a public HTTPS webhook with an encoded path', async () => {
      const session = await signIn(browser, merchantUser, 'Integrations');
      const signingSecret = randomBytes(32).toString('hex');
      const url = `https://webhook.site/${receiverId}/phase-y/%E2%82%AC`;
      const response = await request.post(`${webOrigin}/api/v1/webhook-endpoints`, {
        headers: {
          Authorization: `Bearer ${session.token}`,
          'Idempotency-Key': `phase-y-endpoint-${randomUUID()}`,
        },
        data: {
          url,
          eventTypes: ['payment.completed.v1', 'refund.completed.v1'],
          signingSecret,
        },
      });
      expect(response.status()).toBe(201);
      const endpoint = await response.json();
      expect(endpoint.url).toBe(url);
      liveEndpoint = { id: endpoint.endpointId, token: session.token };
      await session.context.close();
      expect(await request.put(`https://webhook.site/token/${receiverId}`, {
        data: { default_status: 503 },
      })).toBeOK();
      return { id: endpoint.endpointId, signingSecret };
    });
  }

  let payerToken;
  const payment = await test.step('Person signs in, is isolated to their owner, and pays merchant', async () => {
    const session = await signIn(browser, personUser, 'Wallets');
    payerToken = session.token;
    await expect(session.page.locator('.identity')).toContainText('Personal account');
    expect((await request.get(`${webOrigin}/api/v1/operations/summary`, {
      headers: { Authorization: `Bearer ${session.token}` },
    })).status()).toBe(403);
    const me = await request.get(`${webOrigin}/api/v1/me`, {
      headers: {
        Authorization: `Bearer ${session.token}`,
        'X-LedgerX-Owner-Id': operator.merchant.ownerId,
      },
    });
    expect(me).toBeOK();
    expect((await me.json()).ownerId).toBe(operator.person.ownerId);
    await session.page.locator(`a[href="#/wallets/${operator.person.wallets[0].walletId}"]`).first().click();
    await expect(session.page.locator('#main-content')).toContainText('$25.00');
    await expect(session.page.locator('[data-form="top-up"]')).toHaveCount(0);
    await session.page.locator('[data-form="payment"]').click();
    await session.page.locator('#destination-wallet').fill(operator.merchant.wallets[0].walletId);
    await session.page.locator('#amount').fill('7.50');
    await session.page.getByRole('button', { name: 'Review', exact: true }).click();
    const brokerOutage = live && process.env.E2E_KAFKA_OUTAGE === '1';
    let brokerStopped = false;
    try {
      if (brokerOutage) {
        productionCompose('stop', 'kafka');
        brokerStopped = true;
      }
      const responsePromise = session.page.waitForResponse(response =>
        response.url().endsWith('/api/v1/payments') && response.request().method() === 'POST');
      await session.page.getByRole('button', { name: 'Confirm and submit' }).click();
      const response = await responsePromise;
      expect(response.status()).toBe(201);
      const result = await response.json();
      expect(result.paymentId).toMatch(uuid);
      await expect(session.page.locator('#main-content')).toContainText(result.paymentId);
      if (brokerOutage) {
        await expect.poll(() => Number(sql("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE status = 'PENDING';")), {
          timeout: 20_000,
        }).toBeGreaterThanOrEqual(1);
      }
      return result;
    } finally {
      if (brokerStopped) productionCompose('start', 'kafka');
      await session.context.close();
    }
  });

  const refund = await test.step('Merchant sees the payment and refunds through the browser', async () => {
    const session = await signIn(browser, merchantUser, 'Integrations');
    await expect(session.page.locator('.identity')).toContainText('Merchant account');
    if (webhook) {
      liveEndpoint.token = session.token;
      const deliveriesUrl = `${webOrigin}/api/v1/webhook-endpoints/${webhook.id}/deliveries`;
      await expect.poll(async () => {
        const response = await request.get(deliveriesUrl, {
          headers: { Authorization: `Bearer ${session.token}` },
        });
        expect(response).toBeOK();
        return (await response.json()).find(item => item.eventType === 'payment.completed.v1')?.lastHttpStatus;
      }, { timeout: 60_000 }).toBe(503);
      expect(await request.put(`https://webhook.site/token/${receiverId}`, {
        data: { default_status: 200 },
      })).toBeOK();
      await expect.poll(async () => {
        const response = await request.get(deliveriesUrl, {
          headers: { Authorization: `Bearer ${session.token}` },
        });
        return (await response.json()).find(item => item.eventType === 'payment.completed.v1')?.status;
      }, { timeout: 60_000 }).toBe('DELIVERED');
      expect(await request.put(`https://webhook.site/token/${receiverId}`, {
        data: { default_status: 503 },
      })).toBeOK();
    }
    await session.page.evaluate(id => { location.hash = `#/payments/${id}`; }, payment.paymentId);
    await expect(session.page.locator('#main-content')).toContainText(payment.paymentId);
    await session.page.locator('[data-form="refund"]').click();
    await session.page.locator('#amount').fill('2.50');
    await session.page.getByRole('button', { name: 'Review', exact: true }).click();
    const responsePromise = session.page.waitForResponse(response =>
      response.url().includes(`/api/v1/payments/${payment.paymentId}/refunds`) && response.request().method() === 'POST');
    await session.page.getByRole('button', { name: 'Confirm and submit' }).click();
    const response = await responsePromise;
    expect(response.status()).toBe(201);
    const result = await response.json();
    expect(result.refundId).toMatch(uuid);
    await expect(session.page.locator('#main-content')).toContainText(result.refundId);
    await session.context.close();
    return result;
  });

  await test.step('Kafka audit consumes both immutable events', async () => {
    await expect.poll(() => Number(sql('SELECT COUNT(*) FROM ledgerx.processed_events;')), {
      timeout: 60_000,
      intervals: [1000, 2000, 5000],
    }).toBeGreaterThanOrEqual(2);
    expect(Number(sql("SELECT COUNT(*) FROM ledgerx.outbox_events WHERE status = 'PUBLISHED';"))).toBeGreaterThanOrEqual(2);
    if (live && process.env.E2E_KAFKA_OUTAGE === '1') {
      await expect.poll(() => sql(
        `SELECT status FROM ledgerx.outbox_events WHERE aggregate_id = '${payment.paymentId}' AND event_type = 'payment.completed.v1';`,
      ), { timeout: 90_000 }).toBe('PUBLISHED');
    }
  });

  if (webhook) {
    await test.step('External receiver observes retry, terminal failure, replay, and valid signatures', async () => {
      const headers = { Authorization: `Bearer ${liveEndpoint.token}` };
      const deliveriesUrl = `${webOrigin}/api/v1/webhook-endpoints/${webhook.id}/deliveries`;
      let deliveries;
      await expect.poll(async () => {
        const response = await request.get(deliveriesUrl, { headers });
        expect(response).toBeOK();
        deliveries = await response.json();
        return deliveries.find(item => item.eventType === 'refund.completed.v1')?.status;
      }, { timeout: 90_000 }).toBe('DEAD');
      expect(deliveries).toHaveLength(2);
      const paymentDelivery = deliveries.find(item => item.eventType === 'payment.completed.v1');
      const refundDelivery = deliveries.find(item => item.eventType === 'refund.completed.v1');
      expect(paymentDelivery.status).toBe('DELIVERED');
      expect(paymentDelivery.attemptCount).toBeGreaterThanOrEqual(2);
      expect(refundDelivery.attemptCount).toBe(3);

      expect(await request.put(`https://webhook.site/token/${receiverId}`, {
        data: { default_status: 200 },
      })).toBeOK();
      const replay = await request.post(`${deliveriesUrl}/${refundDelivery.deliveryId}/replay`, { headers });
      expect(replay.status()).toBe(202);
      await expect.poll(async () => {
        const response = await request.get(deliveriesUrl, { headers });
        const current = (await response.json()).find(item => item.deliveryId === refundDelivery.deliveryId);
        return current?.replayCount === 1 ? current.status : undefined;
      }, { timeout: 60_000 }).toBe('DELIVERED');

      const attemptsResponse = await request.get(
        `${deliveriesUrl}/${paymentDelivery.deliveryId}/attempts`, { headers });
      expect(attemptsResponse).toBeOK();
      const attempts = await attemptsResponse.json();
      expect(attempts.some(item => item.httpStatus === 503 && item.outcome === 'RETRYABLE_FAILURE')).toBe(true);
      expect(attempts.some(item => item.httpStatus === 200 && item.outcome === 'DELIVERED')).toBe(true);

      let captured;
      await expect.poll(async () => {
        const response = await request.get(`https://webhook.site/token/${receiverId}/requests?per_page=50`);
        expect(response).toBeOK();
        captured = (await response.json()).data.filter(item =>
          Object.keys(item.headers ?? {}).some(key => key.toLowerCase() === 'ledgerx-event-id'));
        return captured.length;
      }, { timeout: 60_000 }).toBeGreaterThanOrEqual(6);
      const eventTypes = new Set();
      for (const item of captured) {
        const received = Object.fromEntries(Object.entries(item.headers).map(([name, value]) =>
          [name.toLowerCase(), Array.isArray(value) ? value[0] : value]));
        const expectedSignature = 'v1=' + createHmac('sha256', webhook.signingSecret)
          .update(`${received['ledgerx-timestamp']}.${item.content}`).digest('hex');
        expect(received['ledgerx-signature']).toBe(expectedSignature);
        expect(received['ledgerx-delivery-id']).toMatch(uuid);
        eventTypes.add(received['ledgerx-event-type']);
      }
      expect(eventTypes).toEqual(new Set(['payment.completed.v1', 'refund.completed.v1']));
      expect(captured.some(item => item.content.includes(payment.paymentId))).toBe(true);
      expect(captured.some(item => item.content.includes(refund.refundId))).toBe(true);
    });
    const disabled = await request.post(
      `${webOrigin}/api/v1/webhook-endpoints/${webhook.id}/disable`, {
        headers: { Authorization: `Bearer ${liveEndpoint.token}` },
      });
    expect(disabled).toBeOK();
    liveEndpoint = undefined;
  }

  if (live) {
    await test.step('Small concurrent payment smoke preserves idempotency and posting counts', async () => {
      const before = Number(sql('SELECT COUNT(*) FROM ledgerx.payments;'));
      const body = {
        payerWalletId: operator.person.wallets[0].walletId,
        merchantWalletId: operator.merchant.wallets[0].walletId,
        money: { amount: '0.10', currency: 'USD' },
      };
      const calls = Array.from({ length: 5 }, (_, index) => ({
        key: `phase-y-smoke-${randomUUID()}-${index}`,
      }));
      const results = await Promise.all(calls.map(async call => {
        const started = Date.now();
        const response = await request.post(`${webOrigin}/api/v1/payments`, {
          headers: {
            Authorization: `Bearer ${payerToken}`,
            'Idempotency-Key': call.key,
          },
          data: body,
        });
        return { status: response.status(), body: await response.json(), durationMs: Date.now() - started, key: call.key };
      }));
      expect(results.map(result => result.status)).toEqual([201, 201, 201, 201, 201]);
      expect(new Set(results.map(result => result.body.paymentId)).size).toBe(5);

      const replayed = await Promise.all(Array.from({ length: 5 }, async () => {
        const response = await request.post(`${webOrigin}/api/v1/payments`, {
          headers: {
            Authorization: `Bearer ${payerToken}`,
            'Idempotency-Key': results[0].key,
          },
          data: body,
        });
        return { status: response.status(), id: (await response.json()).paymentId };
      }));
      expect(replayed.every(item => item.status === 200 && item.id === results[0].body.paymentId)).toBe(true);
      expect(Number(sql('SELECT COUNT(*) FROM ledgerx.payments;'))).toBe(before + 5);
      const durations = results.map(result => result.durationMs).sort((a, b) => a - b);
      console.log(`Phase Y concurrent payments: 5 created, 5 replays, median=${durations[2]}ms, max=${durations[4]}ms`);
    });
  }

  if (live && process.env.E2E_EXPIRED_TOKEN === '1') {
    await test.step('A real access token is rejected after the validator clock skew', async () => {
      const claims = JSON.parse(Buffer.from(payerToken.split('.')[1], 'base64url').toString('utf8'));
      expect(Number.isSafeInteger(claims.exp)).toBe(true);
      const firstWaitMs = Math.max(0, claims.exp * 1000 - Date.now() + 1_500);
      expect(firstWaitMs).toBeLessThan(600_000);
      await new Promise(resolve => setTimeout(resolve, firstWaitMs));
      expect((await request.get(`${webOrigin}/api/v1/me`, {
        headers: { Authorization: `Bearer ${payerToken}` },
      })).status()).toBe(200);
      const finalWaitMs = Math.max(0, claims.exp * 1000 - Date.now() + 65_000);
      await new Promise(resolve => setTimeout(resolve, finalWaitMs));
      expect((await request.get(`${webOrigin}/api/v1/me`, {
        headers: { Authorization: `Bearer ${payerToken}` },
      })).status()).toBe(401);
    });
  }
});

test('public workbench does not grant access from an owner header or malformed bearer token', async ({ browser, request }) => {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto(webOrigin);
    await expect(page.getByRole('heading', { name: 'Open your workbench' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Workbench sections' })).toHaveCount(0);

    const url = `${webOrigin}/api/v1/me`;
    expect((await request.get(url, {
      headers: { 'X-LedgerX-Owner-Id': randomUUID() },
    })).status()).toBe(401);
    expect((await request.get(url, {
      headers: { Authorization: 'Bearer malformed.token.value' },
    })).status()).toBe(401);
  } finally {
    await context.close();
  }
});
