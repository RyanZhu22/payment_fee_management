import { Download, Upload } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useI18n } from './i18n';
import { errorText, feeReference, json, request } from './lib';
import { useWorkspace } from './Session';
import type { Fee } from './types';
import { Badge, Empty, ErrorNotice, Loading, Modal, PageHeading, PanelHeading } from './ui';

type Status = 'PENDING_REVIEW' | 'VALIDATION_FAILED' | 'APPROVED' | 'REJECTED';
type Format = 'APPLN' | 'FEESU';

interface ReviewRecord {
  id: number;
  status: Status;
  createdBy: string;
  createdAt: string;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  reason?: string | null;
}

interface Batch extends ReviewRecord {
  type: Format;
  filename: string;
  total: number;
  success: number;
  failed: number;
  amount: number;
  rows?: BatchRow[];
}

interface BatchRow {
  id: number;
  row: number;
  reference: string;
  feeId: number | null;
  amount: number | null;
  referenceNumber?: string | null;
  bankInDate?: string | null;
  success: boolean;
  message: string;
}

interface Reversal extends ReviewRecord {
  feeId: number;
  reviewReason?: string | null;
}

type Decision = { kind: 'batches' | 'reversals'; id: number; action: 'approve' | 'reject' };

function ReviewBadge({ status }: { status: Status }) {
  const { t } = useI18n();
  switch (status) {
    case 'APPROVED': return <Badge tone="green">{t('已复核通过', 'Approved')}</Badge>;
    case 'REJECTED': return <Badge tone="red">{t('已驳回', 'Rejected')}</Badge>;
    case 'VALIDATION_FAILED': return <Badge tone="red">{t('预检失败', 'Validation failed')}</Badge>;
    default: return <Badge tone="amber">{t('待财务复核', 'Pending review')}</Badge>;
  }
}

