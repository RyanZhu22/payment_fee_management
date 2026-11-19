import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useI18n } from './i18n';
import { errorText, feeReference, feeTypeLabels, json, request, today } from './lib';
import { useWorkspace } from './Session';
import type { Fee, FeeType } from './types';
import { Badge, Empty, ErrorNotice, Loading, PageHeading, PanelHeading } from './ui';

interface Scheme {
  id: number;
  code: string;
  name: string;
  payer: string;
  payerEmail: string | null;
  active: boolean;
}

interface Rule {
  id: number;
  schemeId: number;
  type: FeeType;
  mode: 'FIXED' | 'PERCENT';
  rate: number;
  effectiveFrom: string;
  dueDays: number;
  cycleMonths: number;
  roundingMode: 'HALF_UP' | 'HALF_EVEN' | 'DOWN';
}

interface Quote { ruleId: number; amount: number; dueDate: string }

export default function Billing({ fees, onChanged }: { fees: Fee[]; onChanged: () => void }) {
  const { language, locale, t, money, date } = useI18n();
  const { can } = useWorkspace();
  const [schemes, setSchemes] = useState<Scheme[]>([]);
  const [rules, setRules] = useState<Rule[]>([]);
  const [ruleType, setRuleType] = useState<FeeType>('APPLICATION');
  const [issueType, setIssueType] = useState<'APPLICATION' | 'SURCHARGE'>('APPLICATION');
  const [issueScheme, setIssueScheme] = useState('');
  const [quote, setQuote] = useState<Quote | null>(null);
  const [issued, setIssued] = useState<Fee | null>(null);
  const [billingRun, setBillingRun] = useState<{ created: number; period: string } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const feeTypes = feeTypeLabels(language);
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 6 });
  const activeSchemes = schemes.filter((scheme) => scheme.active);

  const load = useCallback(async () => {
    try {
      const [nextSchemes, nextRules] = await Promise.all([
        request<Scheme[]>('/billing/schemes'), request<Rule[]>('/billing/rules'),
      ]);
      setSchemes(nextSchemes);
      setRules(nextRules);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function perform(operation: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await operation();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function createScheme(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    void perform(async () => {
      await request<Scheme>('/billing/schemes', json('POST', {
        code: fields.get('code'), name: fields.get('name'), payer: fields.get('payer'),
        payerEmail: fields.get('payerEmail'), active: true,
      }));
      form.reset();
      await load();
    });
  }

  function createRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    void perform(async () => {
      await request<Rule>('/billing/rules', json('POST', {
        schemeId: Number(fields.get('schemeId')), type: ruleType, mode: fields.get('mode') ?? 'FIXED',
        rate: Number(fields.get('rate')), effectiveFrom: fields.get('effectiveFrom'),
        dueDays: Number(fields.get('dueDays')), cycleMonths: Number(fields.get('cycleMonths') ?? 1),
        roundingMode: fields.get('roundingMode'),
      }));
      form.reset();
      await load();
    });
  }

  function calculate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    const submitter = (event.nativeEvent as SubmitEvent).submitter;
    const issue = submitter?.getAttribute('value') === 'issue';
    const input = {
      schemeId: Number(fields.get('schemeId')), type: issueType, calculationDate: fields.get('calculationDate'),
      sourceFeeId: fields.get('sourceFeeId') ? Number(fields.get('sourceFeeId')) : null,
      applicationNumber: fields.get('applicationNumber'), description: fields.get('description'),
    };
    void perform(async () => {
      if (issue) {
        setIssued(await request<Fee>('/billing/issue', json('POST', input)));
        setQuote(null);
        onChanged();
      } else {
        setIssued(null);
        setQuote(await request<Quote>('/billing/quote', json('POST', {
          schemeId: input.schemeId, type: input.type, calculationDate: input.calculationDate, sourceFeeId: input.sourceFeeId,
        })));
      }
    });
  }

  function toggleScheme(scheme: Scheme) {
    void perform(async () => {
      await request<Scheme>('/billing/schemes/' + scheme.id, json('PUT', {
        code: scheme.code, name: scheme.name, payer: scheme.payer, payerEmail: scheme.payerEmail, active: !scheme.active,
      }));
      setQuote(null);
      if (scheme.active && Number(issueScheme) === scheme.id) setIssueScheme('');
      await load();
    });
  }

  function runPeriodic() {
    void perform(async () => {
      setBillingRun(await request<{ created: number; period: string }>('/fees/periodic-run', { method: 'POST' }));
      onChanged();
    });
  }

  const schemeOptions = (items: Scheme[]) => items.map((scheme) => (
    <option key={scheme.id} value={scheme.id}>{scheme.code} · {scheme.name}</option>
  ));
  const schemeName = schemes.find((scheme) => scheme.id === Number(issueScheme))?.name;
  const sourceOptions = fees.filter((fee) => fee.scheme === schemeName && fee.type === 'PERIODIC'
    && fee.status === 'UNPAID' && fee.dueDate < today());

  return (
    <div className="page-content">
      <PageHeading title={t('计费规则', 'Billing rules')} description={t('维护计划主数据与生效日期，按规则估算和开立费用。', 'Maintain schemes and effective-dated rates, then preview and issue fees.')}>
        {can('FINANCE') && <button type="button" className="button secondary" disabled={busy} onClick={runPeriodic}>{t('运行本期计费', 'Run periodic billing')}</button>}
      </PageHeading>
      {error && <ErrorNotice error={error} />}
      {billingRun && <div className="notice notice-success" role="status">{t(
        billingRun.period + ' 计费完成，新增费用 ' + billingRun.created + ' 笔。',
        'Billing for ' + billingRun.period + ' created ' + billingRun.created + ' fee(s).',
      )}</div>}
      {can('FINANCE') && <div className="process-forms">
        <section className="panel process-section form-stack">
          <PanelHeading title={t('登记计划', 'Register a scheme')} />
          <form className="form-stack" onSubmit={createScheme}>
            <label>{t('计划代码', 'Scheme code')}<input name="code" maxLength={40} required /></label>
            <label>{t('计划名称', 'Scheme name')}<input name="name" maxLength={120} required /></label>
            <label>{t('付款人', 'Payer')}<input name="payer" maxLength={120} required /></label>
            <label>{t('付款人邮箱', 'Payer email')}<input name="payerEmail" type="email" maxLength={254} /></label>
            <div><button className="button primary" disabled={busy}>{t('保存计划', 'Save scheme')}</button></div>
          </form>
        </section>
        <section className="panel process-section form-stack">
          <PanelHeading title={t('发布费率版本', 'Publish a rate version')} />
          <form className="form-grid" onSubmit={createRule}>
            <label>{t('计划', 'Scheme')}<select name="schemeId" required defaultValue=""><option value="" disabled>{t('选择计划', 'Select a scheme')}</option>{schemeOptions(activeSchemes)}</select></label>
            <label>{t('费用类型', 'Fee type')}<select value={ruleType} onChange={(event) => setRuleType(event.target.value as FeeType)}>
              {Object.entries(feeTypes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            {ruleType === 'SURCHARGE' && <label>{t('计算方式', 'Calculation method')}<select name="mode">
              <option value="FIXED">{t('固定金额', 'Fixed amount')}</option><option value="PERCENT">{t('原费用的百分比', 'Percentage of original fee')}</option>
            </select></label>}
            <label>{ruleType === 'SURCHARGE' ? t('金额或百分比', 'Amount or percentage') : t('金额（HKD）', 'Amount (HKD)')}<input name="rate" type="number" min="0.000001" step="0.000001" required /></label>
            <label>{t('生效日期', 'Effective date')}<input name="effectiveFrom" type="date" defaultValue={today()} required /></label>
            <label>{t('到期天数', 'Days until due')}<input name="dueDays" type="number" min="0" max="3650" defaultValue="30" required /></label>
            {ruleType === 'PERIODIC' && <label>{t('每隔几个月计费', 'Billing cycle in months')}<input name="cycleMonths" type="number" min="1" max="120" defaultValue="1" required /></label>}
            <label>{t('取整方式', 'Rounding method')}<select name="roundingMode">
              <option value="HALF_UP">{t('四舍五入', 'Round half up')}</option><option value="HALF_EVEN">{t('四舍六入五留双', 'Round half to even')}</option><option value="DOWN">{t('向下截断', 'Round down')}</option>
            </select></label>
            <div className="span-2"><button className="button primary" disabled={busy || !activeSchemes.length}>{t('发布费率', 'Publish rate')}</button></div>
          </form>
        </section>
      </div>}

      {can('OPERATOR', 'FINANCE') && <section className="panel process-section form-stack">
        <PanelHeading title={t('按规则计费', 'Calculate a fee')} />
        <form className="form-grid" onSubmit={calculate} onChange={() => { setQuote(null); setIssued(null); }}>
          <label>{t('计划', 'Scheme')}<select name="schemeId" value={issueScheme} onChange={(event) => setIssueScheme(event.target.value)} required>
            <option value="" disabled>{t('选择计划', 'Select a scheme')}</option>{schemeOptions(activeSchemes)}
          </select></label>
          <label>{t('费用类型', 'Fee type')}<select value={issueType} onChange={(event) => setIssueType(event.target.value as typeof issueType)}>
            <option value="APPLICATION">{feeTypes.APPLICATION}</option><option value="SURCHARGE">{feeTypes.SURCHARGE}</option>
          </select></label>
          <label>{t('计费日期', 'Calculation date')}<input name="calculationDate" type="date" defaultValue={today()} required /></label>
          {issueType === 'APPLICATION' ? <label>{t('申请编号', 'Application number')}<input name="applicationNumber" maxLength={64} required /></label> :
            <label>{t('原费用', 'Original fee')}<select name="sourceFeeId" required defaultValue=""><option value="" disabled>{t('选择原费用', 'Select original fee')}</option>
              {sourceOptions.map((fee) => <option key={fee.id} value={fee.id}>{feeReference(fee)} · {money(fee.amount)}</option>)}
            </select></label>}
          <label className="span-2">{t('说明', 'Description')}<input name="description" maxLength={240} required /></label>
          <div className="span-2 process-actions">
            <button className="button secondary" value="quote" disabled={busy || !activeSchemes.length}>{t('预览金额', 'Preview charge')}</button>
            {can('OPERATOR') && <button className="button primary" value="issue" disabled={busy || !quote}>{t('开立费用', 'Issue fee')}</button>}
          </div>
        </form>
        {quote && <div className="notice notice-success" role="status">{t('预览', 'Preview')} · {money(quote.amount)} · {t('到期日', 'Due date')} {date(quote.dueDate)} · {t('规则', 'Rule')} #{quote.ruleId}</div>}
        {issued && <div className="notice notice-success" role="status">{t('已开立', 'Issued')} {feeReference(issued)} · {money(issued.amount)}</div>}
      </section>}

      <section className="panel">
        <PanelHeading title={t('计划主数据', 'Scheme master data')} />
        {loading ? <Loading /> : !schemes.length ? <Empty /> : <div className="table-wrap"><table>
          <thead><tr><th>{t('计划代码', 'Scheme code')}</th><th>{t('计划名称', 'Scheme name')}</th><th>{t('付款人', 'Payer')}</th><th>{t('状态', 'Status')}</th>{can('FINANCE') && <th>{t('操作', 'Actions')}</th>}</tr></thead>
          <tbody>{schemes.map((scheme) => <tr key={scheme.id}><td>{scheme.code}</td><td>{scheme.name}</td><td>{scheme.payer}<small>{scheme.payerEmail}</small></td>
            <td><Badge tone={scheme.active ? 'green' : 'neutral'}>{scheme.active ? t('启用', 'Active') : t('停用', 'Disabled')}</Badge></td>
            {can('FINANCE') && <td><button type="button" className="text-button" disabled={busy} onClick={() => toggleScheme(scheme)}>
              {scheme.active ? t('停用计划', 'Disable scheme') : t('启用计划', 'Enable scheme')}
            </button></td>}</tr>)}</tbody>
        </table></div>}
      </section>
      <section className="panel">
        <PanelHeading title={t('历史费率版本', 'Rate versions')} />
        {loading ? <Loading /> : !rules.length ? <Empty /> : <div className="table-wrap"><table>
          <thead><tr><th>{t('计划', 'Scheme')}</th><th>{t('费用类型', 'Fee type')}</th><th>{t('费率', 'Rate')}</th><th>{t('生效日', 'Effective date')}</th><th>{t('到期天数', 'Days until due')}</th><th>{t('周期（月）', 'Cycle (months)')}</th></tr></thead>
          <tbody>{rules.map((rule) => <tr key={rule.id}><td>{schemes.find((scheme) => scheme.id === rule.schemeId)?.name ?? rule.schemeId}<small>#{rule.id}</small></td>
            <td>{feeTypes[rule.type]}</td><td>{rule.mode === 'PERCENT' ? number.format(rule.rate) + '%' : 'HKD ' + number.format(rule.rate)}</td><td>{date(rule.effectiveFrom)}</td>
            <td>{rule.dueDays}</td><td>{rule.type === 'PERIODIC' ? rule.cycleMonths : '—'}</td></tr>)}</tbody>
        </table></div>}
      </section>
    </div>
  );
}
