import type { Lang } from './types'

// The chosen citizen-surface language is a per-viewer convenience, remembered in localStorage.
// Losing it (private window, storage blocked) simply falls back to the English default.
const KEY = 'samanvay.lang'

function isLang(value: string | null): value is Lang {
  return value === 'en' || value === 'mr'
}

export function readLang(): Lang {
  try {
    const value = window.localStorage.getItem(KEY)
    if (isLang(value)) return value
  } catch {
    /* storage blocked: fall back to the default */
  }
  return 'en'
}

export function writeLang(lang: Lang): void {
  try {
    window.localStorage.setItem(KEY, lang)
  } catch {
    /* storage blocked: the choice is simply not remembered */
  }
}
