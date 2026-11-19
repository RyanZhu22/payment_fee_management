import { Landmark } from 'lucide-react';
import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { LanguagePicker, useI18n } from './i18n';
import { errorText, request, setRequestCsrf } from './lib';
import { ErrorNotice, Loading } from './ui';

export type Role = 'OPERATOR' | 'FINANCE' | 'AUDITOR' | 'ADMIN';
export interface WorkspaceUser { username: string; role: Role }
interface SessionState {
  user: WorkspaceUser | null;
  csrfToken: string;
  csrfHeader: string;
}
interface SignedIn {
  user: WorkspaceUser;
  can: (...roles: Role[]) => boolean;
  signOut: () => Promise<void>;
}

const WorkspaceContext = createContext<SignedIn | null>(null);

export const roleCopy = {
  OPERATOR: ['运营人员', 'Operator'],
  FINANCE: ['财务复核', 'Finance reviewer'],
  AUDITOR: ['审计员', 'Auditor'],
  ADMIN: ['管理员', 'Administrator'],
} as const;

export function useWorkspace() {
  const workspace = useContext(WorkspaceContext);
  if (!workspace) throw new Error('Workspace session required');
  return workspace;
}

export function SessionGate({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [session, setSession] = useState<SessionState | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    const next = await request<SessionState>('/auth/session', { cache: 'no-store' });
    setRequestCsrf(next.csrfHeader, next.csrfToken);
    setSession(next);
  }, []);

  const load = useCallback(() => {
    setError('');
    void refresh().catch((cause) => setError(errorText(cause)));
  }, [refresh]);

  useEffect(() => {
    load();
    const expire = () => {
      setSession(null);
      load();
    };
    window.addEventListener('omsweb:session-expired', expire);
    return () => window.removeEventListener('omsweb:session-expired', expire);
  }, [load]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const body = new URLSearchParams({
      username: String(form.get('username') ?? '').trim(),
      password: String(form.get('password') ?? ''),
    });

    setBusy(true);
    setError('');
    try {
      await request<void>('/auth/login', { method: 'POST', body });
      await refresh();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    await request<void>('/auth/logout', { method: 'POST' });
    setSession(null);
    await refresh();
  }

  if (session?.user) {
    const user = session.user;
    return (
      <WorkspaceContext.Provider value={{
        user,
        can: (...roles) => user.role === 'ADMIN' || roles.includes(user.role),
        signOut,
      }}>
        {children}
      </WorkspaceContext.Provider>
    );
  }

  return (
    <main className="signin">
      <section className="panel signin-panel">
        <header className="signin-heading">
          <span className="brand-mark"><Landmark size={21} /></span>
          <LanguagePicker />
        </header>
        <h1>{t('登录 OMSWeb', 'Sign in to OMSWeb')}</h1>
        <p>{t('支付与费用管理工作台', 'Payment and fee management workspace')}</p>
        {error && <ErrorNotice error={error} onRetry={!session ? load : undefined} />}
        {session ? (
          <form className="form-stack" onSubmit={(event) => void signIn(event)}>
            <label>{t('账号', 'Username')}<input name="username" required autoComplete="username" /></label>
            <label>{t('密码', 'Password')}<input name="password" type="password" required autoComplete="current-password" /></label>
            <button className="button primary" disabled={busy}>{busy ? t('正在登录…', 'Signing in…') : t('登录', 'Sign in')}</button>
          </form>
        ) : !error && <Loading />}
      </section>
    </main>
  );
}
