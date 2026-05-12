<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[100] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm" @click.self="$emit('close')">
      <div class="bg-white dark:bg-slate-900 w-full max-w-2xl rounded-2xl shadow-2xl flex flex-col overflow-hidden max-h-[85vh] animate-fade-in border border-slate-200 dark:border-slate-800">
        
        <div class="p-6 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
          <h2 class="text-xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
            </svg>
            プレイヤー一覧 (管理者用)
          </h2>
          <div class="flex items-center gap-2">
            <!-- データ管理 -->
            <div class="relative group">
              <button
                @click="showGameDataModal = true"
                class="px-3 py-1.5 bg-teal-100 hover:bg-teal-200 text-teal-700 dark:bg-teal-900/50 dark:hover:bg-teal-800/80 dark:text-teal-300 rounded text-sm font-bold flex items-center gap-1 transition-colors"
              >
                データ管理
              </button>
              <div class="admin-tooltip">楽曲・難易度表のドラフト追加や公開を行います</div>
            </div>

            <!-- 全ユーザー再集計 -->
            <div class="relative group">
              <button
                @click="handleRecalculateAll"
                :disabled="isRecalculating"
                class="px-3 py-1.5 bg-indigo-100 hover:bg-indigo-200 text-indigo-700 dark:bg-indigo-900/50 dark:hover:bg-indigo-800/80 dark:text-indigo-300 rounded text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-50"
              >
                <svg v-if="isRecalculating" class="animate-spin -ml-1 mr-1 h-4 w-4 text-indigo-700" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                {{ isRecalculating ? '集計中...' : '全ユーザー再集計' }}
              </button>
              <div class="admin-tooltip">全ユーザーのBEAT-PTとRate-PTを現在の難易度表で再計算します。難易度表更新後に実行してください</div>
            </div>

            <!-- KENBAN/SARA-PT バックフィル -->
            <div class="relative group">
              <button
                @click="handleBackfillKenbanSara"
                :disabled="isBackfillingKenbanSara"
                class="px-3 py-1.5 bg-orange-100 hover:bg-orange-200 text-orange-700 dark:bg-orange-900/50 dark:hover:bg-orange-800/80 dark:text-orange-300 rounded text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-50"
              >
                <svg v-if="isBackfillingKenbanSara" class="animate-spin -ml-1 mr-1 h-4 w-4 text-orange-700" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                {{ isBackfillingKenbanSara ? 'バックフィル中...' : 'KENBAN/SARA-PT 計算' }}
              </button>
              <div class="admin-tooltip">全ユーザーの KENBAN-PT / SARA-PT を再計算して users と score_history_logs に保存します。1 度実行すれば以降のアップロードで自動更新されます</div>
            </div>

            <!-- 譜面プロファイルDB投入 -->
            <div class="relative group">
              <input ref="profileFileInput" type="file" accept=".json" class="hidden" @change="onProfileFileSelected" />
              <button
                @click="handleImportChartProfiles"
                :disabled="isImportingProfiles"
                class="px-3 py-1.5 bg-cyan-100 hover:bg-cyan-200 text-cyan-700 dark:bg-cyan-900/50 dark:hover:bg-cyan-800/80 dark:text-cyan-300 rounded text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-50"
              >
                <svg v-if="isImportingProfiles" class="animate-spin -ml-1 mr-1 h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                {{ isImportingProfiles ? 'インポート中...' : '譜面プロファイルDB投入' }}
              </button>
              <div class="admin-tooltip">JSONファイルを選択して譜面プロファイルをDBに登録します。export_profiles.pyで生成したファイルを使用してください</div>
            </div>

            <!-- Push通知リセット -->
            <div class="relative group">
              <button
                @click="handleClearPushAll"
                :disabled="isClearingPush"
                class="px-3 py-1.5 bg-rose-100 hover:bg-rose-200 text-rose-700 dark:bg-rose-900/50 dark:hover:bg-rose-800/80 dark:text-rose-300 rounded text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-50"
              >
                <svg v-if="isClearingPush" class="animate-spin -ml-1 mr-1 h-4 w-4 text-rose-700" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                {{ isClearingPush ? '処理中...' : 'Push通知リセット' }}
              </button>
              <div class="admin-tooltip">全ユーザーのプッシュ通知設定を削除します。VAPIDキーを変更した際に実行してください</div>
            </div>

            <button @click="$emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors p-2 -mr-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
              </svg>
            </button>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto p-6 bg-slate-50 dark:bg-slate-900/50">
          <div v-if="loading" class="flex flex-col items-center justify-center p-12">
            <div class="w-8 h-8 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 font-medium tracking-wide">ユーザー一覧を取得中...</p>
          </div>
          <div v-else-if="error" class="bg-red-50 text-red-700 p-4 rounded-xl border border-red-200 mb-4">
            {{ error }}
          </div>
          
          <div v-if="recalculateError" class="bg-red-50 text-red-700 p-4 rounded-xl border border-red-200 mb-4">
            {{ recalculateError }}
          </div>
          <div v-if="recalculateSuccess" class="bg-green-50 text-green-700 p-4 rounded-xl border border-green-200 mb-4">
            {{ recalculateSuccess }}
          </div>

          <div v-if="!loading && !error" class="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div 
              v-for="u in users" 
              :key="u.id" 
              @click="selectUser(u)"
              class="bg-white dark:bg-slate-800 p-4 rounded-xl shadow-sm border border-slate-200 dark:border-slate-700 cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 hover:shadow-md transition-all group"
            >
              <div class="flex items-center gap-3">
                <div class="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-500 rounded-full flex items-center justify-center text-white font-bold shrink-0">
                  {{ u.displayName ? u.displayName.charAt(0).toUpperCase() : 'U' }}
                </div>
                <div class="flex flex-col overflow-hidden">
                  <span class="font-bold text-slate-800 dark:text-white truncate group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">{{ u.displayName || '名無し' }}</span>
                  <div class="flex items-center gap-2 mt-1">
                    <span class="text-xs text-slate-500 dark:text-slate-400 font-mono">{{ u.iidxId }}</span>
                    <span v-if="u.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded">{{ u.danRank }}</span>
                    <span v-if="u.arenaRank" class="px-1.5 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-[10px] font-bold rounded">{{ u.arenaRank }}</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

      </div>
    </div>
  </Teleport>
  <AdminGameDataModal :isOpen="showGameDataModal" @close="showGameDataModal = false" />
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
import AdminGameDataModal from './AdminGameDataModal.vue';

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

