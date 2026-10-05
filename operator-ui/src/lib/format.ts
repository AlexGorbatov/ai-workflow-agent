export const money = (amount: number | null | undefined, currency?: string | null) =>
  amount == null
    ? '—'
    : `${Number(amount).toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency ?? ''}`.trim();

export const time = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';

export function ago(iso: string, now = Date.now()) {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h ago` : `${Math.floor(hours / 24)} d ago`;
}

/** "in 1 min 20 s", or "overdue 3 min". */
export function due(iso: string, now = Date.now()) {
  const diff = Math.round((new Date(iso).getTime() - now) / 1000);
  const abs = Math.abs(diff);
  const text = abs < 60 ? `${abs} s` : abs < 3600 ? `${Math.floor(abs / 60)} min` : `${Math.floor(abs / 3600)} h`;
  return diff >= 0 ? `in ${text}` : `overdue ${text}`;
}

/** "flag:NEW_CUSTOMER" → "New customer", "amount>5000" → "Amount above 5000". */
export function reason(r: string) {
  const flag = r.startsWith('flag:') ? r.slice(5) : null;
  if (flag) return flag.charAt(0) + flag.slice(1).toLowerCase().replace(/_/g, ' ');
  const amount = r.match(/^amount>(.+)$/);
  if (amount) return `Amount above ${amount[1]}`;
  const margin = r.match(/^margin<(.+)$/);
  if (margin) return `Margin below ${margin[1]}%`;
  return r.replace(/_/g, ' ').toLowerCase();
}

export const humanize = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ');
