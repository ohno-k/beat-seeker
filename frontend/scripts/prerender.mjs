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
 *  - /guide (ガイド一覧)
 *  - /guide/:slug (各ガイド記事)
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

// 難易度表とガイド記事のデータをビルド時に読み込む
const diffTableJson = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, '../src/data/difficulty_table.json'), 'utf-8')
);
const guidesJson = JSON.parse(
  fs.readFileSync(path.resolve(__dirname, '../src/data/guides.json'), 'utf-8')
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
// ガイド記事 HTML
// ============================================================
function buildGuideArticleHtml(guide) {
  return `
<article style="${articleStyle}">
  <nav style="font-size:0.85rem;color:#64748b;margin-bottom:12px;">
    <a href="/" style="${linkStyle}">ホーム</a> ›
    <a href="/guide" style="${linkStyle}">攻略ガイド</a> ›
    <span>${guide.title}</span>
  </nav>
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:8px;">${guide.title}</h1>
  <p style="font-size:0.85rem;color:#64748b;margin-bottom:24px;">公開日: ${guide.publishedAt}</p>
  <div style="font-size:1rem;color:#1e293b;">${guide.html}</div>
  <p style="margin-top:32px;"><a href="/guide" style="${linkStyle}">← ガイド一覧へ戻る</a></p>
</article>`;
}

function buildGuideIndexHtml(guides) {
  const items = guides.map(g => `
<li style="margin-bottom:16px;padding:16px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;">
  <a href="/guide/${g.slug}" style="${linkStyle}text-decoration:none;">
    <h2 style="font-size:1.15rem;font-weight:bold;margin-bottom:6px;color:#1e293b;">${g.title}</h2>
  </a>
  <p style="font-size:0.9rem;color:#475569;margin-bottom:6px;">${g.summary}</p>
  <p style="font-size:0.8rem;color:#94a3b8;">公開日: ${g.publishedAt}</p>
</li>`).join('');

  return `
<article style="${articleStyle}">
  <h1 style="font-size:1.8rem;font-weight:900;margin-bottom:12px;">攻略ガイド一覧</h1>
  <p style="color:#475569;margin-bottom:24px;">beat-seeker の使い方と、beatmania IIDX 上達のコツをまとめた解説記事です。スコア管理だけでなく、地力上げ・AAA 取り・スクラッチ譜面攻略といった実戦的なノウハウも掲載しています。</p>
  <ul style="list-style:none;padding:0;">${items}</ul>
  <p style="margin-top:32px;"><a href="/" style="${linkStyle}">← トップへ戻る</a></p>
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

  <section style="margin-bottom:40px;">
    <h2 style="${h2Style}">攻略ガイド</h2>
    <p style="color:#475569;margin-bottom:16px;">beat-seeker の使い方に加えて、IIDX の上達に役立つ攻略記事を公開しています。地力上げや AAA 取り、CSV 取得方法などを丁寧に解説します。</p>
    <ul style="list-style:none;padding:0;">
${guidesJson.guides.map(g => `      <li style="margin-bottom:8px;"><a href="/guide/${g.slug}" style="${linkStyle}">${g.title}</a> — <span style="color:#64748b;font-size:0.9rem;">${g.summary}</span></li>`).join('\n')}
    </ul>
  </section>

  <section style="margin-bottom:40px;background:#f8fafc;padding:24px;border-radius:16px;border:1px solid #e2e8f0;">
    <h2 style="${h2Style}">beat-seeker について</h2>
    <p style="color:#475569;margin-bottom:8px;">beat-seeker は IIDX プレイヤー個人によって運営されている、ファンメイドの非公式スコア管理ツールです。営利目的ではなく、KONAMI とは一切関係ありません。</p>
    <p style="color:#475569;margin-bottom:8px;">スコアデータはユーザー自身が公式 CSV からアップロードしたものに限定し、個人を特定しない範囲で集計・分析を行います。データの取り扱いについては<a href="/privacy-policy" style="${linkStyle}">プライバシーポリシー</a>をご確認ください。</p>
    <p style="font-size:0.8rem;color:#94a3b8;margin-top:16px;">beatmania IIDX は株式会社コナミアミューズメントの登録商標です。本サービスは KONAMI 公式とは一切関係のない非公式のファンメイドツールです。</p>
  </section>

  <nav style="display:flex;gap:16px;flex-wrap:wrap;justify-content:center;padding-top:24px;border-top:1px solid #e2e8f0;color:#64748b;font-size:0.9rem;">
    <a href="/about" style="${linkStyle}">アプリについて</a>
    <a href="/guide" style="${linkStyle}">攻略ガイド</a>
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
    <li><strong>取得する情報:</strong> ユーザーが登録した IIDX ID・パスワード（ハッシュ化して保存）・ユーザー名・段位・アリーナランク等のプロフィール情報、およびユーザーがアップロードした全スコアデータ。</li>
    <li><strong>利用目的:</strong> 本サービスの中核機能（スコアの可視化・管理）の提供、および本サービス不具合時のデバッグや改善のためにのみ利用します。</li>
    <li><strong>第三者への提供:</strong> 取得した情報を、法令等に基づく場合を除き、ユーザーの同意なく第三者に提供することはありません。</li>
    <li><strong>データ保護:</strong> ユーザーのデータは適切に保護・管理されますが、インターネット上のデータ転送の性質上、100%の安全保障はできません。</li>
  </ul>

  <h2 style="${h2Style}">広告の配信について（Google AdSense）</h2>
  <p>本サービスでは、第三者配信の広告サービス「Google AdSense」を利用しています。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>Google AdSense はユーザーの興味に応じた広告を表示するために <strong>Cookie</strong> を使用します。Cookie はユーザーのコンピュータを識別しますが、個人を特定するものではありません。</li>
    <li>Google AdSense における Cookie の使用により、Google およびそのパートナーはユーザーのアクセス情報に基づいて適切な広告を表示します。</li>
    <li>Cookie を無効にするには、ブラウザの設定から Cookie を無効にするか、<a href="https://www.google.com/settings/ads" style="${linkStyle}">Google 広告設定ページ</a>にてパーソナライズ広告を無効にしてください。</li>
    <li>Google AdSense の詳細については、<a href="https://policies.google.com/technologies/ads?hl=ja" style="${linkStyle}">Google のポリシーと規約</a>をご確認ください。</li>
  </ul>

  <h2 style="${h2Style}">アクセス解析について（Google Analytics）</h2>
  <p>本サービスではサービス改善のために Google Analytics を利用しています。Google Analytics はトラフィックデータの収集のために Cookie を使用していますが、これは匿名で収集されており個人を特定するものではありません。詳細は <a href="https://policies.google.com/privacy?hl=ja" style="${linkStyle}">Google のプライバシーポリシー</a>をご確認ください。</p>

  <h2 style="${h2Style}">Cookie の使用について</h2>
  <p>本サービスでは、ユーザー体験の向上やアクセス解析、広告配信のために Cookie を使用しています。</p>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>認証 Cookie: ログインセッションの維持に使用します。ログインしない場合は使用されません。</li>
    <li>解析 Cookie: ページの訪問数や滞在時間を匿名で計測するために使用します。</li>
    <li>広告 Cookie: Google AdSense が興味関心に応じた広告を表示するために使用します。ブラウザ設定や Google 広告設定ページで無効化できます。</li>
  </ul>

  <p style="margin-top:32px;font-size:0.85rem;color:#94a3b8;">制定日: 2026年3月5日 / 最終更新: 2026年4月26日</p>
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
    <dd style="margin-left:16px;color:#475569;">IIDX公式サイトのプレーデータ画面からCSVをダウンロードできます。詳しい手順は<a href="/guide/csv-import" style="${linkStyle}">CSV ダウンロードガイド</a>をご覧ください。</dd>

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
    <li>ユーザーは、本サービスを法令や公序良俗に反する目的で利用してはなりません。</li>
    <li>他のユーザーへの迷惑行為や、サーバーへ過度な負荷をかける行為を禁止します。</li>
    <li>本規約に悪質に違反した場合、運営者は事前の通知なくアカウントの停止またはデータの削除を行う権利を有します。</li>
  </ul>

  <h2 style="${h2Style}">免責事項 (Disclaimer)</h2>
  <ul style="padding-left:24px;line-height:2;margin-top:8px;">
    <li>本サービスは現状有姿（As-Is）で、基本ベータ版として提供されます。機能の完全性、正確性、有用性について、いかなる保証もいたしません。</li>
    <li>本サービスの利用やデータの損失によってユーザーに生じたあらゆる損害について、運営者は一切の責任を負いません。</li>
    <li>運営者の判断により、事前の予告なく本サービスの仕様変更、機能追加、またはサービスの提供を一時的または恒久的に停止する場合があります。</li>
    <li>アップロードされたデータはシステム上保存されますが、完全なバックアップを保証するものではありません。</li>
  </ul>

  <p style="margin-top:32px;"><a href="/privacy-policy" style="${linkStyle}">プライバシーポリシーへ</a> / <a href="/contact" style="${linkStyle}">お問い合わせ</a></p>
  <p style="margin-top:16px;font-size:0.85rem;color:#94a3b8;">制定日: 2026年3月5日 / 最終更新: 2026年4月26日</p>
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
    description: 'beat-seekerは、beatmania IIDXのスコアを可視化・分析する無料 Web アプリです。公式 CSV をアップロードするだけでクリア率・AAA 率・Beat-Tier ランクを自動計算。攻略ガイドや非公式難易度表も公開しています。',
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
    description: 'beat-seeker のプライバシーポリシー、Cookie の使用、Google AdSense / Google Analytics の利用についての説明を掲載しています。',
    bodyHtml: buildPrivacyPolicyHtml(),
    canonical: `${SITE_URL}/privacy-policy`,
    jsonLd: buildJsonLd('article', 'プライバシーポリシー', 'beat-seeker のプライバシーポリシー、Cookie・Google AdSense・Google Analytics の利用について', `${SITE_URL}/privacy-policy`),
  },
  contact: {
    title: 'お問い合わせ | beat-seeker',
    description: 'beat-seeker への機能要望・不具合報告・その他お問い合わせ窓口、運営者情報を掲載しています。',
    bodyHtml: buildContactHtml(),
    canonical: `${SITE_URL}/contact`,
    jsonLd: buildJsonLd('article', 'お問い合わせ', 'beat-seeker への問い合わせ手段と運営者情報', `${SITE_URL}/contact`),
  },
  guide: {
    title: '攻略ガイド一覧 | beat-seeker',
    description: 'beatmania IIDX の上達に役立つ攻略ガイド一覧。CSV ダウンロード手順、スクラッチ攻略、AAA を狙う練習法、☆12 地力上げ譜面、クリアタイプ解説など。',
    bodyHtml: buildGuideIndexHtml(guidesJson.guides),
    canonical: `${SITE_URL}/guide`,
    jsonLd: buildJsonLd('website', '攻略ガイド一覧', 'beatmania IIDX 上達のための攻略ガイド', `${SITE_URL}/guide`),
  },
  'difficulty-table': {
    title: 'beatmania IIDX 非公式難易度表 | beat-seeker',
    description: `beatmania IIDXの高難度譜面（☆11〜☆13）を独自評価で細分した非公式難易度表。全${diffTableJson.ranks.reduce((a, r) => a + r.songs.length, 0)}曲を${diffTableJson.ranks.filter(r => !isNaN(parseFloat(r.rank))).length}段階に分類。スコアと組み合わせてBEAT-PTを計算できます。`,
    bodyHtml: buildDiffTableHtml(diffTableJson.ranks),
    canonical: `${SITE_URL}/difficulty-table`,
    jsonLd: buildJsonLd('article', 'beatmania IIDX 非公式難易度表', '☆11〜☆13 を独自評価で細分した非公式難易度表', `${SITE_URL}/difficulty-table`),
  },
};

// ガイド記事ごとのページを追加
guidesJson.guides.forEach((guide) => {
  pages[`guide/${guide.slug}`] = {
    title: `${guide.title} | beat-seeker`,
    description: guide.description,
    bodyHtml: buildGuideArticleHtml(guide),
    canonical: `${SITE_URL}/guide/${guide.slug}`,
    jsonLd: buildJsonLd('article', guide.title, guide.description, `${SITE_URL}/guide/${guide.slug}`),
  };
});

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
