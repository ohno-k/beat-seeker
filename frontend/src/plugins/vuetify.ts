/**
 * 【プラグインの役割】 Vuetify（UI コンポーネント）の設定。
 *
 * - テーマ: Vuetify 標準の light / dark（色・影・角丸・リップル・入力欄の見た目は Vuetify の既定のまま）。
 *   ダークモードの切り替えは従来どおり useDarkMode が <html> の `dark` クラスで行い、ここではそのクラスを監視して
 *   Vuetify のテーマを追従させる（Tailwind の `dark:` と Vuetify のテーマが常にそろう）。
 * - スタイル: Vuetify の CSS はカスケードレイヤー（vuetify-*）に入っている。レイヤー外の Tailwind のクラスは常に勝つので、
 *   Vuetify のコンポーネントには見た目（色・枠線・角丸・文字の大きさ）を変える Tailwind のクラスを付けず、props で指定する。
 */
import { createVuetify } from 'vuetify';
import { aliases, mdi } from 'vuetify/iconsets/mdi-svg';

const isDark = () => typeof document !== 'undefined' && document.documentElement.classList.contains('dark');

export const vuetify = createVuetify({
  theme: {
    defaultTheme: isDark() ? 'dark' : 'light',
  },
  icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
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
