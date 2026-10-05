// The agent's API, as agent-app serves it (api/InstanceViews, approval/ApprovalViews, audit/Timeline).

export const STATES = [
  'RECEIVED', 'AWAIT_REPLY', 'UNDERSTOOD', 'ENRICHED', 'PRICED', 'AWAIT_APPROVAL', 'APPROVED',
  'RESPONDED', 'FOLLOW_UP', 'INVESTIGATING', 'CLOSED', 'EXCEPTION',
] as const;
export type State = (typeof STATES)[number];

export type ApprovalStatus = 'OPEN' | 'ESCALATED' | 'APPROVED' | 'REJECTED' | 'RETRIED';

export interface InstanceItem {
  id: string;
  state: State;
  customer: string | null;
  route: string | null;
  price: number | null;
  currency: string | null;
  flags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> { items: T[]; page: number; size: number; total: number }

export interface QuoteRequest {
  origin: string; destination: string; weightKg: number | null; pallets: number | null;
  cargoType: string | null; pickupDate: string | null; language: string | null;
}
export interface Customer { id: string; name: string; email: string; tier: string }
export interface CarrierRate { carrier: string; cost: number; currency: string; transitDays: number }
export interface Quote {
  carrier: string; cost: number; price: number; marginPct: number; currency: string;
  validUntil: string | null; breakdown: string[];
}
export interface Investigation { summary: string; likelyCause: string; suggestedAction: string; evidence: string[] }

export interface InstanceDetail {
  id: string; state: State; createdAt: string; updatedAt: string;
  sender: string | null; subject: string | null; replies: number;
  request: QuoteRequest | null; customer: Customer | null; rates: CarrierRate[]; quote: Quote | null;
  policy: { auto: boolean; reasons: string[] } | null; investigation: Investigation | null;
  flags: string[]; closeReason: string | null; error: string | null;
}

export interface TimelineEvent {
  at: string;
  type: 'STEP' | 'LLM_CALL' | 'TOOL_CALL' | 'EMAIL_IN' | 'EMAIL_OUT' | 'APPROVAL' | string;
  title: string;
  status: string;
  details: Record<string, unknown>;
}
export interface Timeline {
  instanceId: string; state: State; createdAt: string; updatedAt: string;
  promptTokens: number; completionTokens: number; costEur: number; events: TimelineEvent[];
}

export interface ApprovalItem {
  id: string; instanceId: string; kind: 'QUOTE' | 'INVESTIGATION'; round: number; status: ApprovalStatus;
  reasons: string[]; dueAt: string; createdAt: string; customer: string | null; route: string | null;
  price: number | null; currency: string | null;
}

export interface ApprovalDetail {
  id: string; instanceId: string; instanceState: State; kind: 'QUOTE' | 'INVESTIGATION'; round: number;
  status: ApprovalStatus; reasons: string[]; dueAt: string; createdAt: string;
  decidedBy: string | null; decidedAt: string | null; comment: string | null; approvedPrice: number | null;
  summary: string | null; sender: string | null; customer: Customer | null; request: QuoteRequest | null;
  rates: CarrierRate[]; quote: Quote | null; flags: string[]; error: string | null;
  investigation: Investigation | null;
}

export interface Decision {
  action: 'APPROVE' | 'REJECT' | 'RETRY';
  editedPrice?: number;
  comment?: string | null;
  retryFrom?: 'UNDERSTOOD' | 'ENRICHED';
}

export interface Summary { total: number; byState: Partial<Record<State, number>>; openApprovals: number }
