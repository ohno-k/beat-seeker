<script setup lang="ts">
/**
 * 【コンポーネントの役割】 beat-seeker の各機能の使い方を 1 ページにまとめた「使い方ガイド」。
 *
 * About.vue が「サービス紹介」だったのに対し、こちらは「各タブの操作手順」を
 * 機能単位で詳しく解説する位置付け。サイドバーの SUPPORT セクションから到達する。
 *
 * 対象:
 *  - 全ログインユーザー (および未ログイン閲覧者) が利用できる機能
 *  - サポーター限定のスコアペア散布図も「サポーター限定」と明示したうえで紹介する
 *
 * 非対象 (このページには記載しない):
 *  - 管理者専用画面 (人気曲ランキング / 管理パネル / 任意 2 ユーザー比較)
 *  - 大会主催・運営限定 (Competition Admin / Player / TL / Strategy Card / Song Reveal / OBS Standings)
 *  - 特定ユーザー ID 限定 (曲別平均スコアレート など)
 */
import { onMounted } from 'vue';

/** 目次クリックで該当セクションへスクロールする。ヘッダー固定を考慮して 80px のオフセットを付ける。 */
const scrollToSection = (id: string) => {
  const el = document.getElementById(id);
  if (!el) return;
  const top = el.getBoundingClientRect().top + window.pageYOffset - 80;
  window.scrollTo({ top, behavior: 'smooth' });
};

/**
 * 各セクションのメタ情報。目次描画と本文の見出し ID の両方に流用する。
 * - id: スクロール先のアンカー
 * - title: 目次・見出しに表示する日本語タイトル
 * - desc: 目次の説明 1 行 (省略可)
 */
const sections = [
  { id: 'getting-started', title: 'はじめに — スコアを取り込む', desc: 'CSV / ブックマークレット / OCR からスコアを登録する手順' },
  { id: 'dashboard', title: 'ダッシュボード', desc: '現在の Beat-Tier / Rate-Tier と最近の伸びを一望する' },
  { id: 'scores', title: 'スコア一覧', desc: '全スコアの検索・絞り込み・編集ができる表ビュー' },
  { id: 'profile', title: 'プロフィール', desc: '表示名・段位・公開設定をまとめて編集する' },
  { id: 'ranking', title: 'ランキング', desc: '全ユーザー / フレンドの Beat-Tier 順位を確認する' },
  { id: 'friends', title: 'フレンド', desc: 'フレンド追加・申請・通知の管理' },
  { id: 'history', title: '成長記録 (アップロード履歴)', desc: 'アップロードごとのポイント推移グラフ' },
  { id: 'arena', title: 'ARENA モード', desc: 'アリーナの対戦履歴と相手別集計' },
  { id: 'arcade-assist', title: '選曲アシスト', desc: '次にプレイすべき譜面を 6 モードで提案' },
  { id: 'tier-voting', title: '投票所 (難易度表投票)', desc: '非公式難易度の昇格 / 据え置き / 降格に投票' },
  { id: 'song-avg', title: 'ティア別平均', desc: '各 Beat-Tier 帯での曲別の平均スコアを参照' },
  { id: 'diff-table', title: '難易度表', desc: '非公式難易度表の全ランクをアコーディオン表示' },
  { id: 'score-prediction', title: '譜面分析 (スコア予測)', desc: '譜面の傾向と予測スコアを可視化' },
  { id: 'score-scatter', title: 'スコアペア散布図 (サポーター限定)', desc: '2 譜面間の相関を散布図で分析' },
  { id: 'chart-list', title: '譜面一覧', desc: '全譜面を検索 / ソート / textage プレビュー' },
  { id: 'share', title: '公開共有リンク', desc: '未ログインでも見られるトークン URL でスコアを共有' },
  { id: 'guide', title: '攻略ガイド', desc: '皿対策 / AAA 狙い / 地力上げなど実戦的なコラム' },
  { id: 'changelog', title: '更新履歴', desc: 'アプリ更新と難易度表改訂の履歴' },
  { id: 'misc', title: 'その他の便利機能', desc: 'ダークモード / 言語切替 / PWA / プッシュ通知' },
];

