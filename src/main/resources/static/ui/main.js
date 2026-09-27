// @ts-check

import { api, ApiError } from './api.js';
import { getSession, initializeAuth, setDevelopmentOwner, signIn, signOut } from './auth.js';

/** @typedef {{walletId:string,currency:string,status:string,balance:{amount:string,currency:string}}} Wallet */
/** @typedef {{ownerId:string,ownerType:'PERSON'|'MERCHANT',status:string,wallets:Wallet[]}} Workspace */
/** @typedef {{kind:'TRANSFER'|'PAYMENT'|'REFUND'|'TOP_UP',id:string,sourceWalletId:string|null,destinationWalletId:string,amount:string,currency:string,occurredAt:string,ledgerTransactionId:string,direction:'IN'|'OUT'}} Activity */
/** @typedef {{paymentId:string,payerWalletId:string,merchantWalletId:string,money:{amount:string,currency:string},status:string,refundedMoney:{amount:string,currency:string},remainingRefundableMoney:{amount:string,currency:string},ledgerTransactionId:string,completedAt:string}} Payment */
/** @typedef {{decisionId:string,caseId:string|null,outcome:'ALLOW'|'REVIEW'|'BLOCK',caseStatus:string|null,expiresAt:string|null}} RiskCase */
/** @typedef {{endpointId:string,url:string,eventTypes:string[],status:string,secretKeyVersion:number,createdAt:string,updatedAt:string,disabledAt:string|null}} Endpoint */
/** @typedef {{deliveryId:string,eventId:string,eventType:string,status:string,attemptCount:number,replayCount:number,queuedAt:string,nextAttemptAt:string,deliveredAt:string|null,lastHttpStatus:number|null,lastErrorCategory:string|null}} Delivery */
/** @typedef {{attemptId:string,replayCount:number,attemptNumber:number,startedAt:string,completedAt:string,outcome:string,httpStatus:number|null,durationMillis:number,errorCategory:string|null}} DeliveryAttempt */

const app = /** @type {HTMLElement} */ (document.getElementById('app'));
if (!app) throw new Error('Workbench root is missing.');

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const EVENTS = ['payment.completed.v1', 'refund.completed.v1'];
const state = {
  ready: false, bootError: '', loading: false, context: 'wallets',
  /** @type {Workspace|null} */ me: null,
  /** @type {Activity[]} */ activity: [],
  /** @type {Activity[]} */ walletActivity: [],
  activityPage: 0, walletPage: 0,
  activityHasMore: true, walletHasMore: true,
  /** @type {Endpoint[]} */ endpoints: [],
  /** @type {Delivery[]} */ deliveries: [],
  /** @type {DeliveryAttempt[]} */ attempts: [],
  attemptsError: '',
  attemptsPage: 0, attemptsHasMore: true,
  /** @type {unknown} */ record: null,
  /** @type {Payment|null} */ payment: null,
  /** @type {RiskCase|null} */ riskCase: null,
  /** @type {unknown[]} */ refunds: [],
  /** @type {unknown} */ journal: null,
  /** @type {unknown[]} */ owners: [],
  ownersPage: 0, ownersHasMore: true,
  /** @type {unknown[]} */ runs: [],
  /** @type {unknown[]} */ findings: [],
  /** @type {unknown} */ summary: null,
  /** @type {any} */ riskPolicy: null,
  /** @type {any[]} */ riskCases: [],
  /** @type {any[]} */ riskActions: [],
  /** @type {{kind:string,id?:string,parentId?:string}|null} */ selection: null,
  action: '', review: false, uncertain: false,
  busy: false,
  /** @type {Record<string,string>} */ draft: {},
  /** @type {{type:string,id:string,parentId?:string}|null} */ confirm: null,
  /** @type {{kind:'success'|'error'|'warning'|'info',message:string}|null} */ notice: null,
  /** @type {{endpointId:string,secret:string}|null} */ oneTimeSecret: null,
  routeEpoch: 0,
  routeError: '',
};

/** @param {unknown} value */
function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[char] || char);
}
/** @param {string|undefined|null} id */
function shortId(id) { return id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '—'; }
/** @param {Activity} item */
function activityPath(item) { return item.kind === 'TOP_UP' ? `ledger-transactions/${item.ledgerTransactionId}` : `${item.kind.toLowerCase()}s/${item.id}`; }
/** @param {Activity} item */
function activityLabel(item) { return item.kind === 'TOP_UP' ? 'demo top-up' : item.kind.toLowerCase(); }
/** @param {string|undefined|null} value */
function date(value) { return value ? new Intl.DateTimeFormat(undefined, { dateStyle:'medium', timeStyle:'short' }).format(new Date(value)) : '—'; }
/** @param {string|undefined|null} amount */
function money(amount) {
  if (amount == null) return '—';
  const [whole, fraction = ''] = amount.split('.');
  const negative = whole.startsWith('-');
  const digits = negative ? whole.slice(1) : whole;
  if (!/^\d+$/.test(digits) || !/^\d{0,2}$/.test(fraction)) return amount;
  return `${negative ? '−' : ''}${new Intl.NumberFormat(undefined).format(BigInt(digits))}.${fraction.padEnd(2,'0')}`;
}
/** @param {string} amount */
function cents(amount) {
  const [whole, fraction = ''] = amount.split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2,'0'));
}
/** @param {string} status */
function badge(status) {
  const tone = ['ACTIVE','COMPLETED','DELIVERED','REFUNDED','CONSUMED','APPROVED'].includes(status) ? 'success'
    : ['DEAD','FAILED','CLOSED','SUSPENDED','DISABLED','BLOCK','DECLINED','POLICY_BLOCKED','EXPIRED'].includes(status) ? 'danger'
    : ['PENDING','IN_FLIGHT','PARTIALLY_REFUNDED','RUNNING','OPEN','REVIEW'].includes(status) ? 'warning' : '';
  return `<span class="badge ${tone}">${esc(status.replaceAll('_',' ').toLowerCase())}</span>`;
}
/** @param {string} value */
function validUuid(value) { return UUID.test(value.trim()); }
/** @param {string} value */
function validAmount(value) {
  const amount = value.trim();
  if (!/^(?:0|[1-9]\d{0,16})(?:\.\d{1,2})?$/.test(amount)) return false;
  const [whole, fraction = ''] = amount.split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2,'0')) > 0n;
}
/** @param {string} value */
function normalizedAmount(value) {
  const [whole, fraction = ''] = value.trim().split('.');
  return `${whole}.${fraction.padEnd(2,'0')}`;
}
/** @param {string} kind @param {string} message */
function notify(kind, message) {
  state.notice = { kind: /** @type {'success'|'error'|'warning'|'info'} */ (kind), message };
  render();
}
/** @param {unknown} error */
function messageOf(error) {
  if (error instanceof ApiError) {
    const fields = error.details.map(detail => `${detail.field}: ${detail.message}`).join(' · ');
    return fields ? `${error.message} ${fields}` : error.message;
  }
  return error instanceof Error ? error.message : 'An unexpected error occurred.';
}

async function boot() {
  try {
    await initializeAuth();
    state.ready = true;
    render();
    if (getSession().signedIn) await loadWorkspace();
  } catch (error) {
    state.ready = true;
    state.bootError = messageOf(error);
    render();
  }
}

async function loadWorkspace() {
  state.loading = true;
  render();
  const session = getSession();
  try {
    if (session.ownerId) {
      try {
        state.me = await api('/me');
        state.activity = await api('/activity?limit=50&page=0');
        state.activityPage = 0;
        state.activityHasMore = state.activity.length === 50;
        if (state.me?.ownerType === 'MERCHANT') state.endpoints = await api('/webhook-endpoints');
      } catch (error) {
        if (!session.operator) throw error;
        state.me = null;
      }
    }
    if (session.operator) await loadOperations();
    if (!state.me && !session.operator) {
      state.bootError = 'This sign-in is not linked to a LedgerX wallet owner.';
    } else {
      state.bootError = '';
      await loadRoute();
    }
  } catch (error) {
    state.bootError = messageOf(error);
  } finally {
    state.loading = false;
    render();
  }
}

async function loadOperations() {
  const [summary, owners, runs, riskPolicy, riskCases] = await Promise.all([
    api('/operations/summary'), api('/operations/owners?limit=50&page=0'),
    api('/operations/reconciliation-runs?limit=50'), api('/operations/risk/policy'),
    api('/operations/risk/cases?limit=50'),
  ]);
  state.summary = summary;
  state.owners = /** @type {unknown[]} */ (owners);
  state.ownersPage = 0;
  state.ownersHasMore = state.owners.length === 50;
  state.runs = /** @type {unknown[]} */ (runs);
  state.riskPolicy = riskPolicy;
  state.riskCases = /** @type {any[]} */ (riskCases);
}

function route() {
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  const first = parts[0] || (state.me ? 'wallets' : 'operations');
  if (first === 'wallets') return { context:'wallets', selection: parts[1] ? { kind:'wallet', id:parts[1] } : null };
  if (first === 'activity') return { context:'activity', selection:null };
  if (['transfers','payments','refunds','ledger-transactions'].includes(first))
    return { context:'activity', selection: parts[1] ? { kind:first.slice(0,-1), id:parts[1] } : null };
  if (first === 'payment-risk-cases') return { context:'activity', selection: parts[1] ? { kind:'risk-case', id:parts[1] } : null };
  if (first === 'webhooks') return { context:'integrations', selection: parts[1] ? { kind:'webhook', id:parts[1] } : null };
  if (first === 'deliveries') return { context:'integrations', selection: parts[1] && parts[2] ? { kind:'delivery', parentId:parts[1], id:parts[2] } : null };
  if (first === 'operations') {
    if (parts[1] === 'risk' && parts[2]) return { context:'operations', selection:{ kind:'operator-risk-case', id:parts[2] } };
    if (parts[1] === 'owners' && parts[2]) return { context:'operations', selection:{ kind:'owner', id:parts[2] } };
    if (parts[1] === 'reconciliation' && parts[2]) return { context:'operations', selection:{ kind:'reconciliation', id:parts[2] } };
    if (parts[1] === 'ledger-transactions' && parts[2]) return { context:'operations', selection:{ kind:'operator-journal', id:parts[2] } };
    return { context:'operations', selection:null };
  }
  return { context:'wallets', selection:null };
}

