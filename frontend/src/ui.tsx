import { AlertCircle, ChevronRight, ClipboardList, LoaderCircle, X } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { Fee } from './types';
import { isOverdue } from './lib';
import { useI18n } from './i18n';

export function PageHeading({ title, description, children }: {
  title: string;
  description?: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {children && <div className="page-actions">{children}</div>}
    </div>
  );
}

export function PanelHeading({ title, detail, children }: { title: string; detail?: string; children?: ReactNode }) {
  return (
    <div className="panel-heading">
      <div>
        <h2>{title}</h2>
        {detail && <p>{detail}</p>}
      </div>
      {children}
    </div>
  );
}

export function Empty({ message }: { message?: string }) {
  const { t } = useI18n();
  return <div className="empty"><ClipboardList size={23} /><p>{message ?? t('暂无记录', 'No records yet')}</p></div>;
}

export function Loading({ label }: { label?: string }) {
  const { t } = useI18n();
  return <div className="loading" role="status"><LoaderCircle className="spinner" size={17} />{label ?? t('正在加载', 'Loading')}</div>;
}

export function ErrorNotice({ error, onRetry }: { error: string; onRetry?: () => void }) {
  const { t } = useI18n();
  return (
    <div role="alert" className="notice notice-error">
      <AlertCircle size={17} /><span>{error}</span>
      {onRetry && <button type="button" className="text-button" onClick={onRetry}>{t('重试', 'Retry')}</button>}
    </div>
  );
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'green' | 'amber' | 'red' | 'indigo' }) {
  return <span className={'badge badge-' + tone}>{children}</span>;
}

export function FeeBadge({ fee }: { fee: Fee }) {
  const { t } = useI18n();
  if (fee.status === 'PAID') return <Badge tone="green">{t('已结算', 'Settled')}</Badge>;
  if (fee.status === 'NON_PAYMENT') return <Badge>{t('非付款结算', 'Non-payment settlement')}</Badge>;
  if (isOverdue(fee)) return <Badge tone="red">{t('已逾期', 'Overdue')}</Badge>;
  return <Badge tone="amber">{t('待结算', 'Awaiting settlement')}</Badge>;
}

export function SectionLink({ href, children }: { href: string; children: ReactNode }) {
  return <a className="text-button section-link" href={href}>{children}<ChevronRight size={15} /></a>;
}

export function Modal({ title, onClose, busy = false, children }: {
  title: string;
  onClose: () => void;
  busy?: boolean;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  return (
    <dialog
      ref={dialog}
      className="modal"
      aria-label={title}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
    >
      <header className="modal-heading">
        <h2>{title}</h2>
        <button aria-label={t('关闭', 'Close')} type="button" className="icon-button" disabled={busy} onClick={onClose}><X size={20} /></button>
      </header>
      {children}
    </dialog>
  );
}
