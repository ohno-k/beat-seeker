<script setup lang="ts">
import { ref } from 'vue';

const activeTab = ref<'changelog' | 'difficulty'>('changelog');
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

      <!-- Tabs -->
      <div class="flex flex-wrap mt-6 gap-2 border-t border-slate-100 dark:border-slate-700 pt-6">
        <button
          @click="activeTab = 'changelog'"
          class="px-5 py-2.5 text-sm font-bold rounded-lg transition-all"
          :class="activeTab === 'changelog' ? 'bg-blue-600 text-white shadow-md' : 'bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600'"
        >
          システムアップデート
        </button>
        <button
          @click="activeTab = 'difficulty'"
          class="px-5 py-2.5 text-sm font-bold rounded-lg transition-all"
          :class="activeTab === 'difficulty' ? 'bg-indigo-600 text-white shadow-md' : 'bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600'"
        >
          楽曲難易度制定履歴
        </button>
      </div>
    </div>

    <div v-if="activeTab === 'changelog'" class="space-y-8 animate-in slide-in-from-bottom-4 duration-300">
      <!-- Update Entry: v1.3.0 (Difficulty Revision) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.3.0</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">難易度改訂と履歴への自動記録</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>

      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">RATE-TIER システムの実装:</span> スコアレート（最大スコアに対する達成率）をもとにプレイヤーの精度を評価する新指標「RATE-PT / RATE-TIER」を追加しました。ANOTHER・LEGGENDARIA 全曲が対象で、上位100曲のポイント合計によってランクが決まります。ランクボード・スコアレート換算表はヘルプモーダルで確認できます。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">RATE-PT 楽曲ランキングの追加:</span> 全ユーザーのRATE-PT算出に使われているTop100楽曲を「多い順／少ない順」で確認できる楽曲ランキング画面を追加しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">楽曲難易度表 第2版の適用:</span> 新規追加・既存変更を含む難易度改訂（第2版）を実施しました。詳細は「楽曲難易度制定履歴」タブをご覧ください。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">難易度改訂のアップロード履歴への自動記録:</span> 難易度改訂が行われた際、全ユーザーのアップロード履歴に「難易度改訂」ラベルの記録が自動的に追加されるようになりました。改訂前後のBEAT-PTの変動を履歴で確認できます。</li>
          </ul>
        </div>
      </div>
    </div>

      <!-- Update Entry: v1.2.0 (Notification Tabs & Vote UI) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.2.0</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">通知タブの分離とインライン投票 UI</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>

      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">通知ボックスのタブ分離:</span> 通知ボックスが「フレンド申請」と「アクティビティ」の2タブに分かれました。未読のアクティビティ通知は赤バッジで件数を表示し、「全て既読」ボタンでまとめて既読にできます。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">おすすめオプションのインライン投票:</span> スコアアップロード後のリザルト画面で、各楽曲カードに正規・MIRROR・RANDOM・R-RAN・S-RANのボタンが直接表示されるようになりました。クリック一つで推奨オプションに投票でき、もう一度押すと取り消せます。</li>
          </ul>
        </div>

        <div>
          <h4 class="text-sm font-black text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" /></svg>
            不具合対応 (Bug Fixes)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">リザルト画面の楽曲名が切れる問題を修正:</span> 難易度バッジ・LAMP UP・スコア値と同じ行に表示していたため、タイトルが途中で切れていた問題を解消しました。各情報を行ごとに分けることで、長い楽曲名も正しく表示されます。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">スマホで通知ボックスが画面外にはみ出す問題を修正:</span> 通知ボックスの位置指定を絶対座標から画面固定（fixed）に変更し、モバイル端末でも常に正しく表示されるよう修正しました。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: v1.1.1 (Folder Rank System Redesign) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.1.1</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">フォルダランクシステムの刷新</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>

      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            改善・変更 (Improvements &amp; Changes)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">フォルダランク算出ロジックの全面刷新:</span> 各フォルダのLEGENDに必要な平均スコアレートが難易度ごとに変化するようになりました。☆11.0では約99.6%、☆13.0では約94.44%（MAX-越え）を基準に、曲線補間で各フォルダのLEGENDラインが決まります。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ランク間隔の統一:</span> LEGENDから0.25%刻みで必要スコアレートが下がるごとにランクが1段階下がる方式に変更し、全フォルダで直感的なランク体系になりました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">必要スコアレート表の追加:</span> 非公式難易度表サマリーのヘルプボタンから、全フォルダ×全ランクの必要スコアレート一覧表をモーダルで確認できるようになりました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ヘルプテキストの更新:</span> フォルダランクの説明文を新しいスコアレートベースの仕組みに合わせて更新しました。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: v1.1.0 (Arena Feature) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.1.0</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">ARENAモード対戦履歴の追加</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>

      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ARENAモード対戦履歴:</span> e-AMUSEMENTのARENAモードの対戦結果をbeat-seekerに取り込み、順位推移・楽曲ごとのスコア比較・対戦相手別の勝敗統計を確認できる画面を追加しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ブックマークレットによるワンクリック取得:</span> e-AMUSEMENTサイトでブックマークレットを実行するだけで、ARENAデータとスコアCSVを同時に取得してクリップボードにコピーできる機能を追加しました。スコアのアップロードとARENAデータの取り込みを一度の操作で行えます。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">対戦相手分析:</span> これまで対戦した相手（CPU・NPCを除く）ごとに、対戦回数・楽曲スコアの勝率を集計して確認できます。</li>
          </ul>
        </div>

        <div>
          <h4 class="text-sm font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            改善・変更 (Improvements & Changes)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">アプリ更新通知:</span> beat-seekerがアップデートされた際、画面上部に通知バナーが表示されるようになりました。ページを再読み込みするだけで最新版に切り替わります。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: v1.0.4 (Rival (Friend) Feature) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.0.4</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">ライバル（フレンド）機能の追加</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>
      
      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ライバル（フレンド）機能:</span> 他のユーザーとフレンドになることができ、相互にスコア状況を確認・比較できる機能を追加しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">詳細スコア比較:</span> フレンドとのスコアを非公式難易度ごとに比較し、どの楽曲で勝っているか・負けているかを一目で確認できる詳細モードを搭載しました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">プライバシー設定:</span> プロフィール設定から、自分のスコアを「公開」「フレンドのみ公開」「非公開」に切り替えられるようになりました。</li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Update Entry: v1.0.3 (Ranking & UI Overhaul) -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.0.3</span>
          <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">ランキング機能の実装とUIの全面刷新</h3>
        </div>
        <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
      </div>
      
      <div class="p-8 space-y-6">
        <div>
          <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            新機能 (New Features)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">ランキング機能の実装:</span> 全ユーザーのBEAT-PTを比較できるランキング画面を公開しました。BEAT-TIERアイコンの表示、検索やページネーションなど、使いやすさを追求したUIを備えています。</li>
          </ul>
        </div>

        <div>
          <h4 class="text-sm font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            改善・変更 (Improvements & Changes)
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">UIの全面的な修正と洗練:</span> 画面上部のナビゲーションをタブ形式に刷新し、モバイルを含む全画面で常に表示されるように変更。情報密度を高めつつ、操作感を大幅に向上させました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">レイアウトの最適化:</span> ワイド画面でのサイドバー固定表示を復活させ、広い画面環境での使い勝手を向上させました。</li>
            <li><span class="font-bold text-slate-900 dark:text-slate-100">データ安定性の向上:</span> Double Play用CSVの誤アップロード防止機能を追加しました。</li>
          </ul>
        </div>
      </div>
    </div>

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
            <li><span class="font-bold text-slate-900 dark:text-slate-100">アカウント機能の追加:</span> アカウントによるログインに対応しました。</li>
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

    <!-- Difficulty History Tab -->
    <div v-else-if="activeTab === 'difficulty'" class="space-y-8 animate-in slide-in-from-bottom-4 duration-300">
      
      <!-- Difficulty Entry: v2 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">第2版</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">難易度改訂 (Ver 1.3.0)</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">2026年3月</span>
        </div>
        
        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              新規追加
            </h4>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-slate-700 dark:text-slate-300 ml-2 font-medium">
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>Butterfly Twist</span><span class="font-bold text-blue-600 dark:text-blue-400">11.2</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>Time to Empress[L]</span><span class="font-bold text-blue-600 dark:text-blue-400">11.6</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>華麗なる！音戯探偵ひなビタ</span><span class="font-bold text-blue-600 dark:text-blue-400">12.1</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>Nemophira</span><span class="font-bold text-blue-600 dark:text-blue-400">12.0</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>タンポポ</span><span class="font-bold text-blue-600 dark:text-blue-400">11.1</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>Amor∞Fati</span><span class="font-bold text-blue-600 dark:text-blue-400">12.7</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>PERFECT GREAT!!</span><span class="font-bold text-blue-600 dark:text-blue-400">12.6</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>UNDO THE NIGHT</span><span class="font-bold text-blue-600 dark:text-blue-400">12.5</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>HEROES</span><span class="font-bold text-blue-600 dark:text-blue-400">12.8</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>ちょえちょえまぎか[L]</span><span class="font-bold text-blue-600 dark:text-blue-400">11.7</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>オーバーライド</span><span class="font-bold text-blue-600 dark:text-blue-400">11.0</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>ラブコメトキドキシリアス</span><span class="font-bold text-blue-600 dark:text-blue-400">12.5</span></div>
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1"><span>ベンガル vs. アムール Battle</span><span class="font-bold text-blue-600 dark:text-blue-400">12.0</span></div>
            </div>
          </div>

          <div>
            <h4 class="text-sm font-black text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" /></svg>
              既存変更
            </h4>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-slate-700 dark:text-slate-300 ml-2 font-medium">
              <div class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1">
                <span>BRAINSTORM</span>
                <div class="flex items-center gap-2">
                  <span class="text-slate-400 line-through">12.6</span>
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                  <span class="font-bold text-amber-600 dark:text-amber-500">12.4</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Difficulty Entry: v1 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200 opacity-90">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-slate-500 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">初版</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">現行（初期制定）</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">初期</span>
        </div>
        
        <div class="p-8">
          <h4 class="text-sm font-black text-slate-600 dark:text-slate-400 uppercase tracking-widest mb-3 flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
            制定内容
          </h4>
          <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
            <li><span class="font-bold text-slate-900 dark:text-slate-100">初版の制定:</span>beat-seekerリリース時の基準となる難易度表です。</li>
          </ul>
        </div>
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