async function loadRoute() {
  if (!getSession().signedIn) return;
  const epoch = ++state.routeEpoch;
  const next = route();
  state.context = next.context;
  state.selection = next.selection;
  state.action = '';
  state.review = false;
  state.uncertain = false;
  state.confirm = null;
  if (state.oneTimeSecret && state.oneTimeSecret.endpointId !== next.selection?.id) state.oneTimeSecret = null;
  state.record = null;
  state.routeError = '';
  state.payment = null;
  state.riskCase = null;
  state.refunds = [];
  state.journal = null;
  state.findings = [];
  state.riskActions = [];
  state.attempts = [];
  state.attemptsError = '';
  render();
  try {
    const selected = next.selection;
    if (!selected) return;
    if (!selected.id || !validUuid(selected.id) || (selected.parentId && !validUuid(selected.parentId))) {
      throw new Error('Enter a valid record UUID.');
    }
    if (selected.kind === 'wallet') {
      if (!state.me?.wallets.some(wallet => wallet.walletId === selected.id)) throw new Error('Wallet was not found.');
      state.walletActivity = await api(`/activity?walletId=${encodeURIComponent(selected.id || '')}&limit=50&page=0`);
      state.walletPage = 0;
      state.walletHasMore = state.walletActivity.length === 50;
    } else if (selected.kind === 'transfer') {
      state.record = await api(`/transfers/${selected.id}`);
    } else if (selected.kind === 'payment') {
      state.payment = await api(`/payments/${selected.id}`);
      state.record = state.payment;
      state.refunds = await api(`/payments/${selected.id}/refunds`);
    } else if (selected.kind === 'risk-case') {
      state.riskCase = await api(`/payment-risk-cases/${selected.id}`);
      state.record = state.riskCase;
    } else if (selected.kind === 'refund') {
      state.record = await api(`/refunds/${selected.id}`);
    } else if (selected.kind === 'ledger-transaction') {
      state.journal = await api(`/ledger-transactions/${selected.id}`);
    } else if (selected.kind === 'webhook') {
      state.record = await api(`/webhook-endpoints/${selected.id}`);
      state.deliveries = await api(`/webhook-endpoints/${selected.id}/deliveries?limit=100`);
    } else if (selected.kind === 'delivery') {
      state.record = await api(`/webhook-endpoints/${selected.parentId}/deliveries/${selected.id}`);
      try {
        state.attempts = await api(`/webhook-endpoints/${selected.parentId}/deliveries/${selected.id}/attempts?limit=100&page=0`);
        state.attemptsPage = 0;
        state.attemptsHasMore = state.attempts.length === 100;
      } catch (error) {
        state.attemptsError = messageOf(error);
      }
    } else if (selected.kind === 'owner') {
      state.record = await api(`/operations/owners/${selected.id}`);
    } else if (selected.kind === 'reconciliation') {
      state.findings = await api(`/operations/reconciliation-runs/${selected.id}/findings`);
    } else if (selected.kind === 'operator-journal') {
      state.journal = await api(`/operations/ledger-transactions/${selected.id}`);
    } else if (selected.kind === 'operator-risk-case') {
      state.record = await api(`/operations/risk/cases/${selected.id}`);
      state.riskActions = await api(`/operations/risk/cases/${selected.id}/actions`);
    }
    if (epoch !== state.routeEpoch) return;
  } catch (error) {
    if (epoch !== state.routeEpoch) return;
    state.record = null;
    state.routeError = messageOf(error);
  } finally {
    if (epoch === state.routeEpoch) render();
  }
}

/** @param {string} path */
function navigate(path) { location.hash = `#/${path}`; }

let priorHash = location.hash;
window.addEventListener('hashchange', () => {
  if (state.uncertain && location.hash !== priorHash && !window.confirm('This request may have completed. Leaving will discard the safe retry. Leave anyway?')) {
    history.replaceState({}, '', `/${priorHash}`);
    return;
  }
  priorHash = location.hash;
  if (getSession().signedIn) void loadRoute();
});
window.addEventListener('beforeunload', event => {
  if (state.uncertain) { event.preventDefault(); event.returnValue = ''; }
});
void boot();

function render() {
  if (!state.ready) return;
  const session = getSession();
  if (!session.signedIn) {
    app.innerHTML = `
      <div class="sign-in">
        <div class="brand"><span class="brand-mark">LX</span> LedgerX</div>
        <h1>Open your workbench</h1>
        <p>${session.mode === 'development' ? 'Enter a prepared wallet owner UUID for local development.' : 'Sign in with your LedgerX account to continue.'}</p>
        ${state.bootError ? `<div class="notice error" role="alert">${esc(state.bootError)}</div>` : ''}
        ${session.mode === 'development' ? `
          <form id="dev-auth" class="form">
            <div class="field"><label for="owner-id">Owner UUID</label><input id="owner-id" name="ownerId" type="text" inputmode="text" autocomplete="off" required placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" /></div>
            <button class="btn btn-primary" type="submit">Open workbench</button>
          </form>
          <p class="small">Local mode uses the development owner header. It is not authentication.</p>
        ` : `<button class="btn btn-primary" type="button" data-action="signin">Sign in</button>`}
      </div>`;
    return;
  }
  if (state.bootError && !state.me && !session.operator) {
    app.innerHTML = `<div class="sign-in"><div class="brand"><span class="brand-mark">LX</span> LedgerX</div><h1>Workbench unavailable</h1><div class="notice error" role="alert">${esc(state.bootError)}</div><div class="toolbar"><button class="btn" data-action="reload">Retry</button><button class="btn" data-action="signout">Sign out</button></div></div>`;
    return;
  }
  const selected = state.selection;
  const mobileDetail = Boolean(selected) || state.context === 'activity' || state.context === 'operations';
  const ownerText = state.me ? `${state.me.ownerType === 'MERCHANT' ? 'Merchant' : 'Personal'} account` : 'Operator';
  app.innerHTML = `
    <div class="app">
      <header class="topbar">
        <a class="brand" href="#/${state.me ? 'wallets' : 'operations'}" aria-label="LedgerX home"><span class="brand-mark">LX</span> LedgerX</a>
        <form class="search" id="record-search" role="search" aria-label="Open a record">
          <select name="kind" aria-label="Record type">
            ${state.me ? `<option value="transfers">Transfer</option><option value="payments">Payment</option>
            <option value="refunds">Refund</option><option value="ledger-transactions">Journal</option><option value="payment-risk-cases">Payment review</option>
            ${state.me.ownerType === 'MERCHANT' ? '<option value="webhooks">Webhook</option>' : ''}` : ''}
            ${session.operator ? '<option value="operations/owners">Owner</option><option value="operations/reconciliation">Reconciliation</option><option value="operations/ledger-transactions">Operator journal</option>' : ''}
          </select>
          <input name="id" aria-label="Record UUID" type="search" placeholder="Open record by ID" autocomplete="off" required />
          <button class="btn btn-primary" type="submit">Open</button>
        </form>
        <div class="topbar-right"><div class="identity"><strong>${esc(ownerText)}</strong><span class="mono">${esc(shortId(state.me?.ownerId || session.ownerId))}</span></div><button class="btn btn-sm" type="button" data-action="signout">Sign out</button></div>
      </header>
      <nav class="nav-row" aria-label="Workbench sections">
        ${state.me ? `<a class="nav-link ${state.context === 'wallets' ? 'active' : ''}" href="#/wallets">Wallets</a><a class="nav-link ${state.context === 'activity' ? 'active' : ''}" href="#/activity">Activity</a>` : ''}
        ${state.me?.ownerType === 'MERCHANT' ? `<a class="nav-link ${state.context === 'integrations' ? 'active' : ''}" href="#/webhooks">Integrations</a>` : ''}
        ${session.operator ? `<a class="nav-link ${state.context === 'operations' ? 'active' : ''}" href="#/operations">Operations</a>` : ''}
      </nav>
      <div class="workspace ${mobileDetail ? 'mobile-detail' : ''}">
        <aside class="pane pane-list" aria-label="Context list">${renderList()}</aside>
        <main class="pane pane-main" id="main-content" tabindex="-1">${renderMain()}</main>
        <aside class="pane pane-inspector" aria-label="Actions and details">${renderInspector()}</aside>
      </div>
    </div>
    ${state.notice ? `<div class="notice ${state.notice.kind} toast" role="${state.notice.kind === 'error' ? 'alert' : 'status'}">${esc(state.notice.message)} <button class="btn btn-quiet btn-sm" data-action="dismiss-notice" aria-label="Dismiss message">Dismiss</button></div>` : ''}`;
}

