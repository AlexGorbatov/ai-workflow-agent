import type { ApprovalStatus, State } from '@/lib/types';
import { humanize } from '@/lib/format';

type Tone = 'brand' | 'green' | 'amber' | 'rose' | 'sky' | 'slate' | 'violet';

const TONES: Record<Tone, string> = {
  brand: 'bg-brand/15 text-indigo-200 ring-brand/40',
  green: 'bg-emerald-400/10 text-emerald-300 ring-emerald-400/30',
  amber: 'bg-amber-400/10 text-amber-300 ring-amber-400/30',
  rose: 'bg-rose-400/10 text-rose-300 ring-rose-400/30',
  sky: 'bg-sky-400/10 text-sky-300 ring-sky-400/30',
  slate: 'bg-white/5 text-slate-300 ring-white/15',
  violet: 'bg-violet-400/10 text-violet-300 ring-violet-400/30',
};

export const STATE_TONE: Record<State, Tone> = {
  RECEIVED: 'sky',
  UNDERSTOOD: 'sky',
  ENRICHED: 'sky',
  PRICED: 'sky',
  APPROVED: 'brand',
  RESPONDED: 'brand',
  AWAIT_REPLY: 'violet',
  AWAIT_APPROVAL: 'amber',
  FOLLOW_UP: 'green',
  INVESTIGATING: 'amber',
  CLOSED: 'slate',
  EXCEPTION: 'rose',
};

/** What a state means to an operator, in words. */
export const STATE_LABEL: Record<State, string> = {
  RECEIVED: 'Received',
  UNDERSTOOD: 'Understood',
  ENRICHED: 'Customer checked',
  PRICED: 'Priced',
  APPROVED: 'Approved',
  RESPONDED: 'Quote sent',
  AWAIT_REPLY: 'Asked customer',
  AWAIT_APPROVAL: 'Needs approval',
  FOLLOW_UP: 'Quote sent',
  INVESTIGATING: 'Investigating',
  CLOSED: 'Closed',
  EXCEPTION: 'Exception',
};

const STATUS_TONE: Record<string, Tone> = {
  OPEN: 'amber', ESCALATED: 'rose', APPROVED: 'green', REJECTED: 'rose', RETRIED: 'sky',
  SUCCEEDED: 'green', OK: 'green', SENT: 'green', RECEIVED: 'sky', RUNNING: 'sky', PENDING: 'amber',
  FAILED: 'rose', DENIED: 'rose', ERROR: 'rose', TIMEOUT: 'rose', ABANDONED: 'slate',
};

export function Pill({ tone = 'slate', children }: { tone?: Tone; children: React.ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${TONES[tone]}`}>
      {children}
    </span>
  );
}

export function StateBadge({ state }: { state: State }) {
  return (
    <Pill tone={STATE_TONE[state]}>
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      {STATE_LABEL[state]}
    </Pill>
  );
}

export function StatusBadge({ status }: { status: ApprovalStatus | string }) {
  return <Pill tone={STATUS_TONE[status] ?? 'slate'}>{humanize(status)}</Pill>;
}
