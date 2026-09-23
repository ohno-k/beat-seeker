/**
 * prerender.mjs
 * ビルド後に主要な公開ページの静的 HTML を生成するスクリプト。
 *
 * 出力対象:
 *  - / (ランディング)
 *  - /about
 *  - /terms
 *  - /privacy-policy
 *  - /contact
 *  - /difficulty-table
 *
 * クローラー (Googlebot / AdSense Bot / SNS の OGP プレビュー) が JavaScript を
 * 実行しなくても、ページの本文・タイトル・説明文・正規 URL を読み取れるようにする。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const distDir = path.resolve(__dirname, '../dist');
const indexHtml = fs.readFileSync(path.join(distDir, 'index.html'), 'utf-8');

const SITE_URL = 'https://beat-seeker.com';
const SITE_NAME = 'beat-seeker';

// 難易度表のデータをビルド時に読み込む
const diffTableJson = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, '../src/data/difficulty_table.json'), 'utf-8')
);

const articleStyle = 'max-width:900px;margin:0 auto;padding:24px 16px;font-family:sans-serif;color:#1e293b;line-height:1.85;';
const h2Style = 'font-size:1.3rem;font-weight:bold;color:#2563eb;margin-top:32px;margin-bottom:12px;';
const linkStyle = 'color:#2563eb;';

// ============================================================
// 難易度表 HTML
// ============================================================
function buildDiffTableHtml(ranks) {
  const numericRanks = ranks
    .filter(r => !isNaN(parseFloat(r.rank)))
    .slice()
    .reverse();

  const rankColors = (rank) => {
    const v = parseFloat(rank);
    if (v >= 13.0) return '#dc2626';
    if (v >= 12.5) return '#f97316';
    if (v >= 12.0) return '#f59e0b';
    if (v >= 11.5) return '#10b981';
    return '#3b82f6';
  };

  const rankSections = numericRanks.map(r => {
    const songItems = r.songs.map(s => {
      const isL = s.endsWith('[L]');
      const name = isL ? s.slice(0, -3) : s;
      const badge = isL ? `<span style="font-size:0.7rem;font-weight:bold;color:#9333ea;background:#f3e8ff;padding:1px 5px;border-radius:4px;margin-right:4px;">L</span>` : '';
      return `<li style="padding:4px 0;border-bottom:1px solid #f1f5f9;font-size:0.9rem;">${badge}${name}</li>`;
    }).join('');

    return `
<section style="margin-bottom:16px;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
  <div style="padding:14px 20px;background:#f8fafc;display:flex;align-items:center;gap:12px;">
    <span style="font-size:1.5rem;font-weight:900;color:${rankColors(r.rank)};">☆${r.rank}</span>
    <span style="font-size:0.85rem;color:#64748b;">${r.songs.length}曲</span>
  </div>
  <ul style="padding:8px 20px 12px;list-style:none;margin:0;column-count:2;column-gap:24px;">
    ${songItems}
  </ul>
</section>`;
  }).join('');

  const totalSongs = ranks.reduce((acc, r) => acc + r.songs.length, 0);

  return `
<article style="${articleStyle}">
  <h1 style="font-size:2rem;font-weight:900;margin-bottom:8px;">beatmania IIDX 非公式難易度表</h1>
  <p style="color:#64748b;margin-bottom:8px;">全${totalSongs}曲 / ${numericRanks.length}段階（☆11.0〜☆13.0）</p>
  <p style="color:#475569;margin-bottom:32px;">
    beatmania IIDXの高難度譜面（☆11〜☆13）を独自の難易度評価で細かく分類した非公式難易度表です。
    各ランクの楽曲リストは投票や管理者判断をもとに随時更新されます。
    スコアと組み合わせてBEAT-PTを算出し、プレイヤーの地力をBeginner〜Legendのランクで可視化します。
  </p>
  ${rankSections}
  <p style="margin-top:32px;"><a href="/" style="${linkStyle}">← アプリトップへ</a></p>
</article>`;
}

// ============================================================
// ランディング (/) HTML
// ============================================================
function buildLandingHtml() {
  return `
<article style="${articleStyle}">
  <header style="text-align:center;padding:48px 16px;background:linear-gradient(135deg,#2563eb 0%,#7c3aed 100%);color:#fff;border-radius:24px;margin-bottom:40px;">
    <p style="font-size:0.9rem;opacity:0.85;letter-spacing:0.1em;margin-bottom:8px;">beatmania IIDX スコア管理ツール</p>
    <h1 style="font-size:2.4rem;font-weight:900;margin-bottom:16px;line-height:1.3;">IIDX のスコアを、もっと深く分析しよう。</h1>
    <p style="font-size:1.05rem;line-height:1.8;max-width:680px;margin:0 auto 24px;opacity:0.95;">
      beat-seeker は、e-amusement GATE の公式 CSV をアップロードするだけで、クリア率・AAA 率・Beat-Tier ランクを自動で計算してくれる無料 Web アプリです。
      ログイン不要で使えて、フレンドとのスコア比較・難易度表・ランキング機能まで揃っています。
    </p>
    <p>
      <a href="/dashboard" style="display:inline-block;padding:12px 28px;background:#fff;color:#2563eb;font-weight:bold;border-radius:12px;text-decoration:none;margin-right:8px;">アプリを使う（ログイン不要）</a>
    </p>
  </header>

  <section style="margin-bottom:40px;">
    <h2 style="${h2Style}">主な機能</h2>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px;">
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">ダッシュボード</h3>
        <p style="font-size:0.9rem;color:#475569;">クリアタイプ・DJ レベル・スコア帯をドーナツチャートで一望。レベル別フィルタも対応。</p>
      </div>
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">Beat-Tier ランク</h3>
        <p style="font-size:0.9rem;color:#475569;">非公式難易度表に基づき、上位 100 曲のスコアレートから地力段位を算出。</p>
      </div>
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">差分レポート</h3>
        <p style="font-size:0.9rem;color:#475569;">CSV を再アップロードすると、前回からの伸びを 1 画面で可視化。</p>
      </div>
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">楽曲ランキング</h3>
        <p style="font-size:0.9rem;color:#475569;">譜面ごとの平均スコアやトップランカーの記録を未ログインでも閲覧できます。</p>
      </div>
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">フレンド比較</h3>
        <p style="font-size:0.9rem;color:#475569;">フレンド申請を承認すれば、お互いのスコアと地力を並べて比較できます。</p>
      </div>
      <div style="padding:16px;border:1px solid #e2e8f0;border-radius:12px;background:#fff;">
        <h3 style="font-weight:bold;margin-bottom:6px;">PWA / ダークモード</h3>
        <p style="font-size:0.9rem;color:#475569;">スマホのホームに追加してオフライン対応、画面はライト・ダーク両対応。</p>
      </div>
    </div>
  </section>

  <section style="margin-bottom:40px;background:#f8fafc;padding:24px;border-radius:16px;border:1px solid #e2e8f0;">
    <h2 style="${h2Style}">beat-seeker について</h2>
    <p style="color:#475569;margin-bottom:8px;">beat-seeker は IIDX プレイヤー個人によって運営されている、ファンメイドの非公式スコア管理ツールです。営利目的ではなく、KONAMI とは一切関係ありません。</p>
    <p style="color:#475569;margin-bottom:8px;">スコアデータはユーザー自身が公式 CSV からアップロードしたものに限定し、個人を特定しない範囲で集計・分析を行います。データの取り扱いについては<a href="/privacy-policy" style="${linkStyle}">プライバシーポリシー</a>をご確認ください。</p>
    <p style="font-size:0.8rem;color:#94a3b8;margin-top:16px;">beatmania IIDX は株式会社コナミアミューズメントの登録商標です。本サービスは KONAMI 公式とは一切関係のない非公式のファンメイドツールです。</p>
  </section>

  <nav style="display:flex;gap:16px;flex-wrap:wrap;justify-content:center;padding-top:24px;border-top:1px solid #e2e8f0;color:#64748b;font-size:0.9rem;">
    <a href="/about" style="${linkStyle}">アプリについて</a>
    <a href="/difficulty-table" style="${linkStyle}">非公式難易度表</a>
    <a href="/ranking" style="${linkStyle}">ランキング</a>
    <a href="/terms" style="${linkStyle}">利用規約</a>
    <a href="/privacy-policy" style="${linkStyle}">プライバシーポリシー</a>
    <a href="/contact" style="${linkStyle}">お問い合わせ</a>
  </nav>
</article>`;
}

// ============================================================
// プライバシーポリシー HTML
// ============================================================
function buildPrivacyPolicyHtml() {
  return `
<article style="${articleStyle}">
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:24px;">プライバシーポリシー (Privacy Policy)</h1>

  <h2 style="${h2Style}">取得する情報と利用目的</h2>
  <p>運営者は、本サービスにおいて取得するユーザー情報の取り扱いについて以下の通り定めます。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li><strong>取得する情報:</strong> (1) ユーザーが登録した IIDX ID・パスワード（ハッシュ化して保存）・ユーザー名・段位・アリーナランク等のプロフィール情報、(2) パスワード再設定用に任意で登録するメールアドレス、(3) ユーザーが登録したスコアデータ（CSV・ブックマークレット）およびリザルト画像、(4) お問い合わせチャット等でユーザーが送信した内容、(5) サーバー運用・不正防止のために自動的に記録されるアクセスログ（IP アドレス・ブラウザ情報・アクセス日時等）。</li>
    <li><strong>利用目的:</strong> 本サービスの機能（スコアの可視化・管理、ランキング、フレンド、リーグ、大会等）の提供、パスワード再設定やお問い合わせへの返信等の連絡、不具合の調査と改善、不正利用の防止、および個人を特定しない形での統計情報（譜面ごとの平均スコア・難易度表・譜面分析・スコア予測など）の作成と公開に利用します。</li>
    <li><strong>公開される情報:</strong> ユーザー名・IIDX ID・スコア・クリア状況・BEAT-TIER 等のプレイ情報は、ランキング・リーグ・フレンド機能・大会機能・ユーザーが発行した共有 URL などを通じて、他のユーザーや未ログインの第三者に表示されることがあります。公開範囲はプロフィールの公開設定（全公開／フレンドのみ／非公開）で変更できます。メールアドレスとパスワードが公開されることはありません。</li>
    <li><strong>共有 URL:</strong> ユーザーが発行した共有 URL は、URL を知っている人であれば誰でも閲覧できます。共有ページは検索エンジンにインデックスされないよう設定していますが、URL の管理はユーザー自身の責任で行ってください。共有 URL はいつでも失効させることができます。</li>
    <li><strong>第三者への提供:</strong> 取得した情報を、法令等に基づく場合を除き、ユーザーの同意なく第三者に提供することはありません。ただし、本サービスの運営に必要な範囲で以下の外部サービスを利用しており、その目的に必要な最低限のデータがこれらのサービス上で処理・保存されます。
      <ul style="padding-left:24px;line-height:1.8;margin-top:4px;list-style-type:circle;">
        <li>サーバー・データベース: Render（米国）— アプリケーションの実行とデータの保存</li>
        <li>画像ストレージ: Cloudflare R2 — リザルト画像の保存</li>
        <li>アクセス解析: Google Analytics — 匿名の利用状況の計測</li>
        <li>メール送信: Gmail（Google）— パスワード再設定メール等の送信</li>
        <li>サポーター支援: Ko-fi — 支援の決済処理。カード番号等の決済情報は Ko-fi が管理し、運営者には支援の通知（Ko-fi 上の表示名・支援額・メッセージ等）のみが送られます</li>
      </ul>
    </li>
    <li><strong>データ保護:</strong> ユーザーのデータは適切に保護・管理されますが、インターネット上のデータ転送の性質上、100%の安全保障はできません。</li>
    <li><strong>保存期間と削除:</strong> ユーザーのデータはアカウントが存在する間保存されます。アカウントやデータの削除をご希望の場合は、<a href="/contact" style="${linkStyle}">お問い合わせ窓口</a>までご連絡ください。削除後も、すでに個人を特定できない形で集計された統計情報（平均スコア等）からは除外されない場合があります。</li>
    <li><strong>開示・訂正等の請求:</strong> ご本人からの保有個人データの開示・訂正・利用停止等のご請求には、本人確認のうえ法令に従って対応します。お問い合わせ窓口までご連絡ください。</li>
  </ul>

  <h2 style="${h2Style}">広告の配信について</h2>
  <p>現在、本サービスでは広告を配信しておらず、Google AdSense 等の広告配信サービスへのデータ送信も行っていません。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>将来、広告配信を導入する場合は、導入前に本ポリシーを改定し、使用する Cookie の種類やパーソナライズ広告の無効化方法をお知らせします。</li>
  </ul>

  <h2 style="${h2Style}">アクセス解析について（Google Analytics）</h2>
  <p>本サービスではサービス改善のために Google Analytics を利用しています。Google Analytics はトラフィックデータの収集のために Cookie を使用していますが、これは匿名で収集されており個人を特定するものではありません。詳細は <a href="https://policies.google.com/privacy?hl=ja" style="${linkStyle}">Google のプライバシーポリシー</a>をご確認ください。</p>

  <h2 style="${h2Style}">Cookie・ローカルストレージの使用について</h2>
  <p>本サービスでは、ログイン状態の維持やアクセス解析、表示設定の保存のために Cookie およびブラウザのローカルストレージを使用しています。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>認証情報: ログイン時に発行される認証トークンをブラウザのローカルストレージに保存し、ログイン状態の維持に使用します。ログアウトすると削除されます。ログインしない場合は使用されません。</li>
    <li>解析 Cookie: Google Analytics がページの訪問数や滞在時間を匿名で計測するために使用します。</li>
    <li>設定情報: 表示言語・ダークモード・各種表示設定をブラウザのローカルストレージに保存します。</li>
  </ul>
  <p style="font-size:0.85rem;color:#64748b;">ブラウザの設定で Cookie やローカルストレージを無効にすると、ログインなど一部の機能が利用できなくなります。</p>

  <h2 style="${h2Style}">ポリシーの変更・準拠法</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>運営者は必要に応じて本ポリシーおよび利用規約を改定することがあります。重要な変更はサービス内のお知らせ等で告知し、改定後に本サービスを利用した場合は改定後の内容に同意したものとみなします。</li>
    <li>本ポリシーは日本法に準拠します。本サービスに関して紛争が生じた場合は、運営者の住所地を管轄する裁判所を第一審の専属的合意管轄裁判所とします。</li>
  </ul>

  <p style="margin-top:32px;font-size:0.85rem;color:#94a3b8;">制定日: 2026年3月5日 / 最終更新: 2026年9月12日</p>
  <p style="margin-top:16px;"><a href="/" style="${linkStyle}">← トップへ戻る</a> / <a href="/terms" style="${linkStyle}">利用規約</a> / <a href="/contact" style="${linkStyle}">お問い合わせ</a></p>
</article>`;
}

// ============================================================
// お問い合わせ HTML
// ============================================================
function buildContactHtml() {
  return `
<article style="${articleStyle}">
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:24px;">お問い合わせ (Contact)</h1>

  <p>本サービスに関するご意見・ご要望・不具合報告は、以下のいずれかの手段でご連絡ください。お問い合わせ内容によっては、回答にお時間をいただく場合や、回答を差し控える場合があります。</p>

  <h2 style="${h2Style}">連絡手段</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li><strong>メール:</strong> <a href="mailto:beat.seeker.iidx@gmail.com" style="${linkStyle}">beat.seeker.iidx@gmail.com</a></li>
    <li><strong>X (旧 Twitter):</strong> <a href="https://x.com/beat_seeker_" style="${linkStyle}">@beat_seeker_</a></li>
  </ul>

  <h2 style="${h2Style}">お問い合わせの種類</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>機能要望: こういう機能が欲しい、こういう表示が見たいといったご要望</li>
    <li>不具合報告: 表示崩れ・データの不整合・エラー画面など</li>
    <li>その他: サポーター登録、データ削除依頼など</li>
  </ul>

  <h2 style="${h2Style}">運営者情報</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>運営者: beat-seeker 開発者 (個人)</li>
    <li>所在地: 日本国内</li>
    <li>サービス開始日: 2026年3月5日</li>
    <li>運営目的: beatmania IIDX プレイヤーが自分のスコアデータをより便利に分析・共有できる無料ツールを提供すること</li>
  </ul>

  <p style="margin-top:32px;font-size:0.8rem;color:#94a3b8;">beatmania IIDX は株式会社コナミアミューズメントの登録商標です。本サービスは KONAMI 公式とは一切関係のない非公式のファンメイドツールです。</p>
  <p style="margin-top:16px;"><a href="/" style="${linkStyle}">← トップへ戻る</a> / <a href="/terms" style="${linkStyle}">利用規約</a> / <a href="/privacy-policy" style="${linkStyle}">プライバシーポリシー</a></p>
</article>`;
}

// ============================================================
// 既存ページのコンテンツ
// ============================================================
const aboutBody = `
<article style="${articleStyle}">
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
    <dd style="margin-left:16px;color:#475569;">IIDX公式サイトのプレーデータ画面からCSVをダウンロードできます。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. ログインしないと使えませんか？</dt>
    <dd style="margin-left:16px;color:#475569;">いいえ、ログインなしでもCSVの読み込みとダッシュボード・スコア一覧の表示は可能です。データのクラウド保存やアップロード履歴の確認にはアカウント登録後のログインが必要です。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. Beat-Tierとは何ですか？</dt>
    <dd style="margin-left:16px;color:#475569;">beat-seeker独自のスキル評価システムです。非公式難易度表に掲載されている楽曲のスコアレートを元にポイントを算出し、上位100曲の合計によってBeginner〜Legendのランクが決まります。</dd>

    <dt style="font-weight:bold;margin-top:16px;">Q. スコアデータは安全ですか？</dt>
    <dd style="margin-left:16px;color:#475569;">はい。運営者は取得したスコアデータを個人の利益となるような利用は一切いたしません。詳しくは<a href="/privacy-policy" style="${linkStyle}">プライバシーポリシー</a>をご確認ください。</dd>
  </dl>

  <p style="margin-top:32px;"><a href="/" style="${linkStyle}">← トップへ戻る</a></p>
</article>`;

const termsBody = `
<article style="${articleStyle}">
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:24px;">利用規約</h1>

  <h2 style="${h2Style}">利用規約 (Terms of Service)</h2>
  <p>本サービス（beat-seeker）をご利用いただくにあたり、以下の事項をお守りください。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスは、ユーザーがアップロードしたスコアデータを可視化し、ユーザー自身のプレイ体験向上を目的として提供されています。</li>
    <li><strong>運営者は、取得したユーザーのスコアデータを個人の利益となるような利用（データの販売や不正な商用利用など）はいたしません。</strong></li>
    <li>アカウントはご自身のプレイデータを管理する目的で作成し、ログイン情報はユーザー自身の責任で管理してください。他人のプレイデータを本人の許可なく登録する行為や、1人で複数のアカウントを作成してランキング・リーグ等を操作する行為を禁止します。</li>
    <li>ユーザーは、本サービスを法令や公序良俗に反する目的で利用してはなりません。</li>
    <li>他のユーザーへの迷惑行為（チャット・大会等での嫌がらせを含む）や、サーバーへ過度な負荷をかける行為（不正なスクリプトによるアクセス、自動化されたスクレイピング等）を禁止します。</li>
    <li>改ざんした CSV やリザルト画像など、実際のプレイ結果と異なるスコアデータを登録する行為を禁止します。ランキング・リーグ・難易度表は全ユーザーのデータを元に算出されるため、不正なデータは他のユーザーにも影響します。</li>
    <li>お問い合わせチャット・大会・投票など、ユーザーが本サービス上に投稿した内容は運営者が閲覧できます。法令・公序良俗に反する投稿や他者の権利を侵害する投稿は、事前の通知なく削除することがあります。</li>
    <li>本規約に悪質に違反した場合、運営者は事前の通知なくアカウントの停止またはデータの削除を行う権利を有します。</li>
  </ul>

  <h2 style="${h2Style}">公式サイトからのデータ取得について</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>ブックマークレットおよびアプリによるデータ取得は、ユーザー自身がログインしている e-amusement GATE のセッション内で、ユーザーのブラウザ上で動作します。KONAMI ID やパスワードが本サービスに送信されることはありません。</li>
    <li>e-amusement GATE の利用は KONAMI の定める規約に従い、ユーザー自身の責任で行ってください。公式サイトの仕様変更等により、データ取得機能が予告なく利用できなくなる場合があります。</li>
  </ul>

  <h2 style="${h2Style}">サポーター機能について</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>サポーター登録は Ko-fi を通じた任意の支援であり、対価を伴う商品・サービスの販売ではありません。支援金の返金には応じられません。</li>
    <li>サポーター向け機能は開発中の機能を先行してお試しいただくものであり、内容の変更・一般公開・終了を予告なく行うことがあります。</li>
    <li>決済は Ko-fi が処理します。運営者はクレジットカード番号等の決済情報を受け取りません。</li>
  </ul>

  <h2 style="${h2Style}">知的財産権</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスのプログラム・デザイン、BEAT-TIER や RATE-TIER 等の独自指標、および非公式難易度表に関する権利は運営者に帰属します。</li>
    <li>楽曲名・アーティスト名等のゲームに関する情報は、株式会社コナミアミューズメントほか各権利者に帰属します。本サービスは KONAMI 公式とは一切関係のない非公式のファンメイドツールです。</li>
    <li>ユーザーが本サービスに登録・投稿した内容（スコアデータ・リザルト画像・チャット等）の権利はユーザーに帰属しますが、運営者は本サービスの提供・改善・統計の作成に必要な範囲で、これを無償で利用できるものとします。</li>
  </ul>

  <h2 style="${h2Style}">免責事項 (Disclaimer)</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスは現状有姿（As-Is）で、基本ベータ版として提供されます。機能の完全性、正確性、有用性について、いかなる保証もいたしません。</li>
    <li>本サービスの利用やデータの損失によってユーザーに生じたあらゆる損害について、運営者は一切の責任を負いません。</li>
    <li>運営者の判断により、事前の予告なく本サービスの仕様変更、機能追加、またはサービスの提供を一時的または恒久的に停止する場合があります。</li>
    <li>アップロードされたデータはシステム上保存されますが、完全なバックアップを保証するものではありません。</li>
    <li>BEAT-TIER・難易度表・スコア予測などの指標は、ユーザーのデータを統計的に処理して算出した参考値であり、その正確性を保証するものではありません。</li>
    <li>本サービスからリンクする外部サイト（Ko-fi・X 等）の内容について、運営者は責任を負いません。</li>
  </ul>

  <h2 style="${h2Style}">規約の変更・準拠法</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>運営者は必要に応じて本規約およびプライバシーポリシーを改定することがあります。重要な変更はサービス内のお知らせ等で告知し、改定後に本サービスを利用した場合は改定後の内容に同意したものとみなします。</li>
    <li>未成年の方は、保護者の同意を得たうえで本サービスをご利用ください。</li>
    <li>本規約は日本法に準拠します。本サービスに関して紛争が生じた場合は、運営者の住所地を管轄する裁判所を第一審の専属的合意管轄裁判所とします。</li>
  </ul>

  <p style="margin-top:32px;"><a href="/privacy-policy" style="${linkStyle}">プライバシーポリシーへ</a> / <a href="/contact" style="${linkStyle}">お問い合わせ</a></p>
  <p style="margin-top:16px;font-size:0.85rem;color:#94a3b8;">制定日: 2026年3月5日 / 最終更新: 2026年9月12日</p>
  <p style="margin-top:16px;"><a href="/" style="${linkStyle}">← トップへ戻る</a></p>
</article>`;

// ============================================================
// JSON-LD 構造化データ
// ============================================================
function buildJsonLd(pageType, title, description, url) {
  if (pageType === 'website') {
    return {
      "@context": "https://schema.org",
      "@type": "WebSite",
      "name": SITE_NAME,
      "url": url,
      "description": description,
      "potentialAction": {
        "@type": "SearchAction",
        "target": `${SITE_URL}/?q={search_term_string}`,
        "query-input": "required name=search_term_string"
      }
    };
  }
  if (pageType === 'article') {
    return {
      "@context": "https://schema.org",
      "@type": "Article",
      "headline": title,
      "description": description,
      "url": url,
      "author": { "@type": "Organization", "name": SITE_NAME },
      "publisher": { "@type": "Organization", "name": SITE_NAME }
    };
  }
  return null;
}

// ============================================================
// ページ定義
// ============================================================
const pages = {
  '': {
    title: 'beat-seeker | beatmania IIDX スコア管理・分析ツール',
    description: 'beat-seekerは、beatmania IIDXのスコアを可視化・分析する無料 Web アプリです。公式 CSV をアップロードするだけでクリア率・AAA 率・Beat-Tier ランクを自動計算。非公式難易度表も公開しています。',
    bodyHtml: buildLandingHtml(),
    canonical: `${SITE_URL}/`,
    jsonLd: buildJsonLd('website', 'beat-seeker', 'beatmania IIDX スコア管理・分析ツール', `${SITE_URL}/`),
  },
  about: {
    title: 'beat-seekerとは？ | beat-seeker',
    description: 'beat-seekerは、beatmania IIDXのスコアデータを可視化・分析する無料Webアプリです。使い方・主な機能・よくある質問を掲載しています。',
    bodyHtml: aboutBody,
    canonical: `${SITE_URL}/about`,
    jsonLd: buildJsonLd('article', 'beat-seekerとは？', 'beat-seeker の使い方・主な機能・よくある質問', `${SITE_URL}/about`),
  },
  terms: {
    title: '利用規約 | beat-seeker',
    description: 'beat-seeker の利用規約と免責事項を掲載しています。サービス利用前にご確認ください。',
    bodyHtml: termsBody,
    canonical: `${SITE_URL}/terms`,
    jsonLd: buildJsonLd('article', '利用規約', 'beat-seeker の利用規約と免責事項', `${SITE_URL}/terms`),
  },
  'privacy-policy': {
    title: 'プライバシーポリシー | beat-seeker',
    description: 'beat-seeker のプライバシーポリシー。取得する情報と利用目的、公開範囲、外部サービスの利用、Cookie・ローカルストレージの使用について説明しています。',
    bodyHtml: buildPrivacyPolicyHtml(),
    canonical: `${SITE_URL}/privacy-policy`,
    jsonLd: buildJsonLd('article', 'プライバシーポリシー', 'beat-seeker のプライバシーポリシー。取得する情報・公開範囲・外部サービス・Cookie の使用について', `${SITE_URL}/privacy-policy`),
  },
  contact: {
    title: 'お問い合わせ | beat-seeker',
    description: 'beat-seeker への機能要望・不具合報告・その他お問い合わせ窓口、運営者情報を掲載しています。',
    bodyHtml: buildContactHtml(),
    canonical: `${SITE_URL}/contact`,
    jsonLd: buildJsonLd('article', 'お問い合わせ', 'beat-seeker への問い合わせ手段と運営者情報', `${SITE_URL}/contact`),
  },
  'difficulty-table': {
    title: 'beatmania IIDX 非公式難易度表 | beat-seeker',
    description: `beatmania IIDXの高難度譜面（☆11〜☆13）を独自評価で細分した非公式難易度表。全${diffTableJson.ranks.reduce((a, r) => a + r.songs.length, 0)}曲を${diffTableJson.ranks.filter(r => !isNaN(parseFloat(r.rank))).length}段階に分類。スコアと組み合わせてBEAT-PTを計算できます。`,
    bodyHtml: buildDiffTableHtml(diffTableJson.ranks),
    canonical: `${SITE_URL}/difficulty-table`,
    jsonLd: buildJsonLd('article', 'beatmania IIDX 非公式難易度表', '☆11〜☆13 を独自評価で細分した非公式難易度表', `${SITE_URL}/difficulty-table`),
  },
};

// ============================================================
// 各ページの HTML を生成して dist/<page>/index.html に書き出す
// ============================================================
for (const [slug, { title, description, bodyHtml, canonical, jsonLd }] of Object.entries(pages)) {
  // ルート (slug === '') は dist/index.html を直接上書き、それ以外は dist/<slug>/index.html
  const outDir = slug === '' ? distDir : path.join(distDir, slug);
  fs.mkdirSync(outDir, { recursive: true });

  let html = indexHtml
    .replace(/<title>.*?<\/title>/, `<title>${title}</title>`)
    .replace(/<meta name="description"[^>]*>/, `<meta name="description" content="${description}" />`)
    .replace(/<meta property="og:title"[^>]*>/, `<meta property="og:title" content="${title}" />`)
    .replace(/<meta property="og:description"[^>]*>/, `<meta property="og:description" content="${description}" />`)
    .replace(/<meta property="og:url"[^>]*>/, `<meta property="og:url" content="${canonical}" />`)
    .replace(/<link rel="canonical"[^>]*>/, `<link rel="canonical" href="${canonical}" />`)
    .replace('<div id="app"></div>', `<div id="app">${bodyHtml}</div>`);

  // JSON-LD を </head> 直前に注入
  if (jsonLd) {
    const ldScript = `<script type="application/ld+json">${JSON.stringify(jsonLd)}</script>`;
    html = html.replace('</head>', `${ldScript}\n</head>`);
  }

  fs.writeFileSync(path.join(outDir, 'index.html'), html, 'utf-8');
  const label = slug === '' ? 'dist/index.html (landing)' : `dist/${slug}/index.html`;
  console.log(`✓ ${label} を生成しました`);
}

console.log('プリレンダリング完了');
