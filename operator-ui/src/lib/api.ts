// The agent's API. Errors come back as RFC 9457 problem details; their "detail" is what the user sees.
import { accessToken } from './auth';
import type {
  ApprovalDetail, ApprovalItem, ApprovalStatus, Decision, InstanceDetail, InstanceItem, Page, State, Summary, Timeline,
} from './types';

export class ApiError extends Error {
  constructor(readonly status: number, detail?: string) {
    super(detail || `Request failed (${status})`);
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Authorization: `Bearer ${accessToken()}`,
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
    },
  });
  if (!response.ok) {
    let detail: string | undefined;
    try {
      const problem = await response.json();
      detail = problem.detail || problem.title;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, detail);
  }
  return response.json() as Promise<T>;
}

export const api = {
  summary: () => call<Summary>('/api/v1/instances/summary'),
  instances: (state: State | '', page = 0, size = 20) =>
    call<Page<InstanceItem>>(
      `/api/v1/instances?${new URLSearchParams({ ...(state ? { state } : {}), page: String(page), size: String(size) })}`,
    ),
  instance: (id: string) => call<InstanceDetail>(`/api/v1/instances/${id}`),
  timeline: (id: string) => call<Timeline>(`/api/v1/instances/${id}/timeline`),
  approvals: (status?: ApprovalStatus) => call<ApprovalItem[]>(`/api/v1/approvals${status ? `?status=${status}` : ''}`),
  approval: (id: string) => call<ApprovalDetail>(`/api/v1/approvals/${id}`),
  decide: (id: string, decision: Decision) =>
    call<ApprovalDetail>(`/api/v1/approvals/${id}/decision`, { method: 'POST', body: JSON.stringify(decision) }),
};
