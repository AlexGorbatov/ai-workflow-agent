'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import {
  AlertTriangle, ArrowLeft, Bot, Calendar, CheckCircle2, Cog, Inbox, Languages, Mail, Package, Plug, RotateCcw,
  Scale, Send, ShieldCheck, Sparkles, Truck, XCircle,
} from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import { ago, due, humanize, money, reason, time } from '@/lib/format';
import type { ApprovalDetail, InstanceDetail, Quote, Timeline, TimelineEvent } from '@/lib/types';
import { Pill, StateBadge, StatusBadge } from '@/components/badges';
import { isApprover, useUser } from '@/components/session';
import { useNow, usePoll } from '@/components/use-poll';

export default function InstancePage() {
  return (
    <Suspense>
      <InstanceView />
    </Suspense>
  );
}

function InstanceView() {
  const id = useSearchParams().get('id') ?? '';
  const user = useUser();
  const approver = isApprover(user);
  const now = useNow();

  const instance = usePoll(() => api.instance(id), 4000, [id]);
  const timeline = usePoll(() => api.timeline(id), 4000, [id]);
  const taskId = lastApprovalTask(timeline.data);
  const approval = usePoll(
    () => (approver && taskId ? api.approval(taskId) : Promise.resolve(null)),
    4000,
    [taskId, approver],
  );

  const reloadAll = async () => {
    await Promise.all([instance.reload(), timeline.reload(), approval.reload()]);
  };

  if (instance.error) {
    const missing = instance.error instanceof ApiError && instance.error.status === 404;
    return <Notice text={missing ? 'This request does not exist.' : instance.error.message} />;
  }
  const i = instance.data;
  if (!i) return <Notice text="Loading…" />;

  const a = approval.data;
  const pending = a && (a.status === 'OPEN' || a.status === 'ESCALATED');

  return (
    <div className="space-y-6">
      <Link href="/" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-text">
        <ArrowLeft className="h-4 w-4" /> All requests
      </Link>

      <HeaderCard i={i} now={now} />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          {a && <DecisionPanel a={a} pending={!!pending} now={now} onDecided={reloadAll} />}
          {i.investigation && <InvestigationCard i={i} />}
          <TimelineCard t={timeline.data} />
        </div>
        <div className="space-y-6">
          {i.quote && <QuoteCard q={i.quote} rates={i.rates} />}
          <RequestCard i={i} />
          {timeline.data && <ModelUseCard t={timeline.data} />}
        </div>
      </div>
    </div>
  );
}

function lastApprovalTask(t: Timeline | null): string | null {
  const events = t?.events.filter((e) => e.type === 'APPROVAL') ?? [];
  const last = events[events.length - 1];
  return (last?.details.approvalTaskId as string | undefined) ?? null;
}

function Notice({ text }: { text: string }) {
  return <div className="glass mx-auto mt-10 max-w-md p-8 text-center text-sm text-muted">{text}</div>;
}

// --- header with the route

function HeaderCard({ i, now }: { i: InstanceDetail; now: number }) {
  const r = i.request;
  return (
    <section className="glass rise relative overflow-hidden p-6 sm:p-8">
      <div className="absolute -right-24 -top-24 h-64 w-64 rounded-full bg-brand/20 blur-3xl" />
      <div className="relative flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
        <div className="min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <StateBadge state={i.state} />
            {i.closeReason && <Pill>{humanize(i.closeReason)}</Pill>}
            <span className="text-xs text-muted">received {ago(i.createdAt, now)}</span>
          </div>
          <h1 className="truncate text-2xl font-semibold tracking-tight sm:text-3xl">{i.customer?.name ?? i.sender}</h1>
          <p className="mt-1 truncate text-sm text-muted">
            <Mail className="mr-1.5 inline h-3.5 w-3.5" />
            {i.subject ?? '—'} <span className="opacity-60">· {i.sender}</span>
          </p>
          {i.flags.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {i.flags.map((f) => (
                <Pill key={f} tone={f === 'SUSPICIOUS_INSTRUCTIONS' ? 'rose' : 'amber'}>
                  <AlertTriangle className="h-3 w-3" /> {reason(`flag:${f}`)}
                </Pill>
              ))}
            </div>
          )}
          {i.error && <p className="mt-3 text-sm text-rose-300">{i.error}</p>}
        </div>
        {r && (
          <div className="flex shrink-0 items-center gap-4 rounded-2xl border border-line bg-white/[0.03] px-5 py-4">
            <City name={r.origin} label="From" />
            <div className="relative flex w-24 items-center sm:w-32">
              <div className="h-px w-full border-t border-dashed border-white/25" />
              <span className="absolute left-1/2 grid h-8 w-8 -translate-x-1/2 place-items-center rounded-full bg-gradient-to-br from-brand to-brand-2 shadow-lg shadow-brand/30">
                <Truck className="h-4 w-4 text-white" />
              </span>
            </div>
            <City name={r.destination} label="To" />
          </div>
        )}
      </div>
    </section>
  );
}