function renderList() {
  let title = '';
  let items = '';
  if (state.context === 'wallets') {
    title = 'Your wallets';
    items = state.me?.wallets.length ? state.me.wallets.map(wallet => `
      <a class="list-item ${state.selection?.id === wallet.walletId ? 'selected' : ''}" href="#/wallets/${esc(wallet.walletId)}">
        <span class="list-item-title">USD wallet ${badge(wallet.status)}</span>
        <span class="list-item-sub mono">${esc(wallet.walletId)}</span>
        <span class="list-item-meta">$${esc(money(wallet.balance.amount))} available</span>
      </a>`).join('') : `<div class="empty"><strong>No wallet yet</strong>Your account has no wallet.</div>`;
  } else if (state.context === 'activity') {
    title = 'Recent records';
    items = state.activity.length ? state.activity.map(item => `
      <a class="list-item ${state.selection?.id === (item.kind === 'TOP_UP' ? item.ledgerTransactionId : item.id) ? 'selected' : ''}" href="#/${esc(activityPath(item))}">
        <span class="list-item-title">${esc(activityLabel(item))} <span class="muted">${item.direction === 'IN' ? '+' : '−'}$${esc(money(item.amount))}</span></span>
        <span class="list-item-sub">${esc(date(item.occurredAt))}</span>
        <span class="list-item-meta mono">${esc(shortId(item.id))}</span>
      </a>`).join('') : `<div class="empty"><strong>No activity</strong>Completed records will appear here.</div>`;
  } else if (state.context === 'integrations') {
    title = 'Webhook endpoints';
    items = state.endpoints.length ? state.endpoints.map(endpoint => `
      <a class="list-item ${state.selection?.id === endpoint.endpointId || state.selection?.parentId === endpoint.endpointId ? 'selected' : ''}" href="#/webhooks/${esc(endpoint.endpointId)}">
        <span class="list-item-title">Endpoint ${badge(endpoint.status)}</span>
        <span class="list-item-sub">${esc(endpoint.url)}</span>
        <span class="list-item-meta mono">${esc(shortId(endpoint.endpointId))}</span>
      </a>`).join('') : `<div class="empty"><strong>No endpoints</strong>Add an endpoint to receive payment events.</div>`;
  } else {
    title = 'Operations';
    items = `<div class="section-label">Owners</div>${state.owners.length ? state.owners.map(/** @param {any} owner */ owner => `
      <a class="list-item ${state.selection?.id === owner.ownerId ? 'selected' : ''}" href="#/operations/owners/${esc(owner.ownerId)}">
        <span class="list-item-title">${esc(owner.ownerType.toLowerCase())} ${badge(owner.status)}</span>
        <span class="list-item-sub mono">${esc(owner.ownerId)}</span>
      </a>`).join('') : '<p class="muted small">No owners.</p>'}
      ${state.ownersHasMore ? '<button class="btn btn-sm" type="button" data-action="load-more" data-scope="owners">Load more owners</button>' : ''}
      <div class="section-label">Risk review</div>${state.riskCases.length ? state.riskCases.map(item => `
      <a class="list-item ${state.selection?.id === item.caseId ? 'selected' : ''}" href="#/operations/risk/${esc(item.caseId)}"><span class="list-item-title">Case ${badge(item.status)}</span><span class="list-item-sub mono">${esc(shortId(item.caseId))}</span><span class="list-item-meta">${esc(date(item.createdAt))}</span></a>`).join('') : '<p class="muted small">No review cases.</p>'}
      <div class="section-label">Reconciliation</div>${state.runs.length ? state.runs.map(/** @param {any} run */ run => `
      <a class="list-item ${state.selection?.id === run.runId ? 'selected' : ''}" href="#/operations/reconciliation/${esc(run.runId)}">
        <span class="list-item-title">${esc(date(run.startedAt))}</span>
        <span class="list-item-sub">${esc(run.findingCount)} findings · ${esc(run.status.toLowerCase())}</span>
      </a>`).join('') : '<p class="muted small">No reconciliation runs.</p>'}`;
  }
  return `<div class="pane-header"><h2>${title}</h2></div><div class="pane-body">${items}</div>`;
}

/** @param {string} title @param {string} body @param {string} [action] */
function pane(title, body, action = '') {
  return `<div class="pane-header"><h1>${esc(title)}</h1>${action}</div><div class="pane-body">${body}</div>`;
}
function backButton() {
  const target = state.context === 'integrations' ? 'webhooks' : state.context === 'operations' ? 'operations' : state.context === 'activity' ? 'activity' : 'wallets';
  return `<a class="btn btn-sm mobile-back" href="#/${target}">Back</a>`;
}
function renderMain() {
  if (state.loading) return pane('Loading', '<div class="loading" role="status">Loading your workbench…</div>');
  if (state.context === 'wallets') return renderWalletMain();
  if (state.context === 'activity') return renderActivityMain();
  if (state.context === 'integrations') return renderIntegrationMain();
  return renderOperationsMain();
}

function renderWalletMain() {
  const wallet = state.me?.wallets.find(item => item.walletId === state.selection?.id);
  if (!wallet) return pane('Wallets', '<div class="empty"><strong>Select a wallet</strong>Choose an owned wallet to see its balance and activity.</div>');
  return pane('Wallet', `
    <div class="record-head"><div><div class="eyebrow">USD wallet</div><h2>Available balance</h2><div class="record-sub mono">${esc(wallet.walletId)}</div></div>${badge(wallet.status)}</div>
    <div class="balance">$${esc(money(wallet.balance.amount))}<span class="currency">USD</span></div>
    ${state.me?.status !== 'ACTIVE' ? '<div class="notice warning">This owner is suspended. Money movement is unavailable.</div>' : ''}
    <div class="hint-box">Balances are derived from the immutable ledger. ${getSession().mode === 'development' ? 'Use Add demo money to test local flows; no real funds are involved.' : 'Production funding is managed outside this application.'}</div>
    <hr class="divider" />
    <div class="record-head"><h2>Wallet activity</h2></div>
    ${state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : activityTable(state.walletActivity, 'wallet')}
  `, backButton());
}

/** @param {Activity[]} items @param {'wallet'|'all'} scope */
function activityTable(items, scope) {
  if (!items.length) return `<div class="empty"><strong>No completed activity</strong>Transfers, payments, refunds, and local top-ups will appear here.</div>`;
  return `<div class="table-wrap"><table class="table"><thead><tr><th>When</th><th>Type</th><th>Record</th><th class="numeric">Amount</th></tr></thead><tbody>
    ${items.map(item => `<tr><td>${esc(date(item.occurredAt))}</td><td>${esc(activityLabel(item))}</td><td><a class="row-action mono" href="#/${esc(activityPath(item))}">${esc(shortId(item.id))}</a></td><td class="numeric">${item.direction === 'IN' ? '+' : '−'}$${esc(money(item.amount))}</td></tr>`).join('')}
    </tbody></table></div>
    ${(scope === 'wallet' ? state.walletHasMore : state.activityHasMore) ? `<button class="btn btn-sm" type="button" data-action="load-more" data-scope="${scope}">Load more</button>` : ''}`;
}

function renderActivityMain() {
  const selected = state.selection;
  if (!selected) return pane('Activity', `<p class="muted">Completed transfers, payments, refunds, and local top-ups for your wallets.</p>${activityTable(state.activity, 'all')}`);
  if (selected.kind === 'ledger-transaction') return pane('Journal', renderJournal(), backButton());
  if (!state.record) return pane('Record', state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : '<div class="loading" role="status">Loading record…</div>', backButton());
  if (selected.kind === 'risk-case') {
    const risk = /** @type {RiskCase} */ (state.record);
    return pane('Payment review', `<div class="record-head"><div><div class="eyebrow">Risk decision</div><h2>${esc(risk.outcome === 'BLOCK' ? 'Payment blocked' : 'Payment needs review')}</h2><div class="record-sub mono">${esc(risk.caseId || risk.decisionId)}</div></div>${badge(risk.caseStatus || risk.outcome)}</div><dl class="meta-grid">${recordField('Decision ID',risk.decisionId,true)}${recordField('Case ID',risk.caseId || '—',true)}${recordField('Expires',date(risk.expiresAt))}</dl><div class="hint-box">No money moves while a payment is under review. After approval, only the original payer can retry the exact request.</div>`, backButton());
  }
  if (selected.kind === 'transfer') return pane('Transfer', renderTransfer(), backButton());
  if (selected.kind === 'payment') return pane('Payment', renderPayment(), backButton());
  if (selected.kind === 'refund') return pane('Refund', renderRefund(), backButton());
  return pane('Activity', activityTable(state.activity, 'all'));
}

/** @param {string} label @param {string} value @param {boolean} [mono] */
function recordField(label, value, mono = false) {
  return `<div><dt>${esc(label)}</dt><dd class="${mono ? 'mono' : ''}">${esc(value)}</dd></div>`;
}
/** @param {string} id */
function journalLink(id) { return `<a class="mono" href="#/ledger-transactions/${esc(id)}">${esc(id)}</a>`; }

