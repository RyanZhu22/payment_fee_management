import { useWorkspace } from './Session';
import { CalendarClock, Pencil, Plus, Search } from 'lucide-react';
import { useState } from 'react';
import type { FormEvent } from 'react';
import type { Fee, FeeInput, FeeType } from './types';
import { useI18n } from './i18n';
import { errorText, feeReference, feeTypeLabels, isOverdue, json, request, today } from './lib';
import { Empty, ErrorNotice, FeeBadge, Loading, Modal, PageHeading } from './ui';

type PeriodicRunResult = { created: number; period: string };

export function FeeEditor({ fee, fees, onClose, onSave }: {
  fee: Fee | null;
  fees: Fee[];
  onClose: () => void;
  onSave: (fee: Fee) => void;
}) {
  const { language, locale, t, date } = useI18n();
  const feeTypes = feeTypeLabels(language);
  const [type, setType] = useState<FeeType>(fee?.type || 'APPLICATION');
  const [selectedSource, setSelectedSource] = useState(fee?.sourceFeeId?.toString() ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const source = fees.find((item) => item.id.toString() === selectedSource);
  const linked = type === 'SURCHARGE' ? source : undefined;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    const form = new FormData(event.currentTarget);
    const string = (name: string) => (form.get(name) as string).trim();
    const payload: FeeInput = {
      type,
      applicationNumber: string('applicationNumber'),
      scheme: string('scheme'),
      payer: string('payer'),
      payerEmail: string('payerEmail'),
      description: string('description'),
      amount: Number(form.get('amount')),
      dueDate: string('dueDate'),
      sourceFeeId: type === 'SURCHARGE' ? Number(selectedSource) : null,
    };
    try {
      const saved = await request<Fee>(fee ? '/fees/' + fee.id : '/fees', json(fee ? 'PUT' : 'POST', payload));
      onSave(saved);
      onClose();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={fee ? t('编辑费用', 'Edit fee') + ' · ' + feeReference(fee) : t('新建费用', 'New fee')} onClose={onClose} busy={busy}>
      <form onSubmit={submit}>
        <div className="modal-content">
          {error && <ErrorNotice error={error} />}
          {fee && <div className="fee-edit-status">
            <FeeBadge fee={fee} />
            {fee.settlementDate && <span>{t('结算日', 'Settlement date')} {date(fee.settlementDate)}</span>}
            {fee.bankInDate && <span>{t('入账日', 'Bank-in date')} {date(fee.bankInDate)}</span>}
            {fee.referenceNumber && <span>{t('参考号', 'Reference number')} {fee.referenceNumber}</span>}
          </div>}
          <fieldset className="form-grid" disabled={busy}>
            <label>{t('费用类型', 'Fee type')}<select value={type} onChange={(event) => setType(event.target.value as FeeType)}>
              {Object.entries(feeTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <label>{t('到期日', 'Due date')}<input name="dueDate" type="date" lang={locale} required defaultValue={fee?.dueDate ?? today()} /></label>
            {type === 'SURCHARGE' && (
              <label className="span-two">{t('原费用', 'Original fee')}<select value={selectedSource} onChange={(event) => setSelectedSource(event.target.value)} required>
                <option value="">{t('选择关联的费用', 'Select the original fee')}</option>
                {fees.filter((item) => item.type === 'PERIODIC' && (isOverdue(item) || item.id === fee?.sourceFeeId)).map((item) => (
                  <option key={item.id} value={item.id}>{feeReference(item)} · {item.payer} · {item.description}</option>
                ))}
              </select></label>
            )}
            <label key={'scheme-' + (linked?.id ?? '')}>{t('所属计划', 'Scheme')}<input name="scheme" required defaultValue={linked?.scheme ?? fee?.scheme ?? ''} /></label>
            <label key={'application-' + (linked?.id ?? '')}>{t('申请编号', 'Application number')}<input name="applicationNumber" required={type === 'APPLICATION'} defaultValue={linked?.applicationNumber ?? fee?.applicationNumber ?? ''} /></label>
            <label key={'payer-' + (linked?.id ?? '')}>{t('付款方', 'Payer')}<input name="payer" required defaultValue={linked?.payer ?? fee?.payer ?? ''} /></label>
            <label key={'email-' + (linked?.id ?? '')}>{t('付款方邮箱', 'Payer email')}<input name="payerEmail" type="email" defaultValue={linked?.payerEmail ?? fee?.payerEmail ?? ''} /></label>
            <label>{t('金额（HKD）', 'Amount (HKD)')}<input name="amount" inputMode="decimal" type="number" required min="0.01" step="0.01" defaultValue={fee?.amount} /></label>
            <label>{t('费用说明', 'Fee description')}<input name="description" required defaultValue={fee?.description ?? ''} /></label>
          </fieldset>
        </div>
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={busy}>{t('取消', 'Cancel')}</button>
          <button type="submit" className="button primary" disabled={busy}>{busy ? t('正在保存…', 'Saving…') : t('保存费用', 'Save fee')}</button>
        </footer>
      </form>
    </Modal>
  );
}

export default function Fees({ fees, loading, onCreate, onEdit, refresh }: {
  fees: Fee[];
  loading: boolean;
  onCreate: () => void;
  onEdit: (fee: Fee) => void;
  refresh: () => Promise<void>;
}) {
  const { language, t, money, date } = useI18n();
  const feeTypes = feeTypeLabels(language);
  const { can } = useWorkspace();
  const canEdit = can('OPERATOR');
  const [search, setSearch] = useState('');
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [runResult, setRunResult] = useState<PeriodicRunResult | null>(null);

  const keyword = search.trim().toLocaleLowerCase();
  const rows = fees.filter((fee) =>
    (!type || fee.type === type)
    && (!status || (status === 'OVERDUE' ? isOverdue(fee) : fee.status === status))
    && (!keyword || [feeReference(fee), String(fee.id), fee.applicationNumber, fee.scheme, fee.payer, fee.description]
      .some((field) => field?.toLocaleLowerCase().includes(keyword)))
  );

  async function runPeriodic() {
    setRunning(true);
    setError('');
    setRunResult(null);
    try {
      const result = await request<PeriodicRunResult>('/fees/periodic-run', { method: 'POST' });
      setRunResult(result);
      await refresh();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="page-content">
      <PageHeading title={t('费用台账', 'Fee ledger')} description={t('维护申请费、定期费用以及由原费用关联的附加费。', 'Manage application fees, recurring fees, and surcharges linked to their original fees.')}>
        {can('FINANCE') && <button className="button secondary" onClick={runPeriodic} disabled={running}><CalendarClock size={16} />{running ? t('正在生成…', 'Generating…') : t('运行定期计费', 'Run recurring billing')}</button>}
        {canEdit && <button className="button primary" onClick={onCreate}><Plus size={17} />{t('新建费用', 'New fee')}</button>}
      </PageHeading>
      {error && <ErrorNotice error={error} />}
      {runResult && <div className="notice notice-success" role="status">{t(
        `${runResult.period} 已完成定期计费，新增 ${runResult.created} 条费用。`,
        `Periodic billing for ${runResult.period} completed. ${runResult.created} new ${runResult.created === 1 ? 'fee' : 'fees'} added.`,
      )}</div>}
      <section className="panel">
        <div className="table-toolbar">
          <label className="search-box"><Search size={17} /><span className="visually-hidden">{t('搜索费用', 'Search fees')}</span>
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t('搜索编号、付款方或计划', 'Search by ID, payer, or scheme')} />
          </label>
          <select aria-label={t('费用类型', 'Fee type')} value={type} onChange={(event) => setType(event.target.value)}>
            <option value="">{t('全部类型', 'All types')}</option>
            {Object.entries(feeTypes).map(([value, label]) => <option value={value} key={value}>{label}</option>)}
          </select>
          <select aria-label={t('结算状态', 'Settlement status')} value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">{t('全部状态', 'All statuses')}</option>
            <option value="UNPAID">{t('未结算（含逾期）', 'Unsettled (including overdue)')}</option>
            <option value="OVERDUE">{t('已逾期', 'Overdue')}</option>
            <option value="PAID">{t('已结算', 'Settled')}</option>
            <option value="NON_PAYMENT">{t('非付款结算', 'Non-payment settlement')}</option>
          </select>
          <span className="table-count">{t(`${rows.length} 条费用`, `${rows.length} ${rows.length === 1 ? 'fee' : 'fees'}`)}</span>
        </div>
        {loading ? <Loading /> : !rows.length ? <Empty message={t('没有符合条件的费用', 'No fees match these filters')} /> : (
          <div className="table-wrap"><table>
            <thead><tr>
              <th>{t('费用编号', 'Fee ID')}</th>
              <th>{t('付款方 / 计划', 'Payer / scheme')}</th>
              <th>{t('说明', 'Description')}</th>
              <th>{t('类型', 'Type')}</th>
              <th>{t('到期日', 'Due date')}</th>
              <th className="numeric">{t('金额', 'Amount')}</th>
              <th>{t('状态', 'Status')}</th>
              <th><span className="visually-hidden">{t('操作', 'Actions')}</span></th>
            </tr></thead>
            <tbody>{rows.map((fee) => (
              <tr key={fee.id}>
                <td>{canEdit && fee.status === 'UNPAID' ? <button className="table-link" onClick={() => onEdit(fee)}>{feeReference(fee)}</button> : feeReference(fee)}
                  {fee.applicationNumber && <small>{fee.applicationNumber}</small>}</td>
                <td><strong>{fee.payer}</strong><small>{fee.scheme}</small></td>
                <td>{fee.description}{fee.sourceFeeId && <small>{t('原费用', 'Original fee')} {feeReference({ id: fee.sourceFeeId })}</small>}</td>
                <td>{feeTypes[fee.type]}</td><td>{date(fee.dueDate)}</td><td className="numeric strong-cell">{money(fee.amount)}</td>
                <td><FeeBadge fee={fee} /></td>
                <td>{canEdit && fee.status === 'UNPAID' && <button type="button" className="icon-button" onClick={() => onEdit(fee)} aria-label={t('编辑 ', 'Edit ') + feeReference(fee)}><Pencil size={15} /></button>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </div>
  );
}