function City({ name, label }: { name: string; label: string }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wider text-muted">{label}</div>
      <div className="font-medium">{name}</div>
    </div>
  );
}

// --- decision

function DecisionPanel({ a, pending, now, onDecided }: {
  a: ApprovalDetail; pending: boolean; now: number; onDecided: () => Promise<void>;
}) {
  const q = a.quote;
  const [price, setPrice] = useState(q ? String(q.price) : '');
  const [comment, setComment] = useState('');
  const [retryFrom, setRetryFrom] = useState<'ENRICHED' | 'UNDERSTOOD'>('ENRICHED');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const p = Number(price);
  const margin = q && p > 0 ? ((p - q.cost) / p) * 100 : null;

  async function decide(action: 'APPROVE' | 'REJECT' | 'RETRY') {
    setBusy(true);
    setError(null);
    try {
      await api.decide(a.id, {
        action,
        comment: comment || null,
        ...(action === 'APPROVE' && q && p !== Number(q.price) ? { editedPrice: p } : {}),
        ...(action === 'RETRY' ? { retryFrom } : {}),
      });
      await onDecided();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={`glass rise p-6 ${pending ? 'ring-1 ring-amber-400/30' : ''}`}>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <ShieldCheck className="h-5 w-5 text-amber-300" />
        <h2 className="text-lg font-medium">{pending ? 'Your decision' : 'Decision'}</h2>
        <StatusBadge status={a.status} />
        {a.round > 1 && <Pill>round {a.round}</Pill>}
        {pending && (
          <span className={`ml-auto text-xs ${a.status === 'ESCALATED' ? 'text-rose-300' : 'text-muted'}`}>
            SLA {due(a.dueAt, now)}
          </span>
        )}
      </div>

      {a.summary && (
        <div className="mb-5 rounded-xl border border-brand/30 bg-brand/[0.08] p-4">
          <div className="mb-1.5 flex items-center gap-1.5 text-xs font-medium uppercase tracking-wider text-indigo-200">
            <Sparkles className="h-3.5 w-3.5" /> AI briefing
          </div>
          <p className="text-sm leading-relaxed">{a.summary}</p>
        </div>
      )}

      <div className="mb-5 flex flex-wrap items-center gap-1.5">
        <span className="mr-1 text-xs text-muted">Why a person decides:</span>
        {a.reasons.map((r) => (
          <Pill key={r} tone="amber">{reason(r)}</Pill>
        ))}
      </div>

      {pending ? (
        <>
          <div className="grid gap-4 sm:grid-cols-[200px_1fr]">
            {q && (
              <label className="space-y-1.5">
                <span className="label">Price ({q.currency})</span>
                <input className="field tabular-nums" type="number" step="0.01" min={q.cost} value={price} onChange={(e) => setPrice(e.target.value)} />
                <span className={`block text-xs ${margin != null && margin < 10 ? 'text-amber-300' : 'text-muted'}`}>
                  {margin != null ? `margin ${margin.toFixed(1)}% · cost ${money(q.cost, q.currency)}` : ' '}
                </span>
              </label>
            )}
            <label className="space-y-1.5">
              <span className="label">Comment</span>
              <textarea className="field min-h-[74px]" placeholder="Required to reject" value={comment} onChange={(e) => setComment(e.target.value)} />
            </label>
          </div>
          <div className="mt-5 flex flex-wrap items-center gap-2">
            {q && (
              <button className="btn btn-primary" disabled={busy} onClick={() => decide('APPROVE')}>
                <CheckCircle2 className="h-4 w-4" /> Approve & send
              </button>
            )}
            <button className="btn btn-danger" disabled={busy} onClick={() => decide('REJECT')}>
              <XCircle className="h-4 w-4" /> Reject
            </button>
            <div className="flex items-center gap-2 sm:ml-auto">
              <select className="field !w-auto !py-2" value={retryFrom} onChange={(e) => setRetryFrom(e.target.value as 'ENRICHED' | 'UNDERSTOOD')} aria-label="Retry from">
                <option value="ENRICHED">Price again</option>
                <option value="UNDERSTOOD">Read the email again</option>
              </select>
              <button className="btn btn-ghost" disabled={busy} onClick={() => decide('RETRY')}>
                <RotateCcw className="h-4 w-4" /> Retry
              </button>
            </div>
          </div>
          {error && <p className="mt-3 text-sm text-rose-300">{error}</p>}
        </>
      ) : (
        <p className="text-sm text-muted">
          {humanize(a.status)} by <span className="text-text">{a.decidedBy ?? '—'}</span> · {time(a.decidedAt)}
          {a.approvedPrice != null && <> · <span className="text-text">{money(a.approvedPrice, q?.currency)}</span></>}
          {a.comment && <> · “{a.comment}”</>}
        </p>
      )}
    </section>
  );
}

function InvestigationCard({ i }: { i: InstanceDetail }) {
  const v = i.investigation!;
  return (
    <section className="glass rise p-6">
      <div className="mb-3 flex items-center gap-2">
        <Bot className="h-5 w-5 text-violet-300" />
        <h2 className="text-lg font-medium">Investigation</h2>
      </div>
      <dl className="space-y-3 text-sm">
        <div><dt className="label">What happened</dt><dd className="mt-1">{v.summary}</dd></div>
        <div><dt className="label">Likely cause</dt><dd className="mt-1">{v.likelyCause}</dd></div>
        <div><dt className="label">Suggested</dt><dd className="mt-1">{v.suggestedAction}</dd></div>
      </dl>
    </section>
  );
}

// --- quote, request, model use

function QuoteCard({ q, rates }: { q: Quote; rates: InstanceDetail['rates'] }) {
  return (
    <section className="glass rise overflow-hidden">
      <div className="bg-gradient-to-br from-brand/25 via-transparent to-transparent p-6">
        <div className="label">Quote</div>
        <div className="mt-1 text-4xl font-semibold tracking-tight tabular-nums">{money(q.price, q.currency)}</div>
        <div className="mt-1 text-sm text-muted">
          margin {q.marginPct}% · valid until {q.validUntil ?? '—'}
        </div>
      </div>
      <div className="space-y-5 p-6 pt-4">
        <ul className="space-y-1.5 text-sm">
          {q.breakdown.map((line) => (
            <li key={line} className="flex gap-2 text-muted"><span className="text-brand-2">·</span>{line}</li>
          ))}
        </ul>
        {rates.length > 0 && (
          <div>
            <div className="label mb-2">Carrier rates</div>
            <ul className="space-y-1.5">
              {rates.map((r) => {
                const chosen = r.carrier === q.carrier;
                return (
                  <li key={r.carrier} className={`flex items-center justify-between rounded-lg px-3 py-2 text-sm ${chosen ? 'bg-white/[0.06] ring-1 ring-brand/40' : ''}`}>
                    <span className={chosen ? 'font-medium' : 'text-muted'}>{r.carrier}</span>
                    <span className="tabular-nums text-muted">{r.transitDays} d · <span className={chosen ? 'text-text' : ''}>{money(r.cost, r.currency)}</span></span>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
}

function RequestCard({ i }: { i: InstanceDetail }) {
  const r = i.request;
  const rows: [React.ReactNode, string, string][] = [
    [<Scale key="w" />, 'Weight', r?.weightKg != null ? `${r.weightKg.toLocaleString('en')} kg` : '—'],
    [<Package key="p" />, 'Pallets', r?.pallets != null ? String(r.pallets) : '—'],
    [<Truck key="c" />, 'Cargo', r?.cargoType ?? '—'],
    [<Calendar key="d" />, 'Pickup', r?.pickupDate ?? '—'],
    [<Languages key="l" />, 'Language', r?.language?.toUpperCase() ?? '—'],
  ];
  return (
    <section className="glass rise p-6">
      <div className="label mb-3">Request</div>
      <dl className="space-y-2.5 text-sm">
        {rows.map(([icon, label, value]) => (
          <div key={label} className="flex items-center gap-3">
            <span className="text-muted [&>svg]:h-4 [&>svg]:w-4">{icon}</span>
            <dt className="text-muted">{label}</dt>
            <dd className="ml-auto text-right">{value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-4 border-t border-line pt-4 text-sm">
        <div className="label mb-1">Customer</div>
        {i.customer ? (
          <div>{i.customer.name} <span className="text-muted">· {i.customer.id} · {humanize(i.customer.tier)}</span></div>
        ) : (
          <div className="text-muted">Not in the CRM</div>
        )}
      </div>
    </section>
  );
}

function ModelUseCard({ t }: { t: Timeline }) {
  return (
    <section className="glass rise p-6">
      <div className="label mb-3">AI usage</div>
      <div className="grid grid-cols-3 gap-3 text-center">
        <Stat value={t.events.filter((e) => e.type === 'LLM_CALL').length} label="model calls" />
        <Stat value={(t.promptTokens + t.completionTokens).toLocaleString('en')} label="tokens" />
        <Stat value={`€${Number(t.costEur).toFixed(4)}`} label="cost" />
      </div>
    </section>
  );
}

function Stat({ value, label }: { value: React.ReactNode; label: string }) {
  return (
    <div className="rounded-xl bg-white/[0.03] p-3">
      <div className="font-semibold tabular-nums">{value}</div>
      <div className="text-[11px] text-muted">{label}</div>
    </div>
  );
}

// --- timeline

const STEP_NAMES: Record<string, string> = {
  RECEIVED: 'Read the email',
  UNDERSTOOD: 'Look up the customer',
  ENRICHED: 'Get carrier rates and price',
  PRICED: 'Apply the policy',
  APPROVED: 'Write the reply',
  RESPONDED: 'Record in the CRM',
  INVESTIGATING: 'Investigate',
};

function describe(e: TimelineEvent): { icon: React.ReactNode; title: string; tone: string } {
  const d = e.details;
  switch (e.type) {
    case 'STEP': {
      const [state, attempt] = e.title.split(' #');
      const name = STEP_NAMES[state] ?? humanize(state);
      return { icon: <Cog />, title: Number(attempt) > 1 ? `${name} (attempt ${attempt})` : name, tone: 'text-sky-300' };
    }
    case 'LLM_CALL':
      return { icon: <Sparkles />, title: `AI · ${e.title}`, tone: 'text-indigo-300' };
    case 'TOOL_CALL':
      return { icon: <Plug />, title: `${e.title === 'getRates' ? 'Rates' : 'CRM'} · ${e.title}`, tone: 'text-cyan-300' };
    case 'EMAIL_IN':
      return { icon: <Inbox />, title: 'Email from the customer', tone: 'text-violet-300' };
    case 'EMAIL_OUT':
      return { icon: <Send />, title: `Email · ${humanize(e.title.replace(/-\d+$/, ''))}${d.to ? ` to ${d.to}` : ''}`, tone: 'text-emerald-300' };
    case 'APPROVAL':
      return { icon: <ShieldCheck />, title: `Approval · ${e.title.toLowerCase()}`, tone: 'text-amber-300' };
    default:
      return { icon: <Cog />, title: e.title, tone: 'text-muted' };
  }
}

function detail(e: TimelineEvent): string {
  const d = e.details as Record<string, string | number | string[] | undefined>;
  switch (e.type) {
    case 'STEP': return [d.durationMs != null ? `${d.durationMs} ms` : 'running', d.error].filter(Boolean).join(' · ');
    case 'LLM_CALL': return [d.model, `${d.promptTokens ?? 0}+${d.completionTokens ?? 0} tokens`, `${d.latencyMs} ms`, d.error].filter(Boolean).join(' · ');
    case 'TOOL_CALL': return [d.kind, `${d.durationMs} ms`, d.error].filter(Boolean).join(' · ');
    case 'EMAIL_OUT': return d.sentAt ? `sent ${time(d.sentAt as string)}` : `attempts ${d.attempts}`;
    case 'APPROVAL': return [(d.reasons as string[] | undefined)?.map(reason).join(', '), d.decidedBy ? `by ${d.decidedBy}` : null].filter(Boolean).join(' · ');
    default: return '';
  }
}

function TimelineCard({ t }: { t: Timeline | null }) {
  return (
    <section className="glass rise p-6">
      <h2 className="mb-5 text-lg font-medium">What the agent did</h2>
      {!t ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : (
        <ol className="relative space-y-4 before:absolute before:bottom-2 before:left-[15px] before:top-2 before:w-px before:bg-line">
          {t.events.map((e, n) => {
            const { icon, title, tone } = describe(e);
            const bad = ['FAILED', 'DENIED', 'ERROR', 'TIMEOUT', 'REJECTED'].includes(e.status);
            return (
              <li key={n} className="relative flex gap-4">
                <span className={`z-10 grid h-8 w-8 shrink-0 place-items-center rounded-full border border-line bg-panel ${bad ? 'text-rose-300' : tone} [&>svg]:h-4 [&>svg]:w-4`}>
                  {icon}
                </span>
                <div className="min-w-0 flex-1 pt-1">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <span className="text-sm font-medium">{title}</span>
                    <StatusBadge status={e.status} />
                    <span className="ml-auto text-xs tabular-nums text-muted">{time(e.at)}</span>
                  </div>
                  <div className="mt-0.5 truncate text-xs text-muted">{detail(e)}</div>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </section>
  );
}
