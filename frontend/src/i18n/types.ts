import type { en } from './en'

/** The supported citizen-surface languages. English is the default. */
export type Lang = 'en' | 'mr'

/** Every translation key, taken from the English dictionary (the source of truth). */
export type TranslationKey = keyof typeof en

/** A dictionary provides exactly the same keys as `en`, so `mr` can never drift out of sync. */
export type Dictionary = Record<TranslationKey, string>

/** Values placed into a string's {named} placeholders by `t(key, params)`. */
export type TParams = Record<string, string | number>

/** Translates a key to the current language, substituting any {named} placeholders. */
export type TFunction = (key: TranslationKey, params?: TParams) => string
