import { useWorkspace } from './Session';
import { Download, FileSpreadsheet, ListFilter } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { localizedUrl, useI18n } from './i18n';
import { errorText, request } from './lib';
import type { ReportData } from './types';
import { Badge, Empty, ErrorNotice, Loading, PageHeading, PanelHeading } from './ui';

type Copy = readonly [chinese: string, english: string];

interface ReportDefinition {
  value: string;
  code: string;
  label: Copy;
  detail: Copy;
  hint: Copy;
}

const reports: ReportDefinition[] = [
  {
    value: 'fees',
    code: 'FEE004R01',
    label: ['费用资料', 'Fee register'],
    detail: ['按到期日导出费用记录', 'Fee records by due date'],
    hint: [
      '按到期日筛选费用台账，用于日常应收核对。日期留空表示不限。',
      'Filter fees by due date to review receivables. Leave dates blank to include all records.',
    ],
  },
  {
    value: 'due',
    code: 'FEE004R02',
    label: ['到期定期费用', 'Periodic fees due'],
    detail: ['指定期间到期的定期费用', 'Periodic fees due in a date range'],
    hint: [
      '列出到期日落在指定期间的定期费用，包含已结算记录。',
      'List periodic fees due in the selected period, including settled fees.',
    ],
  },
  {
    value: 'surcharges',
    code: 'FEE004R03',
    label: ['未结清附加费', 'Outstanding surcharges'],
    detail: ['尚未结清的定期费用附加费', 'Outstanding surcharges on periodic fees'],
    hint: [
      '按附加费到期日筛选仍未结清的定期费用附加费。',
      'Filter outstanding surcharges on periodic fees by surcharge due date.',
    ],
  },
  {
    value: 'month-end',
    code: 'FEE004R04',
    label: ['月末未结清', 'Month-end balances'],
    detail: ['月末尚未结清的费用及附加费', 'Fees and surcharges outstanding at month-end'],
    hint: [
      '以结束日期作为月末截止日，列出截至当日仍未结清的费用与附加费。',
      'Use the end date as the month-end cutoff to see fees and surcharges still outstanding on that date.',
    ],
  },
  {
    value: 'non-payment',
    code: 'FEE004R05',
    label: ['非付款结算', 'Non-payment settlements'],
    detail: ['减免、作废等非付款结算', 'Waivers, cancellations and other non-payment settlements'],
    hint: [
      '按结算日筛选非付款结算记录，例如演示数据中的费用减免。',
      'Filter non-payment settlements by settlement date. The demo data includes a fee waiver.',
    ],
  },
  {
    value: 'revenue',
    code: 'FEE004R06',
    label: ['月度收入', 'Monthly revenue'],
    detail: ['按银行入账月份汇总', 'Grouped by bank-in month'],
    hint: [
      '按银行入账月份统计实收笔数与金额，可配合起止日期缩小范围。',
      'See payment counts and amounts by bank-in month. Use the date range to narrow the results.',
    ],
  },
  {
    value: 'tip',
    code: 'FEE004R07',
    label: ['TIP 报表', 'TIP report'],
    detail: ['按到期日输出 TIP 复核数据', 'TIP review records by due date'],
    hint: [
      '按到期日输出 TIP 复核报表，列出计划、付款人、金额与结算状态。',
      'Review TIP records by due date, including the scheme, payer, amount and settlement status.',
    ],
  },
];

interface Criteria {
  type: string;
  from: string;
  to: string;
}

function numericColumn(column: string) {
  return /金额|笔数|数量|总额|\bamount\b|^banked payments$/i.test(column);
}

