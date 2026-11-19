import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useI18n } from './i18n';
import { errorText, json, request } from './lib';
import { roleCopy } from './Session';
import type { WorkspaceUser } from './Session';
import { Empty, ErrorNotice, Loading, Modal, PageHeading, PanelHeading } from './ui';

interface AuditEntry {
  id: number;
  actor: string;
  action: string;
  entityType: string;
  entityId: string;
  before: unknown;
  after: unknown;
  createdAt: string;
}

const translations: Record<string, readonly [string, string]> = {
  LOGIN: ['登录', 'Sign in'],
  LOGOUT: ['退出登录', 'Sign out'],
  ACCOUNT_CREATED: ['创建账号', 'Create account'],
  DOCUMENT_GENERATED: ['生成文档', 'Generate document'],
  DOCUMENT_DOWNLOADED: ['下载文档', 'Download document'],
  REPORT_EXPORTED: ['导出报表', 'Export report'],
  FIS_EXPORTED: ['导出 FIS', 'Export FIS'],
  CREATE: ['创建', 'Create'],
  UPDATE: ['编辑', 'Update'],
  ISSUE: ['开立费用', 'Issue fee'],
  SETTLEMENT_BATCH_SUBMITTED: ['提交结算批次', 'Submit settlement batch'],
  SETTLEMENT_BATCH_APPROVED: ['批准结算批次', 'Approve settlement batch'],
  SETTLEMENT_BATCH_REJECTED: ['驳回结算批次', 'Reject settlement batch'],
  SETTLEMENT_FILE_DOWNLOADED: ['下载结算原文件', 'Download settlement file'],
  PAYMENT_POSTED: ['登记到账', 'Post payment'],
  PAYMENT_REVERSAL_REQUESTED: ['申请冲正', 'Request reversal'],
  PAYMENT_REVERSAL_APPROVED: ['批准冲正', 'Approve reversal'],
  PAYMENT_REVERSAL_REJECTED: ['驳回冲正', 'Reject reversal'],
  PAYMENT_REVERSED: ['冲正到账记录', 'Reverse payment'],
};

export function AuditLog() {
  const { t, dateTime } = useI18n();
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState('');
  const [active, setActive] = useState<AuditEntry | null>(null);

  const load = useCallback(async () => {
    setError('');
    try {
      setEntries(await request<AuditEntry[]>('/audit'));
    } catch (cause) {
      setError(errorText(cause));
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  function actionLabel(action: string) {
    const copy = translations[action];
    return copy ? t(copy[0], copy[1]) : action;
  }

  return (
    <div className="page-content">
      <PageHeading title={t('审计日志', 'Audit log')} description={t('查看费用、结算、凭证和导出的近期操作记录。', 'Review recent activity on fees, settlements, documents and exports. List shows the latest entries.')}>
        <button className="button secondary" onClick={() => void load()}>{t('刷新', 'Refresh')}</button>
      </PageHeading>
      {error && <ErrorNotice error={error} onRetry={() => void load()} />}
      <section className="panel">
        {entries === null ? !error && <Loading /> : entries.length === 0 ? <Empty /> :
          <div className="table-wrap"><table>
            <thead><tr><th>{t('时间', 'Time')}</th><th>{t('操作者', 'Actor')}</th><th>{t('事件', 'Action')}</th><th>{t('对象', 'Record')}</th><th>{t('详情', 'Details')}</th></tr></thead>
            <tbody>{entries.map((entry) => <tr key={entry.id}>
              <td>{dateTime(entry.createdAt)}</td><td>{entry.actor}</td><td>{actionLabel(entry.action)}</td>
              <td>{entry.entityType}<small>{entry.entityId}</small></td>
              <td><button className="table-link" onClick={() => setActive(entry)}>{t('查看详情', 'View details')}</button></td>
            </tr>)}</tbody>
          </table></div>}
      </section>
      {active && <Modal title={t('审计记录', 'Audit entry') + ' #' + active.id} onClose={() => setActive(null)}>
        <div className="form-stack modal-body">
          <p>{actionLabel(active.action)} · {active.actor} · {dateTime(active.createdAt)}</p>
          <div><strong>{t('变更前', 'Before')}</strong><pre className="audit-state">{active.before == null ? '—' : JSON.stringify(active.before, null, 2)}</pre></div>
          <div><strong>{t('变更后', 'After')}</strong><pre className="audit-state">{active.after == null ? '—' : JSON.stringify(active.after, null, 2)}</pre></div>
        </div>
      </Modal>}
    </div>
  );
}

export function UserAccounts() {
  const { t } = useI18n();
  const [users, setUsers] = useState<WorkspaceUser[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    try {
      setUsers(await request<WorkspaceUser[]>('/auth/users'));
    } catch (cause) {
      setError(errorText(cause));
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fields = new FormData(form);
    setBusy(true);
    setError('');
    try {
      await request<WorkspaceUser>('/auth/users', json('POST', {
        username: fields.get('username'), password: fields.get('password'), role: fields.get('role'),
      }));
      form.reset();
      await load();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  return <div className="page-content">
    <PageHeading title={t('用户账号', 'User accounts')} description={t('创建账号并分配工作台角色。', 'Create accounts and assign workspace roles.')} />
    {error && <ErrorNotice error={error} />}
    <section className="panel form-stack process-section">
      <PanelHeading title={t('新建账号', 'Create account')} />
      <form className="form-grid" onSubmit={(event) => void create(event)}>
        <label>{t('用户名', 'Username')}<input name="username" autoComplete="off" required maxLength={64} /></label>
        <label>{t('角色', 'Role')}<select name="role">
          {Object.entries(roleCopy).map(([role, copy]) => <option key={role} value={role}>{t(copy[0], copy[1])}</option>)}
        </select></label>
        <label>{t('初始密码', 'Initial password')}<input name="password" type="password" minLength={12} autoComplete="new-password" required /></label>
        <div className="span-2"><button className="button primary" disabled={busy}>{t('创建账号', 'Create account')}</button></div>
      </form>
    </section>
    <section className="panel">
      <PanelHeading title={t('账号列表', 'Accounts')} />
      {users === null ? !error && <Loading /> : users.length === 0 ? <Empty /> : <div className="table-wrap"><table>
        <thead><tr><th>{t('用户名', 'Username')}</th><th>{t('角色', 'Role')}</th></tr></thead>
        <tbody>{users.map((user) => <tr key={user.username}><td>{user.username}</td><td>{t(roleCopy[user.role][0], roleCopy[user.role][1])}</td></tr>)}</tbody>
      </table></div>}
    </section>
  </div>;
}
