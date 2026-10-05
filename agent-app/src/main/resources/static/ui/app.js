// Three screens over the agent's API: instances, an instance's timeline, an approval. The UI only shows what the
// API says and sends decisions; prices, rules and permissions are the server's.
import { init, logout, user } from './auth.js';
import { api, ApiError } from './api.js';
import { age, html, money, mount, time } from './html.js';

const STATES = ['RECEIVED', 'AWAIT_REPLY', 'UNDERSTOOD', 'ENRICHED', 'PRICED', 'AWAIT_APPROVAL', 'APPROVED',
  'RESPONDED', 'FOLLOW_UP', 'INVESTIGATING', 'CLOSED', 'EXCEPTION'];

const view = document.getElementById('view');
let me;

const badge = (status) => html`<span class="badge ${String(status).toLowerCase()}">${status}</span>`;
const isApprover = () => me.roles.includes('approver');

function failure(error) {
  const text = error instanceof ApiError && error.status === 403
    ? 'You do not have the role for this screen.'
    : error.message;
  mount(view, html`<p class="error">${text}</p>`);
}

// --- Instances

async function instancesScreen(params) {
  const state = params.get('state') || '';
  const page = Number(params.get('page') || 0);
  const [instances, approvals] = await Promise.all([
    api.instances(state, page),
    isApprover() ? api.approvals() : Promise.resolve([]),
  ]);
  const pages = Math.max(1, Math.ceil(instances.total / instances.size));
  const link = (p) => `#/instances?${new URLSearchParams({ ...(state ? { state } : {}), page: p })}`;
  mount(view, html`
    ${approvals.length ? html`
      <section>
        <h2>Waiting for you <span class="muted">(${approvals.length})</span></h2>
        ${approvalsTable(approvals)}
      </section>` : ''}
    <section>
      <h2>Instances</h2>
      <form class="filters" id="filter">
        <label>State
          <select name="state">
            <option value="">all</option>
            ${STATES.map((s) => html`<option ${s === state ? 'selected' : ''}>${s}</option>`)}
          </select>
        </label>
      </form>
      <table>
        <thead><tr><th>Customer</th><th>Route</th><th class="num">Amount</th><th>State</th><th>Age</th></tr></thead>
        <tbody>
          ${instances.items.map((i) => html`
            <tr>
              <td><a href="#/instances/${i.id}">${i.customer ?? '—'}</a></td>
              <td>${i.route ?? '—'}</td>
              <td class="num">${money(i.price, i.currency)}</td>
              <td>${badge(i.state)}</td>
              <td title="${time(i.createdAt)}">${age(i.createdAt)}</td>
            </tr>`)}
          ${instances.items.length ? '' : html`<tr><td colspan="5" class="muted">No instances.</td></tr>`}
        </tbody>
      </table>
      <p class="pager">
        ${page > 0 ? html`<a href="${link(page - 1)}">← newer</a>` : ''}
        <span class="muted">page ${page + 1} of ${pages} · ${instances.total} in all</span>
        ${page + 1 < pages ? html`<a href="${link(page + 1)}">older →</a>` : ''}
      </p>
    </section>`);
  document.querySelector('#filter select').addEventListener('change', (e) => {
    location.hash = `#/instances?${new URLSearchParams(e.target.value ? { state: e.target.value } : {})}`;
  });
}

function approvalsTable(approvals) {
  return html`
    <table>
      <thead><tr><th>Customer</th><th>Route</th><th class="num">Amount</th><th>Reasons</th><th>Status</th><th>Due</th></tr></thead>
      <tbody>
        ${approvals.map((a) => html`
          <tr>
            <td><a href="#/approvals/${a.id}">${a.customer ?? '—'}</a></td>
            <td>${a.route ?? '—'}</td>
            <td class="num">${money(a.price, a.currency)}</td>
            <td>${a.reasons.join(', ')}</td>
            <td>${badge(a.status)}</td>
            <td>${time(a.dueAt)}</td>
          </tr>`)}
      </tbody>
    </table>`;
}

async function approvalsScreen() {
  const approvals = await api.approvals();
  mount(view, html`
    <section>
      <h2>Waiting for a decision</h2>
      ${approvals.length ? approvalsTable(approvals) : html`<p class="muted">Nothing is waiting.</p>`}
    </section>`);
}

// --- Timeline