export default function Reports() {
  const { can } = useWorkspace();
  const { language, locale, t, date } = useI18n();
  const [criteria, setCriteria] = useState<Criteria>({ type: 'fees', from: '', to: '' });
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [result, setResult] = useState<{
    criteria: Criteria;
    language: typeof language;
    data: ReportData | null;
    error: string;
  } | null>(null);
  const [fisFrom, setFisFrom] = useState('');
  const [fisTo, setFisTo] = useState('');

  const active = reports.find((item) => item.value === criteria.type) ?? reports[0];
  const currentResult = result?.criteria === criteria && result.language === language ? result : null;
  const data = currentResult?.data;
  const amounts = new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  const reportQuery = new URLSearchParams({ type: criteria.type });
  if (criteria.from) reportQuery.set('from', criteria.from);
  if (criteria.to) reportQuery.set('to', criteria.to);
  const reportSearch = reportQuery.toString();

  useEffect(() => {
    const controller = new AbortController();
    request<ReportData>('/reports?' + reportSearch, { signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ criteria, language, data, error: '' });
      })
      .catch((cause) => {
        if (!controller.signal.aborted) setResult({ criteria, language, data: null, error: errorText(cause) });
      });
    return () => controller.abort();
  }, [criteria, language, reportSearch]);

  function query(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCriteria({ type: criteria.type, from, to });
  }

  function cellText(column: string, cell: string) {
    if (!cell) return '—';
    if (/金额|总额|\bamount\b/i.test(column) && /^-?\d+(\.\d+)?$/.test(cell)) return amounts.format(Number(cell));
    if (/到期日|^due date$/i.test(column) && /^\d{4}-\d{2}-\d{2}$/.test(cell)) return date(cell);
    return cell;
  }

  const fisQuery = new URLSearchParams();
  if (fisFrom) fisQuery.set('from', fisFrom);
  if (fisTo) fisQuery.set('to', fisTo);
  const fisUrl = '/api/fis/export' + (fisQuery.size ? '?' + fisQuery.toString() : '');

  return (
    <div className="page-content">
      <PageHeading
        title={t('运营报表', 'Operational reports')}
        description={t('按报表口径查询费用与结算记录，并导出 CSV 或 FIS 接口文件。', 'Review fees and settlements, then export report CSVs or FIS interface files.')}
      />
      <div className="report-layout">
        <section className="panel report-picker">
          <PanelHeading title={t('选择报表', 'Select a report')} detail="FEE004R01–R07" />
          <div className="report-options">
            {reports.map((report) => (
              <button
                key={report.value}
                type="button"
                className={'report-option' + (report.value === criteria.type ? ' active' : '')}
                aria-pressed={report.value === criteria.type}
                onClick={() => {
                  if (report.value !== criteria.type) setCriteria({ type: report.value, from, to });
                }}
              >
                <span className="report-code">{report.code}</span>
                <strong>{t(...report.label)}</strong>
                <span>{t(...report.detail)}</span>
              </button>
            ))}
          </div>
        </section>

        <div className="report-main">
          <section className="panel">
            <form className="report-filters" onSubmit={query}>
              <label>{t('开始日期', 'Start date')}<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
              <label>{t('结束日期', 'End date')}<input type="date" value={to} onChange={(event) => setTo(event.target.value)} /></label>
              <button type="submit" className="button primary"><ListFilter size={16} />{t('查询', 'Run report')}</button>
              {can('FINANCE', 'AUDITOR') && <a className="button secondary" href={localizedUrl('/api/reports/download?' + reportSearch, language)}>
                <Download size={16} />{t('导出 CSV', 'Export CSV')}
              </a>}
              {(from || to || criteria.from || criteria.to) && (
                <button
                  type="button"
                  className="text-button"
                  onClick={() => {
                    setFrom('');
                    setTo('');
                    setCriteria({ type: criteria.type, from: '', to: '' });
                  }}
                >
                  {t('清除日期', 'Clear dates')}
                </button>
              )}
            </form>
            <p className="report-hint">{t(...active.hint)}</p>
          </section>

          <section className="panel">
            <PanelHeading
              title={t(...active.label)}
              detail={data
                ? t('共 ' + data.rows.length + ' 条记录', data.rows.length + (data.rows.length === 1 ? ' record' : ' records'))
                : active.code}
            >
              <Badge tone="indigo">{active.code}</Badge>
            </PanelHeading>
            {!currentResult ? <Loading label={t('正在查询报表', 'Loading report')} /> : currentResult.error ? (
              <ErrorNotice error={currentResult.error} onRetry={() => setCriteria({ ...criteria })} />
            ) : !data?.rows.length ? (
              <Empty message={t('所选条件下没有记录', 'No records match the selected dates')} />
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {data.columns.map((column) => (
                        <th key={column} className={numericColumn(column) ? 'numeric' : undefined}>{column}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {data.rows.map((row, rowIndex) => (
                      <tr key={rowIndex}>
                        {row.map((cell, cellIndex) => (
                          <td
                            key={cellIndex}
                            className={numericColumn(data.columns[cellIndex]) ? 'numeric' : undefined}
                          >
                            {cellText(data.columns[cellIndex], cell)}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {can('FINANCE') && <section className="panel report-fis">
            <PanelHeading
              title={t('FIS 财务接口导出', 'FIS interface export')}
              detail={t('FEE004R08 · 供财务接收与复核', 'FEE004R08 · Finance handoff and review')}
            >
              <FileSpreadsheet size={18} />
            </PanelHeading>
            <div className="report-filters">
              <label>{t('银行入账开始日期', 'Bank-in start date')}<input type="date" value={fisFrom} onChange={(event) => setFisFrom(event.target.value)} /></label>
              <label>{t('银行入账结束日期', 'Bank-in end date')}<input type="date" value={fisTo} onChange={(event) => setFisTo(event.target.value)} /></label>
              <a className="button secondary" href={fisUrl}><Download size={16} />{t('下载 FIS 文件', 'Download FIS file')}</a>
            </div>
            <p className="report-hint">
              {t(
                '文件字段为交易参考编号、金额、借贷标识、收入代码、到期日和计划或收入账户标识。按银行入账日筛选，日期留空表示全部。',
                'Fields include the transaction reference, amount, debit or credit indicator, revenue code, due date, and scheme or revenue account ID. Filter by bank-in date or leave dates blank to include all records.',
              )}
            </p>
          </section>}
        </div>
      </div>
    </div>
  );
}
