<template>
  <v-dialog
    :model-value="isOpen"
    max-width="768"
    @update:model-value="(v: boolean) => { if (!v) $emit('close') }"
  >
    <v-card class="bg-white dark:bg-slate-900 w-full flex flex-col overflow-hidden max-h-[85vh] shadow-xl border-slate-200 dark:border-slate-800">

        <div class="p-5 border-b border-slate-200 dark:border-slate-800 flex flex-col gap-4 shrink-0">
          <!-- タイトル行: タイトル + お問い合わせ + 閉じる -->
          <div class="flex items-center justify-between gap-2">
            <h2 class="text-lg sm:text-xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
              <v-icon :icon="mdiAccountGroupOutline" size="24" class="text-blue-600 dark:text-blue-400" />
              プレイヤー一覧 (管理者用)
            </h2>
            <div class="flex items-center gap-2">
              <!-- お問い合わせ (チャットへ移動): わかりやすく目立たせる -->
              <v-badge
                :model-value="supportUnread > 0"
                :content="supportUnread > 99 ? '99+' : supportUnread"
                color="error"
              >
                <v-btn
                  color="primary"
                  class="text-sm"
                  @click="showSupportModal = true"
                >
                  <v-icon :icon="mdiMessageProcessingOutline" size="16" />
                  <span class="hidden sm:inline ml-1.5">お問い合わせ</span>
                </v-btn>
              </v-badge>

              <v-btn icon variant="text" size="small" class="text-slate-400 -mr-1" aria-label="close" @click="$emit('close')">
                <v-icon :icon="mdiClose" />
              </v-btn>
            </div>
          </div>

          <!-- 管理ツール: 6 ボタンを均一サイズ + アイコン付きで折り返し配置 -->
          <v-card variant="flat" class="rounded-xl bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 p-2.5">
            <p class="text-[10px] font-bold uppercase tracking-wider text-slate-400 dark:text-slate-500 mb-2 px-0.5">データ管理ツール</p>
            <div class="flex flex-wrap gap-2">
              <!-- データ管理 -->
              <div>
                <v-btn
                  class="admin-tool-btn bg-teal-100 hover:bg-teal-200 text-teal-700 dark:bg-teal-900/50 dark:hover:bg-teal-800/80 dark:text-teal-300"
                  @click="showGameDataModal = true"
                >
                  <span aria-hidden="true">🗂️</span>
                  データ管理
                </v-btn>
                <v-tooltip activator="parent" location="bottom" max-width="220">楽曲・難易度表のドラフト追加や公開を行います</v-tooltip>
              </div>

              <!-- 全ユーザー再集計 -->
              <div>
                <v-btn
                  :disabled="isRecalculating"
                  class="admin-tool-btn bg-indigo-100 hover:bg-indigo-200 text-indigo-700 dark:bg-indigo-900/50 dark:hover:bg-indigo-800/80 dark:text-indigo-300"
                  @click="handleRecalculateAll"
                >
                  <v-progress-circular v-if="isRecalculating" size="16" width="2" color="indigo" />
                  <span v-else aria-hidden="true">🔄</span>
                  {{ isRecalculating ? '集計中...' : '全ユーザー再集計' }}
                </v-btn>
                <v-tooltip activator="parent" location="bottom" max-width="220">全ユーザーのBEAT-PTとRate-PTを現在の難易度表で再計算します。難易度表更新後に実行してください</v-tooltip>
              </div>

              <!-- 曲別ランクキャッシュ再構築 (AVERAGE-RANKING 用) -->
              <div>
                <v-btn
                  :disabled="isRecalculatingSongRanks"
                  class="admin-tool-btn bg-purple-100 hover:bg-purple-200 text-purple-700 dark:bg-purple-900/50 dark:hover:bg-purple-800/80 dark:text-purple-300"
                  @click="handleRecalculateSongRanks"
                >
                  <v-progress-circular v-if="isRecalculatingSongRanks" size="16" width="2" color="purple" />
                  <span v-else aria-hidden="true">📊</span>
                  {{ isRecalculatingSongRanks ? '再構築中...' : '曲別ランク再構築' }}
                </v-btn>
                <v-tooltip activator="parent" location="bottom" max-width="220">user_song_ranks キャッシュ（曲別の全ユーザー順位）を再構築します。AVERAGE ランキングが空になっている場合に実行してください</v-tooltip>
              </div>

              <!-- 譜面プロファイルDB投入 -->
              <div>
                <input ref="profileFileInput" type="file" accept=".json" class="hidden" @change="onProfileFileSelected" />
                <v-btn
                  :disabled="isImportingProfiles"
                  class="admin-tool-btn bg-cyan-100 hover:bg-cyan-200 text-cyan-700 dark:bg-cyan-900/50 dark:hover:bg-cyan-800/80 dark:text-cyan-300"
                  @click="handleImportChartProfiles"
                >
                  <v-progress-circular v-if="isImportingProfiles" size="16" width="2" color="cyan" />
                  <span v-else aria-hidden="true">📁</span>
                  {{ isImportingProfiles ? 'インポート中...' : '譜面プロファイルDB投入' }}
                </v-btn>
                <v-tooltip activator="parent" location="bottom" max-width="220">JSONファイルを選択して譜面プロファイルをDBに登録します。export_profiles.pyで生成したファイルを使用してください</v-tooltip>
              </div>

              <!-- Push通知リセット -->
              <div>
                <v-btn
                  :disabled="isClearingPush"
                  class="admin-tool-btn bg-rose-100 hover:bg-rose-200 text-rose-700 dark:bg-rose-900/50 dark:hover:bg-rose-800/80 dark:text-rose-300"
                  @click="handleClearPushAll"
                >
                  <v-progress-circular v-if="isClearingPush" size="16" width="2" color="pink" />
                  <span v-else aria-hidden="true">🔔</span>
                  {{ isClearingPush ? '処理中...' : 'Push通知リセット' }}
                </v-btn>
                <v-tooltip activator="parent" location="bottom" max-width="220">全ユーザーのプッシュ通知設定を削除します。VAPIDキーを変更した際に実行してください</v-tooltip>
              </div>
            </div>
          </v-card>
        </div>

        <div class="flex-1 overflow-y-auto p-6 bg-slate-50 dark:bg-slate-900/50">
          <div v-if="loading" class="flex flex-col items-center justify-center p-12">
            <v-progress-circular size="32" width="4" class="mb-4" />
            <p class="text-slate-500 font-medium tracking-wide">ユーザー一覧を取得中...</p>
          </div>
          <v-alert v-else-if="error" type="error" class="mb-4">
            {{ error }}
          </v-alert>

          <v-alert v-if="recalculateError" type="error" class="mb-4">
            {{ recalculateError }}
          </v-alert>
          <v-alert v-if="recalculateSuccess" type="success" class="mb-4">
            {{ recalculateSuccess }}
          </v-alert>

          <div v-if="!loading && !error" class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <v-card
              v-for="u in users"
              :key="u.id"
              class="p-4 cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 transition-all group"
              @click="selectUser(u)"
            >
              <div class="flex items-center gap-3">
                <div class="w-10 h-10 bg-indigo-500 rounded-full flex items-center justify-center text-white font-bold shrink-0">
                  {{ u.displayName ? u.displayName.charAt(0).toUpperCase() : 'U' }}
                </div>
                <div class="flex flex-col overflow-hidden">
                  <span class="font-bold text-slate-800 dark:text-white truncate group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">{{ u.displayName || '名無し' }}</span>
                  <div class="flex items-center gap-2 mt-1">
                    <span class="text-xs text-slate-500 dark:text-slate-400 font-mono">{{ u.iidxId }}</span>
                    <v-chip v-if="u.danRank" label size="x-small" variant="flat" class="px-1.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold">{{ u.danRank }}</v-chip>
                    <v-chip v-if="u.arenaRank" label size="x-small" variant="flat" class="px-1.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-[10px] font-bold">{{ u.arenaRank }}</v-chip>
                  </div>
                </div>
              </div>
            </v-card>
          </div>
        </div>

    </v-card>
  </v-dialog>
  <AdminGameDataModal :isOpen="showGameDataModal" @close="showGameDataModal = false" />
  <AdminSupportChatModal
    :isOpen="showSupportModal"
    @close="showSupportModal = false"
    @unread-change="supportUnread = $event"
  />
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 管理者専用のプレイヤー一覧モーダル。
 *
 * 機能:
 *  - 全ユーザーの一覧を取得してカードグリッド表示
 *  - ユーザーカードのクリックで 'select' イベント（親で該当ユーザーの履歴等を開く）
 *  - ツールバー: データ管理 / 全ユーザー再集計 / 譜面プロファイル JSON 投入 / Push 通知リセット
 *
 * props:
 *  - isOpen: モーダル開閉
 * emits:
 *  - close: 閉じる
 *  - select: ユーザー選択
 */
