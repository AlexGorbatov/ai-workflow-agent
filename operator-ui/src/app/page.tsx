'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import {
  AlertTriangle, ArrowRight, CheckCircle2, Clock3, Inbox, MailQuestion, Search, Send, ShieldAlert, Sparkles,
} from 'lucide-react';
import { api } from '@/lib/api';
import { ago, due, money, reason } from '@/lib/format';
import type { ApprovalItem, State, Summary } from '@/lib/types';
import { Pill, StateBadge } from '@/components/badges';
import { isApprover, useUser } from '@/components/session';
import { useNow, usePoll } from '@/components/use-poll';

const IN_PROGRESS: State[] = ['RECEIVED', 'UNDERSTOOD', 'ENRICHED', 'PRICED', 'APPROVED', 'RESPONDED', 'INVESTIGATING'];

const PIPELINE: { label: string; states: State[]; color: string }[] = [
  { label: 'In progress', states: IN_PROGRESS, color: 'bg-sky-400' },
  { label: 'Needs approval', states: ['AWAIT_APPROVAL'], color: 'bg-amber-400' },
  { label: 'Asked customer', states: ['AWAIT_REPLY'], color: 'bg-violet-400' },
  { label: 'Quote sent', states: ['FOLLOW_UP'], color: 'bg-emerald-400' },
  { label: 'Closed', states: ['CLOSED'], color: 'bg-slate-500' },
  { label: 'Exception', states: ['EXCEPTION'], color: 'bg-rose-500' },
];

const FILTERS: { label: string; state: State | '' }[] = [
  { label: 'All', state: '' },
  { label: 'Needs approval', state: 'AWAIT_APPROVAL' },
  { label: 'Asked customer', state: 'AWAIT_REPLY' },
  { label: 'Quote sent', state: 'FOLLOW_UP' },
  { label: 'Closed', state: 'CLOSED' },
  { label: 'Exception', state: 'EXCEPTION' },
];

const count = (s: Summary | null, states: State[]) => states.reduce((n, st) => n + (s?.byState[st] ?? 0), 0);

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
}