const eventDetails = (e) => {
  const d = e.details;
  switch (e.type) {
    case 'STEP': return [d.durationMs != null ? `${d.durationMs} ms` : 'running', d.error].filter(Boolean).join(' · ');
    case 'LLM_CALL': return [d.model, `${d.promptTokens ?? 0}+${d.completionTokens ?? 0} tokens`, d.costEur != null ? `€${d.costEur}` : null, `${d.latencyMs} ms`, d.error].filter(Boolean).join(' · ');
    case 'TOOL_CALL': return [d.kind, `${d.durationMs} ms`, d.error].filter(Boolean).join(' · ');
    case 'EMAIL_OUT': return [`to ${d.to}`, d.sentAt ? `sent ${time(d.sentAt)}` : `attempts ${d.attempts}`, d.error].filter(Boolean).join(' · ');
    case 'EMAIL_IN': return d.messageId;
    case 'APPROVAL': return [d.reasons?.join(', '), d.decidedBy ? `by ${d.decidedBy}` : `due ${time(d.dueAt)}`].filter(Boolean).join(' · ');
    default: return '';
  }
};

async function timelineScreen(id) {
  const [instance, timeline] = await Promise.all([api.instance(id), api.timeline(id)]);
  const q = instance.quote;
  mount(view, html`
    <p><a href="#/instances">← Instances</a></p>
    <section class="card">
      <h2>${instance.customer?.name ?? instance.sender} ${badge(instance.state)}</h2>
      <dl class="facts">
        <dt>Subject</dt><dd>${instance.subject ?? '—'}</dd>
        <dt>Route</dt><dd>${instance.request ? `${instance.request.origin} → ${instance.request.destination}` : '—'}</dd>
        <dt>Quote</dt><dd>${q ? `${money(q.price, q.currency)} (${q.carrier}, margin ${q.marginPct}%)` : '—'}</dd>
        <dt>Flags</dt><dd>${instance.flags.length ? instance.flags.join(', ') : '—'}</dd>
        ${instance.closeReason ? html`<dt>Closed</dt><dd>${instance.closeReason}</dd>` : ''}
        ${instance.error ? html`<dt>Error</dt><dd>${instance.error}</dd>` : ''}
        <dt>Model use</dt><dd>${timeline.promptTokens}+${timeline.completionTokens} tokens · €${timeline.costEur}</dd>
      </dl>
    </section>
    <section>
      <h2>Timeline</h2>
      <table class="timeline">
        <thead><tr><th>Time</th><th>What</th><th></th><th>Status</th><th>Details</th></tr></thead>
        <tbody>
          ${timeline.events.map((e) => html`
            <tr class="${e.type.toLowerCase()}">
              <td>${time(e.at)}</td>
              <td>${e.type.replace('_', ' ').toLowerCase()}</td>
              <td>${e.type === 'APPROVAL' && isApprover()
                  ? html`<a href="#/approvals/${e.details.approvalTaskId}">${e.title}</a>` : e.title}</td>
              <td>${badge(e.status)}</td>
              <td class="muted">${eventDetails(e)}</td>
            </tr>`)}
        </tbody>
      </table>
    </section>`);
}

// --- Approval

