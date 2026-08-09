<script setup lang="ts">
/**
 * 【コンポーネントの役割】 リーグモードのルール説明モーダル。
 *
 * LeagueView のトップ（タイトル横の「ルール説明」ボタン）から開く。
 * 概要 / スケジュール / DIVISION と昇降格 / 集計ルール の 4 セクション構成。
 * 文言はすべて i18n（league.infoModal.*）で ja/en/ko に対応する。
 */
import { ref, onMounted } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import { useLeague } from '../composables/useLeague';

const emit = defineEmits<{ (e: 'close'): void; (e: 'joined'): void }>();

const { t } = useI18n();
const { isLoggedIn } = useAuth();
const league = useLeague();

/** セクション定義（見出しキー + 箇条書きキー）。表示順に並べる。 */
const sections = [
  { titleKey: 'league.infoModal.overviewTitle', items: ['league.infoModal.overview1', 'league.infoModal.overview2'] },
  { titleKey: 'league.infoModal.scheduleTitle', items: ['league.infoModal.schedule1', 'league.infoModal.schedule2', 'league.infoModal.songPool', 'league.infoModal.schedule3'] },
  { titleKey: 'league.infoModal.divisionTitle', items: ['league.infoModal.division1', 'league.infoModal.divisionGroups', 'league.infoModal.division2', 'league.infoModal.division3', 'league.infoModal.challengeDefense'] },
  { titleKey: 'league.infoModal.rulesTitle', items: ['league.infoModal.rules1', 'league.infoModal.rules2', 'league.infoModal.rules3'] },
];

/** すでにスコアリーグに参加登録済みか。 */
const joined = ref(false);
/** 参加リクエスト送信中フラグ。 */
const joining = ref(false);
/** 参加失敗時のエラーメッセージ。 */
const joinError = ref('');

// モーダルを開いた時点の参加状態を取得（ログイン時のみ）。取得失敗しても説明表示は続ける。
onMounted(async () => {
  if (!isLoggedIn.value) return;
  try {
    const entries = await league.fetchMe();
    joined.value = entries.some((e) => e.ladderType === 'score' && e.active);
  } catch {
    /* 参加状態が取れなくても無視（ボタンは押せる状態のまま） */
  }
});

/** リーグ（スコア）に参加登録する。成功したら参加済み表示に切り替え、親に通知する。 */
const doJoin = async () => {
  if (joining.value || joined.value || !isLoggedIn.value) return;
  joining.value = true;
  joinError.value = '';
  try {
    await league.join('score');
    joined.value = true;
    emit('joined');
  } catch (e: any) {
    joinError.value = e?.message || '参加に失敗しました';
  } finally {
    joining.value = false;
  }
};

/**
 * 初回配属の BEAT-TIER（サブティア）→ DIVISION 対応表。
 * バックエンドの LeagueDivision.forBeatPt のしきい値と揃えること。
 * ティア名は言語共通の英語表記のため i18n しない（最下段の「以下」のみ翻訳）。
 */
