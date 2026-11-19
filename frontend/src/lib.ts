import { browserLocale, getLanguage } from './i18n';
import type { Language } from './i18n';
import type { DocumentKind, Fee, FeeType } from './types';

const feeTypeCopy: Record<Language, Record<FeeType, string>> = {
  zh: { APPLICATION: '申请费', PERIODIC: '定期费用', SURCHARGE: '附加费' },
  en: { APPLICATION: 'Application fee', PERIODIC: 'Periodic fee', SURCHARGE: 'Surcharge' },
};

const documentKindCopy: Record<Language, Record<DocumentKind, string>> = {
  zh: { NOTICE: '缴费通知', REMINDER: '催缴提醒', RECEIPT: '收据' },
  en: { NOTICE: 'Payment notice', REMINDER: 'Payment reminder', RECEIPT: 'Receipt' },
};

const deliveryCopy: Record<Language, Record<string, string>> = {
  zh: { NOT_SENT: '未投递', SENT: '已发送', BOUNCED: '已退信' },
  en: { NOT_SENT: 'Not sent', SENT: 'Sent', BOUNCED: 'Bounced' },
};

const fpsCopy: Record<Language, Record<string, string>> = {
  zh: { DEMO: '演示请求', EXPIRED: '已过期', SETTLED: '已结算' },
  en: { DEMO: 'Demo request', EXPIRED: 'Expired', SETTLED: 'Settled' },
};

export const feeTypeLabels = (language: Language) => feeTypeCopy[language];
export const documentKindLabels = (language: Language) => documentKindCopy[language];
export const deliveryLabels = (language: Language) => deliveryCopy[language];
export const fpsLabels = (language: Language) => fpsCopy[language];

const clock = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Hong_Kong' });

export function today() {
  return clock.format(new Date());
}

export function isOverdue(fee: Fee, date = today()) {
  return fee.status === 'UNPAID' && fee.dueDate < date;
}

export function feeReference(fee: Pick<Fee, 'id'>) {
  return 'FEE-' + String(fee.id).padStart(4, '0');
}

let csrf: { header: string; token: string } | undefined;

export function setRequestCsrf(header: string, token: string) {
  csrf = { header, token };
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const language = getLanguage();
  const headers = new Headers(options?.headers);
  headers.set('Accept-Language', browserLocale(language));
  const method = options?.method?.toUpperCase() ?? 'GET';
  if (csrf && method !== 'GET' && method !== 'HEAD') headers.set(csrf.header, csrf.token);
  const response = await fetch('/api' + path, { ...options, headers });
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/')) {
      window.dispatchEvent(new Event('omsweb:session-expired'));
    }
    const payload = await response.json().catch(() => null);
    const fallback = language === 'en'
      ? 'Request failed (' + response.status + ').'
      : '请求失败（' + response.status + '）。';
    throw new Error(payload?.detail || payload?.message || fallback);
  }
  return response.status === 204 ? undefined as T : response.json();
}

export function json(method: 'POST' | 'PUT', payload: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  };
}

export function errorText(error: unknown) {
  if (error instanceof Error && !(error instanceof TypeError)) return error.message;
  return getLanguage() === 'en' ? 'The operation failed. Please try again.' : '操作失败，请稍后重试。';
}