export default function Settlements({ fees, onChanged }: { fees: Fee[]; onChanged: () => void }) {
  const { language, t, date, dateTime, money } = useI18n();
  const { can, user } = useWorkspace();
  const [format, setFormat] = useState<Format>('APPLN');
  const [batches, setBatches] = useState<Batch[]>([]);
  const [reversals, setReversals] = useState<Reversal[]>([]);
  const [active, setActive] = useState<Batch | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [decisionError, setDecisionError] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [nextBatches, nextReversals] = await Promise.all([
        request<Batch[]>('/settlements/batches'), request<Reversal[]>('/settlements/reversals'),
      ]);
      setBatches(nextBatches);
      setReversals(nextReversals);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    data.set('type', format);
    setBusy(true);
    setError('');
    try {
      setActive(await request<Batch>('/settlements/batches', { method: 'POST', body: data }));
      form.reset();
      await load();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function open(id: number) {
    setError('');
    try {
      setActive(await request<Batch>('/settlements/batches/' + id));
    } catch (cause) {
      setError(errorText(cause));
    }
  }

  async function reverse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    setBusy(true);
    setError('');
    try {
      await request<Reversal>('/settlements/reversals', json('POST', {
        feeId: Number(fields.get('feeId')), reason: String(fields.get('reason')).trim(),
      }));
      form.reset();
      await load();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function selectDecision(value: Decision) {
    setDecisionError('');
    setDecision(value);
  }

  async function review(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!decision) return;
    const reason = String(new FormData(event.currentTarget).get('reason') ?? '').trim();
    setBusy(true);
    setDecisionError('');
    try {
      const path = '/settlements/' + decision.kind + '/' + decision.id + '/' + decision.action;
      await request<Batch | Reversal>(path, json('POST', { reason }));
      setDecision(null);
      await load();
      if (decision.kind === 'batches' && active?.id === decision.id) await open(decision.id);
      onChanged();
    } catch (cause) {
      setDecisionError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function canReview(record: ReviewRecord) {
    return can('FINANCE') && record.status === 'PENDING_REVIEW' && record.createdBy !== user.username;
  }

  function decisions(kind: Decision['kind'], record: ReviewRecord) {
    if (!canReview(record)) return null;
    return <div className="process-actions">
      <button type="button" className="button primary" onClick={() => selectDecision({ kind, id: record.id, action: 'approve' })}>{t('批准', 'Approve')}</button>
      <button type="button" className="button secondary" onClick={() => selectDecision({ kind, id: record.id, action: 'reject' })}>{t('驳回', 'Reject')}</button>
    </div>;
  }

  const pendingFees = new Set(reversals.filter((item) => item.status === 'PENDING_REVIEW').map((item) => item.feeId));
  const reversible = fees.filter((fee) => fee.status === 'PAID' && !pendingFees.has(fee.id));

  return (
    <div className="page-content">
      <PageHeading title={t('结算批次', 'Settlement batches')} description={t('上传结算凭证进行预检，由财务复核后登记到账。', 'Validate settlement evidence before finance reviews and posts a batch.')} />
      {error && <ErrorNotice error={error} onRetry={() => { setError(''); void load(); }} />}
      {can('OPERATOR') && <div className="process-forms">
        <section className="panel process-section form-stack">
          <PanelHeading title={t('预检结算文件', 'Validate a workbook')} />
          <form className="form-stack" onSubmit={(event) => void upload(event)}>
            <label>{t('工作簿类型', 'Workbook type')}<select value={format} onChange={(event) => setFormat(event.target.value as Format)}>
              <option value="APPLN">APPLN · {t('申请编号', 'Application number')}</option>
              <option value="FEESU">FEESU · {t('费用编号', 'Fee ID')}</option>
            </select></label>
            <label>{t('结算工作簿', 'Settlement workbook')}<input name="file" type="file" accept=".xls,.xlsx" required /></label>
            <div className="process-actions">
              <button className="button primary" disabled={busy}><Upload size={16} />{t('上传并预检', 'Upload and validate')}</button>
              <a className="button secondary" href={'/api/settlements/template?type=' + format + '&lang=' + language}><Download size={16} />{t('空白模板', 'Blank template')}</a>
            </div>
          </form>
        </section>
        <section className="panel process-section form-stack">
          <PanelHeading title={t('提交错账冲正', 'Request a reversal')} />
          <form className="form-stack" onSubmit={(event) => void reverse(event)}>
            <label>{t('已付款费用', 'Settled fee')}<select name="feeId" required defaultValue="">
              <option value="" disabled>{t('选择费用', 'Select a fee')}</option>
              {reversible.map((fee) => <option key={fee.id} value={fee.id}>{feeReference(fee)} · {fee.payer} · {money(fee.amount)}</option>)}
            </select></label>
            <label>{t('冲正原因', 'Reason')}<textarea name="reason" rows={2} required maxLength={500} /></label>
            <div className="process-actions"><button className="button primary" disabled={busy || !reversible.length}>{t('提交复核', 'Submit for review')}</button></div>
          </form>
        </section>
      </div>}

      <section className="panel">
        <PanelHeading title={t('导入历史', 'Import history')} />
        {loading ? <Loading /> : !batches.length ? <Empty /> : <div className="table-wrap">
          <table><thead><tr>
            <th>{t('批次', 'Batch')}</th><th>{t('提交人', 'Submitted by')}</th><th>{t('状态', 'Status')}</th><th>{t('通过 / 总行数', 'Valid / total')}</th><th>{t('复核人', 'Reviewed by')}</th>
          </tr></thead><tbody>
            {batches.map((batch) => <tr key={batch.id}>
              <td><button type="button" className="table-link" onClick={() => void open(batch.id)}>#{batch.id} · {batch.filename}</button><small>{batch.type}</small></td>
              <td>{batch.createdBy}<small>{dateTime(batch.createdAt)}</small></td>
              <td><ReviewBadge status={batch.status} /></td><td>{batch.success} / {batch.total}</td><td>{batch.reviewedBy || '—'}</td>
            </tr>)}
          </tbody></table>
        </div>}
      </section>

      {active && <section className="panel">
        <PanelHeading title={t('批次详情', 'Batch details') + ' #' + active.id} detail={active.filename}>
          <div className="process-actions">
            <ReviewBadge status={active.status} />
            <a className="button secondary" href={'/api/settlements/batches/' + active.id + '/file'}><Download size={16} />{t('原始文件', 'Original file')}</a>
            {decisions('batches', active)}
          </div>
        </PanelHeading>
        <p className="panel-note">
          {t('通过预检金额', 'Validated amount')} {money(active.amount)}
          {active.reviewedAt && <> · {t('复核时间', 'Reviewed at')} {dateTime(active.reviewedAt)}</>}
          {active.reason && <> · {active.reason}</>}
        </p>
        <div className="table-wrap"><table><thead><tr>
          <th>{t('行号', 'Row')}</th><th>{t('费用或申请编号', 'Fee or application')}</th><th>{t('实收金额', 'Amount received')}</th><th>{t('银行入账日', 'Bank-in date')}</th><th>{t('渠道参考号', 'Channel reference')}</th><th>{t('预检结果', 'Validation')}</th>
        </tr></thead><tbody>{active.rows?.map((row) => <tr key={row.id}>
          <td>{row.row}</td><td>{row.feeId ? feeReference({ id: row.feeId }) : row.reference || '—'}</td>
          <td>{row.amount === null ? '—' : money(row.amount)}</td><td>{row.bankInDate ? date(row.bankInDate) : '—'}</td>
          <td>{row.referenceNumber || '—'}</td>
          <td><Badge tone={row.success ? 'green' : 'red'}>{row.success ? t('通过', 'Valid') : t('失败', 'Failed')}</Badge><small>{row.message}</small></td>
        </tr>)}</tbody></table></div>
      </section>}

      <section className="panel">
        <PanelHeading title={t('冲正申请', 'Reversal requests')} />
        {loading ? <Loading /> : !reversals.length ? <Empty /> : <div className="table-wrap">
          <table><thead><tr><th>{t('编号 / 费用', 'Request / fee')}</th><th>{t('原因', 'Reason')}</th><th>{t('提交人 / 复核人', 'Submitted / reviewed by')}</th><th>{t('状态', 'Status')}</th><th>{t('操作', 'Actions')}</th></tr></thead>
            <tbody>{reversals.map((item) => <tr key={item.id}>
              <td>#{item.id}<small>{feeReference({ id: item.feeId })}</small></td>
              <td>{item.reason}<small>{item.reviewReason}</small></td>
              <td>{item.createdBy}<small>{item.reviewedBy || '—'}</small></td>
              <td><ReviewBadge status={item.status} /></td><td>{decisions('reversals', item)}</td>
            </tr>)}</tbody>
          </table>
        </div>}
      </section>

      {decision && <Modal title={decision.action === 'approve' ? t('确认批准', 'Confirm approval') : t('驳回复核', 'Reject request')} busy={busy} onClose={() => setDecision(null)}>
        <form className="form-stack modal-body" onSubmit={(event) => void review(event)}>
          {decisionError && <ErrorNotice error={decisionError} />}
          <p>{decision.action === 'reject'
            ? t('复核说明会与原申请一同保存。', 'The review note is saved with the original request.')
            : decision.kind === 'batches'
            ? t('批准后，整个批次将统一登记到账。', 'Approval posts the entire batch together.')
            : t('批准冲正后，费用将恢复待结算，原凭证保留作废记录。', 'Approval reopens the fee and records the original receipt as void.')}</p>
          <label>{t('复核说明', 'Review note')}<textarea name="reason" rows={3} maxLength={500} required={decision.action === 'reject'} /></label>
          <div className="process-actions">
            <button className="button primary" disabled={busy}>{decision.action === 'approve' ? t('批准', 'Approve') : t('驳回', 'Reject')}</button>
          </div>
        </form>
      </Modal>}
    </div>
  );
}
