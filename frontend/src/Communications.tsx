import { useWorkspace } from './Session';
import { Download, Plus, QrCode } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useI18n } from './i18n';
import { deliveryLabels, documentKindLabels, errorText, feeReference, fpsLabels, isOverdue, json, request } from './lib';
import type { DocumentKind, Fee, FeeDocument, FpsRequest } from './types';
import { Badge, Empty, ErrorNotice, Loading, Modal, PageHeading } from './ui';

const dayInHongKong = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Hong_Kong' });

function FeePicker({ fees, value, onChange }: { fees: Fee[]; value: string; onChange: (value: string) => void }) {
  const { t, money } = useI18n();
  return (
    <label>{t('选择费用', 'Fee')}<select value={value} onChange={(event) => onChange(event.target.value)} required>
      <option value="">{t('请选择费用', 'Select a fee')}</option>
      {fees.map((fee) => <option key={fee.id} value={fee.id}>{feeReference(fee)} · {fee.payer} · {money(fee.amount)}</option>)}
    </select>{!fees.length && <small>{t('暂无符合条件的费用。', 'No eligible fees.')}</small>}</label>
  );
}

function GenerateDocument({ fees, receiptedFeeIds, defaultKind, onClose, onSaved }: {
  fees: Fee[];
  receiptedFeeIds: ReadonlySet<number>;
  defaultKind: DocumentKind;
  onClose: () => void;
  onSaved: (document: FeeDocument) => void;
}) {
  const { language, t } = useI18n();
  const documentKinds = documentKindLabels(language);
  const [kind, setKind] = useState<DocumentKind>(defaultKind);
  const [feeId, setFeeId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const choices = fees.filter((fee) => kind === 'RECEIPT'
    ? fee.status === 'PAID' && !receiptedFeeIds.has(fee.id)
    : kind === 'REMINDER' ? isOverdue(fee) : fee.status === 'UNPAID');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      onSaved(await request<FeeDocument>('/documents', json('POST', { feeId: Number(feeId), kind })));
      onClose();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={t('生成业务文档', 'Generate document')} onClose={onClose} busy={busy}>
      <form onSubmit={submit}>
        <div className="modal-content">
          {error && <ErrorNotice error={error} />}
          <fieldset className="form-stack" disabled={busy}>
            <label>{t('文档类型', 'Document type')}<select value={kind} onChange={(event) => { setKind(event.target.value as DocumentKind); setFeeId(''); }}>
              {Object.entries(documentKinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
            </select></label>
            <FeePicker fees={choices} value={feeId} onChange={setFeeId} />
          </fieldset>
        </div>
        <footer className="modal-footer">
          <button type="button" className="button secondary" onClick={onClose} disabled={busy}>{t('取消', 'Cancel')}</button>
          <button type="submit" className="button primary" disabled={!feeId || busy}>{busy ? t('正在生成…', 'Generating…') : t('生成 PDF', 'Generate PDF')}</button>
        </footer>
      </form>
    </Modal>
  );
}

export function Documents({ fees }: { fees: Fee[] }) {
  const { can } = useWorkspace();
  const { language, t, dateTime } = useI18n();
  const documentKinds = documentKindLabels(language);
  const deliveryStatusLabels = deliveryLabels(language);
  const [records, setRecords] = useState<FeeDocument[]>([]);
  const [filter, setFilter] = useState('');
  const [generated, setGenerated] = useState('');
  const [bankIn, setBankIn] = useState('');
  const [modal, setModal] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    request<FeeDocument[]>('/documents', { signal: controller.signal })
      .then((result) => {
        setRecords(result);
        setError('');
      }).catch((cause) => {
        if (!controller.signal.aborted) setError(errorText(cause));
      }).finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reload]);

  const rows = records.filter((record) => (!filter || record.kind === filter)
    && (!generated || dayInHongKong.format(new Date(record.generatedAt)) === generated)
    && (!bankIn || record.bankInDate === bankIn));
  const receiptedFeeIds = new Set(records.filter((record) => record.kind === 'RECEIPT' && record.valid !== false).map((record) => record.feeId));

  return (
    <div className="page-content">
      <PageHeading
        title={t('通知与收据', 'Notices and receipts')}
        description={t(
          '生成缴费通知、催缴提醒及收据 PDF；通知与提醒附 FPS 缴费二维码，收据附核验二维码。',
          'Generate PDF payment notices and reminders with FPS QR codes, and receipts with verification QR codes.',
        )}
      >
        {can('OPERATOR', 'FINANCE') && <button className="button primary" onClick={() => setModal(true)}><Plus size={17} />{t('生成文档', 'Generate document')}</button>}
      </PageHeading>
      {error && <ErrorNotice error={error} onRetry={() => setReload((value) => value + 1)} />}
      {saved && <div className="notice notice-success" role="status">{t(
        '已生成 ' + saved + '，可从列表下载 PDF。',
        'Document ' + saved + ' generated. Download the PDF from the list.',
      )}</div>}
      <section className="panel">
        <div className="table-toolbar">
          <select aria-label={t('文档类型', 'Document type')} value={filter} onChange={(event) => { setFilter(event.target.value); setBankIn(''); }}>
            <option value="">{t('全部文档', 'All documents')}</option>
            {Object.entries(documentKinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
          </select>
          <label className="inline-field">{t('生成日期', 'Generation date')}<input type="date" value={generated} onChange={(event) => setGenerated(event.target.value)} /></label>
          {filter === 'RECEIPT' && <label className="inline-field">{t('入账日期', 'Bank-in date')}<input type="date" value={bankIn} onChange={(event) => setBankIn(event.target.value)} /></label>}
          <span className="table-count">{t(rows.length + ' 份文档', rows.length + (rows.length === 1 ? ' document' : ' documents'))}</span>
        </div>
        {loading ? <Loading /> : !rows.length ? <Empty message={t('暂无符合条件的文档', 'No matching documents')} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>{t('文档编号', 'Document number')}</th><th>{t('文档类型', 'Document type')}</th><th>{t('费用编号', 'Fee ID')}</th><th>{t('生成时间', 'Generated at')}</th><th>{t('投递状态', 'Delivery status')}</th><th>{t('操作', 'Actions')}</th></tr></thead>
            <tbody>{rows.map((record) => <tr key={record.id}>
              <td className="strong-cell">{record.number}{record.valid === false && <small><Badge tone="red">{t('已作废', 'Voided')}</Badge></small>}</td><td>{documentKinds[record.kind]}</td>
              <td>{feeReference({ id: record.feeId })}</td><td>{dateTime(record.generatedAt)}</td>
              <td><Badge>{deliveryStatusLabels[record.deliveryStatus] ?? record.deliveryStatus}</Badge></td>
              <td><a className="text-button" href={'/api/documents/' + record.id + '/download'}><Download size={15} />{record.valid === false ? t('原始 PDF', 'Original PDF') : 'PDF'}</a></td>
            </tr>)}</tbody>
          </table></div>
        )}
      </section>
      {modal && <GenerateDocument
        fees={fees} receiptedFeeIds={receiptedFeeIds} defaultKind={filter ? filter as DocumentKind : 'NOTICE'} onClose={() => setModal(false)}
        onSaved={(record) => {
          setRecords((current) => [record, ...current.filter((existing) => existing.id !== record.id)]);
          setFilter(record.kind);
          setGenerated('');
          setBankIn('');
          setSaved(record.number);
        }}
      />}
    </div>
  );
}

export function FpsRequests({ fees }: { fees: Fee[] }) {
  const { can } = useWorkspace();
  const { language, t, money, date } = useI18n();
  const fpsStatusLabels = fpsLabels(language);
  const [records, setRecords] = useState<FpsRequest[]>([]);
  const [qrRecord, setQrRecord] = useState<FpsRequest | null>(null);
  const [feeId, setFeeId] = useState('');
  const [modal, setModal] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [saved, setSaved] = useState('');
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    request<FpsRequest[]>('/fps-requests', { signal: controller.signal })
      .then((result) => {
        setRecords(result);
        setError('');
      }).catch((cause) => {
        if (!controller.signal.aborted) setError(errorText(cause));
      }).finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [reload]);

  const qrFee = qrRecord ? fees.find((fee) => fee.id === qrRecord.feeId) : undefined;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFormError('');
    setBusy(true);
    try {
      const record = await request<FpsRequest>('/fps-requests', json('POST', { feeId: Number(feeId) }));
      setRecords((current) => [record, ...current.filter((existing) => existing.id !== record.id)]);
      setSaved(record.reference);
      setModal(false);
      setFeeId('');
    } catch (cause) {
      setFormError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page-content">
      <PageHeading
        title={t('FPS 二维码请求', 'FPS QR requests')}
        description={t(
          '为未结算费用生成演示请求，记录编号、关联费用和到期日，并可预览或下载二维码。',
          'Create demo requests for unsettled fees and preview or download the QR codes. Each request has a reference, an associated fee, and an expiry date.',
        )}
      >
        {can('OPERATOR') && <button className="button primary" onClick={() => { setModal(true); setFormError(''); }}><Plus size={17} />{t('新建演示请求', 'New demo request')}</button>}
      </PageHeading>
      {error && <ErrorNotice error={error} onRetry={() => setReload((value) => value + 1)} />}
      {saved && <div className="notice notice-success" role="status">{t('演示请求 ' + saved + ' 已生成。', 'Demo request ' + saved + ' generated.')}</div>}
      <section className="panel">
        {loading ? <Loading /> : !records.length ? <Empty message={t('暂无 FPS 演示请求', 'No demo FPS requests')} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>{t('请求编号', 'Request reference')}</th><th>{t('费用编号', 'Fee ID')}</th><th>{t('付款方', 'Payer')}</th><th>{t('到期日', 'Expiry date')}</th><th>{t('状态', 'Status')}</th><th>{t('二维码', 'QR code')}</th></tr></thead>
            <tbody>{records.map((record) => <tr key={record.id}>
              <td className="strong-cell">{record.reference}</td><td>{feeReference({ id: record.feeId })}</td>
              <td>{fees.find((fee) => fee.id === record.feeId)?.payer || '—'}</td><td>{date(record.expiresOn)}</td>
              <td><Badge tone="indigo">{fpsStatusLabels[record.status] ?? record.status}</Badge></td>
              <td><button type="button" className="qr-link" onClick={() => setQrRecord(record)}><QrCode size={15} />{t('二维码', 'QR code')}</button></td>
            </tr>)}</tbody>
          </table></div>
        )}
      </section>
      {modal && <Modal title={t('新建 FPS 演示请求', 'New demo FPS request')} onClose={() => setModal(false)} busy={busy}>
        <form onSubmit={submit}>
          <div className="modal-content">
            {formError && <ErrorNotice error={formError} />}
            <fieldset className="form-stack" disabled={busy}>
              <FeePicker fees={fees.filter((fee) => fee.status === 'UNPAID')} value={feeId} onChange={setFeeId} />
            </fieldset>
          </div>
          <footer className="modal-footer">
            <button type="button" className="button secondary" onClick={() => setModal(false)} disabled={busy}>{t('取消', 'Cancel')}</button>
            <button type="submit" className="button primary" disabled={!feeId || busy}>{busy ? t('正在生成…', 'Generating…') : t('生成演示请求', 'Generate demo request')}</button>
          </footer>
        </form>
      </Modal>}
      {qrRecord && (
        <Modal title={t('FPS 二维码', 'FPS QR code') + ' · ' + qrRecord.reference} onClose={() => setQrRecord(null)}>
          <div className="modal-content qr-content">
            <div className="qr-preview">
              <img
                src={'/api/fps-requests/' + qrRecord.id + '/qr'}
                alt={t('FPS 二维码', 'FPS QR code') + ' ' + qrRecord.reference}
                width={260}
                height={260}
              />
            </div>
            <dl className="qr-meta">
              <div><dt>{t('费用编号', 'Fee ID')}</dt><dd>{feeReference({ id: qrRecord.feeId })}</dd></div>
              <div><dt>{t('付款方', 'Payer')}</dt><dd>{qrFee?.payer || '—'}</dd></div>
              <div><dt>{t('金额', 'Amount')}</dt><dd>{qrFee ? money(qrFee.amount) : '—'}</dd></div>
              <div><dt>{t('到期日', 'Expiry date')}</dt><dd>{date(qrRecord.expiresOn)}</dd></div>
              <div><dt>{t('状态', 'Status')}</dt><dd><Badge tone="indigo">{fpsStatusLabels[qrRecord.status] ?? qrRecord.status}</Badge></dd></div>
              <div><dt>{t('请求编号', 'Request reference')}</dt><dd>{qrRecord.reference}</dd></div>
            </dl>
            <p className="qr-hint">{t('二维码内容', 'QR code content')} <code>{qrRecord.qrText || '—'}</code></p>
          </div>
          <footer className="modal-footer">
            <button type="button" className="button secondary" onClick={() => setQrRecord(null)}>{t('关闭', 'Close')}</button>
            <a className="button primary" href={'/api/fps-requests/' + qrRecord.id + '/qr?download=true'}>
              <Download size={15} />{t('下载 PNG', 'Download PNG')}
            </a>
          </footer>
        </Modal>
      )}
    </div>
  );
}
