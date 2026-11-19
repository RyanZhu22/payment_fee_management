import { Globe2 } from 'lucide-react';
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

export type Language = 'zh' | 'en';

const languageKey = 'omsweb.language';
const localeByLanguage = { zh: 'zh-Hans-HK', en: 'en-HK' } as const;
const timeZone = 'Asia/Hong_Kong';

export function getLanguage(): Language {
  return localStorage.getItem(languageKey) === 'en' ? 'en' : 'zh';
}

export function browserLocale(language: Language) {
  return localeByLanguage[language];
}

export function localizedUrl(url: string, language: Language) {
  const target = new URL(url, window.location.origin);
  target.searchParams.set('lang', language);
  return target.pathname + target.search + target.hash;
}

interface I18n {
  language: Language;
  locale: string;
  setLanguage: (language: Language) => void;
  t: (zh: string, en: string) => string;
  money: (amount: number) => string;
  date: (value: string) => string;
  dateTime: (value: string) => string;
}

const I18nContext = createContext<I18n | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguage] = useState(getLanguage);
  const value = useMemo<I18n>(() => {
    const locale = browserLocale(language);
    const money = new Intl.NumberFormat(locale, {
      style: 'currency', currency: 'HKD', currencyDisplay: 'code',
    });
    const date = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone });
    const dateTime = new Intl.DateTimeFormat(locale, {
      dateStyle: 'medium', timeStyle: 'short', hour12: false, timeZone,
    });

    return {
      language,
      locale,
      setLanguage: (next) => {
        localStorage.setItem(languageKey, next);
        setLanguage(next);
      },
      t: (zh, en) => language === 'en' ? en : zh,
      money: (amount) => money.format(amount),
      date: (iso) => date.format(new Date(iso)),
      dateTime: (iso) => dateTime.format(new Date(iso)),
    };
  }, [language]);

  useEffect(() => {
    document.documentElement.lang = value.locale;
    document.title = value.t('OMSWeb 支付与费用工作台', 'OMSWeb Payment and Fee Management');
    document.querySelector('meta[name="description"]')?.setAttribute(
      'content',
      value.t('OMSWeb 支付与费用运营工作台', 'OMSWeb payment and fee operations workspace'),
    );
  }, [value]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const context = useContext(I18nContext);
  if (!context) throw new Error('I18nProvider is required');
  return context;
}

export function LanguagePicker() {
  const { language, setLanguage, t } = useI18n();
  return (
    <label className="language-control">
      <Globe2 size={16} aria-hidden="true" />
      <select
        value={language}
        aria-label={t('语言', 'Language')}
        onChange={(event) => setLanguage(event.currentTarget.value === 'en' ? 'en' : 'zh')}
      >
        <option value="zh" lang="zh-Hans">简体中文</option>
        <option value="en" lang="en">English</option>
      </select>
    </label>
  );
}
