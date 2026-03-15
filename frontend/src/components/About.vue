<template>
  <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 animate-fade-in overflow-hidden">
    
    <!-- Hero Section -->
    <div class="relative bg-gradient-to-br from-blue-600 via-blue-700 to-indigo-800 text-white p-8 sm:p-12 overflow-hidden">
      <div class="absolute inset-0 opacity-10">
        <svg width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
              <path d="M 40 0 L 0 0 0 40" fill="none" stroke="white" stroke-width="0.5"/>
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#grid)" />
        </svg>
      </div>
      <div class="relative z-10 max-w-3xl">
        <div class="inline-flex items-center gap-2 bg-white/10 backdrop-blur-sm px-3 py-1 rounded-full text-xs font-bold uppercase tracking-widest mb-6">
          <span class="w-2 h-2 bg-green-400 rounded-full animate-pulse"></span>
          Beta版 公開中
        </div>
        <h1 class="text-3xl sm:text-4xl font-black mb-4 leading-tight">
          beat-seeker とは？
        </h1>
        <p class="text-blue-100 text-base sm:text-lg leading-relaxed max-w-2xl">
          beat-seekerは、beatmania IIDX のスコアデータを<strong class="text-white">可視化・分析</strong>するための無料Webアプリケーションです。
          公式サイトからダウンロードしたCSVファイルをアップロードするだけで、あなたのプレイデータを美しいグラフやランクで確認できます。
        </p>
      </div>
    </div>

    <div class="p-6 sm:p-10 space-y-16">

      <!-- How it works -->
      <section>
        <div class="flex items-center gap-3 mb-8">
          <div class="w-10 h-10 bg-blue-100 dark:bg-blue-900/50 rounded-xl flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5 text-blue-600 dark:text-blue-400">
              <path fill-rule="evenodd" d="M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12zm11.378-3.917c-.89-.777-2.366-.777-3.255 0a.75.75 0 01-.988-1.129c1.454-1.272 3.776-1.272 5.23 0 1.513 1.324 1.513 3.518 0 4.842a3.75 3.75 0 01-.837.552c-.676.328-1.028.774-1.028 1.152v.75a.75.75 0 01-1.5 0v-.75c0-1.279 1.06-2.107 1.875-2.502.182-.088.351-.199.503-.331.89-.777.89-2.038 0-2.815zM12 18a.75.75 0 100-1.5.75.75 0 000 1.5z" clip-rule="evenodd" />
            </svg>
          </div>
          <h2 class="text-xl sm:text-2xl font-black text-slate-800 dark:text-white">使い方（3ステップ）</h2>
        </div>

        <!-- Steps Flow -->
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
          <!-- Step 1 -->
          <div class="relative bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-6 border border-slate-200 dark:border-slate-700">
            <div class="absolute -top-3 -left-1 w-8 h-8 bg-blue-600 text-white rounded-full flex items-center justify-center text-sm font-black shadow-lg">1</div>
            <div class="flex justify-center mb-4 mt-2">
              <!-- CSV Icon -->
              <svg class="w-16 h-16 text-blue-500 dark:text-blue-400" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="12" y="8" width="40" height="48" rx="4" stroke="currentColor" stroke-width="2.5" fill="none"/>
                <path d="M20 20h24M20 28h24M20 36h16" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                <path d="M36 40l4 4 8-8" stroke="#22c55e" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <h3 class="font-black text-slate-800 dark:text-white text-center mb-2">CSVをアップロード</h3>
            <p class="text-sm text-slate-600 dark:text-slate-400 text-center leading-relaxed">
              公式サイトからダウンロードしたCSVファイルを、ドラッグ＆ドロップまたはクリックでアップロードします。
            </p>
          </div>

          <!-- Step 2 -->
          <div class="relative bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-6 border border-slate-200 dark:border-slate-700">
            <div class="absolute -top-3 -left-1 w-8 h-8 bg-blue-600 text-white rounded-full flex items-center justify-center text-sm font-black shadow-lg">2</div>
            <div class="flex justify-center mb-4 mt-2">
              <!-- Chart Icon -->
              <svg class="w-16 h-16 text-blue-500 dark:text-blue-400" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="10" y="36" width="8" height="18" rx="2" fill="currentColor" opacity="0.3"/>
                <rect x="22" y="24" width="8" height="30" rx="2" fill="currentColor" opacity="0.5"/>
                <rect x="34" y="16" width="8" height="38" rx="2" fill="currentColor" opacity="0.7"/>
                <rect x="46" y="10" width="8" height="44" rx="2" fill="currentColor"/>
                <path d="M10 58h48" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
              </svg>
            </div>
            <h3 class="font-black text-slate-800 dark:text-white text-center mb-2">自動で分析</h3>
            <p class="text-sm text-slate-600 dark:text-slate-400 text-center leading-relaxed">
              アップロードするだけで、ランク・クリア率・スコアレートなどが自動で計算・可視化されます。
            </p>
          </div>

          <!-- Step 3 -->
          <div class="relative bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-6 border border-slate-200 dark:border-slate-700">
            <div class="absolute -top-3 -left-1 w-8 h-8 bg-blue-600 text-white rounded-full flex items-center justify-center text-sm font-black shadow-lg">3</div>
            <div class="flex justify-center mb-4 mt-2">
              <!-- Growth Icon -->
              <svg class="w-16 h-16 text-blue-500 dark:text-blue-400" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="32" cy="32" r="22" stroke="currentColor" stroke-width="2.5" fill="none"/>
                <path d="M20 40 L28 30 L36 34 L46 20" stroke="#f59e0b" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M40 20h6v6" stroke="#f59e0b" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <h3 class="font-black text-slate-800 dark:text-white text-center mb-2">成長を記録</h3>
            <p class="text-sm text-slate-600 dark:text-slate-400 text-center leading-relaxed">
              アカウント登録してログインすると、スコアがクラウドに保存され、過去との比較や成長の確認ができます。
            </p>
          </div>
        </div>
      </section>

      <!-- Feature List -->
      <section>
        <div class="flex items-center gap-3 mb-8">
          <div class="w-10 h-10 bg-purple-100 dark:bg-purple-900/50 rounded-xl flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5 text-purple-600 dark:text-purple-400">
              <path fill-rule="evenodd" d="M10.788 3.21c.448-1.077 1.976-1.077 2.424 0l2.082 5.007 5.404.433c1.164.093 1.636 1.545.749 2.305l-4.117 3.527 1.257 5.273c.271 1.136-.964 2.033-1.96 1.425L12 18.354 7.373 21.18c-.996.608-2.231-.29-1.96-1.425l1.257-5.273-4.117-3.527c-.887-.76-.415-2.212.749-2.305l5.404-.433 2.082-5.006z" clip-rule="evenodd" />
            </svg>
          </div>
          <h2 class="text-xl sm:text-2xl font-black text-slate-800 dark:text-white">主な機能</h2>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-6">

          <!-- Feature 1: Dashboard -->
          <div class="bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-900/20 dark:to-indigo-900/20 rounded-2xl p-6 border border-blue-100 dark:border-blue-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-blue-600 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path d="M18.375 2.25c-1.035 0-1.875.84-1.875 1.875v15.75c0 1.035.84 1.875 1.875 1.875h.75c1.035 0 1.875-.84 1.875-1.875V4.125c0-1.036-.84-1.875-1.875-1.875h-.75zM9.75 8.625c0-1.036.84-1.875 1.875-1.875h.75c1.036 0 1.875.84 1.875 1.875v11.25c0 1.035-.84 1.875-1.875 1.875h-.75a1.875 1.875 0 01-1.875-1.875V8.625zM3 13.125c0-1.036.84-1.875 1.875-1.875h.75c1.036 0 1.875.84 1.875 1.875v6.75c0 1.035-.84 1.875-1.875 1.875h-.75A1.875 1.875 0 013 19.875v-6.75z" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">ダッシュボード</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              クリア状況をドーナツチャートで一目で把握。DJレベル（AAA/AA/A等）の分布もグラフ表示。
              「☆12のみ」「☆11のみ」のフィルターや、「未プレイを除外」など細かい設定が可能です。
              統計は<strong>クリア率・AAA率・MAX-率</strong>の3つの主要指標で表示されます。
            </p>
          </div>

          <!-- Feature 2: Beat-Tier -->
          <div class="bg-gradient-to-br from-amber-50 to-orange-50 dark:from-amber-900/20 dark:to-orange-900/20 rounded-2xl p-6 border border-amber-100 dark:border-amber-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-amber-500 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path fill-rule="evenodd" d="M5.166 2.621v.858c-1.035.148-2.059.33-3.071.543a.75.75 0 00-.584.859 6.753 6.753 0 006.138 5.6 6.73 6.73 0 002.743 1.346A6.707 6.707 0 019.279 15H8.54c-1.036 0-1.875.84-1.875 1.875V19.5h-.75a.75.75 0 000 1.5h12.75a.75.75 0 000-1.5h-.75v-2.625c0-1.036-.84-1.875-1.875-1.875h-.739a6.707 6.707 0 01-1.112-3.173 6.73 6.73 0 002.743-1.347 6.753 6.753 0 006.139-5.6.75.75 0 00-.585-.858 47.077 47.077 0 00-3.07-.543V2.62a.75.75 0 00-.658-.744 49.22 49.22 0 00-6.093-.377c-2.063 0-4.096.128-6.093.377a.75.75 0 00-.657.744zm0 2.629c0 1.196.312 2.32.857 3.294A5.266 5.266 0 013.16 5.337a45.6 45.6 0 012.006-.343v.256zm13.67 0v-.256c.674.1 1.343.214 2.006.343a5.265 5.265 0 01-2.863 3.207 6.72 6.72 0 00.857-3.294z" clip-rule="evenodd" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">Beat-Tier ランキングシステム</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              beat-seeker独自のランクシステム。非公式難易度表（☆12 / ☆11）に掲載されている楽曲を対象とし、
              スコアレートの1.5乗 × 譜面ごとの重みで<strong>Beat-PT</strong>を算出。
              上位100曲の合計であなたの真の地力を<strong>Beginner〜Legend</strong>の段位で可視化します。
            </p>
          </div>

          <!-- Feature 3: Score List -->
          <div class="bg-gradient-to-br from-green-50 to-emerald-50 dark:from-green-900/20 dark:to-emerald-900/20 rounded-2xl p-6 border border-green-100 dark:border-green-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-green-600 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path fill-rule="evenodd" d="M2.625 6.75a1.125 1.125 0 112.25 0 1.125 1.125 0 01-2.25 0zm4.875 0A.75.75 0 018.25 6h12a.75.75 0 010 1.5h-12a.75.75 0 01-.75-.75zM2.625 12a1.125 1.125 0 112.25 0 1.125 1.125 0 01-2.25 0zM7.5 12a.75.75 0 01.75-.75h12a.75.75 0 010 1.5h-12A.75.75 0 017.5 12zm-4.875 5.25a1.125 1.125 0 112.25 0 1.125 1.125 0 01-2.25 0zM7.5 17.25a.75.75 0 01.75-.75h12a.75.75 0 010 1.5h-12a.75.75 0 01-.75-.75z" clip-rule="evenodd" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">スコア一覧テーブル</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              全楽曲のスコアを一覧表示。<strong>レベル・譜面難易度・DJレベル</strong>でのフィルターに対応し、
              曲名やアーティスト名で検索も可能。各カラムでソートもできる高機能なテーブルです。
              非公式難易度やBeat-PT、スコアレートも列として確認できます。
            </p>
          </div>

          <!-- Feature 4: Upload Diff -->
          <div class="bg-gradient-to-br from-rose-50 to-pink-50 dark:from-rose-900/20 dark:to-pink-900/20 rounded-2xl p-6 border border-rose-100 dark:border-rose-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-rose-500 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path fill-rule="evenodd" d="M11.47 2.47a.75.75 0 011.06 0l3.75 3.75a.75.75 0 01-1.06 1.06l-2.47-2.47V14.25a.75.75 0 01-1.5 0V4.81L8.78 7.28a.75.75 0 01-1.06-1.06l3.75-3.75zM12 14.25a.75.75 0 01.75.75v2.25a1.5 1.5 0 01-1.5 1.5h-6a1.5 1.5 0 01-1.5-1.5v-2.25a.75.75 0 011.5 0v2.25h6v-2.25a.75.75 0 01.75-.75z" clip-rule="evenodd" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">アップロード差分レポート</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              CSVを更新アップロードするたびに、前回との<strong>差分</strong>をレポート形式でリアルタイム表示。
              スコアアップした曲、クリアランプが更新された曲、Beat-Tierの変動（例: Expert 3 → Expert 4）などが一覧で確認できます。
            </p>
          </div>

          <!-- Feature 5: Upload History -->
          <div class="bg-gradient-to-br from-violet-50 to-purple-50 dark:from-violet-900/20 dark:to-purple-900/20 rounded-2xl p-6 border border-violet-100 dark:border-violet-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-violet-600 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path fill-rule="evenodd" d="M12 2.25c-5.385 0-9.75 4.365-9.75 9.75s4.365 9.75 9.75 9.75 9.75-4.365 9.75-9.75S17.385 2.25 12 2.25zM12.75 6a.75.75 0 00-1.5 0v6c0 .414.336.75.75.75h4.5a.75.75 0 000-1.5h-3.75V6z" clip-rule="evenodd" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">アップロード履歴</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              過去にアップロートした全履歴を確認可能。
              各回のスコア更新数・クリアランプ更新数・Beat-Tier変動がカード形式で表示されます。
              それぞれの詳細レポートを再表示することもできます。
            </p>
          </div>

          <!-- Feature 6: Cloud Save -->
          <div class="bg-gradient-to-br from-cyan-50 to-teal-50 dark:from-cyan-900/20 dark:to-teal-900/20 rounded-2xl p-6 border border-cyan-100 dark:border-cyan-800/50">
            <div class="flex items-center gap-3 mb-3">
              <div class="w-8 h-8 bg-cyan-600 rounded-lg flex items-center justify-center">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-4 h-4 text-white">
                  <path fill-rule="evenodd" d="M4.5 9.75a6 6 0 0111.573-2.226 3.75 3.75 0 014.133 4.303A4.5 4.5 0 0118 20.25H6.75a5.25 5.25 0 01-2.23-10.004 6.072 6.072 0 01-.02-.496z" clip-rule="evenodd" />
                </svg>
              </div>
              <h3 class="font-black text-slate-800 dark:text-white">クラウド保存 & ログイン</h3>
            </div>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              アカウント登録してログインすると、スコアデータがクラウドに自動保存されます。
              ログインなしでもCSVの読み込みと分析は利用可能ですが、データの保存や履歴機能を使うにはログインが必要です。
            </p>
          </div>

        </div>
      </section>

      <!-- Dark Mode -->
      <section>
        <div class="flex items-center gap-3 mb-6">
          <div class="w-10 h-10 bg-slate-800 dark:bg-slate-200 rounded-xl flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5 text-white dark:text-slate-800">
              <path fill-rule="evenodd" d="M9.528 1.718a.75.75 0 01.162.819A8.97 8.97 0 009 6a9 9 0 009 9 8.97 8.97 0 003.463-.69.75.75 0 01.981.98 10.503 10.503 0 01-9.694 6.46c-5.799 0-10.5-4.701-10.5-10.5 0-4.368 2.667-8.112 6.46-9.694a.75.75 0 01.818.162z" clip-rule="evenodd" />
            </svg>
          </div>
          <h2 class="text-xl sm:text-2xl font-black text-slate-800 dark:text-white">ダークモード対応</h2>
        </div>
        <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed max-w-2xl">
          画面右上の太陽/月アイコンで<strong>ライトモード・ダークモード</strong>を切り替えられます。
          お好みの表示環境でご利用ください。
        </p>
      </section>

      <!-- FAQ -->
      <section>
        <div class="flex items-center gap-3 mb-8">
          <div class="w-10 h-10 bg-emerald-100 dark:bg-emerald-900/50 rounded-xl flex items-center justify-center">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5 text-emerald-600 dark:text-emerald-400">
              <path fill-rule="evenodd" d="M4.848 2.771A49.144 49.144 0 0112 2.25c2.43 0 4.817.178 7.152.52a1.834 1.834 0 011.899 1.998c.108 1.549.263 3.445.263 5.232 0 1.787-.155 3.683-.263 5.232a1.834 1.834 0 01-1.899 1.998A49.15 49.15 0 0112 17.75a49.15 49.15 0 01-7.152-.52 1.834 1.834 0 01-1.899-1.998c-.108-1.549-.263-3.445-.263-5.232 0-1.787.155-3.683.263-5.232a1.834 1.834 0 011.899-1.998zM8.25 10.5a.75.75 0 000 1.5h7.5a.75.75 0 000-1.5h-7.5z" clip-rule="evenodd" />
            </svg>
          </div>
          <h2 class="text-xl sm:text-2xl font-black text-slate-800 dark:text-white">よくある質問</h2>
        </div>

        <div class="space-y-4">
          <div class="bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-5 border border-slate-200 dark:border-slate-700">
            <h4 class="font-black text-slate-800 dark:text-white mb-2">Q. CSVファイルはどこで入手できますか？</h4>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              IIDX公式サイトのプレーデータ画面からCSVをダウンロードできます。ログイン後、プレーデータの画面を開き、CSV出力ボタンを押してください。
            </p>
          </div>
          <div class="bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-5 border border-slate-200 dark:border-slate-700">
            <h4 class="font-black text-slate-800 dark:text-white mb-2">Q. ログインしないと使えませんか？</h4>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              いいえ、ログインなしでもCSVの読み込みとダッシュボード・スコア一覧の表示は可能です。
              ただし、データのクラウド保存やアップロード履歴の確認にはアカウント登録後のログインが必要です。
            </p>
          </div>
          <div class="bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-5 border border-slate-200 dark:border-slate-700">
            <h4 class="font-black text-slate-800 dark:text-white mb-2">Q. Beat-Tierとは何ですか？</h4>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              beat-seeker独自のスキル評価システムです。非公式難易度表に掲載されている楽曲のスコアレートを元にポイントを算出し、
              上位100曲の合計によってBeginner〜Legendのランクが決まります。詳細はダッシュボード内のBeat-Tierアイコンをクリックするとご覧いただけます。
            </p>
          </div>
          <div class="bg-slate-50 dark:bg-slate-900/50 rounded-2xl p-5 border border-slate-200 dark:border-slate-700">
            <h4 class="font-black text-slate-800 dark:text-white mb-2">Q. スコアデータは安全ですか？</h4>
            <p class="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
              はい。運営者は取得したスコアデータを個人の利益となるような利用は一切いたしません。
              詳しくは画面下部の「利用規約・プライバシーポリシー」をご確認ください。
            </p>
          </div>
        </div>
      </section>

    </div>
  </div>
</template>

<script setup lang="ts">
// About Component
</script>