import { ref, watch } from 'vue';
import { useScores } from '../composables/useScores';
import { useAuth } from '../composables/useAuth';
import { useGameData } from '../composables/useGameData';
import { useSupportChat } from '../composables/useSupportChat';
import AdminGameDataModal from './AdminGameDataModal.vue';
import AdminSupportChatModal from './AdminSupportChatModal.vue';
import { mdiAccountGroupOutline, mdiClose, mdiMessageProcessingOutline } from '@mdi/js';

const { songDataBody: songDataRef, diffTableRanks: diffTableRef } = useGameData();
/** 再集計 API に POST する生データ（body プロパティ付きの曲データ）。 */
const songDataRaw = { body: songDataRef.value };
/** 再集計 API に POST する難易度表（ranks プロパティ付き）。 */
const diffTableRaw = { ranks: diffTableRef.value };

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'select', user: any): void;
}>();

const { fetchAllUsers } = useScores();
/** 取得済みユーザー配列。displayName 昇順でソート済み。 */
const users = ref<any[]>([]);
/** 取得中フラグ。 */
const loading = ref(false);
/** 取得エラー文言。 */
const error = ref('');

/**
 * 【関数の役割】 ユーザー一覧を取得して users に格納する。
 * 既に取得済み（users が空でない）ならキャッシュ代わりにスキップ。
 */
