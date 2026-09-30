import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { readLang, writeLang } from './langStore'
import { enT, makeT } from './translate'
import type { Lang, TFunction } from './types'

interface LanguageContextValue {
  lang: Lang
  setLang: (lang: Lang) => void
  t: TFunction
}

// A default value keyed to English means components outside a provider (the staff surfaces,
// which have no toggle) still render — always in English — instead of throwing.
const LanguageContext = createContext<LanguageContextValue>({
  lang: 'en',
  setLang: () => {},
  t: enT,
})

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(() => readLang())

  // Keep <html lang> in step so screen readers pronounce Devanagari (or English) correctly.
  useEffect(() => {
    document.documentElement.lang = lang
  }, [lang])

  const setLang = useCallback((next: Lang) => {
    writeLang(next)
    setLangState(next)
  }, [])

  const value = useMemo<LanguageContextValue>(() => ({ lang, setLang, t: makeT(lang) }), [lang, setLang])
  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useLanguage(): LanguageContextValue {
  return useContext(LanguageContext)
}

/** The translation function for the current language. */
// eslint-disable-next-line react-refresh/only-export-components
export function useT(): TFunction {
  return useContext(LanguageContext).t
}