// モーダル開放時にユーザー一覧を取得（初回のみ）。
watch(() => props.isOpen, (newVal) => {
  if (newVal) loadUsers();
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

/** KENBAN/SARA-PT バックフィル実行中フラグ。 */
const isBackfillingKenbanSara = ref(false);

/**
 * 【関数の役割】 全ユーザーの KENBAN-PT / SARA-PT を一括再計算し、users と score_history_logs に保存する。
 *
 * バックエンドは {@code POST /api/admin/backfill-kenban-sara-pt} で 1 リクエスト内で全件処理する同期エンドポイント。
 * 数十秒〜数分かかる想定。
 */
const handleBackfillKenbanSara = async () => {
  if (!confirm('全ユーザーの KENBAN-PT / SARA-PT を再計算しますか？ 数十秒〜数分かかります。')) return;

  isBackfillingKenbanSara.value = true;
  recalculateError.value = '';
  recalculateSuccess.value = '';

  try {
    const { authHeaders } = useAuth();
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

    const res = await fetch(`${API_BASE}/api/admin/backfill-kenban-sara-pt`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
    });

    if (!res.ok) {
      const body = await res.text();
      throw new Error(`API error ${res.status}: ${body || '(empty body)'}`);
    }
    const data = await res.json();
    recalculateSuccess.value = data.message || 'KENBAN/SARA-PT のバックフィルが完了しました。';
  } catch (e: any) {
    recalculateError.value = 'KENBAN/SARA-PT バックフィルに失敗しました: ' + e.message;
  } finally {
    isBackfillingKenbanSara.value = false;
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

  if (!confirm(`${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB) をDBに投入します。\n既存データは上書きされます。続行しますか？`)) return;

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
    recalculateSuccess.value = `譜面プロファイルを投入しました: ${data.inserted} 件登録、${data.skipped} 件スキップ`;
  } catch (e: any) {
    recalculateError.value = '投入に失敗しました: ' + e.message;
  } finally {
    isImportingProfiles.value = false;
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

/* ツールチップ */
.admin-tooltip {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  width: 220px;
  padding: 6px 10px;
  background: #1e293b;
  color: #e2e8f0;
  font-size: 12px;
  font-weight: normal;
  line-height: 1.5;
  border-radius: 8px;
  white-space: normal;
  pointer-events: none;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.15s ease, transform 0.15s ease;
  z-index: 200;
  box-shadow: 0 4px 12px rgba(0,0,0,0.3);
}

.admin-tooltip::after {
  content: '';
  position: absolute;
  top: 100%;
  right: 12px;
  border: 5px solid transparent;
  border-top-color: #1e293b;
}

.group:hover .admin-tooltip {
  opacity: 1;
  transform: translateY(0);
}
</style>
