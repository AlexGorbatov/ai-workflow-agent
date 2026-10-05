// The agent's API. Errors come back as RFC 9457 problem details; their "detail" is shown to the user.
import { accessToken } from './auth.js';

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `Request failed (${status})`);
    this.status = status;
  }
}

async function call(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      Authorization: `Bearer ${accessToken()}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    },
  });
  if (!response.ok) {
    let detail;
    try {
      const problem = await response.json();
      detail = problem.detail || problem.title;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, detail);
  }
  return response.json();
}

export const api = {
  instances: (state, page) =>
    call(`/api/v1/instances?${new URLSearchParams({ ...(state ? { state } : {}), page, size: 20 })}`),
  instance: (id) => call(`/api/v1/instances/${id}`),
  timeline: (id) => call(`/api/v1/instances/${id}/timeline`),
  approvals: (status) => call(`/api/v1/approvals${status ? `?status=${status}` : ''}`),
  approval: (id) => call(`/api/v1/approvals/${id}`),
  decide: (id, decision) =>
    call(`/api/v1/approvals/${id}/decision`, { method: 'POST', body: JSON.stringify(decision) }),
};
