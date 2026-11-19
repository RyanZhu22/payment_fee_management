import {
  BarChart3,
  Calculator,
  History,
  LogOut,
  Users,
  FileText,
  LayoutDashboard,
  Landmark,
  Menu,
  QrCode,
  ReceiptText,
  RefreshCw,
  Upload,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { AuditLog, UserAccounts } from './Administration';
import Billing from './Billing';
import Dashboard from './Dashboard';
import Fees, { FeeEditor } from './Fees';
import Imports from './Imports';
import Reports from './Reports';
import Settlements from './Settlements';
import { roleCopy, useWorkspace } from './Session';
import { Documents, FpsRequests } from './Communications';
import { LanguagePicker, useI18n } from './i18n';
import { errorText, request } from './lib';
import type { Fee } from './types';
import { ErrorNotice } from './ui';

const navigation = [
  { id: 'dashboard', label: ['总览', 'Overview'], icon: LayoutDashboard, group: 'operations' },
  { id: 'fees', label: ['费用台账', 'Fee ledger'], icon: ReceiptText, group: 'operations' },
  { id: 'billing', label: ['计费规则', 'Billing rules'], icon: Calculator, group: 'operations' },
  { id: 'settlements', label: ['结算批次', 'Settlement batches'], icon: Upload, group: 'finance' },
  { id: 'reconciliation', label: ['FPS 对账', 'FPS reconciliation'], icon: RefreshCw, group: 'finance' },
  { id: 'documents', label: ['通知与收据', 'Notices and receipts'], icon: FileText, group: 'documents' },
  { id: 'fps', label: ['FPS 请求', 'FPS requests'], icon: QrCode, group: 'documents' },
  { id: 'reports', label: ['运营报表', 'Operational reports'], icon: BarChart3, group: 'reports' },
  { id: 'audit', label: ['审计日志', 'Audit log'], icon: History, group: 'administration' },
  { id: 'users', label: ['用户账号', 'User accounts'], icon: Users, group: 'administration' },
] as const;

type Route = (typeof navigation)[number]['id'];

const groupLabels = {
  operations: ['运营', 'Operations'],
  finance: ['财务处理', 'Financial processing'],
  documents: ['文档与接口', 'Documents and interfaces'],
  reports: ['报表', 'Reporting'],
  administration: ['管理', 'Administration'],
} as const;

const groups = [...new Set(navigation.map((item) => item.group))];

function routeFromHash(): Route {
  const value = window.location.hash.replace(/^#\/?/, '');
  return navigation.some((item) => item.id === value) ? (value as Route) : 'dashboard';
}

export default function App() {
  const { language, t } = useI18n();
  const { user, can, signOut } = useWorkspace();
  const visibleNavigation = navigation.filter(({ id }) => {
    if (id === 'audit') return can('FINANCE', 'AUDITOR');
    if (id === 'users') return can('ADMIN');
    if (id === 'reconciliation') return can('OPERATOR');
    return true;
  });
  const [route, setRoute] = useState<Route>(routeFromHash);
  const [fees, setFees] = useState<Fee[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorFee, setEditorFee] = useState<Fee | null>(null);
  const [navOpen, setNavOpen] = useState(false);
  const [sessionError, setSessionError] = useState('');

  const refresh = useCallback(async () => {
    try {
      setFees(await request<Fee[]>('/fees'));
      setError('');
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [language, refresh]);

  useEffect(() => {
    const onHashChange = () => {
      setRoute(routeFromHash());
      setNavOpen(false);
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  function createFee() {
    if (!can('OPERATOR')) return;
    setEditorFee(null);
    setEditorOpen(true);
  }

  function editFee(fee: Fee) {
    if (!can('OPERATOR') || fee.status !== 'UNPAID') return;
    setEditorFee(fee);
    setEditorOpen(true);
  }

  let content: ReactNode;
  const currentRoute = visibleNavigation.some((item) => item.id === route) ? route : 'dashboard';
  switch (currentRoute) {
    case 'fees':
      content = <Fees fees={fees} loading={loading} onCreate={createFee} onEdit={editFee} refresh={refresh} />;
      break;
    case 'billing':
      content = <Billing fees={fees} onChanged={() => void refresh()} />;
      break;
    case 'settlements':
      content = <Settlements fees={fees} onChanged={() => void refresh()} />;
      break;
    case 'reconciliation':
      content = <Imports />;
      break;
    case 'documents':
      content = <Documents fees={fees} />;
      break;
    case 'fps':
      content = <FpsRequests fees={fees} />;
      break;
    case 'reports':
      content = <Reports />;
      break;
    case 'audit':
      content = <AuditLog />;
      break;
    case 'users':
      content = <UserAccounts />;
      break;
    default:
      content = <Dashboard fees={fees} loading={loading} onCreate={createFee} onEdit={editFee} />;
  }

  return (
    <div className="app-shell">
      <aside className={'sidebar' + (navOpen ? ' open' : '')} aria-label={t('主导航', 'Main navigation')}>
        <div className="sidebar-heading">
          <a className="brand" href="#dashboard">
            <span className="brand-mark"><Landmark size={19} strokeWidth={1.9} /></span>
            <span className="brand-text"><strong>OMSWeb</strong><small>{t('支付与费用管理', 'Payment and Fee Management')}</small></span>
          </a>
          <button type="button" className="icon-button sidebar-close" aria-label={t('关闭导航', 'Close navigation')} onClick={() => setNavOpen(false)}>
            <X size={19} />
          </button>
        </div>
        <nav className="nav">
          {groups.filter((group) => visibleNavigation.some((item) => item.group === group)).map((group) => (
            <div className="nav-group" key={group}>
              <span className="nav-group-label">{t(groupLabels[group][0], groupLabels[group][1])}</span>
              {visibleNavigation.filter((item) => item.group === group).map(({ id, label, icon: Icon }) => (
                <a key={id} href={'#' + id} className={'nav-item' + (currentRoute === id ? ' active' : '')} aria-current={currentRoute === id ? 'page' : undefined}>
                  <Icon size={18} strokeWidth={1.8} /><span>{t(label[0], label[1])}</span>
                </a>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-footer">
          <LanguagePicker />
          <div className="session-account">
            <div><strong>{user.username}</strong><small>{t(roleCopy[user.role][0], roleCopy[user.role][1])}</small></div>
            <button type="button" className="icon-button" aria-label={t('退出登录', 'Sign out')}
              onClick={() => void signOut().catch((cause) => setSessionError(errorText(cause)))}><LogOut size={17} /></button>
          </div>
        </div>
      </aside>
      {navOpen && <button type="button" className="nav-backdrop" aria-label={t('关闭导航', 'Close navigation')} onClick={() => setNavOpen(false)} />}

      <main className="main">
        <div className="mobile-bar">
          <button type="button" className="icon-button" aria-label={t('打开导航', 'Open navigation')} onClick={() => setNavOpen(true)}><Menu size={20} /></button>
          <span className="mobile-brand">{t('OMSWeb 支付与费用管理', 'OMSWeb Payments')}</span>
          <LanguagePicker />
        </div>
        {sessionError && <div className="global-notice"><ErrorNotice error={sessionError} /></div>}
        {error && !loading && (
          <div className="global-notice">
            <ErrorNotice error={error} onRetry={() => void refresh()} />
          </div>
        )}
        {content}
      </main>

      {editorOpen && (
        <FeeEditor
          fee={editorFee}
          fees={fees}
          onClose={() => setEditorOpen(false)}
          onSave={() => void refresh()}
        />
      )}
    </div>
  );
}