const loadUsers = async () => {
  if (!props.isOpen || users.value.length > 0) return;
  
  loading.value = true;
  error.value = '';
  try {
    const all = await fetchAllUsers();
    users.value = all.sort((a: any, b: any) =>
      (a.displayName ?? '').localeCompare(b.displayName ?? '', undefined, { sensitivity: 'base' })
    );
  } catch (err: any) {
    error.value = err.message || '一覧の取得に失敗しました。';
  } finally {
    loading.value = false;
  }
};

// モーダル開放時にユーザー一覧 + お問い合わせ未読数を取得。
watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    loadUsers();
    loadSupportUnread();
  }
});

/** 【関数の役割】 カードクリックで親へ選択通知。 */
const selectUser = (u: any) => {
  emit('select', u);
};

/** 全ユーザー再集計中フラグ。 */
const isRecalculating = ref(false);
/** 各種管理アクションのエラー文言（共通）。 */
const recalculateError = ref('');
/** 各種管理アクションの成功文言（共通）。 */
const recalculateSuccess = ref('');
/** ゲームデータ管理モーダルの表示状態。 */
const showGameDataModal = ref(false);
/** お問い合わせ (運営チャット) モーダルの表示状態。 */
const showSupportModal = ref(false);
/** お問い合わせの全スレッド合計の未読数 (ヘッダのバッジ表示用)。 */
const supportUnread = ref(0);

const { fetchThreads: fetchSupportThreads } = useSupportChat();

/**
 * 【関数の役割】 お問い合わせスレッドの未読合計を取得してバッジに反映する。
 * モーダルを開いた時点で 1 度取得する。詳細モーダルを開くと unread-change でも同期される。
 */
const loadSupportUnread = async () => {
  try {
    const threads = await fetchSupportThreads();
    supportUnread.value = threads.reduce((sum, t) => sum + t.unreadCount, 0);
  } catch {
    /* 未読バッジの取得失敗は無視 (機能本体には影響しない) */
  }
};

/**
 * 【関数の役割】 全ユーザーの BEAT-PT / Rate-PT を最新の楽曲データ + 難易度表で再計算する。
 * 重い処理なのでバックエンド側は 202 Accepted で非同期受付する。
 */
const handleRecalculateAll = async () => {
  if (!confirm('全ユーザーのポイント再計算を実行しますか？この操作は取り消せません。')) return;
  
  isRecalculating.value = true;
  recalculateError.value = '';
  recalculateSuccess.value = '';
  
  try {
    const { authHeaders } = useAuth();
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
    
    const res = await fetch(`${API_BASE}/api/admin/recalculate-points`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        songDataJson: JSON.stringify(songDataRaw),
        difficultyTableJson: JSON.stringify(diffTableRaw)
      })
    });
    
    if (!res.ok && res.status !== 202) throw new Error(`API error: ${res.status}`);
    recalculateSuccess.value = 'バックグラウンドで再集計を開始しました。数分後に履歴を確認してください。';
  } catch(e: any) {
    recalculateError.value = '再計算に失敗しました: ' + e.message;
  } finally {
    isRecalculating.value = false;
  }
};

/** 譜面プロファイル JSON 投入中フラグ。 */
const isImportingProfiles = ref(false);
/** 非表示の <input type="file"> への参照。 */
const profileFileInput = ref<HTMLInputElement | null>(null);

/** 【関数の役割】 非表示 input をクリックして JSON ファイル選択ダイアログを開く。 */
const handleImportChartProfiles = () => {
  profileFileInput.value?.click();
};

/**
 * 【関数の役割】 ファイル選択後、JSON を読み込んでそのまま管理 API に POST する。
 * export_profiles.py で生成した譜面傾向プロファイル（配列形式）を DB に一括登録する想定。
 * サイズ表示 + 確認ダイアログ付き。
 */
const onProfileFileSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  input.value = ''; // 同じファイルを再選択可能にするためクリア。

  if (!confirm(`${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) をDBに投入します。\n同じ譜面（textage）は上書きし、ファイルに無い譜面は残します（textage 同期で解析済みの譜面は旧方式のファイルでは上書きしません）。続行しますか？`)) return;

  isImportingProfiles.value = true;
  recalculateError.value = '';
  recalculateSuccess.value = '';

  try {
    const { authHeaders } = useAuth();
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

    recalculateSuccess.value = 'ファイル読み込み中...';
    const text = await file.text();
    const profiles = JSON.parse(text);
    if (!Array.isArray(profiles)) throw new Error('JSONファイルは配列形式で必要です');

    recalculateSuccess.value = `${profiles.length} 件送信中...`;

    const res = await fetch(`${API_BASE}/api/admin/chart-tendencies/import-json`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: text, // JSON.parse 済みだが送信はテキストのまま（パースコスト削減 + サーバ側で再検証）。
    });

    const data = await res.json();
    if (!res.ok) throw new Error(data.error ?? `API error: ${res.status}`);
    recalculateSuccess.value = `譜面プロファイルを投入しました: ${data.inserted} 件追加、${data.updated ?? 0} 件上書き、${data.keptNewer ?? 0} 件据え置き（解析済み）、${data.skipped} 件スキップ`;
  } catch (e: any) {
    recalculateError.value = '投入に失敗しました: ' + e.message;
  } finally {
    isImportingProfiles.value = false;
  }
};

/** 曲別ランクキャッシュ再構築中フラグ（多重押下防止）。 */
const isRecalculatingSongRanks = ref(false);

/**
 * 【関数の役割】 user_song_ranks（曲別の全ユーザー順位キャッシュ）を再構築する。
 *
 * バックエンドは {@code POST /api/scores/recalculate-song-ranks} を 202 で受け付け、
 * 非同期で TRUNCATE → INSERT を実行する。AVERAGE ランキングはこのキャッシュを
 * 元に集計されるため、空になっていればこのボタンで再構築する。
 */
const handleRecalculateSongRanks = async () => {
  if (!confirm('曲別ランクキャッシュ（user_song_ranks）を再構築しますか？ 全ユーザーの全譜面の順位を再計算します（数十秒〜数分かかります）。')) return;

  isRecalculatingSongRanks.value = true;
  recalculateError.value = '';
  recalculateSuccess.value = '';

  try {
    const { authHeaders } = useAuth();
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

    const res = await fetch(`${API_BASE}/api/scores/recalculate-song-ranks`, {
      method: 'POST',
      headers: authHeaders(),
    });

    if (!res.ok && res.status !== 202) throw new Error(`API error: ${res.status}`);
    recalculateSuccess.value = 'バックグラウンドで曲別ランクの再構築を開始しました。数分後に AVERAGE ランキングを確認してください。';
  } catch (e: any) {
    recalculateError.value = '曲別ランク再構築に失敗しました: ' + e.message;
  } finally {
    isRecalculatingSongRanks.value = false;
  }
};

/** Push 通知クリア中フラグ（ボタン多重押下防止）。 */
const isClearingPush = ref(false);

/**
 * 【関数の役割】 全ユーザーのプッシュ通知購読データを一括削除する。
 * VAPID 鍵をローテーションした際、旧鍵で登録された購読データは送信不可になるため全削除する運用を想定。
 * 確認ダイアログ → API 呼び出し → 結果メッセージ更新、の流れ。
 */
const handleClearPushAll = async () => {
  if (!confirm('全ユーザーのプッシュ通知設定を初期化しますか？古いVAPID鍵での登録データが全て削除され、ユーザーは再設定が必要になります。')) return;
  
  isClearingPush.value = true;
  recalculateError.value = '';
  recalculateSuccess.value = '';
  
  try {
    const { authHeaders } = useAuth();
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
    
    const res = await fetch(`${API_BASE}/api/admin/push/clear-all`, {
      method: 'POST',
      headers: authHeaders()
    });
    
    if (!res.ok) throw new Error(`API error: ${res.status}`);
    recalculateSuccess.value = '全てのユーザーのプッシュ通知設定を初期化しました。';
  } catch(e: any) {
    recalculateError.value = '初期化に失敗しました: ' + e.message;
  } finally {
    isClearingPush.value = false;
  }
};
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.2s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}

/* 管理ツールボタン: 全ボタン共通の均一サイズ・アイコン付きレイアウト */
.admin-tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.2;
  transition: background-color 0.15s ease;
}
.admin-tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