function renderTransfer() {
  const transfer = /** @type {any} */ (state.record);
  return `<div class="record-head"><div><div class="eyebrow">Transfer</div><h2>$${esc(money(transfer.money.amount))} USD</h2><div class="record-sub">Completed ${esc(date(transfer.completedAt))}</div></div>${badge(transfer.status)}</div>
    <dl class="meta-grid">${recordField('From wallet',transfer.sourceWalletId,true)}${recordField('To wallet',transfer.destinationWalletId,true)}${recordField('Transfer ID',transfer.transferId,true)}<div><dt>Journal</dt><dd>${journalLink(transfer.ledgerTransactionId)}</dd></div></dl>`;
}
function renderPayment() {
  const payment = state.payment;
  if (!payment) return '<div class="loading">Loading payment…</div>';
  return `<div class="record-head"><div><div class="eyebrow">Merchant payment</div><h2>$${esc(money(payment.money.amount))} USD</h2><div class="record-sub">Completed ${esc(date(payment.completedAt))}</div></div>${badge(payment.status)}</div>
    <dl class="meta-grid">${recordField('Payer wallet',payment.payerWalletId,true)}${recordField('Merchant wallet',payment.merchantWalletId,true)}${recordField('Payment ID',payment.paymentId,true)}<div><dt>Journal</dt><dd>${journalLink(payment.ledgerTransactionId)}</dd></div>${recordField('Refunded',`$${money(payment.refundedMoney.amount)} USD`)}${recordField('Remaining refundable',`$${money(payment.remainingRefundableMoney.amount)} USD`)}</dl>
    <hr class="divider" /><h2>Refunds</h2>
    ${state.refunds.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Completed</th><th>Refund</th><th class="numeric">Amount</th></tr></thead><tbody>${state.refunds.map(/** @param {any} refund */ refund => `<tr><td>${esc(date(refund.completedAt))}</td><td><a class="row-action mono" href="#/refunds/${esc(refund.refundId)}">${esc(shortId(refund.refundId))}</a></td><td class="numeric">$${esc(money(refund.money.amount))}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty"><strong>No refunds</strong>This payment has not been refunded.</div>'}`;
}
function renderRefund() {
  const refund = /** @type {any} */ (state.record);
  return `<div class="record-head"><div><div class="eyebrow">Refund</div><h2>$${esc(money(refund.money.amount))} USD</h2><div class="record-sub">Completed ${esc(date(refund.completedAt))}</div></div>${badge('COMPLETED')}</div>
    <dl class="meta-grid">${recordField('From merchant',refund.merchantWalletId,true)}${recordField('To payer',refund.payerWalletId,true)}${recordField('Refund ID',refund.refundId,true)}<div><dt>Payment</dt><dd><a class="mono" href="#/payments/${esc(refund.paymentId)}">${esc(refund.paymentId)}</a></dd></div><div><dt>Journal</dt><dd>${journalLink(refund.ledgerTransactionId)}</dd></div></dl>`;
}
function renderJournal() {
  const journal = /** @type {any} */ (state.journal);
  if (!journal) return state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : '<div class="loading" role="status">Loading journal…</div>';
  return `<div class="record-head"><div><div class="eyebrow">Immutable journal</div><h2>${esc(journal.description)}</h2><div class="record-sub">Posted ${esc(date(journal.postedAt))}</div></div>${badge('COMPLETED')}</div>
    <dl class="meta-grid">${recordField('Journal ID',journal.transactionId,true)}${recordField('Currency',journal.currency)}</dl>
    <div class="table-wrap"><table class="table"><thead><tr><th>Line</th><th>Account</th><th>Side</th><th class="numeric">Amount</th></tr></thead><tbody>${journal.entries.map(/** @param {any} entry */ entry => `<tr><td>${esc(entry.lineNumber)}</td><td class="mono">${esc(shortId(entry.walletOrAccountId))}</td><td>${esc(entry.side.toLowerCase())}</td><td class="numeric">$${esc(money(entry.amount))}</td></tr>`).join('')}</tbody></table></div>`;
}

