/**
 * Composable 層で共有される**定数モジュール**。
 *
 * ここに集約しているのは、各 composable（useAuth / useI18n / useRateTierVisibility など）から
 * 同じ値を import させ、キー名や URL 文字列の重複定義を防ぐため。
 */

/**
 * localStorage に JWT を保存する際のキー。
 *
 * 全 composable（useAuth / useI18n / useRateTierVisibility 等）が
 * この定数を参照するので、キー名変更はこの 1 箇所だけで済む。
 */
export const TOKEN_KEY = 'beat-seeker-token';

/**
 * バックエンド API のベース URL。
 *
 * Vite の環境変数 `VITE_API_BASE` があればそれを優先し、
 * 未設定ならローカル開発用の `http://localhost:8080` をフォールバックとする。
 */
export const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
