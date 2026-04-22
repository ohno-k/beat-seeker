import { ref, onMounted } from 'vue';

/**
 * ダークモード状態を**アプリ全体で共有する**ためのリアクティブ変数。
 *
 * この宣言を `useDarkMode()` の外に出しているのは、
 * どのコンポーネントから呼んでも「同じ ref インスタンス」を返すため（モジュールシングルトン）。
 * もし関数内部に置くと、呼ぶたびに別の ref が生成され、画面ごとに状態がズレてしまう。
 */
const isDarkMode = ref(false);

/**
 * 【Composable の役割】 ダークモードの ON/OFF を管理する。
 *
 * 機能:
 *  - ユーザーが過去に選択したテーマを `localStorage` から復元する
 *  - 未設定の場合は OS のカラースキーム（`prefers-color-scheme`）を初期値として採用
 *  - 切替時に <html> タグへ `class="dark"` を付け外しし、Tailwind の dark: 系クラスを有効化
 *
 * 使い方:
 * ```ts
 * const { isDarkMode, toggleDarkMode } = useDarkMode();
 * // <button @click="toggleDarkMode">切替</button>
 * ```
 */
export function useDarkMode() {
    /**
     * 【関数の役割】 起動直後に一度だけ呼び、<html> の `dark` class を正しい状態に揃える。
     *
     * 呼び出しタイミング:
     *  - `onMounted` 内で自動実行される（下部参照）
     *  - 外部から `initDarkMode` を直接呼ぶことも可能（リロード時の早期適用など）
     */
    const initDarkMode = () => {
        // 手順1: ユーザーが明示的に選んだテーマ（'dark' or 'light'）を localStorage から取得。
        //        未保存なら null。
        const savedTheme = localStorage.getItem('theme');
        // 手順2: OS のダークモード設定を参照。Windows/macOS の「夜間モード」など。
        const systemPrefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;

        // 手順3: 「ユーザーが明示的にダーク」または
        //        「未設定かつ OS がダーク」ならダークモードを有効化する。
        if (savedTheme === 'dark' || (!savedTheme && systemPrefersDark)) {
            isDarkMode.value = true;
            // Tailwind の darkMode: 'class' 戦略に合わせ、<html> に class="dark" を付与。
            document.documentElement.classList.add('dark');
        } else {
            // それ以外（ユーザーがライト指定 or OS がライトかつ未設定）はライトモード。
            isDarkMode.value = false;
            document.documentElement.classList.remove('dark');
        }
    };

    /**
     * 【関数の役割】 ユーザーのクリック等で、現在の状態を反転させる。
     *
     * 状態を <html> の class と localStorage の両方に同期させることで、
     * 画面遷移やリロード後もユーザー選択が維持される。
     */
    const toggleDarkMode = () => {
        // まずリアクティブ状態を反転。Vue がこれを検知して再レンダリング等を発火する。
        isDarkMode.value = !isDarkMode.value;
        if (isDarkMode.value) {
            // ダークへ切替: <html> に class 付与 + localStorage に 'dark' を保存。
            document.documentElement.classList.add('dark');
            localStorage.setItem('theme', 'dark');
        } else {
            // ライトへ切替: class 除去 + localStorage に 'light' を保存。
            document.documentElement.classList.remove('dark');
            localStorage.setItem('theme', 'light');
        }
    };

    // このコンポーザブルを最初に呼び出したコンポーネントのマウント時に
    // 初期テーマを適用する。複数コンポーネントから呼ばれても ref はシングルトンなので
    // 状態がズレることはない（ただし onMounted 自体は各コンポーネントで発火する点は許容）。
    onMounted(() => {
        initDarkMode();
    });

    return {
        /** 現在のダークモード状態（true: ダーク / false: ライト）。テンプレート内でのバインド用。 */
        isDarkMode,
        /** ユーザー操作による ON/OFF 切替関数。 */
        toggleDarkMode,
        /** 初期化関数。通常は自動実行されるが、SSR 復元時などに手動で呼ぶ余地を残している。 */
        initDarkMode
    };
}
