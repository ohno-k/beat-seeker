/**
 * prerender.mjs
 * ビルド後に /about と /terms の静的HTMLを生成するスクリプト。
 * クローラーがJavaScriptを実行しなくても内容を読めるようにする。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.resolve(__dirname, '../dist');
const indexHtml = fs.readFileSync(path.join(distDir, 'index.html'), 'utf-8');

// ページ定義: URL → { title, description, bodyHtml }
const pages = {
  about: {
    title: 'beat-seekerとは？ | beat-seeker',
    description: 'beat-seekerは、beatmania IIDXのスコアデータを可視化・分析する無料Webアプリです。使い方・主な機能・よくある質問を掲載しています。',
    bodyHtml: `
<article style="max-width:800px;margin:0 auto;padding:24px 16px;font-family:sans-serif;color:#1e293b;">
  <h1 style="font-size:2rem;font-weight:900;margin-bottom:8px;">beat-seeker とは？</h1>
  <p style="color:#475569;margin-bottom:32px;">beat-seekerは、beatmania IIDX のスコアデータを<strong>可視化・分析</strong>するための無料Webアプリケーションです。公式サイトからダウンロードしたCSVファイルをアップロードするだけで、あなたのプレイデータを美しいグラフやランクで確認できます。</p>

  <h2 style="font-size:1.4rem;font-weight:900;margin-bottom:16px;">使い方（3ステップ）</h2>
  <ol style="padding-left:24px;line-height:2;">
    <li><strong>CSVをアップロード</strong> — 公式サイトからダウンロードしたCSVファイルをドラッグ＆ドロップまたはクリックでアップロード。</li>
    <li><strong>自動で分析</strong> — ランク・クリア率・スコアレートなどが自動で計算・可視化されます。</li>
    <li><strong>成長を記録</strong> — アカウント登録してログインすると、スコアがクラウドに保存され、過去との比較や成長の確認ができます。</li>
  </ol>

  <h2 style="font-size:1.4rem;font-weight:900;margin-top:32px;margin-bottom:16px;">主な機能</h2>
  <ul style="padding-left:24px;line-height:2;">
    <li><strong>ダッシュボード</strong> — クリア状況をドーナツチャートで一目で把握。クリア率・AAA率・MAX-率の3つの指標で表示。</li>
    <li><strong>Beat-Tier ランキングシステム</strong> — 非公式難易度表の楽曲を対象に、スコアレートの1.5乗×譜面の重みでBeat-PTを算出。Beginner〜Legendの段位で地力を可視化。</li>
    <li><strong>スコア一覧テーブル</strong> — 全楽曲のスコアを一覧表示。レベル・難易度・DJレベルでのフィルターや、曲名・アーティスト名での検索に対応。</li>
    <li><strong>アップロード差分レポート</strong> — CSVを更新するたびにスコアアップした曲・クリアランプ更新・Beat-Tier変動を一覧表示。</li>
    <li><strong>アップロード履歴</strong> — 過去の全アップロード履歴を確認可能。各回のスコア更新数・クリアランプ更新数・Beat-Tier変動を表示。</li>
    <li><strong>クラウド保存 & ログイン</strong> — IIDX IDとパスワードでアカウント登録後、スコアデータがクラウドに自動保存されます。</li>
    <li><strong>フレンド機能</strong> — フレンド申請・承認を行い、フレンドのスコアと自分のスコアを比較できます。</li>
    <li><strong>ダークモード対応</strong> — ライトモード・ダークモードを切り替えられます。</li>
  </ul>

  <h2 style="font-size:1.4rem;font-weight:900;margin-top:32px;margin-bottom:16px;">よくある質問</h2>
  <dl style="line-height:1.8;">
    <dt style="font-weight:bold;margin-top:16px;">Q. CSVファイルはどこで入手できますか？</dt>
    <dd style="margin-left:16px;color:#475569;">IIDX公式サイトのプレーデータ画面からCSVをダウンロードできます。ログイン後、プレーデータの画面を開き、CSV出力ボタンを押してください。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. ログインしないと使えませんか？</dt>
    <dd style="margin-left:16px;color:#475569;">いいえ、ログインなしでもCSVの読み込みとダッシュボード・スコア一覧の表示は可能です。データのクラウド保存やアップロード履歴の確認にはアカウント登録後のログインが必要です。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. Beat-Tierとは何ですか？</dt>
    <dd style="margin-left:16px;color:#475569;">beat-seeker独自のスキル評価システムです。非公式難易度表に掲載されている楽曲のスコアレートを元にポイントを算出し、上位100曲の合計によってBeginner〜Legendのランクが決まります。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. スコアデータは安全ですか？</dt>
    <dd style="margin-left:16px;color:#475569;">はい。運営者は取得したスコアデータを個人の利益となるような利用は一切いたしません。詳しくは利用規約・プライバシーポリシーをご確認ください。</dd>
  </dl>

  <p style="margin-top:32px;"><a href="/" style="color:#2563eb;">← アプリに戻る</a></p>
</article>`,
  },

  terms: {
    title: '利用規約・プライバシーポリシー | beat-seeker',
    description: 'beat-seekerの利用規約、免責事項、プライバシーポリシー、Google AdSenseに関するCookieポリシー、およびお問い合わせ先を掲載しています。',
    bodyHtml: `
<article style="max-width:800px;margin:0 auto;padding:24px 16px;font-family:sans-serif;color:#1e293b;">
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:24px;">利用規約およびプライバシーポリシー</h1>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">利用規約 (Terms of Service)</h2>
  <p>本サービス（beat-seeker）をご利用いただくにあたり、以下の事項をお守りください。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスは、ユーザーがアップロードしたスコアデータを可視化し、ユーザー自身のプレイ体験向上を目的として提供されています。</li>
    <li><strong>運営者は、取得したユーザーのスコアデータを個人の利益となるような利用（データの販売や不正な商用利用など）はいたしません。</strong></li>
    <li>ユーザーは、本サービスを法令や公序良俗に反する目的で利用してはなりません。</li>
    <li>他のユーザーへの迷惑行為や、サーバーへ過度な負荷をかける行為を禁止します。</li>
    <li>本規約に悪質に違反した場合、運営者は事前の通知なくアカウントの停止またはデータの削除を行う権利を有します。</li>
  </ul>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">免責事項 (Disclaimer)</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスは現状有姿（As-Is）で、基本ベータ版として提供されます。機能の完全性、正確性、有用性について、いかなる保証もいたしません。</li>
    <li>本サービスの利用やデータの損失によってユーザーに生じたあらゆる損害について、運営者は一切の責任を負いません。</li>
    <li>運営者の判断により、事前の予告なく本サービスの仕様変更、機能追加、またはサービスの提供を一時的または恒久的に停止する場合があります。</li>
    <li>アップロードされたデータはシステム上保存されますが、完全なバックアップを保証するものではありません。</li>
  </ul>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">プライバシーポリシー (Privacy Policy)</h2>
  <p>運営者は、本サービスにおいて取得するユーザー情報の取り扱いについて以下の通り定めます。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li><strong>取得する情報:</strong> ユーザーが登録したIIDX ID・パスワード（ハッシュ化して保存）・ユーザー名・段位・アリーナランク等のプロフィール情報、およびユーザーがアップロードした全スコアデータ。</li>
    <li><strong>利用目的:</strong> 本サービスの中核機能（スコアの可視化・管理）の提供、および本サービス不具合時のデバッグや改善のためにのみ利用します。</li>
    <li><strong>第三者への提供:</strong> 取得した情報を、法令等に基づく場合を除き、ユーザーの同意なく第三者に提供することはありません。</li>
    <li><strong>データ保護:</strong> ユーザーのデータは適切に保護・管理されますが、インターネット上のデータ転送の性質上、100%の安全保障はできません。</li>
  </ul>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">広告の配信について（Google AdSense）</h2>
  <p>本サービスでは、第三者配信の広告サービス「Google AdSense（グーグルアドセンス）」を利用しています。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>Google AdSenseは、ユーザーの興味に応じた広告を表示するために、<strong>Cookie（クッキー）</strong>を使用します。Cookieはユーザーのコンピュータを識別しますが、個人を特定するものではありません。</li>
    <li>Google AdSenseにおけるCookieの使用により、Googleおよびそのパートナーはユーザーのアクセス情報に基づいて適切な広告を表示します。</li>
    <li>Cookieを無効にするには、ブラウザの設定からCookieを無効にするか、<a href="https://www.google.com/settings/ads" style="color:#2563eb;">Google広告設定ページ</a>にてパーソナライズ広告を無効にしてください。</li>
    <li>Google AdSenseの詳細については、<a href="https://policies.google.com/technologies/ads?hl=ja" style="color:#2563eb;">Googleのポリシーと規約</a>をご確認ください。</li>
  </ul>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">アクセス解析について（Google Analytics）</h2>
  <p>本サービスではサービス改善のためにGoogle Analyticsを利用しています。Google Analyticsはトラフィックデータの収集のためにCookieを使用しています。このトラフィックデータは匿名で収集されており、個人を特定するものではありません。詳細は<a href="https://policies.google.com/privacy?hl=ja" style="color:#2563eb;">Googleのプライバシーポリシー</a>をご確認ください。</p>

  <h2 style="font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;">お問い合わせ (Contact)</h2>
  <p>本サービスに関するご意見・ご要望・お問い合わせは、下記の手段にてご連絡ください。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li><strong>メール:</strong> <a href="mailto:beat.seeker.iidx@gmail.com" style="color:#2563eb;">beat.seeker.iidx@gmail.com</a></li>
    <li><strong>X（旧Twitter）:</strong> <a href="https://x.com/beat_seeker_" style="color:#2563eb;">@beat_seeker_</a> へのDMまたはリプライ</li>
  </ul>

  <p style="margin-top:16px;font-size:0.8rem;color:#64748b;">制定日: 2026年3月5日 / 最終更新: 2026年3月15日</p>
  <p style="margin-top:32px;"><a href="/" style="color:#2563eb;">← アプリに戻る</a></p>
</article>`,
  },
};

// 各ページのHTMLを生成して dist/<page>/index.html に書き出す
for (const [slug, { title, description, bodyHtml }] of Object.entries(pages)) {
  const outDir = path.join(distDir, slug);
  fs.mkdirSync(outDir, { recursive: true });

  // index.html の <title> と <meta name="description"> を更新し、
  // <div id="app"> の中に静的コンテンツを挿入する
  let html = indexHtml
    .replace(
      /<title>.*?<\/title>/,
      `<title>${title}</title>`
    )
    .replace(
      /<meta name="description"[^>]*>/,
      `<meta name="description" content="${description}" />`
    )
    .replace(
      '<div id="app"></div>',
      `<div id="app">${bodyHtml}</div>`
    );

  fs.writeFileSync(path.join(outDir, 'index.html'), html, 'utf-8');
  console.log(`✓ dist/${slug}/index.html を生成しました`);
}

console.log('プリレンダリング完了');
