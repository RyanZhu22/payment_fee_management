import { useWorkspace } from './Session';
import { ArrowDownToLine, ArrowRight, Banknote, CircleCheck, Clock3, ReceiptText, Upload } from 'lucide-react';
import type { Fee } from './types';
import { useI18n } from './i18n';
import { feeReference, feeTypeLabels, isOverdue, today } from './lib';
import { Empty, FeeBadge, Loading, PageHeading, PanelHeading, SectionLink } from './ui';

function SettlementChart({ fees }: { fees: Fee[] }) {
  const { locale, money, t } = useI18n();
  const compactNumber = new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 });
  const monthLabel = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' });
  const now = today();
  const year = Number(now.slice(0, 4));
  const month = Number(now.slice(5, 7));
  const periods = Array.from({ length: 6 }, (_, index) => {
    const date = new Date(Date.UTC(year, month - 6 + index, 1));
    const key = date.toISOString().slice(0, 7);
    return {
      key,
      label: monthLabel.format(date),
      gross: fees.filter((fee) => fee.dueDate.startsWith(key)).reduce((sum, fee) => sum + Number(fee.amount), 0),
      settled: fees.filter((fee) => fee.status === 'PAID' && fee.settlementDate?.startsWith(key))
        .reduce((sum, fee) => sum + Number(fee.amount), 0),
    };
  });
  const largest = Math.max(1, ...periods.flatMap((period) => [period.gross, period.settled]));
  const tickUnit = 10 ** Math.floor(Math.log10(largest / 4));
  const step = Math.ceil(largest / 4 / tickUnit) * tickUnit;
  const ticks = [4, 3, 2, 1, 0].map((index) => step * index);
  return (
    <section className="panel chart-panel">
      <PanelHeading title={t('应收与结算趋势', 'Receivables and settlements')} detail={t('过去六个月 · 港元', 'Last six months · HKD')} />
      <div className="chart-legend" aria-hidden="true">
        <span><i className="legend-light" />{t('应收费用', 'Receivables')}</span>
        <span><i className="legend-primary" />{t('已结算', 'Settled')}</span>
      </div>
      <div className="chart" aria-label={t('每月到期费用与实收结算金额', 'Monthly fees due and amounts collected')}>
        <div className="chart-axis" aria-hidden="true">{ticks.map((tick) => <span key={tick}>{compactNumber.format(tick)}</span>)}</div>
        <div className="chart-plot">
          <div className="chart-grid" aria-hidden="true">{ticks.map((tick) => <i key={tick} />)}</div>
          <div className="chart-columns">
            {periods.map((period) => {
              const gross = money(period.gross);
              const settled = money(period.settled);
              const description = t(`${period.label} 应收 ${gross}，已结算 ${settled}`, `${period.label} · ${gross} due, ${settled} settled`);

              return (
                <div className="chart-month" key={period.key} title={description}>
                  <div className="chart-bars" aria-label={description}>
                    <div className="chart-bar chart-bar-light" style={{ height: (period.gross / ticks[0]) * 100 + '%' }} />
                    <div className="chart-bar chart-bar-primary" style={{ height: (period.settled / ticks[0]) * 100 + '%' }} />
                  </div>
                  <span>{period.label}</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}

function StatusChart({ fees }: { fees: Fee[] }) {
  const { t } = useI18n();
  const categories = [
    { label: t('已结算', 'Settled'), count: fees.filter((fee) => fee.status === 'PAID').length, color: '#624be8' },
    { label: t('待结算', 'Awaiting settlement'), count: fees.filter((fee) => fee.status === 'UNPAID' && !isOverdue(fee)).length, color: '#a99af7' },
    { label: t('已逾期', 'Overdue'), count: fees.filter((fee) => isOverdue(fee)).length, color: '#f3a776' },
    { label: t('非付款结算', 'Non-payment settlement'), count: fees.filter((fee) => fee.status === 'NON_PAYMENT').length, color: '#cbd0dc' },
  ];
  let end = 0;
  const segments = fees.length ? categories.map(({ count, color }) => {
    const start = end;
    end += count * 100 / fees.length;
    return color + ' ' + start + '% ' + end + '%';
  }).join(', ') : '#e9e8f4 0% 100%';

  return (
    <section className="panel status-panel">
      <PanelHeading title={t('结算状态', 'Settlement status')} detail={t('按费用记录统计', 'By fee record')} />
      <div className="status-donut" style={{ background: 'conic-gradient(' + segments + ')' }}>
        <div><strong>{fees.length}</strong><span>{t('费用记录', 'Fee records')}</span></div>
      </div>
      <div className="status-items">
        {categories.map((category) => (
          <div key={category.label}>
            <span><i style={{ background: category.color }} />{category.label}</span><strong>{category.count}</strong>
          </div>
        ))}
      </div>
    </section>
  );
}

export default function Dashboard({ fees, loading, onCreate, onEdit }: {
  fees: Fee[];
  loading: boolean;
  onCreate: () => void;
  onEdit: (fee: Fee) => void;
}) {
  const { language, t, money, date } = useI18n();
  const feeTypes = feeTypeLabels(language);
  const { can } = useWorkspace();
  const canEdit = can('OPERATOR');
  const paid = fees.filter((fee) => fee.status === 'PAID');
  const unpaid = fees.filter((fee) => fee.status === 'UNPAID');
  const overdue = fees.filter((fee) => isOverdue(fee)).sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const stats = [
    { label: t('总应收费用', 'Total receivables'), rows: fees, icon: ReceiptText, tone: 'indigo' },
    { label: t('已结算金额', 'Settled amount'), rows: paid, icon: CircleCheck, tone: 'green' },
    { label: t('待结算金额', 'Outstanding amount'), rows: unpaid, icon: Banknote, tone: 'blue' },
    { label: t('已逾期金额', 'Overdue amount'), rows: overdue, icon: Clock3, tone: 'orange' },
  ];

  return (
    <div className="page-content">
      <PageHeading title={t('支付与费用总览', 'Payments and fees overview')} description={t('查看应收费用、结算进度与待跟进款项。', 'Track receivables, settlements, and fees that need follow-up.')}>
        <a href="#settlements" className="button secondary"><Upload size={16} />{t('查看结算批次', 'View settlement batches')}</a>
        {canEdit && <button className="button primary" onClick={onCreate}><span className="plus-sign" aria-hidden="true">+</span>{t('新建费用', 'New fee')}</button>}
      </PageHeading>
      {loading ? <Loading /> : <>
        <div className="kpi-grid">
          {stats.map(({ label, rows, icon: Icon, tone }) => (
            <section key={label} className="panel kpi">
              <div><span className={'kpi-icon icon-' + tone}><Icon size={18} strokeWidth={1.9} /></span><span className="kpi-label">{label}</span></div>
              <strong>{money(rows.reduce((sum, fee) => sum + Number(fee.amount), 0))}</strong>
              <p>{t(`${rows.length} 条费用记录`, `${rows.length} fee ${rows.length === 1 ? 'record' : 'records'}`)}</p>
            </section>
          ))}
        </div>
        <div className="dashboard-grid">
          <SettlementChart fees={fees} />
          <StatusChart fees={fees} />
        </div>
        <section className="panel">
          <PanelHeading title={t('待跟进的逾期费用', 'Overdue fees to follow up')} detail={t('按到期日由早到晚排列', 'Earliest due dates first')}>
            <SectionLink href="#fees">{t('查看费用台账', 'View fee ledger')}</SectionLink>
          </PanelHeading>
          {!overdue.length ? <Empty message={t('当前没有逾期费用', 'No overdue fees')} /> : (
            <div className="table-wrap"><table>
              <thead><tr>
                <th>{t('费用编号', 'Fee ID')}</th>
                <th>{t('付款方', 'Payer')}</th>
                <th>{t('费用类型', 'Fee type')}</th>
                <th>{t('到期日', 'Due date')}</th>
                <th className="numeric">{t('金额', 'Amount')}</th>
                <th>{t('状态', 'Status')}</th>
                <th><span className="visually-hidden">{t('操作', 'Actions')}</span></th>
              </tr></thead>
              <tbody>{overdue.slice(0, 5).map((fee) => (
                <tr key={fee.id}>
                  <td>{canEdit ? <button className="table-link" onClick={() => onEdit(fee)}>{feeReference(fee)}</button> : feeReference(fee)}</td>
                  <td className="strong-cell">{fee.payer}</td><td>{feeTypes[fee.type]}</td><td>{date(fee.dueDate)}</td>
                  <td className="numeric strong-cell">{money(fee.amount)}</td><td><FeeBadge fee={fee} /></td>
                  <td>{canEdit && <button className="icon-button" onClick={() => onEdit(fee)} aria-label={t('编辑 ', 'Edit ') + feeReference(fee)}><ArrowRight size={16} /></button>}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )}
        </section>
        <div className="dashboard-bottom"><ArrowDownToLine size={15} /><a href="#reports">{t('查看财务报表', 'View financial reports')}</a></div>
      </>}
    </div>
  );
}