async function approvalScreen(id) {
  const a = await api.approval(id);
  const q = a.quote;
  const r = a.request;
  const pending = a.status === 'OPEN' || a.status === 'ESCALATED';
  mount(view, html`
    <p><a href="#/instances/${a.instanceId}">← Timeline</a></p>
    <section class="card">
      <h2>${a.customer?.name ?? a.sender} — ${a.kind.toLowerCase()} ${badge(a.status)}</h2>
      ${a.summary ? html`<p class="summary">${a.summary}</p>` : html`<p class="muted">No summary.</p>`}
      <p><strong>Why a person decides:</strong> ${a.reasons.join(', ') || '—'}</p>
      ${a.investigation ? html`
        <div class="investigation">
          <p><strong>Investigation:</strong> ${a.investigation.summary}</p>
          <p><strong>Likely cause:</strong> ${a.investigation.likelyCause}</p>
          <p><strong>Suggested:</strong> ${a.investigation.suggestedAction}</p>
        </div>` : ''}
    </section>
    <div class="columns">
      <section class="card">
        <h3>Request</h3>
        <dl class="facts">
          <dt>Sender</dt><dd>${a.sender}</dd>
          <dt>Customer</dt><dd>${a.customer ? `${a.customer.name} (${a.customer.id}, ${a.customer.tier})` : 'not in the CRM'}</dd>
          <dt>Route</dt><dd>${r ? `${r.origin} → ${r.destination}` : '—'}</dd>
          <dt>Weight</dt><dd>${r?.weightKg != null ? `${r.weightKg} kg` : '—'}</dd>
          <dt>Pallets</dt><dd>${r?.pallets ?? '—'}</dd>
          <dt>Cargo</dt><dd>${r?.cargoType ?? '—'}</dd>
          <dt>Pickup</dt><dd>${r?.pickupDate ?? '—'}</dd>
          <dt>Flags</dt><dd>${a.flags.length ? a.flags.join(', ') : '—'}</dd>
        </dl>
      </section>
      <section class="card">
        <h3>Quote</h3>
        ${q ? html`
          <p class="price">${money(q.price, q.currency)} <span class="muted">margin ${q.marginPct}%, valid until ${q.validUntil ?? '—'}</span></p>
          <ul class="breakdown">${q.breakdown.map((line) => html`<li>${line}</li>`)}</ul>` : html`<p class="muted">No quote.</p>`}
        <h3>Rates</h3>
        <table>
          <thead><tr><th>Carrier</th><th class="num">Cost</th><th>Days</th></tr></thead>
          <tbody>${a.rates.map((rate) => html`
            <tr class="${q && rate.carrier === q.carrier ? 'chosen' : ''}">
              <td>${rate.carrier}</td><td class="num">${money(rate.cost, rate.currency)}</td><td>${rate.transitDays}</td>
            </tr>`)}</tbody>
        </table>
      </section>
    </div>
    <section class="card">
      <h3>Decision</h3>
      ${pending ? html`
        <form id="decision" class="decision">
          ${q ? html`<label>Price (${q.currency}) <input name="price" type="number" step="0.01" min="${q.cost}" value="${q.price}"></label>` : ''}
          <label>Comment <textarea name="comment" rows="2" placeholder="required to reject"></textarea></label>
          <label>Retry from
            <select name="retryFrom"><option>ENRICHED</option><option>UNDERSTOOD</option></select>
          </label>
          <div class="buttons">
            ${q ? html`<button type="button" data-action="APPROVE" class="primary">Approve</button>` : ''}
            <button type="button" data-action="REJECT" class="danger">Reject</button>
            <button type="button" data-action="RETRY">Retry</button>
          </div>
          <p id="decision-error" class="error" hidden></p>
        </form>` : html`
        <p>${badge(a.status)} by ${a.decidedBy ?? '—'} at ${time(a.decidedAt)}
          ${a.approvedPrice != null ? html` · ${money(a.approvedPrice, q?.currency)}` : ''}
          ${a.comment ? html` · “${a.comment}”` : ''}</p>`}
    </section>`);

  const form = document.getElementById('decision');
  form?.addEventListener('click', async (event) => {
    const action = event.target.dataset?.action;
    if (!action) return;
    const data = new FormData(form);
    const decision = { action, comment: data.get('comment') || null };
    if (action === 'APPROVE' && q && Number(data.get('price')) !== Number(q.price)) decision.editedPrice = Number(data.get('price'));
    if (action === 'RETRY') decision.retryFrom = data.get('retryFrom');
    form.querySelectorAll('button').forEach((b) => (b.disabled = true));
    try {
      await api.decide(id, decision);
      await approvalScreen(id);
    } catch (error) {
      const message = document.getElementById('decision-error');
      message.textContent = error.message;
      message.hidden = false;
      form.querySelectorAll('button').forEach((b) => (b.disabled = false));
    }
  });
}

// --- Routing

async function route() {
  const [path, query] = location.hash.slice(1).split('?');
  const params = new URLSearchParams(query);
  const parts = path.split('/').filter(Boolean);
  try {
    if (parts[0] === 'instances' && parts[1]) await timelineScreen(parts[1]);
    else if (parts[0] === 'approvals' && parts[1]) await approvalScreen(parts[1]);
    else if (parts[0] === 'approvals') await approvalsScreen();
    else await instancesScreen(params);
  } catch (error) {
    failure(error);
  }
}

await init();
me = user();
mount(document.getElementById('user'), html`${me.name} <button id="logout" class="link">log out</button>`);
document.getElementById('logout').addEventListener('click', logout);
document.querySelectorAll('[data-role]').forEach((el) => {
  el.hidden = !me.roles.includes(el.dataset.role);
});
window.addEventListener('hashchange', route);
route();
