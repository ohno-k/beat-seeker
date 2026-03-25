import { ja } from './ja';
import { en } from './en';
import { ko } from './ko';

export type LocaleKey = keyof typeof ja;
export type Translations = Record<string, string>;
export type LocaleMap = Record<string, Translations>;

export const translations: Record<string, Translations> = { ja, en, ko };
