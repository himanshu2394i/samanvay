import { en } from './en'
import { mr } from './mr'
import type { Dictionary, Lang, TFunction, TParams, TranslationKey } from './types'

export const DICTIONARIES: Record<Lang, Dictionary> = { en, mr }

/** Fills {named} placeholders in a template; unknown placeholders are left untouched. */
function interpolate(template: string, params?: TParams): string {
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in params ? String(params[name]) : whole,
  )
}

/** Builds a `t(key, params)` function bound to one language's dictionary. */
export function makeT(lang: Lang): TFunction {
  const dict = DICTIONARIES[lang] ?? en
  return (key: TranslationKey, params?: TParams) => interpolate(dict[key] ?? en[key], params)
}

/** English translator, used as the default when no LanguageProvider is present (staff surfaces). */
export const enT = makeT('en')