function renderIntegrationMain() {
  const selected = state.selection;
  if (!selected) return pane('Integrations', `<p class="muted">Signed payment and refund events are delivered to active merchant endpoints.</p>${state.endpoints.length ? '<div class="hint-box">Select an endpoint to inspect deliveries and manage its signing secret.</div>' : '<div class="empty"><strong>No webhook endpoints</strong>Create one to receive events.</div>'}`);
  if (!state.record) return pane('Integration', state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : '<div class="loading" role="status">Loading…</div>', backButton());
  if (selected.kind === 'delivery') {
    const delivery = /** @type {Delivery} */ (state.record);
    return pane('Delivery', `<div class="record-head"><div><div class="eyebrow">${esc(delivery.eventType)}</div><h2>Delivery ${esc(shortId(delivery.deliveryId))}</h2><div class="record-sub">Queued ${esc(date(delivery.queuedAt))}</div></div>${badge(delivery.status)}</div>
      <dl class="meta-grid">${recordField('Event ID',delivery.eventId,true)}${recordField('Attempts',String(delivery.attemptCount))}${recordField('Replays',String(delivery.replayCount))}${recordField('Next attempt',date(delivery.nextAttemptAt))}${recordField('Delivered',date(delivery.deliveredAt))}${recordField('Last HTTP status',delivery.lastHttpStatus == null ? '—' : String(delivery.lastHttpStatus))}${recordField('Error category',delivery.lastErrorCategory || '—')}</dl>
      <div class="hint-box">Payloads, signatures, secrets, and receiver responses are intentionally unavailable here.</div>
      <hr class="divider" /><h2>Attempt history</h2>
      ${state.attemptsError ? `<div class="notice error" role="alert">Attempt history is unavailable: ${esc(state.attemptsError)}</div>` : state.attempts.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>When</th><th>Attempt</th><th>Outcome</th><th>HTTP</th></tr></thead><tbody>${state.attempts.map(attempt => `<tr><td>${esc(date(attempt.startedAt))}</td><td>${esc(attempt.attemptNumber)}${attempt.replayCount ? ` · replay ${esc(attempt.replayCount)}` : ''}</td><td>${badge(attempt.outcome)}</td><td>${esc(attempt.httpStatus ?? '—')}</td></tr>`).join('')}</tbody></table></div>${state.attemptsHasMore ? '<button class="btn btn-sm" data-action="load-more" data-scope="attempts">Load more attempts</button>' : ''}` : '<div class="empty"><strong>No attempts yet</strong>This delivery has not been sent.</div>'}`, backButton());
  }
  const endpoint = /** @type {Endpoint} */ (state.record);
  return pane('Webhook endpoint', `<div class="record-head"><div><div class="eyebrow">Webhook endpoint</div><h2>${esc(endpoint.url)}</h2><div class="record-sub mono">${esc(endpoint.endpointId)}</div></div>${badge(endpoint.status)}</div>
    <dl class="meta-grid">${recordField('Events',endpoint.eventTypes.join(', '))}${recordField('Secret version',String(endpoint.secretKeyVersion))}${recordField('Created',date(endpoint.createdAt))}${recordField('Updated',date(endpoint.updatedAt))}${recordField('Disabled',date(endpoint.disabledAt))}</dl>
    <hr class="divider" /><div class="record-head"><h2>Recent deliveries</h2><span class="muted small">Latest 100</span></div>
    ${state.deliveries.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Queued</th><th>Event</th><th>Status</th><th>Attempts</th></tr></thead><tbody>${state.deliveries.map(delivery => `<tr><td>${esc(date(delivery.queuedAt))}</td><td><a class="row-action" href="#/deliveries/${esc(endpoint.endpointId)}/${esc(delivery.deliveryId)}">${esc(delivery.eventType)}</a></td><td>${badge(delivery.status)}</td><td>${esc(delivery.attemptCount)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty"><strong>No deliveries yet</strong>New matching events will appear here.</div>'}`, backButton());
}

function renderOperationsMain() {
  const selected = state.selection;
  if (selected?.kind === 'operator-risk-case') {
    const risk = /** @type {any} */ (state.record);
    if (!risk) return pane('Risk case', state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : '<div class="loading" role="status">Loading case…</div>', backButton());
    return pane('Risk case', `<div class="record-head"><div><div class="eyebrow">Payment review</div><h2>${esc(shortId(risk.caseId))}</h2><div class="record-sub mono">${esc(risk.caseId)}</div></div>${badge(risk.status)}</div>
      <dl class="meta-grid">${recordField('Payer owner',risk.payerOwnerId,true)}${recordField('Matched rules',risk.ruleCodes || '—')}${recordField('Completed payments in 24h',String(risk.completedCount))}${recordField('Completed total in 24h',`$${money(String(risk.completedTotal))} USD`)}${recordField('Policy version ID',risk.policyId,true)}${recordField('Open until',date(risk.openExpiresAt))}${recordField('Approval until',date(risk.approvalExpiresAt))}</dl>
      <div class="hint-box">An approval permits the original payer to retry. It does not move money or reserve balance.</div>
      <hr class="divider" /><h2>Decision history</h2>${state.riskActions.length ? state.riskActions.map(item => `<dl class="meta-grid">${recordField('Action',item.action)}${recordField('Operator',item.actorSubject)}${recordField('Reason',item.reason)}${recordField('When',date(item.createdAt))}</dl>`).join('<hr class="divider" />') : '<div class="empty"><strong>No operator decision yet</strong>This case is awaiting review.</div>'}`, backButton());
  }
  if (selected?.kind === 'owner') {
    const owner = /** @type {any} */ (state.record);
    if (!owner) return pane('Owner', state.routeError ? `<div class="notice error" role="alert">${esc(state.routeError)}</div>` : '<div class="loading" role="status">Loading owner…</div>', backButton());
    return pane('Owner', `<div class="record-head"><div><div class="eyebrow">${esc(owner.ownerType.toLowerCase())} owner</div><h2>${esc(shortId(owner.ownerId))}</h2><div class="record-sub mono">${esc(owner.ownerId)}</div></div>${badge(owner.status)}</div>
      <hr class="divider" /><h2>Wallets</h2>
      ${owner.wallets.length ? owner.wallets.map(/** @param {any} wallet */ wallet => `<dl class="meta-grid"><div><dt>Wallet</dt><dd class="mono">${esc(wallet.walletId)}</dd></div><div><dt>Status</dt><dd>${badge(wallet.status)}</dd></div><div><dt>Derived balance</dt><dd>$${esc(money(wallet.balance.amount))} USD</dd></div></dl>`).join('<hr class="divider" />') : '<div class="empty"><strong>No wallet</strong>This owner has no wallet.</div>'}`, backButton());
  }
  if (selected?.kind === 'reconciliation') {
    if (state.routeError) return pane('Reconciliation', `<div class="notice error" role="alert">${esc(state.routeError)}</div>`, backButton());
    const run = /** @type {any} */ (state.runs.find(/** @param {any} item */ item => item.runId === selected.id));
    return pane('Reconciliation', `<div class="record-head"><div><div class="eyebrow">Integrity check</div><h2>${esc(run ? date(run.startedAt) : shortId(selected.id))}</h2><div class="record-sub">${esc(run ? `${run.findingCount} findings` : selected.id || '')}</div></div>${run ? badge(run.status) : ''}</div>
      ${run?.failureCategory ? `<div class="notice error">Run failed: ${esc(run.failureCategory)}</div>` : ''}
      ${state.findings.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Severity</th><th>Finding</th><th>Entity</th></tr></thead><tbody>${state.findings.map(/** @param {any} item */ item => `<tr><td>${badge(item.severity)}</td><td>${esc(item.findingType.replaceAll('_',' ').toLowerCase())}</td><td class="mono">${esc(shortId(item.entityId))}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty"><strong>No findings</strong>No integrity mismatch was recorded for this run.</div>'}`, backButton());
  }
  if (selected?.kind === 'operator-journal') return pane('Journal', renderJournal(), backButton());
  const summary = /** @type {any} */ (state.summary);
  const policy = state.riskPolicy;
  return pane('Operations', `<p class="muted">Privileged setup, risk review, and processing evidence.</p>
    <dl class="meta-grid">${recordField('Pending outbox events',String(summary?.pendingOutboxEvents ?? '—'))}${recordField('Pending webhook deliveries',String(summary?.pendingWebhookDeliveries ?? '—'))}${recordField('Dead webhook deliveries',String(summary?.deadWebhookDeliveries ?? '—'))}${recordField('Latest reconciliation',summary?.latestReconciliation ? `${summary.latestReconciliation.status} · ${date(summary.latestReconciliation.startedAt)}` : 'No runs')}</dl>
    <div class="hint-box">Financial records and reconciliation findings are immutable. This workbench does not offer manual ledger edits.</div>
    <hr class="divider" /><h2>Payment risk policy</h2>
    <dl class="meta-grid">${recordField('Version',String(policy?.versionNumber ?? '—'))}${recordField('Enabled',policy?.enabled ? 'Yes' : 'No')}${recordField('Maximum payment',policy ? `$${money(String(policy.maxPaymentAmount))} USD` : '—')}${recordField('Review count in 24h',String(policy?.reviewPaymentCount ?? '—'))}${recordField('Review total in 24h',policy ? `$${money(String(policy.reviewPaymentTotal))} USD` : '—')}${recordField('Reason',policy?.changeReason || '—')}</dl>
    <hr class="divider" /><h2>Recent risk cases</h2>
    ${state.riskCases.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Created</th><th>Case</th><th>Status</th></tr></thead><tbody>${state.riskCases.map(item => `<tr><td>${esc(date(item.createdAt))}</td><td><a class="row-action mono" href="#/operations/risk/${esc(item.caseId)}">${esc(shortId(item.caseId))}</a></td><td>${badge(item.status)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty"><strong>No review cases</strong>Payment reviews will appear here.</div>'}
    <hr class="divider" /><h2>Recent reconciliation runs</h2>
    ${state.runs.length ? `<div class="table-wrap"><table class="table"><thead><tr><th>Started</th><th>Status</th><th>Findings</th></tr></thead><tbody>${state.runs.map(/** @param {any} run */ run => `<tr><td><a class="row-action" href="#/operations/reconciliation/${esc(run.runId)}">${esc(date(run.startedAt))}</a></td><td>${badge(run.status)}</td><td>${esc(run.findingCount)}</td></tr>`).join('')}</tbody></table></div>` : '<div class="empty"><strong>No reconciliation runs</strong>Scheduled checks may be disabled.</div>'}`);
}

function renderInspector() {
  if (state.confirm) return pane('Confirm action', renderConfirmation());
  if (state.action) return pane(actionTitle(state.action), renderActionForm());
  if (state.oneTimeSecret && state.selection?.id === state.oneTimeSecret.endpointId) {
    return pane('Signing secret', `<div class="notice warning">This secret is available only in this session. Copy it into your receiver configuration now.</div><div class="mono review" id="one-time-secret">${esc(state.oneTimeSecret.secret)}</div><div class="form-actions"><button class="btn" data-action="copy-secret">Copy secret</button><button class="btn" data-action="dismiss-secret">Done</button></div>`);
  }
  const selected = state.selection;
  if (state.context === 'wallets') {
    const wallet = state.me?.wallets.find(item => item.walletId === selected?.id);
    if (!wallet) return pane('Actions', '<p class="muted">Select a wallet to see available actions.</p>');
    const hasFunds = cents(wallet.balance.amount) > 0n;
    const available = wallet.status === 'ACTIVE' && state.me?.status === 'ACTIVE' && hasFunds;
    return pane('Wallet actions', `<div class="stack">
      ${getSession().mode === 'development' ? `<button class="btn btn-block" data-action="open-form" data-form="top-up" ${wallet.status === 'ACTIVE' && state.me?.status === 'ACTIVE' ? '' : 'disabled'}>Add demo money</button>` : ''}
      <button class="btn btn-primary btn-block" data-action="open-form" data-form="transfer" ${available ? '' : 'disabled'}>Transfer money</button>
      ${state.me?.ownerType === 'PERSON' ? `<button class="btn btn-block" data-action="open-form" data-form="payment" ${available ? '' : 'disabled'}>Pay merchant</button>` : ''}
      ${!available ? `<div class="hint-box">${hasFunds ? 'Actions require an active owner and wallet.' : 'This wallet has no available funds.'}</div>` : '<div class="hint-box">Recipient wallets are entered by ID. Confirm the ID before sending; completed transfers cannot be reversed here.</div>'}
    </div>`);
  }
  if (state.context === 'activity') {
    if (selected?.kind === 'risk-case' && state.riskCase) {
      const stored = sessionStorage.getItem(`ledgerx-risk-${state.riskCase.caseId}`);
      let hasRequest = false;
      try { hasRequest = Boolean(stored && JSON.parse(stored).ownerId === state.me?.ownerId); } catch { /* Ignore stale local data. */ }
      return pane('Review actions', state.riskCase.caseStatus === 'APPROVED' && hasRequest
        ? '<button class="btn btn-primary btn-block" data-action="retry-reviewed-payment">Retry approved payment</button><p class="help">The original request and key will be reused. Your balance and wallet state are checked again.</p>'
        : '<p class="muted">A payment under review moves no money. Keep the original request key until the case is resolved.</p>');
    }
    if (selected?.kind === 'payment' && state.payment) {
      const merchant = state.me?.wallets.some(wallet => wallet.walletId === state.payment?.merchantWalletId);
      const refundable = validAmount(state.payment.remainingRefundableMoney.amount);
      return pane('Payment actions', `<div class="stack">${merchant && refundable ? '<button class="btn btn-primary btn-block" data-action="open-form" data-form="refund">Refund payment</button>' : '<p class="muted">No refund action is available to this account.</p>'}<div class="hint-box">Only the original merchant may refund, up to the remaining amount.</div></div>`);
    }
    return pane('Record actions', '<p class="muted">Select a payment to inspect its refund options. Financial records remain immutable.</p>');
  }
  if (state.context === 'integrations') {
    if (!selected) return pane('Integration actions', '<button class="btn btn-primary btn-block" data-action="open-form" data-form="webhook-create">Add endpoint</button>');
    if (selected.kind === 'delivery') {
      const delivery = /** @type {Delivery|null} */ (state.record);
      const endpoint = state.endpoints.find(item => item.endpointId === selected.parentId);
      return pane('Delivery actions', delivery?.status === 'DEAD' && endpoint?.status === 'ACTIVE'
        ? `<button class="btn btn-primary btn-block" data-action="ask-confirm" data-confirm="replay" data-id="${esc(delivery.deliveryId)}" data-parent="${esc(selected.parentId)}">Replay delivery</button><p class="help">Replaying sends the same event again. Your receiver must tolerate duplicates.</p>`
        : '<p class="muted">Replay is available only for dead deliveries on an active endpoint.</p>');
    }
    const endpoint = /** @type {Endpoint|null} */ (state.record);
    return pane('Endpoint actions', endpoint?.status === 'ACTIVE' ? `<div class="stack"><button class="btn btn-block" data-action="open-form" data-form="rotate">Rotate signing secret</button><button class="btn btn-danger btn-block" data-action="ask-confirm" data-confirm="disable" data-id="${esc(endpoint.endpointId)}">Disable endpoint</button><p class="help">Disabled endpoints cannot be re-enabled or replay deliveries.</p></div>` : '<p class="muted">This endpoint is disabled. Existing delivery history remains available.</p>');
  }
  if (state.context === 'operations') {
    if (selected?.kind === 'operator-risk-case') {
      const risk = /** @type {any} */ (state.record);
      return pane('Review actions', risk?.status === 'OPEN'
        ? '<div class="stack"><button class="btn btn-primary btn-block" data-action="open-form" data-form="risk-approve">Approve case</button><button class="btn btn-danger btn-block" data-action="open-form" data-form="risk-decline">Decline case</button></div>'
        : '<p class="muted">This case is no longer open.</p>');
    }
    if (selected?.kind === 'owner' && state.record) {
      const owner = /** @type {any} */ (state.record);
      return pane('Owner actions', `<div class="stack">
        ${!owner.wallets.length && owner.status === 'ACTIVE' ? `<button class="btn btn-block" data-action="ask-confirm" data-confirm="create-wallet" data-id="${esc(owner.ownerId)}">Create USD wallet</button>` : ''}
        ${owner.status === 'ACTIVE' ? `<button class="btn btn-danger btn-block" data-action="ask-confirm" data-confirm="suspend-owner" data-id="${esc(owner.ownerId)}">Suspend owner</button>` : ''}
        ${owner.wallets.map(/** @param {any} wallet */ wallet => `${wallet.status === 'ACTIVE' ? `<button class="btn btn-danger btn-block" data-action="ask-confirm" data-confirm="suspend-wallet" data-id="${esc(wallet.walletId)}">Suspend wallet ${esc(shortId(wallet.walletId))}</button>` : ''}${wallet.status !== 'CLOSED' ? `<button class="btn btn-danger btn-block" data-action="ask-confirm" data-confirm="close-wallet" data-id="${esc(wallet.walletId)}">Close wallet ${esc(shortId(wallet.walletId))}</button>` : ''}`).join('')}
        <div class="hint-box">A wallet can close only with a zero balance. Suspensions and closure cannot be undone here.</div></div>`);
    }
    return pane('Operator actions', '<div class="stack"><button class="btn btn-primary btn-block" data-action="open-form" data-form="owner-create">Provision owner and wallet</button><button class="btn btn-block" data-action="open-form" data-form="risk-policy">Update risk policy</button></div>');
  }
  return pane('Actions', '');
}

/** @param {string} action */
function actionTitle(action) {
  return ({ 'top-up':'Add demo money', transfer:'Transfer money', payment:'Pay merchant', refund:'Refund payment', 'webhook-create':'Add endpoint', rotate:'Rotate secret', 'owner-create':'Provision owner', 'risk-policy':'Update risk policy', 'risk-approve':'Approve review case', 'risk-decline':'Decline review case' })[action] || 'Action';
}

function renderActionForm() {
  const draft = state.draft;
  if (state.review) {
    let summary = '';
    if (state.action === 'top-up') {
      summary = `<dl class="meta-grid">${recordField('Wallet',state.selection?.id || '',true)}${recordField('Demo amount',`$${money(draft.amount)} USD`)}</dl>`;
    } else if (state.action === 'transfer' || state.action === 'payment') {
      summary = `<dl class="meta-grid">${recordField('From wallet',draft.sourceWalletId,true)}${recordField('To wallet',draft.destinationWalletId,true)}${recordField('Amount',`$${money(draft.amount)} USD`)}</dl>`;
    } else if (state.action === 'refund') {
      summary = `<dl class="meta-grid">${recordField('Payment',state.payment?.paymentId || '',true)}${recordField('Refund amount',`$${money(draft.amount)} USD`)}</dl>`;
    } else if (state.action === 'webhook-create') {
      summary = `<dl class="meta-grid">${recordField('URL',draft.url)}${recordField('Events',draft.eventTypes)}${recordField('Signing secret','Provided · shown only once after save')}</dl>`;
    } else if (state.action === 'rotate') {
      summary = `<p>Replace the signing secret for this endpoint. Your receiver must be updated to use the new secret.</p>`;
    } else if (state.action === 'owner-create') {
      summary = `<p>Create a ${esc(draft.ownerType?.toLowerCase())} owner and an empty USD wallet.</p>`;
    } else if (state.action === 'risk-policy') {
      summary = `<dl class="meta-grid">${recordField('Enabled',draft.enabled === 'true' ? 'Yes' : 'No')}${recordField('Maximum payment',`$${money(draft.maxPaymentAmount)} USD`)}${recordField('Review count',draft.reviewPaymentCount)}${recordField('Review total',`$${money(draft.reviewPaymentTotal)} USD`)}${recordField('Reason',draft.reason)}</dl>`;
    } else if (state.action === 'risk-approve' || state.action === 'risk-decline') {
      summary = `<p>${esc(actionTitle(state.action))} for case ${esc(state.selection?.id || '')}.</p><p>${esc(draft.reason)}</p><div class="hint-box">Approval does not move money. The payer must retry the original request.</div>`;
    }
    const caution = state.action === 'top-up'
      ? '<p class="help">This adds test money in local development only. The journal entry is permanent in this local database.</p>'
      : ['transfer','payment','refund'].includes(state.action)
        ? '<p class="help">A completed financial action cannot be undone here. Verify the wallet IDs and amount.</p>' : '';
    return `<div class="review"><strong>Review ${esc(actionTitle(state.action).toLowerCase())}</strong>${summary}${caution}</div>
      ${state.uncertain ? '<div class="notice warning" role="alert">The outcome is unknown. Retry this exact request with its original key; do not create a new one.</div>' : ''}
      <div class="form-actions"><button class="btn btn-primary" data-action="submit-reviewed" ${state.busy ? 'disabled' : ''}>${state.busy ? 'Submitting…' : state.uncertain ? 'Retry same request' : 'Confirm and submit'}</button><button class="btn" data-action="edit-form" ${state.uncertain || state.busy ? 'disabled' : ''}>Edit</button><button class="btn btn-quiet" data-action="cancel-form" ${state.uncertain || state.busy ? 'disabled' : ''}>Cancel</button></div>`;
  }
  const walletOptions = (state.me?.wallets || []).filter(wallet => wallet.status === 'ACTIVE').map(wallet => `<option value="${esc(wallet.walletId)}" ${draft.sourceWalletId === wallet.walletId ? 'selected' : ''}>${esc(shortId(wallet.walletId))} · $${esc(money(wallet.balance.amount))}</option>`).join('');
  let fields = '';
  if (state.action === 'top-up') {
    fields = `<div class="field"><label for="amount">Demo amount in USD</label><input id="amount" name="amount" inputmode="decimal" value="${esc(draft.amount || '')}" placeholder="0.00" required /><div class="help">$0.01 to $10,000.00 per top-up. No real money is added.</div></div>`;
  } else if (state.action === 'transfer' || state.action === 'payment') {
    fields = `<div class="field"><label for="source-wallet">From wallet</label><select id="source-wallet" name="sourceWalletId" required>${walletOptions}</select></div>
      <div class="field"><label for="destination-wallet">${state.action === 'payment' ? 'Merchant wallet ID' : 'Destination wallet ID'}</label><input id="destination-wallet" name="destinationWalletId" value="${esc(draft.destinationWalletId || '')}" spellcheck="false" autocomplete="off" required /><div class="help">Enter the complete wallet UUID supplied by the recipient.</div></div>
      <div class="field"><label for="amount">Amount in USD</label><input id="amount" name="amount" inputmode="decimal" value="${esc(draft.amount || '')}" placeholder="0.00" required /></div>`;
  } else if (state.action === 'refund') {
    fields = `<div class="hint-box">Remaining refundable: $${esc(money(state.payment?.remainingRefundableMoney.amount))} USD</div><div class="field"><label for="amount">Refund amount in USD</label><input id="amount" name="amount" inputmode="decimal" value="${esc(draft.amount || '')}" required /></div>`;
  } else if (state.action === 'webhook-create') {
    fields = `<div class="field"><label for="url">HTTPS endpoint URL</label><input id="url" name="url" type="url" value="${esc(draft.url || '')}" placeholder="https://example.com/webhook" required /></div>
      <fieldset class="field"><legend class="field-label">Events</legend>${EVENTS.map(event => `<label class="check-row"><input type="checkbox" name="eventTypes" value="${event}" ${draft.eventTypes?.includes(event) ? 'checked' : ''} />${esc(event)}</label>`).join('')}</fieldset>
      <div class="field"><label for="secret">Signing secret</label><input id="secret" name="secret" type="password" value="${esc(draft.secret || '')}" minlength="32" autocomplete="new-password" required /><div class="toolbar"><button class="btn btn-sm" type="button" data-action="generate-secret">Generate secret</button><button class="btn btn-quiet btn-sm" type="button" data-action="toggle-secret">Show or hide</button></div><div class="help">At least 32 characters. Store it securely; the server never returns it.</div></div>`;
  } else if (state.action === 'rotate') {
    fields = `<div class="field"><label for="secret">New signing secret</label><input id="secret" name="secret" type="password" value="${esc(draft.secret || '')}" minlength="32" autocomplete="new-password" required /><div class="toolbar"><button class="btn btn-sm" type="button" data-action="generate-secret">Generate secret</button><button class="btn btn-quiet btn-sm" type="button" data-action="toggle-secret">Show or hide</button></div></div>`;
  } else if (state.action === 'owner-create') {
    fields = `<div class="field"><label for="owner-type">Owner type</label><select id="owner-type" name="ownerType"><option value="PERSON" ${draft.ownerType === 'PERSON' ? 'selected' : ''}>Person</option><option value="MERCHANT" ${draft.ownerType === 'MERCHANT' ? 'selected' : ''}>Merchant</option></select></div><div class="hint-box">A new owner receives an empty USD wallet. Link a Keycloak user to the owner ID separately.</div>`;
  } else if (state.action === 'risk-policy') {
    fields = `<div class="field"><label for="risk-enabled">Risk checks</label><select id="risk-enabled" name="enabled"><option value="true" ${draft.enabled === 'true' ? 'selected' : ''}>Enabled</option><option value="false" ${draft.enabled !== 'true' ? 'selected' : ''}>Disabled</option></select></div>
      <div class="field"><label for="risk-max">Maximum payment USD</label><input id="risk-max" name="maxPaymentAmount" inputmode="decimal" value="${esc(draft.maxPaymentAmount || '')}" required /></div>
      <div class="field"><label for="risk-count">Review above this payment count in 24h</label><input id="risk-count" name="reviewPaymentCount" type="number" min="1" max="2147483647" value="${esc(draft.reviewPaymentCount || '')}" required /></div>
      <div class="field"><label for="risk-total">Review above this completed total USD in 24h</label><input id="risk-total" name="reviewPaymentTotal" inputmode="decimal" value="${esc(draft.reviewPaymentTotal || '')}" required /></div>
      <div class="field"><label for="risk-reason">Change reason</label><input id="risk-reason" name="reason" maxlength="500" value="${esc(draft.reason || '')}" required /></div>`;
  } else if (state.action === 'risk-approve' || state.action === 'risk-decline') {
    fields = `<div class="field"><label for="risk-reason">Decision reason</label><textarea id="risk-reason" name="reason" maxlength="500" required>${esc(draft.reason || '')}</textarea></div>`;
  }
  return `<form class="form" id="action-form">${fields}<div class="form-actions"><button class="btn btn-primary" type="submit">Review</button><button class="btn" type="button" data-action="cancel-form">Cancel</button></div></form>`;
}

function renderConfirmation() {
  const action = state.confirm;
  if (!action) return '';
  const descriptions = /** @type {Record<string,string>} */ ({
    replay: 'Queue this failed delivery again. The receiver may see a duplicate event.',
    disable: 'Stop new deliveries to this endpoint. It cannot be re-enabled.',
    'suspend-owner': 'Prevent this owner from moving money. This interface cannot undo the suspension.',
    'suspend-wallet': 'Prevent money movement through this wallet. This interface cannot undo the suspension.',
    'close-wallet': 'Close this wallet permanently. It must have a zero balance.',
    'create-wallet': 'Create a new empty USD wallet for this owner.',
  });
  return `<div class="notice ${action.type === 'create-wallet' ? 'info' : 'warning'}">${esc(descriptions[action.type] || 'Confirm this action.')}</div><p class="mono small">${esc(action.id)}</p><div class="form-actions"><button class="btn ${action.type === 'create-wallet' ? 'btn-primary' : 'btn-danger'}" data-action="confirm-action" ${state.busy ? 'disabled' : ''}>${state.busy ? 'Working…' : `Confirm ${esc(action.type.replaceAll('-',' '))}`}</button><button class="btn" data-action="cancel-confirm" ${state.busy ? 'disabled' : ''}>Cancel</button></div>`;
}

app.addEventListener('submit', event => {
  event.preventDefault();
  const form = /** @type {HTMLFormElement} */ (event.target);
  if (form.id === 'dev-auth') {
    const ownerId = String(new FormData(form).get('ownerId') || '');
    try { setDevelopmentOwner(ownerId); state.bootError = ''; void loadWorkspace(); }
    catch (error) { state.bootError = messageOf(error); render(); }
  } else if (form.id === 'record-search') {
    const data = new FormData(form);
    const id = String(data.get('id') || '').trim();
    if (!validUuid(id)) { notify('error','Enter a valid record UUID.'); return; }
    navigate(`${data.get('kind')}/${id}`);
  } else if (form.id === 'action-form') {
    reviewForm(form);
  }
});

app.addEventListener('click', event => {
  const target = /** @type {HTMLElement|null} */ (event.target instanceof Element ? event.target.closest('[data-action]') : null);
  if (!target) return;
  const action = target.dataset.action;
  if (action === 'signin') void signIn().catch(error => notify('error', messageOf(error)));
  else if (action === 'signout') signOut();
  else if (action === 'reload') void loadWorkspace();
  else if (action === 'dismiss-notice') { state.notice = null; render(); }
  else if (action === 'open-form') openForm(target.dataset.form || '');
  else if (action === 'cancel-form') { state.action = ''; state.review = false; state.draft = {}; render(); }
  else if (action === 'edit-form') { state.review = false; render(); }
  else if (action === 'submit-reviewed') void submitReviewed();
  else if (action === 'retry-reviewed-payment') {
    const caseId = state.riskCase?.caseId;
    const stored = caseId ? sessionStorage.getItem(`ledgerx-risk-${caseId}`) : null;
    try {
      const saved = stored ? JSON.parse(stored) : null;
      if (!saved || saved.ownerId !== state.me?.ownerId || !saved.draft?.idempotencyKey) throw new Error('Original payment request is unavailable in this browser session.');
      state.action = 'payment'; state.draft = saved.draft; state.review = true; state.uncertain = false; render();
    } catch (error) { notify('error',messageOf(error)); }
  }
  else if (action === 'ask-confirm') { state.confirm = { type:target.dataset.confirm || '', id:target.dataset.id || '', parentId:target.dataset.parent }; render(); }
  else if (action === 'cancel-confirm') { state.confirm = null; render(); }
  else if (action === 'confirm-action') void confirmAction();
  else if (action === 'generate-secret') {
    const field = /** @type {HTMLInputElement|null} */ (document.getElementById('secret'));
    if (field) { field.value = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(36)))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,''); field.type = 'text'; field.focus(); }
  } else if (action === 'toggle-secret') {
    const field = /** @type {HTMLInputElement|null} */ (document.getElementById('secret'));
    if (field) field.type = field.type === 'password' ? 'text' : 'password';
  } else if (action === 'copy-secret') {
    if (state.oneTimeSecret) void navigator.clipboard.writeText(state.oneTimeSecret.secret).then(() => notify('success','Secret copied.')).catch(() => notify('error','Copy failed. Select the secret text manually.'));
  } else if (action === 'dismiss-secret') { state.oneTimeSecret = null; render(); }
  else if (action === 'load-more') void loadMore(target.dataset.scope || 'all');
});

/** @param {string} name */
function openForm(name) {
  state.action = name;
  state.review = false;
  state.uncertain = false;
  state.notice = null;
  state.draft = { idempotencyKey:crypto.randomUUID() };
  if (name === 'transfer' || name === 'payment') state.draft.sourceWalletId = state.selection?.id || state.me?.wallets[0]?.walletId || '';
  if (name === 'refund') state.draft.amount = state.payment?.remainingRefundableMoney.amount || '';
  if (name === 'risk-policy' && state.riskPolicy) state.draft = { ...state.draft, enabled:String(state.riskPolicy.enabled), maxPaymentAmount:String(state.riskPolicy.maxPaymentAmount), reviewPaymentCount:String(state.riskPolicy.reviewPaymentCount), reviewPaymentTotal:String(state.riskPolicy.reviewPaymentTotal), reason:'' };
  if (name === 'webhook-create') state.draft.eventTypes = EVENTS.join(',');
  render();
  document.getElementById('amount')?.focus();
}

/** @param {HTMLFormElement} form */
function reviewForm(form) {
  const data = new FormData(form);
  const draft = { ...state.draft };
  if (state.action === 'top-up') {
    draft.amount = String(data.get('amount') || '').trim();
    state.draft = draft;
    if (!validAmount(draft.amount) || cents(draft.amount) > 1000000n) {
      notify('error','Enter a demo amount from $0.01 to $10,000.00 with at most two decimal places.'); return;
    }
    draft.amount = normalizedAmount(draft.amount);
  } else if (state.action === 'transfer' || state.action === 'payment') {
    draft.sourceWalletId = String(data.get('sourceWalletId') || '');
    draft.destinationWalletId = String(data.get('destinationWalletId') || '').trim();
    draft.amount = String(data.get('amount') || '').trim();
    state.draft = draft;
    if (!validUuid(draft.destinationWalletId)) { notify('error','Enter a valid destination wallet UUID.'); return; }
    if (draft.sourceWalletId === draft.destinationWalletId) { notify('error','Source and destination wallets must differ.'); return; }
    if (!validAmount(draft.amount)) { notify('error','Enter a positive USD amount with at most two decimal places.'); return; }
    draft.amount = normalizedAmount(draft.amount);
  } else if (state.action === 'refund') {
    draft.amount = String(data.get('amount') || '').trim();
    state.draft = draft;
    if (!validAmount(draft.amount)) { notify('error','Enter a positive USD amount with at most two decimal places.'); return; }
    if (cents(draft.amount) > cents(state.payment?.remainingRefundableMoney.amount || '0.00')) { notify('error','Amount exceeds the remaining refundable amount.'); return; }
    draft.amount = normalizedAmount(draft.amount);
  } else if (state.action === 'webhook-create' || state.action === 'rotate') {
    draft.secret = String(data.get('secret') || '');
    if (state.action === 'webhook-create') {
      draft.url = String(data.get('url') || '').trim();
      draft.eventTypes = data.getAll('eventTypes').join(',');
    }
    state.draft = draft;
    if (draft.secret.length < 32 || draft.secret.length > 4096) { notify('error','Signing secret must contain 32 to 4096 characters.'); return; }
    if (state.action === 'webhook-create') {
      if (!draft.eventTypes) { notify('error','Choose at least one event type.'); return; }
      try {
        const url = new URL(draft.url);
        if (!['https:','http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error();
      } catch { notify('error','Enter a valid endpoint URL without credentials, query, or fragment.'); return; }
    }
  } else if (state.action === 'owner-create') draft.ownerType = String(data.get('ownerType') || 'PERSON');
  else if (state.action === 'risk-policy') {
    draft.enabled = String(data.get('enabled') || 'false');
    draft.maxPaymentAmount = String(data.get('maxPaymentAmount') || '').trim();
    draft.reviewPaymentCount = String(data.get('reviewPaymentCount') || '').trim();
    draft.reviewPaymentTotal = String(data.get('reviewPaymentTotal') || '').trim();
    draft.reason = String(data.get('reason') || '').trim();
    if (!validAmount(draft.maxPaymentAmount) || !validAmount(draft.reviewPaymentTotal)
      || !/^\d+$/.test(draft.reviewPaymentCount) || Number(draft.reviewPaymentCount) < 1
      || Number(draft.reviewPaymentCount) > 2147483647 || !draft.reason) {
      notify('error','Enter valid positive USD thresholds, count, and a reason.'); return;
    }
    draft.maxPaymentAmount = normalizedAmount(draft.maxPaymentAmount);
    draft.reviewPaymentTotal = normalizedAmount(draft.reviewPaymentTotal);
  } else if (state.action === 'risk-approve' || state.action === 'risk-decline') {
    draft.reason = String(data.get('reason') || '').trim();
    if (!draft.reason) { notify('error','Enter a review reason.'); return; }
  }
  state.draft = draft;
  state.review = true;
  state.notice = null;
  render();
}

async function submitReviewed() {
  if (state.busy) return;
  state.busy = true;
  render();
  const draft = state.draft;
  const action = state.action;
  try {
    /** @type {any} */ let result;
    if (action === 'top-up' && state.selection?.id) {
      result = await api(`/demo/wallets/${state.selection.id}/fundings`, { method:'POST', idempotencyKey:draft.idempotencyKey,
        body:{ money:{ amount:draft.amount, currency:'USD' } } });
    } else if (action === 'transfer') {
      result = await api('/transfers', { method:'POST', idempotencyKey:draft.idempotencyKey,
        body:{ sourceWalletId:draft.sourceWalletId, destinationWalletId:draft.destinationWalletId, money:{ amount:draft.amount, currency:'USD' } } });
    } else if (action === 'payment') {
      result = await api('/payments', { method:'POST', idempotencyKey:draft.idempotencyKey, acceptRisk:true,
        body:{ payerWalletId:draft.sourceWalletId, merchantWalletId:draft.destinationWalletId, money:{ amount:draft.amount, currency:'USD' } } });
    } else if (action === 'refund' && state.payment) {
      result = await api(`/payments/${state.payment.paymentId}/refunds`, { method:'POST', idempotencyKey:draft.idempotencyKey,
        body:{ money:{ amount:draft.amount, currency:'USD' } } });
    } else if (action === 'webhook-create') {
      result = await api('/webhook-endpoints', { method:'POST', idempotencyKey:draft.idempotencyKey,
        body:{ url:draft.url, eventTypes:draft.eventTypes.split(','), signingSecret:draft.secret } });
    } else if (action === 'rotate' && state.selection?.id) {
      result = await api(`/webhook-endpoints/${state.selection.id}/rotate-secret`, { method:'POST', body:{ signingSecret:draft.secret } });
    } else if (action === 'owner-create') {
      result = await api('/operations/owners', { method:'POST', idempotencyKey:draft.idempotencyKey, body:{ ownerType:draft.ownerType } });
    } else if (action === 'risk-policy') {
      result = await api('/operations/risk/policies', { method:'POST', idempotencyKey:draft.idempotencyKey,
        body:{ enabled:draft.enabled === 'true', maxPaymentAmount:draft.maxPaymentAmount, reviewPaymentCount:Number(draft.reviewPaymentCount), reviewPaymentTotal:draft.reviewPaymentTotal, reason:draft.reason, expectedVersion:state.riskPolicy.versionNumber } });
    } else if ((action === 'risk-approve' || action === 'risk-decline') && state.selection?.id) {
      result = await api(`/operations/risk/cases/${state.selection.id}/${action === 'risk-approve' ? 'approve' : 'decline'}`, { method:'POST', body:{ reason:draft.reason } });
    } else return;

    if (action === 'payment' && result.outcome) {
      state.action = ''; state.review = false; state.uncertain = false;
      if (result.caseId) {
        sessionStorage.setItem(`ledgerx-risk-${result.caseId}`, JSON.stringify({ ownerId:state.me?.ownerId, draft }));
        state.notice = { kind:result.outcome === 'BLOCK' || result.code === 'RISK_REVIEW_CLOSED' ? 'error' : 'warning', message:result.message || 'Payment needs review. No money moved.' };
        navigate(`payment-risk-cases/${result.caseId}`);
      } else {
        state.notice = { kind:'error', message:'Payment blocked by the active risk policy. No money moved.' };
        render();
      }
      state.draft = {};
      return;
    }

    if (action === 'risk-policy' || action === 'risk-approve' || action === 'risk-decline') {
      state.action = ''; state.review = false; state.uncertain = false; state.draft = {};
      await refreshLists();
      if (action === 'risk-policy') navigate('operations');
      else await loadRoute();
      state.notice = { kind:'success', message:action === 'risk-policy' ? 'Risk policy activated.' : 'Review case updated. No money moved.' };
      render();
      return;
    }

    if (action === 'payment' && state.selection?.kind === 'risk-case' && state.selection.id) {
      sessionStorage.removeItem(`ledgerx-risk-${state.selection.id}`);
    }

    state.action = '';
    state.review = false;
    state.uncertain = false;
    state.notice = { kind:'success', message:action === 'top-up' ? `Added $${money(draft.amount)} in demo money.` : `${actionTitle(action)} completed.` };
    if (action === 'webhook-create' || action === 'rotate') {
      state.oneTimeSecret = { endpointId:result.endpointId, secret:draft.secret };
    }
    state.draft = {};
    try { await refreshLists(); }
    catch (error) { state.notice = { kind:'warning', message:`Action completed. The list could not refresh: ${messageOf(error)}` }; }
    if (action === 'top-up') {
      await loadRoute();
      render();
      return;
    }
    const destination = action === 'transfer' ? `transfers/${result.transferId}`
      : action === 'payment' ? `payments/${result.paymentId}`
      : action === 'refund' ? `refunds/${result.refundId}`
      : action === 'webhook-create' || action === 'rotate' ? `webhooks/${result.endpointId}`
      : `operations/owners/${result.ownerId}`;
    if (location.hash === `#/${destination}`) await loadRoute(); else navigate(destination);
    render();
  } catch (error) {
    const unknown = error instanceof ApiError &&
      (error.status === 0 || error.status >= 500 || ['IDEMPOTENCY_REQUEST_IN_PROGRESS','CONCURRENT_TRANSFER_CONFLICT','CONCURRENT_PAYMENT_CONFLICT'].includes(error.code));
    state.uncertain = unknown;
    if (!unknown) state.review = false;
    state.notice = { kind: unknown ? 'warning' : 'error', message:messageOf(error) };
    render();
  } finally {
    state.busy = false;
    render();
  }
}

async function refreshLists() {
  const session = getSession();
  if (state.me && session.ownerId) {
    state.me = await api('/me');
    state.activity = await api('/activity?limit=50&page=0');
    state.activityPage = 0;
    state.activityHasMore = state.activity.length === 50;
    if (state.me?.ownerType === 'MERCHANT') state.endpoints = await api('/webhook-endpoints');
  }
  if (session.operator) await loadOperations();
}

async function confirmAction() {
  if (state.busy) return;
  const confirmation = state.confirm;
  if (!confirmation) return;
  state.busy = true;
  render();
  try {
    if (confirmation.type === 'replay') {
      await api(`/webhook-endpoints/${confirmation.parentId}/deliveries/${confirmation.id}/replay`, { method:'POST' });
    } else if (confirmation.type === 'disable') {
      await api(`/webhook-endpoints/${confirmation.id}/disable`, { method:'POST' });
    } else if (confirmation.type === 'suspend-owner') {
      await api(`/operations/owners/${confirmation.id}/suspend`, { method:'POST' });
    } else if (confirmation.type === 'suspend-wallet') {
      await api(`/operations/wallets/${confirmation.id}/suspend`, { method:'POST' });
    } else if (confirmation.type === 'close-wallet') {
      await api(`/operations/wallets/${confirmation.id}/close`, { method:'POST' });
    } else if (confirmation.type === 'create-wallet') {
      await api(`/operations/owners/${confirmation.id}/wallets`, { method:'POST' });
    } else return;
    state.confirm = null;
    await refreshLists();
    await loadRoute();
    state.notice = { kind:'success', message:'Action completed.' };
    render();
  } catch (error) {
    state.notice = { kind:'error', message:messageOf(error) };
    render();
  } finally {
    state.busy = false;
    render();
  }
}

/** @param {string} scope */
async function loadMore(scope) {
  try {
    if (scope === 'attempts' && state.selection?.kind === 'delivery') {
      const page = state.attemptsPage + 1;
      const items = /** @type {DeliveryAttempt[]} */ (await api(`/webhook-endpoints/${state.selection.parentId}/deliveries/${state.selection.id}/attempts?limit=100&page=${page}`));
      state.attempts.push(...items);
      state.attemptsPage = page;
      state.attemptsHasMore = items.length === 100;
    } else if (scope === 'owners' && getSession().operator) {
      const page = state.ownersPage + 1;
      const items = /** @type {unknown[]} */ (await api(`/operations/owners?limit=50&page=${page}`));
      state.owners.push(...items);
      state.ownersPage = page;
      state.ownersHasMore = items.length === 50;
    } else if (scope === 'wallet' && state.selection?.kind === 'wallet') {
      const page = state.walletPage + 1;
      const items = /** @type {Activity[]} */ (await api(`/activity?walletId=${state.selection.id}&limit=50&page=${page}`));
      state.walletActivity.push(...items);
      state.walletPage = page;
      state.walletHasMore = items.length === 50;
    } else {
      const page = state.activityPage + 1;
      const items = /** @type {Activity[]} */ (await api(`/activity?limit=50&page=${page}`));
      state.activity.push(...items);
      state.activityPage = page;
      state.activityHasMore = items.length === 50;
    }
    render();
  } catch (error) { notify('error',messageOf(error)); }
}