export default function Dashboard() {
  const user = useUser();
  const approver = isApprover(user);
  const now = useNow();
  const [filter, setFilter] = useState<State | ''>('');
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');

  const summary = usePoll(() => api.summary(), 5000);
  const approvals = usePoll(() => (approver ? api.approvals() : Promise.resolve([] as ApprovalItem[])), 5000);
  const instances = usePoll(() => api.instances(filter, page, 15), 5000, [filter, page]);

  const s = summary.data;
  const rows = useMemo(() => {
    const items = instances.data?.items ?? [];
    const q = query.trim().toLowerCase();
    return q ? items.filter((i) => `${i.customer} ${i.route}`.toLowerCase().includes(q)) : items;
  }, [instances.data, query]);
  const pages = instances.data ? Math.max(1, Math.ceil(instances.data.total / instances.data.size)) : 1;
  const waiting = approvals.data?.filter((a) => a.status === 'OPEN' || a.status === 'ESCALATED') ?? [];

  return (
    <div className="space-y-10">
      {/* Hero */}
      <section className="rise flex flex-col gap-6 md:flex-row md:items-end md:justify-between">
        <div>
          <div className="mb-3 inline-flex items-center gap-2 rounded-full border border-line bg-white/[0.03] px-3 py-1 text-xs text-muted">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 pulse-dot" /> Live · updates every 5 s
          </div>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            <span className="text-gradient">{greeting()}, {user.name}.</span>
          </h1>
          <p className="mt-2 max-w-2xl text-muted">
            The agent reads quote requests from email, prices them with your rules and answers on its own — and asks
            you when a quote needs a person.
          </p>
        </div>
        {approver && waiting.length > 0 && (
          <a href="#approvals" className="btn btn-primary self-start md:self-auto">
            <ShieldAlert className="h-4 w-4" /> {waiting.length} waiting for you
          </a>
        )}
      </section>

      {/* KPIs */}
      <section className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi icon={<Inbox />} label="Requests" value={s?.total} hint="all time" delay={0} />
        <Kpi icon={<ShieldAlert />} label="Waiting for approval" value={s?.openApprovals} hint="decided by a person" tone="amber" delay={60} />
        <Kpi icon={<Send />} label="Quotes sent" value={count(s, ['FOLLOW_UP'])} hint="awaiting the customer" tone="green" delay={120} />
        <Kpi icon={<MailQuestion />} label="Asked customer" value={count(s, ['AWAIT_REPLY'])} hint="missing details" tone="violet" delay={180} />
      </section>

      {/* Pipeline */}
      <section className="glass rise p-5 sm:p-6" style={{ animationDelay: '120ms' }}>
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-medium">Pipeline</h2>
          <span className="text-xs text-muted">{s ? `${s.total} requests` : '—'}</span>
        </div>
        <div className="flex h-3 w-full overflow-hidden rounded-full bg-white/5">
          {PIPELINE.map((p) => {
            const n = count(s, p.states);
            return n > 0 && s?.total ? (
              <div key={p.label} className={`${p.color} transition-all duration-700`} style={{ width: `${(n / s.total) * 100}%` }} />
            ) : null;
          })}
        </div>
        <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 text-sm sm:grid-cols-3 lg:grid-cols-6">
          {PIPELINE.map((p) => (
            <div key={p.label} className="flex items-center gap-2">
              <span className={`h-2.5 w-2.5 rounded-sm ${p.color}`} />
              <span className="text-muted">{p.label}</span>
              <span className="ml-auto font-medium tabular-nums lg:ml-1">{count(s, p.states)}</span>
            </div>
          ))}
        </div>
      </section>

      {/* Approvals */}
      <section id="approvals" className="scroll-mt-24">
        <SectionTitle icon={<ShieldAlert className="h-4 w-4" />} title="Waiting for you" count={approver ? waiting.length : undefined} />
        {!approver ? (
          <div className="glass p-6 text-sm text-muted">Quotes that need a person are decided by approvers. You can follow every request below.</div>
        ) : waiting.length === 0 ? (
          <div className="glass flex items-center gap-3 p-6 text-sm text-muted">
            <CheckCircle2 className="h-5 w-5 text-emerald-300" /> Nothing is waiting — the agent has it covered.
          </div>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {waiting.map((a, i) => (
              <ApprovalCard key={a.id} a={a} now={now} delay={i * 50} />
            ))}
          </div>
        )}
      </section>

      {/* Requests */}
      <section>
        <SectionTitle icon={<Inbox className="h-4 w-4" />} title="Requests" count={instances.data?.total} />
        <div className="mb-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap gap-2">
            {FILTERS.map((f) => (
              <button
                key={f.label}
                onClick={() => { setFilter(f.state); setPage(0); }}
                className={`rounded-full px-3.5 py-1.5 text-sm transition ${
                  filter === f.state ? 'bg-white text-ink' : 'border border-line bg-white/[0.03] text-muted hover:text-text'
                }`}
              >
                {f.label}
              </button>
            ))}
          </div>
          <label className="relative block lg:w-72">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <input className="field !pl-9" placeholder="Search customer or route" value={query} onChange={(e) => setQuery(e.target.value)} />
          </label>
        </div>

        <div className="glass overflow-hidden">
          {/* table on wide screens */}
          <table className="hidden w-full text-sm md:table">
            <thead>
              <tr className="border-b border-line text-left text-xs uppercase tracking-wider text-muted">
                <th className="px-5 py-3 font-medium">Customer</th>
                <th className="px-5 py-3 font-medium">Route</th>
                <th className="px-5 py-3 text-right font-medium">Quote</th>
                <th className="px-5 py-3 font-medium">Status</th>
                <th className="px-5 py-3 text-right font-medium">Received</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((i) => (
                <tr key={i.id} className="group border-b border-line/60 last:border-0 hover:bg-white/[0.03]">
                  <td className="px-5 py-3.5">
                    <Link href={`/instance/?id=${i.id}`} className="font-medium group-hover:text-white">
                      {i.customer ?? '—'}
                    </Link>
                    {i.flags.length > 0 && (
                      <div className="mt-1 flex flex-wrap gap-1">
                        {i.flags.slice(0, 2).map((f) => (
                          <span key={f} className="text-[11px] text-amber-300/80">{reason(`flag:${f}`)}</span>
                        ))}
                      </div>
                    )}
                  </td>
                  <td className="px-5 py-3.5 text-muted">{i.route ?? '—'}</td>
                  <td className="px-5 py-3.5 text-right font-medium tabular-nums">{money(i.price, i.currency)}</td>
                  <td className="px-5 py-3.5"><StateBadge state={i.state} /></td>
                  <td className="px-5 py-3.5 text-right text-muted">{ago(i.createdAt, now)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {/* cards on phones */}
          <ul className="divide-y divide-line md:hidden">
            {rows.map((i) => (
              <li key={i.id}>
                <Link href={`/instance/?id=${i.id}`} className="flex items-start justify-between gap-3 p-4">
                  <div className="min-w-0">
                    <div className="truncate font-medium">{i.customer ?? '—'}</div>
                    <div className="truncate text-sm text-muted">{i.route ?? 'Route not known yet'}</div>
                    <div className="mt-2"><StateBadge state={i.state} /></div>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-medium tabular-nums">{money(i.price, i.currency)}</div>
                    <div className="text-xs text-muted">{ago(i.createdAt, now)}</div>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
          {instances.data && rows.length === 0 && <p className="p-8 text-center text-sm text-muted">No requests here.</p>}
          {!instances.data && !instances.error && <p className="p-8 text-center text-sm text-muted">Loading…</p>}
          {instances.error && <p className="p-8 text-center text-sm text-rose-300">{instances.error.message}</p>}
        </div>

        {pages > 1 && (
          <div className="mt-4 flex items-center justify-end gap-3 text-sm text-muted">
            <button className="btn btn-ghost !py-1.5" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
            <span>Page {page + 1} of {pages}</span>
            <button className="btn btn-ghost !py-1.5" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</button>
          </div>
        )}
      </section>
    </div>
  );
}

function SectionTitle({ icon, title, count }: { icon: React.ReactNode; title: string; count?: number }) {
  return (
    <div className="mb-4 flex items-center gap-2">
      <span className="text-muted">{icon}</span>
      <h2 className="text-lg font-medium">{title}</h2>
      {count != null && <span className="rounded-full bg-white/5 px-2 py-0.5 text-xs text-muted">{count}</span>}
    </div>
  );
}

const KPI_TONE = {
  brand: 'from-brand/30 text-indigo-200',
  amber: 'from-amber-400/25 text-amber-200',
  green: 'from-emerald-400/25 text-emerald-200',
  violet: 'from-violet-400/25 text-violet-200',
};

function Kpi({ icon, label, value, hint, tone = 'brand', delay }: {
  icon: React.ReactNode; label: string; value?: number; hint: string; tone?: keyof typeof KPI_TONE; delay: number;
}) {
  return (
    <div className="glass rise relative overflow-hidden p-5" style={{ animationDelay: `${delay}ms` }}>
      <div className={`absolute -right-6 -top-6 h-24 w-24 rounded-full bg-gradient-to-br ${KPI_TONE[tone]} to-transparent blur-2xl`} />
      <div className={`mb-4 grid h-9 w-9 place-items-center rounded-xl bg-white/5 [&>svg]:h-4.5 [&>svg]:w-4.5 ${KPI_TONE[tone].split(' ')[1]}`}>
        {icon}
      </div>
      <div className="text-3xl font-semibold tabular-nums tracking-tight">{value ?? '—'}</div>
      <div className="mt-1 text-sm">{label}</div>
      <div className="text-xs text-muted">{hint}</div>
    </div>
  );
}

function ApprovalCard({ a, now, delay }: { a: ApprovalItem; now: number; delay: number }) {
  const escalated = a.status === 'ESCALATED';
  return (
    <Link
      href={`/instance/?id=${a.instanceId}`}
      className="glass rise group flex flex-col p-5 transition hover:-translate-y-0.5 hover:border-white/20"
      style={{ animationDelay: `${delay}ms` }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate font-medium">{a.customer ?? '—'}</div>
          <div className="truncate text-sm text-muted">{a.route ?? '—'}</div>
        </div>
        {a.kind === 'INVESTIGATION' ? (
          <Pill tone="violet"><Sparkles className="h-3 w-3" /> Investigation</Pill>
        ) : (
          <div className="text-right text-lg font-semibold tabular-nums">{money(a.price, a.currency)}</div>
        )}
      </div>
      <div className="mt-4 flex flex-wrap gap-1.5">
        {a.reasons.map((r) => (
          <Pill key={r} tone="amber"><AlertTriangle className="h-3 w-3" /> {reason(r)}</Pill>
        ))}
      </div>
      <div className="mt-5 flex items-center justify-between border-t border-line pt-4 text-xs">
        <span className={`inline-flex items-center gap-1.5 ${escalated ? 'text-rose-300' : 'text-muted'}`}>
          <Clock3 className="h-3.5 w-3.5" />
          {escalated ? `Escalated · ${due(a.dueAt, now)}` : `SLA ${due(a.dueAt, now)}`}
        </span>
        <span className="inline-flex items-center gap-1 text-text/80 group-hover:text-white">
          Review <ArrowRight className="h-3.5 w-3.5 transition group-hover:translate-x-0.5" />
        </span>
      </div>
    </Link>
  );
}
