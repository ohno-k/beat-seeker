<script setup lang="ts">
// Changelog Info Component
</script>

<template>
  <div class="space-y-8 animate-fade-in pb-16">
    <!-- Header -->
    <div class="bg-white dark:bg-slate-800 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h2 class="text-3xl font-black text-slate-800 dark:text-slate-100 flex items-center gap-3">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-blue-600 dark:text-blue-400" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clip-rule="evenodd" />
        </svg>
        更新履歴 (Changelog)
      </h2>
      <p class="text-slate-500 dark:text-slate-400 mt-2 font-medium tracking-wide">
        最近のアップデートや新機能の追加をお知らせします。
      </p>
    </div>

    <!-- Update Entry: v1.0.2 (CSV Diff Feature) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.0.2</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">プレイ成果レポート（差分検知）</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年2月</span>
      </div>
      
      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">プレイ成果の差分レポート:</span> ログインした状態で新しいCSVをアップロードした際、過去のスコアと比較して「どの楽曲のスコアやクリアランプが伸びたか」「どれだけBEAT-PTが増えたか」を自動計算してお知らせするポップアップ画面を追加しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">BEAT-TIER 昇格演出:</span> BEAT-TIERのランク（称号）が上がった際に、リザルト画面で昇格をお祝いする表示を追加しました。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: v1.0.1 (Current Update) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200 opacity-90">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-blue-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.0.1</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">機能の拡充とUI改善</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年2月</span>
      </div>
      
      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">アカウント機能の追加:</span> Googleアカウントでのログインに対応しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">クラウドデータ保存:</span> ログインすると、アップロードしたCSV（スコアデータ）がクラウド上で保存され、プロフィール画面で成長の軌跡を振り返れるようになりました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">メモ機能:</span> 各楽曲のスコア詳細画面にて、自由なメモ（オプションや攻略法など）を残して保存できるようになりました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ダークモード:</span> ダークモードに対応しました。右上のアイコンからON/OFFを切り替えられます。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">目標BEAT-PTシミュレーター:</span> スコア詳細から「あと何pt稼ぐためには何点伸ばす必要があるか」を計算できるシミュレーターを追加しました。</li>
          </ul>
        </div>

        <div>
          <h4 class="text-sm font-black text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clip-rule="evenodd" /></svg>
            改善・変更 (Improvements & Changes)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">スコア一覧のフィルター強化:</span> 非公式難易度による絞り込みの使い勝手が向上しました（レベル11以上、ANOTHER/LEGGENDARIAのみデフォルト表示等）。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">細かいUIレイアウトの調整:</span> モバイル（スマートフォン）など画面幅が狭い端末でのフィルターはみ出しを修正し、より綺麗に表示されるようにしました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">非公式難易度テーブル表示:</span> ダッシュボードの最下部に、非公式難易度ごとの平均スコアレート一覧テーブルを新設しました。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: Ver 1.0.0 Release -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200 opacity-80">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-slate-500 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.0.0</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">beat-seeker 初期リリース</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年2月以前</span>
      </div>
      
      <div class="p-8">
        <p class="text-slate-700 dark:text-slate-300 font-medium leading-relaxed">
          CSVをクライアント側（ブラウザ）で解析し、ダッシュボードでの可視化、スコア一覧の表示、BEAT-PT（独自の腕前指標）の表示を行う最初のバージョンを公開しました。
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
