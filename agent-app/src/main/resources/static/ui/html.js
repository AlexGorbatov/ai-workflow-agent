// A tagged template that escapes every interpolated value, so text from emails never becomes markup.
// Nested html`` results (and arrays of them) are inserted as they are.

const SAFE = Symbol('safe');

const escape = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

const render = (value) => {
  if (value?.[SAFE] !== undefined) return value[SAFE];
  if (Array.isArray(value)) return value.map(render).join('');
  return escape(value);
};

export function html(strings, ...values) {
  return { [SAFE]: strings.reduce((out, s, i) => out + s + (i < values.length ? render(values[i]) : ''), '') };
}

export function mount(element, template) {
  element.innerHTML = template[SAFE];
}

export const money = (amount, currency) =>
  amount == null ? '—' : `${Number(amount).toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency ?? ''}`;

export const time = (iso) => (iso ? new Date(iso).toLocaleString('en-GB') : '—');

export function age(iso) {
  const minutes = Math.floor((Date.now() - new Date(iso)) / 60000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h` : `${Math.floor(hours / 24)} d`;
}