/** ページ表示直後にスクロール位置を一番上にリセットする (タブ遷移で前回位置が残らないように)。 */
onMounted(() => {
  window.scrollTo({ top: 0, behavior: 'auto' });
});
</script>

<template>
  <div class="space-y-12 pb-20 animate-fade-in text-slate-900 dark:text-white">
    <!-- ヘッダー -->
    <section class="bg-white dark:bg-slate-800 rounded-2xl p-8 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700">
      <span class="inline-flex items-center px-3 py-1 rounded-full text-xs font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 mb-4 border border-blue-100 dark:border-blue-800/50">
        USER MANUAL
      </span>
      <h1 class="text-3xl sm:text-4xl font-black mb-4 leading-tight">
        beat-seeker 使い方ガイド
      </h1>
      <p class="text-base sm:text-lg text-slate-600 dark:text-slate-300 leading-relaxed">
        サイドバーから辿れる主要画面の使い方を、機能ごとにまとめました。
        まずは <a href="#getting-started" @click.prevent="scrollToSection('getting-started')" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">スコアを取り込む</a>
        から始めるのがおすすめです。
      </p>
    </section>

    <!-- 目次 -->
    <nav class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-8 shadow-sm border border-slate-200 dark:border-slate-700">
      <h2 class="text-xl font-black mb-4 flex items-center gap-3">
        <span class="w-1.5 h-6 bg-blue-600 dark:bg-blue-500 rounded-full"></span>
        目次
      </h2>
      <ul class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-2">
        <li v-for="(s, idx) in sections" :key="s.id" class="text-sm">
          <a
            :href="`#${s.id}`"
            @click.prevent="scrollToSection(s.id)"
            class="flex items-baseline gap-2 py-1 text-slate-700 dark:text-slate-300 hover:text-blue-600 dark:hover:text-blue-400 hover:underline"
          >
            <span class="text-xs font-mono text-slate-400 dark:text-slate-500 w-6 shrink-0">{{ String(idx + 1).padStart(2, '0') }}</span>
            <span class="font-bold">{{ s.title }}</span>
          </a>
        </li>
      </ul>
    </nav>

    <!-- 1. はじめに -->
    <section id="getting-started" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">01</span>
        はじめに — スコアを取り込む
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        beat-seeker はあなたの IIDX スコアを「取り込む」ところから始まります。取り込み方法は 3 つあります。
      </p>

      <h3 class="text-lg font-bold pt-2">方法 A: 公式 CSV をアップロード</h3>
      <ol class="list-decimal pl-6 space-y-1 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li>e-amusement GATE にログインして、IIDX の「スコアダウンロード」から CSV を取得します (詳しい手順は <a href="/guide/csv-import" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">攻略ガイド「CSV ダウンロード手順」</a> を参照)。</li>
        <li>ダッシュボードの中央にあるドロップエリアに CSV ファイルをドラッグ＆ドロップするか、エリアをクリックしてファイルを選択します。</li>
        <li>取り込みに成功すると、アップロード結果モーダルで「新規追加 / 更新された曲」が一覧表示されます。</li>
      </ol>

      <h3 class="text-lg font-bold pt-2">方法 B: ブックマークレットで自動取り込み</h3>
      <p class="text-slate-700 dark:text-slate-300 leading-relaxed">
        サイドバーの「ブックマークレットを追加」からブラウザのブックマークバーに登録できます。e-amusement GATE のスコア閲覧ページ上でブックマークレットを 1 回クリックすると、CSV のダウンロードを経由せずにそのまま beat-seeker へスコアが送信されます。
      </p>

      <h3 class="text-lg font-bold pt-2">方法 C: スマホカメラで OCR 取り込み (ベータ)</h3>
      <p class="text-slate-700 dark:text-slate-300 leading-relaxed">
        サイドバーの「カメラで曲検索」から、ゲーム画面の写真をアップロードすると曲名を OCR で識別し、その場でスコアを記録できます。試験運用中の機能のため精度に揺らぎがあります。
      </p>

      <div class="mt-4 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl text-sm text-amber-800 dark:text-amber-300">
        <strong>💡 アップロードを繰り返すと自動で差分を検出します</strong>。新規追加された曲・スコアが更新された曲だけがアップロード結果に表示され、過去のアップロード履歴は <a href="#history" @click.prevent="scrollToSection('history')" class="font-bold underline">成長記録</a> に保存されます。
      </div>
    </section>

    <!-- 2. ダッシュボード -->
    <section id="dashboard" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">02</span>
        ダッシュボード
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        ログイン直後の入口となる画面で、現在のスコア状況を一目で把握できます。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>Beat-Tier / Rate-Tier:</strong> 全譜面で算出した「総合段位」と「精度寄り段位」を、ポイントと段位アイコンで表示します。アイコンをクリックすると、各段位の到達条件をモーダルで確認できます。</li>
        <li><strong>アクティビティ:</strong> 直近のスコア更新を時系列で表示します。アップロード直後はここで「何の曲が伸びたか」が一目で分かります。</li>
        <li><strong>他ユーザー閲覧モード:</strong> ランキングやフレンドからユーザーを選ぶと、URL が <code>/user/:userId</code> に変わり、相手のダッシュボードをそのまま閲覧できます。自分のデータに戻すには左上のロゴをクリックしてください。</li>
        <li><strong>フレンド申請バナー:</strong> 他人のダッシュボード閲覧中は、フレンド申請ボタンが上部に表示されます (既にフレンドの場合は出ません)。</li>
      </ul>
      <p class="text-sm text-slate-500 dark:text-slate-400">対象スコア: 集計は ANOTHER / LEGGENDARIA 譜面が中心です。BEGINNER / NORMAL / HYPER は表示はされますが Beat-Tier 計算には入りません。</p>
    </section>

    <!-- 3. スコア一覧 -->
    <section id="scores" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">03</span>
        スコア一覧
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        登録された全スコアを 1 行 1 譜面の表形式で管理する画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>検索とフィルタ:</strong> 上部の検索ボックスで曲名を絞り込み、難易度 (ANOTHER / LEGGENDARIA)・レベル (☆1〜☆12)・ポイント帯でさらにフィルタできます。</li>
        <li><strong>列のソート:</strong> 各列ヘッダーをクリックすると、スコア・スコアレート・ポイント・更新日などの基準で並び替えられます。</li>
        <li><strong>インライン編集:</strong> スコア値・ミスカウントは行をクリックして直接書き換えられます (オンラインデータが間違っていた時の手修正用)。</li>
        <li><strong>合計ポイント表示:</strong> フィルタ後の合計ポイントが即時反映されるので、「☆12 だけで何 PT 取れているか」のような分析が可能です。</li>
      </ul>
    </section>

    <!-- 4. プロフィール -->
    <section id="profile" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">04</span>
        プロフィール
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        自分の表示名や公開設定を編集する画面です。ランキングなどに表示される情報の大半はここから変更できます。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>表示名 / 自己紹介:</strong> IIDX ID とは別に、ランキングに表示する表示名を設定できます。</li>
        <li><strong>段位 / プレイスタイル:</strong> 公式段位 (皆伝・十段など)、ARENA 段位、昼/夜のプレイスタイルなどを登録できます。</li>
        <li><strong>Beat-Tier の非公開化:</strong> 自分の Beat-Tier をランキングから隠す設定が可能です (個人練習用途向け)。</li>
        <li><strong>サポーター表示:</strong> Ko-fi 経由でサポートしてくださった方は、ランキング上でサポーターバッジを表示できます。</li>
        <li><strong>公開共有リンクの管理:</strong> ダッシュボード / スコア一覧 / 成長記録 / プロフィールの各スコープで共有 URL を発行・無効化できます。詳細は <a href="#share" @click.prevent="scrollToSection('share')" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">公開共有リンク</a> を参照。</li>
      </ul>
    </section>

    <!-- 5. ランキング -->
    <section id="ranking" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">05</span>
        ランキング
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        全ユーザーの Beat-PT / Rate-PT による総合ランキング画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>Beat-PT / Rate-PT の切替:</strong> 上部のタブで集計指標を切り替えられます。Rate-PT は ANOTHER / LEGGENDARIA に限定したスコアレート寄りの集計です。</li>
        <li><strong>各行のクリック:</strong> ユーザー行をクリックするとそのユーザーのダッシュボードに遷移します (公開設定の範囲で閲覧可能)。</li>
        <li><strong>段位・サポーター表示:</strong> 行の右側に、各ユーザーの公式段位・ARENA 段位・サポーターバッジが表示されます。</li>
      </ul>
    </section>

    <!-- 6. フレンド -->
    <section id="friends" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">06</span>
        フレンド
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        フレンドの追加・削除・申請管理を行う画面です。フレンドになると、相手の非公開スコアやアップロード履歴も互いに閲覧できるようになります。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>フレンド追加:</strong> 「フレンド検索」から IIDX ID または表示名で検索し、申請を送ります。</li>
        <li><strong>申請受信通知:</strong> 受信した申請は画面上部のベルアイコンとプッシュ通知で告知されます。</li>
        <li><strong>受理 / 拒否:</strong> 受信申請カードで承認・拒否を選択できます。</li>
        <li><strong>フレンド一覧:</strong> Beat-PT 順で並びます。各行をクリックすると、相手のダッシュボード (フレンド閲覧モード) に遷移します。</li>
      </ul>
    </section>

    <!-- 7. 成長記録 -->
    <section id="history" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">07</span>
        成長記録 (アップロード履歴)
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        CSV アップロードごとのスナップショットから、Beat-PT の時系列推移を可視化する画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>推移グラフ:</strong> アップロード日ごとの合計ポイントが折れ線グラフになります。グラフ上の点をクリックすると、その時点で「どの曲が伸びたか」の差分一覧が表示されます。</li>
        <li><strong>期間絞り込み:</strong> 日付フィルタで「直近 1 ヶ月」「半年」など期間を指定できます。</li>
        <li><strong>フレンドの履歴閲覧:</strong> フレンドモードで他ユーザーを開いた状態で成長記録に切り替えると、相手の推移を同じ画面で見られます。</li>
      </ul>
    </section>

    <!-- 8. ARENA -->
    <section id="arena" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">08</span>
        ARENA モード
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        ARENA モードの対戦履歴を beat-seeker に取り込み、相手別の戦績や曲ごとの勝敗を集計できる画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>履歴の取り込み:</strong> 画面上部の取り込みエリアに、ブックマークレットや CSV 経由で対戦データをアップロードします。</li>
        <li><strong>履歴 / 相手別タブ:</strong> 試合単位のログと、相手別に集計したサマリを切り替えられます。</li>
        <li><strong>ALL / Online / Local:</strong> オンラインアリーナと店内対戦を分けて分析できます。</li>
        <li><strong>勝敗ハイライト:</strong> 各試合の 1 位は金、2 位は銀で着色され、MAX 譜面のグレード (MAX- / MAX) も併記されます。</li>
      </ul>
    </section>

    <!-- 9. 選曲アシスト -->
    <section id="arcade-assist" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">09</span>
        選曲アシスト
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        「次に何をプレイするか」を 6 種類のモードで提案してくれる画面です。ゲーセンで眺めながら使うことを想定しています。
      </p>
      <ul class="list-disc pl-6 space-y-2 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>ライバルモード:</strong> フレンドを指定すると、自分が負けている譜面をスコア差順に提示します。</li>
        <li><strong>ボーダーモード:</strong> AA / AAA / MAX- のあと数点で 1 段階上がる「すぐ伸ばせる譜面」を抽出します。</li>
        <li><strong>Beat-PT / Rate-PT モード:</strong> プレイすれば総ポイントが上がりそうな候補を、推定上昇幅順に並べます。</li>
        <li><strong>伸びしろランキング (Potential):</strong> 他ユーザーとの相関から「あなたならもっと出せるはず」の譜面を提示します。</li>
        <li><strong>得意ランキング (Strength):</strong> 平均よりも明らかに高得点を出している譜面を抽出し、得意傾向を可視化します。</li>
      </ul>
      <p class="text-sm text-slate-500 dark:text-slate-400">Lv11 / Lv12 タブで対象帯を切り替え、各モードで上位 50 曲まで表示されます。</p>
    </section>

    <!-- 10. 投票所 -->
    <section id="tier-voting" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">10</span>
        投票所 (難易度表投票)
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        非公式難易度表の各譜面に対して、ランクの妥当性をコミュニティで投票する画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>3 択投票:</strong> 既にランクが付いている譜面には「昇格 (PROMOTE) / 据え置き (STAY) / 降格 (DEMOTE)」を選びます。同じボタンを再度押すと取り消しになります。</li>
        <li><strong>未カテゴリ譜面の投票:</strong> まだランクが付いていない譜面には、11.0 〜 13.0 の 0.1 刻みで Tier 値を選んで投票します。</li>
        <li><strong>コメント:</strong> 各譜面のコメントを閲覧したり、投票理由のコメントを投稿したりできます。</li>
        <li><strong>並び替え:</strong> 検索 + 最新コメントの新しい順 / 投票が活発な順などで並び替え可能です。</li>
      </ul>
      <p class="text-sm text-slate-500 dark:text-slate-400">投票結果は管理者が定期的に集計し、難易度表本体に反映していきます。改訂履歴は <a href="#changelog" @click.prevent="scrollToSection('changelog')" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">更新履歴</a> から参照できます。</p>
    </section>

    <!-- 11. ティア別平均 -->
    <section id="song-avg" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">11</span>
        ティア別平均
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        Beat-Tier 帯 (Beginner 〜 Legend) ごとに、各曲の平均スコアを集計した表です。自分のスコアが「同じ Tier の人と比べてどの位置か」が分かります。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>Tier の選択:</strong> 上部で表示したい Tier (および 1〜5 のサブ階層) を選びます。</li>
        <li><strong>勝敗フィルタ:</strong> 「平均より高い曲のみ」「平均より低い曲のみ」に絞れます。苦手譜面探しに便利です。</li>
        <li><strong>レベル絞り込み:</strong> Lv11 / Lv12 を切り替えて表示できます。</li>
        <li><strong>列ソート:</strong> 平均スコア / 自スコア / 差分 / 順位など各列でソートできます。</li>
      </ul>
    </section>

    <!-- 12. 難易度表 -->
    <section id="diff-table" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">12</span>
        難易度表
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        非公式難易度表の全ランクをアコーディオン形式で一覧する画面です。Lv12.0 / 11.9 などのランク帯と、個人差・未定義などの非数値ランクを分けて表示します。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>検索:</strong> 曲名を検索すると、該当曲を含むランクが自動で展開されます。</li>
        <li><strong>アコーディオン開閉:</strong> ランクのバーをクリックして、その帯の曲リストを開閉できます。</li>
        <li><strong>ログイン誘導:</strong> 未ログインでも閲覧可能ですが、ログインするとクリア状況に応じた色分けが行われます。</li>
      </ul>
    </section>

    <!-- 13. 譜面分析 -->
    <section id="score-prediction" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">13</span>
        譜面分析 (スコア予測)
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        Lv11 / Lv12 の ANOTHER / LEGGENDARIA 譜面について、過去の類似譜面のスコアから「あなたが出せそうなスコア」を予測する画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>曲を選ぶ:</strong> 検索ボックスで譜面を指定すると、予測スコアと予測スコアレート (%) が表示されます。</li>
        <li><strong>譜面プロファイル:</strong> 実効 BPM、皿率、同時押し率、ノーツの配置パターン (16分・24分・縦連) が可視化されます。</li>
        <li><strong>URL での共有:</strong> 各譜面の分析結果は <code>/chart/&lt;version&gt;/&lt;slug&gt;/&lt;diff&gt;</code> という安定した URL を持ちます。SNS シェアや外部サイトからのリンクに使えます。</li>
        <li><strong>他ユーザーの予測閲覧:</strong> フレンドや TOP ランカーを閲覧中の場合、相手の予測値も同じ画面に並べて確認できます。</li>
      </ul>
    </section>

    <!-- 14. スコアペア散布図 (サポーター限定) -->
    <section id="score-scatter" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border-2 border-amber-200 dark:border-amber-800/50 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3 flex-wrap">
        <span class="text-blue-500 font-mono text-base">14</span>
        スコアペア散布図
        <span class="ml-1 text-[10px] font-black uppercase tracking-wider px-2 py-0.5 rounded-full bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-400 border border-amber-200 dark:border-amber-700">
          サポーター限定
        </span>
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        2 つの譜面を指定し、両方をプレイしている全ユーザーのスコアペアを散布図でプロットする分析画面です。譜面間の相関を可視化し、自分の位置や得意 / 苦手の偏りを確認できます。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>譜面 A / B の選択:</strong> 上部の検索ボックスから 2 譜面を指定します。</li>
        <li><strong>散布図表示:</strong> X 軸 = 譜面 A のスコア、Y 軸 = 譜面 B のスコアで全ユーザーをプロット。回帰直線と相関係数も表示されます。</li>
        <li><strong>DJ LEVEL グリッド:</strong> A / AA / AAA / MAX- / MAX のグレード境界が補助線として描画されます。</li>
        <li><strong>ホバー詳細:</strong> 点にマウスを乗せると、ユーザー名と両譜面のスコア / スコアレートが表示されます。</li>
      </ul>
      <div class="mt-2 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 rounded-xl text-sm text-amber-800 dark:text-amber-300">
        この機能は <strong>Ko-fi 経由でサポートしてくださったサポーター限定</strong>です。サイドバー下部の Ko-fi ボタンから支援できます。
      </div>
    </section>

    <!-- 15. 譜面一覧 -->
    <section id="chart-list" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">15</span>
        譜面一覧
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        IIDX の全譜面を、難易度・レベル・BPM・ノーツ数で検索できる汎用テーブルです。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>フリーワード検索:</strong> 曲名・アーティスト名で絞り込めます。</li>
        <li><strong>難易度 / レベルフィルタ:</strong> BEGINNER 〜 LEGGENDARIA、☆1 〜 ☆12 の組み合わせで絞り込めます。</li>
        <li><strong>ソート:</strong> タイトル / レベル / ノーツ数 / BPM 順で並び替えできます。</li>
        <li><strong>textage プレビュー:</strong> 各行から textage.cc の譜面プレビューに直接ジャンプできます。</li>
      </ul>
    </section>

    <!-- 16. 公開共有リンク -->
    <section id="share" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">16</span>
        公開共有リンク
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        自分のスコアやプロフィールを、ログインしていない第三者にも見せたいときに使うトークン URL の仕組みです。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>発行:</strong> プロフィール画面の「共有リンク」セクションから、Dashboard / Scores / History / Profile のいずれかのスコープを指定して URL を発行します。</li>
        <li><strong>URL:</strong> 発行されたリンクは <code>/share/&lt;トークン&gt;</code> という形式で、ログイン不要で閲覧できます。</li>
        <li><strong>無効化:</strong> プロフィールから個別の共有リンクを無効化できます。一度無効化したトークンは <code>410 Gone</code> の専用ページに切り替わります。</li>
        <li><strong>SEO 除外:</strong> 共有ページには noindex メタタグが付与され、検索エンジンにインデックスされません。</li>
      </ul>
    </section>

    <!-- 17. 攻略ガイド -->
    <section id="guide" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">17</span>
        攻略ガイド
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        IIDX の上達に役立つ実戦的なコラム集です。CSV の取り込み手順から、皿対策、AAA 狙い、地力上げのロードマップなどを記事単位で掲載しています。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>一覧:</strong> <a href="/guide" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">/guide</a> から全記事の一覧にアクセスできます。</li>
        <li><strong>個別記事:</strong> 各記事は <code>/guide/&lt;slug&gt;</code> の安定した URL を持ち、SNS で共有できます。</li>
      </ul>
    </section>

    <!-- 18. 更新履歴 -->
    <section id="changelog" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">18</span>
        更新履歴
      </h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        アプリ本体と非公式難易度表それぞれの改訂履歴を、タブ切替で確認できる画面です。
      </p>
      <ul class="list-disc pl-6 space-y-1.5 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>アプリ更新タブ:</strong> 機能追加・改善・バグ修正をバージョン単位で時系列表示します。</li>
        <li><strong>難易度改訂タブ:</strong> <code>data/difficulty_revisions.json</code> から、非公式難易度表の追加曲・ランク変更を版数ごとに表示します。</li>
      </ul>
    </section>

    <!-- 19. その他 -->
    <section id="misc" class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 space-y-5">
      <h2 class="text-2xl font-black flex items-center gap-3">
        <span class="text-blue-500 font-mono text-base">19</span>
        その他の便利機能
      </h2>
      <ul class="list-disc pl-6 space-y-2 text-slate-700 dark:text-slate-300 leading-relaxed">
        <li><strong>ダークモード:</strong> サイドバー下部のトグルでライト / ダークを切り替えられます。OS のテーマ設定に追従するモードもあります。</li>
        <li><strong>言語切替:</strong> サイドバー下部の言語ボタンで日本語 / 英語 / 韓国語をリアルタイムに切り替えられます。</li>
        <li><strong>PWA インストール:</strong> 対応ブラウザでは「ホーム画面に追加 / インストール」が可能で、アプリのように立ち上げられます。スコア未登録時のメイン画面にインストール案内バナーが表示されます。</li>
        <li><strong>プッシュ通知:</strong> フレンド申請の受信時にブラウザのプッシュ通知が届きます。プロフィールから通知の有効 / 無効を切り替えられます。</li>
        <li><strong>段位クイズ:</strong> サイドバーの段位クイズウィジェットから、Beat-Tier 段位の判定問題に挑戦できます。</li>
        <li><strong>コマンドパレット (Ctrl/⌘ + K):</strong> 画面内のどこからでも、曲・タブ・ユーザーを横断検索できる呼び出しメニューを開けます。</li>
      </ul>
    </section>

    <!-- 末尾アンカー -->
    <section class="text-center text-sm text-slate-500 dark:text-slate-400 pt-4">
      使い方が分からないときは <a href="/contact" class="text-blue-600 dark:text-blue-400 font-bold hover:underline">お問い合わせ</a> からご連絡ください。
    </section>
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

/* 本文中の <code> はインライン強調用。darkモード両方で読めるよう色をトーンダウン気味に固定。 */
section :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  background: rgba(99, 102, 241, 0.12);
  padding: 0.1rem 0.4rem;
  border-radius: 0.35rem;
  font-size: 0.85em;
}
</style>
