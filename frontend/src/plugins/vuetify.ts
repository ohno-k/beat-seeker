/**
 * 【プラグインの役割】 Vuetify（UI コンポーネント）の設定。
 *
 * - テーマ: 既存のデザイン（クロームは無彩色 + blue-700 一色、面は slate 系、角丸 6px）に合わせた light / dark。
 *   ダークモードの切り替えは従来どおり useDarkMode が <html> の `dark` クラスで行い、ここではそのクラスを監視して
 *   Vuetify のテーマを追従させる（Tailwind の `dark:` と Vuetify のテーマが常にそろう）。
 * - 既定値: ボタンは大文字化しない・影なし、入力欄は枠線（outlined）・詰めた高さ、カードは罫線（影なし）。
 * - スタイル: Vuetify の CSS はカスケードレイヤー（vuetify-*）に入っているので、レイヤー外の Tailwind のクラスが常に勝つ。
 *   部分的な見た目の調整は Tailwind のクラスをそのまま重ねればよい。
 */
import { createVuetify, type ThemeDefinition } from 'vuetify';
import { aliases, mdi } from 'vuetify/iconsets/mdi-svg';

const light: ThemeDefinition = {
  dark: false,
  colors: {
    background: '#f8fafc', // slate-50
    surface: '#ffffff',
    'surface-variant': '#f1f5f9', // slate-100
    'on-surface-variant': '#334155', // slate-700
    primary: '#1d4ed8', // blue-700
    secondary: '#475569', // slate-600
    success: '#059669', // emerald-600
    warning: '#d97706', // amber-600
    error: '#dc2626', // red-600
    info: '#2563eb', // blue-600
  },
  variables: {
    'border-color': '#e2e8f0', // slate-200
    'border-opacity': 1,
  },
};

const dark: ThemeDefinition = {
  dark: true,
  colors: {
    background: '#0f172a', // slate-900
    surface: '#1e293b', // slate-800
    'surface-variant': '#334155', // slate-700
    'on-surface-variant': '#e2e8f0', // slate-200
    primary: '#2563eb', // blue-600
    secondary: '#94a3b8', // slate-400
    success: '#10b981', // emerald-500
    warning: '#f59e0b', // amber-500
    error: '#ef4444', // red-500
    info: '#3b82f6', // blue-500
  },
  variables: {
    'border-color': '#334155', // slate-700
    'border-opacity': 1,
  },
};

const isDark = () => typeof document !== 'undefined' && document.documentElement.classList.contains('dark');

export const vuetify = createVuetify({
  theme: {
    defaultTheme: isDark() ? 'dark' : 'light',
    themes: { light, dark },
  },
  icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
  defaults: {
    global: { ripple: false },
    VBtn: { variant: 'flat', rounded: 'md', class: 'text-none font-weight-bold', elevation: 0 },
    // outlined は枠線が文字色になるので、flat + border（テーマの border-color = slate-200 / slate-700）で既存の .card に合わせる
    VCard: { variant: 'flat', border: true, rounded: 'md', elevation: 0 },
    VSheet: { rounded: 'md' },
    VTextField: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VTextarea: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VSelect: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VAutocomplete: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VCombobox: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VNumberInput: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VFileInput: { variant: 'outlined', density: 'compact', hideDetails: 'auto', color: 'primary' },
    VCheckbox: { density: 'compact', hideDetails: 'auto', color: 'primary' },
    VSwitch: { density: 'compact', hideDetails: 'auto', color: 'primary', inset: true },
    VRadioGroup: { density: 'compact', hideDetails: 'auto', color: 'primary' },
    VSlider: { density: 'compact', hideDetails: 'auto', color: 'primary' },
    VBtnToggle: { density: 'compact', variant: 'outlined', divided: true, color: 'primary', rounded: 'md' },
    VTabs: { color: 'primary', density: 'compact' },
    VChip: { size: 'small', rounded: 'md' },
    VAlert: { variant: 'tonal', density: 'compact', rounded: 'md' },
    VDialog: { scrollable: true },
    VMenu: { offset: 4 },
    VTooltip: { location: 'top' },
    VTable: { density: 'compact' },
    VProgressCircular: { indeterminate: true, color: 'primary' },
  },
});

/**
 * 【関数の役割】 <html> の `dark` クラス（useDarkMode が付け外しする）を監視し、Vuetify のテーマを追従させる。
 * main.ts でアプリ作成時に 1 回呼ぶ。
 */
export function syncVuetifyThemeWithDarkClass(): void {
  if (typeof document === 'undefined') return;
  const apply = () => {
    const name = isDark() ? 'dark' : 'light';
    if (vuetify.theme.global.name.value !== name) vuetify.theme.change(name);
  };
  apply();
  new MutationObserver(apply).observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
}
