<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグ参加者にブラウザ通知の許可を 1 度だけ案内するモーダル。
 *
 * 表示条件（すべて満たしたときのみ）:
 *  - ログイン済み
 *  - ブラウザの通知許可が「未回答」（'granted' なら不要、'denied' は再要求できない）
 *  - リーグに参加中（`GET /api/league/me` の entries に active なものがある）
 *  - この端末でまだ案内していない（localStorage）
 *
 * ネイティブの許可ダイアログを起動時に直接出さず、この自前モーダルを挟んでいる理由:
 *  - Safari（iOS のホーム画面アプリを含む）は `Notification.requestPermission()` を
 *    ユーザー操作起点でしか許さない。ボタンを踏ませることでこの制約を満たす
 *  - 「なぜ通知が要るのか」を先に説明した方が許可率が高く、Chrome の
 *    サイレント通知モードに回されにくい
 *  - 一度断られるとブラウザが再要求を禁止するため、出すのは 1 回きりにしたい
 *
 * 「後で」で閉じた場合も案内済みとして記録する（何度も出さない）。あとから有効にしたく
 * なった人は、プロフィール画面の「通知を有効にする」からいつでも設定できる。
 */
import { ref, watch } from 'vue';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useFriends } from '../composables/useFriends';

/** 案内済みフラグの localStorage キー。通知許可はブラウザ単位なので端末ごとに持つ。 */
const STORAGE_KEY = 'leaguePushPrompt:asked';

const { isLoggedIn, authHeaders } = useAuth();
const { requestNotificationPermission } = useFriends();

const visible = ref(false);
const isSubscribing = ref(false);

/** リーグに参加中（有効なエントリーがある）かを問い合わせる。 */
async function isLeagueParticipant(): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/league/me`, { headers: authHeaders() });
    if (!res.ok) return false;
    const data = await res.json();
    return Array.isArray(data.entries) && data.entries.some((e: any) => e?.active);
  } catch {
    return false;
  }
}

/** 表示条件を順に確かめ、すべて満たしたときだけ開く。 */
async function maybeShow() {
  if (!isLoggedIn.value) return;
  if (typeof Notification === 'undefined') return;
  // 'granted' は案内不要、'denied' はブラウザが再要求を禁止しているので出しても無駄。
  if (Notification.permission !== 'default') return;
  try {
    if (localStorage.getItem(STORAGE_KEY)) return;
  } catch {
    // localStorage が使えない環境では「1 度だけ」を保証できないので、出さない方を選ぶ。
    return;
  }
  if (!(await isLeagueParticipant())) return;
  visible.value = true;
}

// 初回ロードは /api/auth/me の解決待ちのため、ログイン状態が確定してから判定する。
watch(isLoggedIn, () => maybeShow(), { immediate: true });

/** 案内済みとして記録する。許可・拒否・閉じるのいずれでも記録し、二度と出さない。 */
function markAsked() {
  try {
    localStorage.setItem(STORAGE_KEY, '1');
  } catch {
    /* ignore */
  }
}

function close() {
  visible.value = false;
  markAsked();
}

/** 「通知を有効にする」: この click がユーザー操作となり、Safari でも許可を求められる。 */
async function enable() {
  isSubscribing.value = true;
  try {
    await requestNotificationPermission();
  } catch (e) {
    console.error(e);
  } finally {
    isSubscribing.value = false;
    close();
  }
}
</script>

<template>
  <v-dialog
    :model-value="visible"
    @update:model-value="(v: boolean) => { if (!v) close() }"
    max-width="448"
  >
    <v-card class="bg-white dark:bg-slate-800 overflow-hidden flex flex-col">
        <v-card-text class="p-6 text-center">
          <div class="w-14 h-14 rounded-full bg-indigo-100 dark:bg-indigo-900/40 flex items-center justify-center mx-auto mb-4">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-indigo-600 dark:text-indigo-400" viewBox="0 0 20 20" fill="currentColor">
              <path d="M10 2a6 6 0 00-6 6v3.586l-.707.707A1 1 0 004 14h12a1 1 0 00.707-1.707L16 11.586V8a6 6 0 00-6-6zM10 18a3 3 0 01-3-3h6a3 3 0 01-3 3z" />
            </svg>
          </div>
          <h2 class="text-lg font-bold text-slate-800 dark:text-slate-100 mb-2">
            リーグの開始を通知しますか？
          </h2>
          <p class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
            通知を有効にすると、毎週のリーグが始まったとき（月曜 12:00）に、あなたの DIVISION・グループ・課題曲をお知らせします。<br />
            ライバルにスコアを抜かれたときも通知されます。
          </p>
          <p class="text-xs text-slate-400 dark:text-slate-500 mt-3">
            この案内は 1 回だけ表示されます。あとからプロフィール画面でいつでも設定できます。
          </p>
        </v-card-text>

        <v-divider />
        <v-card-actions class="p-4 flex gap-2">
          <v-btn
            type="button"
            variant="tonal"
            height="40"
            class="flex-1 text-sm"
            @click="close"
          >
            後で
          </v-btn>
          <v-btn
            type="button"
            color="indigo"
            height="40"
            :disabled="isSubscribing"
            class="flex-1 text-sm"
            @click="enable"
          >
            <v-progress-circular v-if="isSubscribing" size="16" width="2" color="white" class="mr-2" />
            通知を有効にする
          </v-btn>
        </v-card-actions>
    </v-card>
  </v-dialog>
</template>