const divisionMapping = [
  { division: 'DIVISION LEGEND', range: 'Legend 〜 Mythic 4' },
  { division: 'DIVISION 1', range: 'Mythic 3 〜 Mythic 1' },
  { division: 'DIVISION 2', range: 'Ancient 5 〜 Ancient 2' },
  { division: 'DIVISION 3', range: 'Ancient 1 〜 Master 4' },
  { division: 'DIVISION 4', range: 'Master 3 〜 Elite 5' },
  { division: 'DIVISION 5', range: 'Elite 4 〜 Elite 1' },
  { division: 'DIVISION 6', range: 'Commander 5 〜 Commander 1' },
  { division: 'DIVISION 7', range: 'Veteran 5 〜 Veteran 1' },
  { division: 'DIVISION 8', range: 'Expert 5 〜 Expert 3' },
  { division: 'DIVISION 9', range: 'Expert 2 〜 Advanced 3' },
  { division: 'DIVISION 10', range: null }, // range は mappingLowest キーで表示
];
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 animate-fade-in">
      <!-- 背景オーバーレイ（クリックで閉じる） -->
      <div class="absolute inset-0 bg-slate-900/60 backdrop-blur-sm" @click="$emit('close')"></div>

      <!-- 本体パネル -->
      <div class="relative w-full max-w-2xl max-h-[85vh] flex flex-col bg-white dark:bg-slate-800 rounded-2xl shadow-2xl overflow-hidden transition-colors duration-200">
        <!-- ヘッダー -->
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex justify-between items-center">
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('league.infoModal.title') }}</h3>
          <button
            class="p-2 text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-all"
            @click="$emit('close')"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- 本文（スクロール領域） -->
        <div class="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
          <section v-for="section in sections" :key="section.titleKey">
            <h4 class="text-sm font-bold text-slate-800 dark:text-slate-100 mb-2 flex items-center gap-2">
              <span class="w-1.5 h-5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></span>
              {{ t(section.titleKey) }}
            </h4>
            <ul class="space-y-1.5">
              <li
                v-for="item in section.items"
                :key="item"
                class="text-sm text-slate-600 dark:text-slate-300 leading-relaxed pl-4 relative"
              >
                <span class="absolute left-0 top-2 w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600"></span>
                {{ t(item) }}
              </li>
            </ul>

            <!-- DIVISION 配属対応表（DIVISION セクションのみ） -->
            <div v-if="section.titleKey === 'league.infoModal.divisionTitle'" class="mt-3 overflow-x-auto">
              <table class="w-full text-xs border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
                <thead>
                  <tr class="bg-slate-50 dark:bg-slate-900/50 text-left text-slate-500 dark:text-slate-400">
                    <th class="px-3 py-1.5 font-semibold">DIVISION</th>
                    <th class="px-3 py-1.5 font-semibold">BEAT-TIER</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="m in divisionMapping"
                    :key="m.division"
                    class="border-t border-slate-100 dark:border-slate-700/50"
                  >
                    <td class="px-3 py-1 font-semibold text-slate-700 dark:text-slate-200 whitespace-nowrap">{{ m.division }}</td>
                    <td class="px-3 py-1 text-slate-600 dark:text-slate-300">{{ m.range ?? t('league.infoModal.mappingLowest') }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>

        <!-- フッター（参加導線 + 参加締切の注記） -->
        <div class="px-6 py-4 border-t border-slate-100 dark:border-slate-700/50 space-y-3">
          <!-- いつから参戦できるか（毎週 月曜 0:00 締切 → 12:00 開始）の注記 -->
          <p class="flex items-start gap-2 text-xs leading-relaxed text-amber-800 dark:text-amber-300 bg-amber-50 dark:bg-amber-900/20 rounded-lg px-3 py-2">
            <svg class="w-4 h-4 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
            </svg>
            <span>{{ t('league.infoModal.signupNote') }}</span>
          </p>

          <div class="flex items-center justify-end gap-2 flex-wrap">
            <span v-if="joinError" class="text-xs text-red-500 mr-auto">{{ joinError }}</span>
            <span v-else-if="!isLoggedIn" class="text-xs text-slate-400 dark:text-slate-500 mr-auto">{{ t('league.infoModal.loginToJoin') }}</span>

            <button
              class="px-4 py-2 rounded-lg bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 text-sm font-semibold transition-colors"
              @click="$emit('close')"
            >{{ t('league.infoModal.close') }}</button>

            <!-- 参加済み: 緑のチェック表示 -->
            <span
              v-if="joined"
              class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-emerald-50 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 text-sm font-bold"
            >
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
              {{ t('league.infoModal.joined') }}
            </span>

            <!-- 未参加: 参加ボタン -->
            <button
              v-else
              :disabled="!isLoggedIn || joining"
              class="inline-flex items-center gap-1.5 px-5 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              @click="doJoin"
            >
              <svg v-if="!joining" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M13 7l5 5m0 0l-5 5m5-5H6" />
              </svg>
              {{ joining ? t('league.infoModal.joining') : t('league.infoModal.join') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
