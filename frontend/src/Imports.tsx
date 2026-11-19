import { CheckCircle2, Download, FileSpreadsheet, UploadCloud } from 'lucide-react';
import { useState } from 'react';
import type { FormEvent } from 'react';
import { useI18n } from './i18n';
import { errorText, feeReference, request } from './lib';
import type { ImportResult } from './types';
import { Badge, Empty, ErrorNotice, PageHeading, PanelHeading } from './ui';

interface ConversionResult extends ImportResult {
  converted?: Array<{ reference: string; feeId: number; amount: number; bankInDate: string }>;
}

export default function Imports() {
  const { t, locale, money, date } = useI18n();
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState<ConversionResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const template = '/api/reconciliation/template';
  const verb = t('转换对账文件', 'Convert reconciliation file');
  const size = file && new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(file.size / 1024);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) return;
    setBusy(true);
    setError('');
    setResult(null);
    const formData = new FormData();
    formData.append('file', file);
    try {
      setResult(await request<ConversionResult>('/reconciliation/convert', {
        method: 'POST',
        body: formData,
      }));
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page-content">
      <PageHeading
        title={t('FPS 对账转换', 'FPS reconciliation')}
        description={t('上传 FPS 对账文件，生成转换结果与异常报告供复核。',
            'Upload an FPS reconciliation file to convert records and flag exceptions for review.')}
      >
        <a className="button secondary" href={template}><Download size={16} />{t('下载模板', 'Download template')}</a>
      </PageHeading>
      <div className="upload-layout">
        <section className="panel">
          <PanelHeading title={t('上传对账文件', 'Upload reconciliation file')} />
          <form onSubmit={submit} className="upload-form">
            <label
              className={'dropzone' + (dragging ? ' dragging' : '') + (file ? ' picked' : '')}
              onDragOver={(event) => { event.preventDefault(); if (!busy) setDragging(true); }}
              onDragLeave={() => setDragging(false)}
              onDrop={(event) => {
                event.preventDefault();
                setDragging(false);
                if (!busy && event.dataTransfer.files[0]) {
                  const input = event.currentTarget.querySelector('input');
                  if (input) input.value = '';
                  setFile(event.dataTransfer.files[0]);
                }
              }}
            >
              <input type="file" className="visually-hidden" disabled={busy} accept=".csv" onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
              <span className="dropzone-icon">{file ? <FileSpreadsheet size={25} /> : <UploadCloud size={25} />}</span>
              <strong>{file ? file.name : t('点击选择或拖拽文件至此', 'Choose a file or drag it here')}</strong>
              <span>{file
                ? size + ' KB · ' + t('点击更换文件', 'Choose another file')
                : t('支持 .csv 格式', '.csv files')}</span>
            </label>
            {error && <ErrorNotice error={error} />}
            <div className="page-actions">
              <button type="submit" className="button primary" disabled={!file || busy}><UploadCloud size={16} />{busy ? t('正在处理…', 'Processing…') : verb}</button>
              {file && <button type="button" className="button secondary" disabled={busy} onClick={(event) => {
                const input = event.currentTarget.form?.querySelector('input[type=file]') as HTMLInputElement | null;
                if (input) input.value = '';
                setFile(null);
              }}>{t('清除选择', 'Clear selection')}</button>}
            </div>
          </form>
        </section>
        <aside className="panel upload-guide">
          <PanelHeading title={t('复核说明', 'Review guide')} />
          <ol>
            <li>{t('下载本地演示 CSV 模板。', 'Download the demo CSV template.')}</li>
            <li>{t('上传转换文件，将成功记录与异常逐笔核对。', 'Upload the file, then review the converted records and exceptions.')}</li>
            <li>{t('如需登记付款结果，请使用结算导入。', 'Use settlement import to record payments.')}</li>
          </ol>
          <a className="text-button" href="#settlements">{t('查看结算批次', 'View settlement batches')}</a>
        </aside>
      </div>
      {result && <section className="panel">
        <PanelHeading title={t('转换结果', 'Conversion results')}>
          <div className="result-badges">
            <Badge>{t('共 ' + result.total + ' 条', 'Total: ' + result.total)}</Badge>
            <Badge tone="green">{t('成功 ' + result.success, 'Succeeded: ' + result.success)}</Badge>
            <Badge tone={result.failed ? 'red' : 'neutral'}>{t('异常 ' + result.failed, 'Exceptions: ' + result.failed)}</Badge>
          </div>
        </PanelHeading>
        {result.rows.length ? <div className="table-wrap"><table>
          <thead><tr><th>{t('源文件行号', 'Source row')}</th><th>{t('参考编号', 'Reference')}</th><th>{t('处理状态', 'Status')}</th><th>{t('说明', 'Details')}</th></tr></thead>
          <tbody>{result.rows.map((row, index) => <tr key={row.row + '-' + index}>
            <td>{row.row}</td><td>{row.reference || '—'}</td>
            <td><Badge tone={row.success ? 'green' : 'red'}>{row.success ? t('成功', 'Succeeded') : t('异常', 'Exception')}</Badge></td><td>{row.message || '—'}</td>
          </tr>)}</tbody>
        </table></div> : <Empty message={t('文件没有可处理的数据行', 'The file contains no data rows')} />}
        {result.converted && result.converted.length > 0 && <div className="converted">
          <h3><CheckCircle2 size={16} />{t('可复核记录', 'Records ready for review')}</h3>
          <div className="table-wrap"><table>
            <thead><tr><th>{t('参考编号', 'Reference')}</th><th>{t('费用编号', 'Fee ID')}</th><th>{t('入账日', 'Bank-in date')}</th><th className="numeric">{t('金额', 'Amount')}</th></tr></thead>
            <tbody>{result.converted.map((item, index) => (
              <tr key={item.reference + '-' + index}><td>{item.reference}</td><td>{feeReference({ id: item.feeId })}</td>
                <td>{date(item.bankInDate)}</td><td className="numeric">{money(item.amount)}</td></tr>
            ))}</tbody>
          </table></div>
        </div>}
      </section>}
    </div>
  );
}
