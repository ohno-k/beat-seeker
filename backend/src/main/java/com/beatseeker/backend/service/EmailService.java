package com.beatseeker.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 SMTP 経由で HTML メールを送信するサービス。
 *
 * 責務:
 *  - パスワードリセットメールの送信（同期、呼び出し元に失敗を通知）
 *  - スコア更新通知メールの送信（非同期、失敗時は stderr ログのみ）
 *  - サポーター登録通知メールの送信（非同期、運営向け）
 *
 * 依存:
 *  - {@link JavaMailSender}: Spring Boot Starter Mail が自動構成する SMTP クライアント
 *  - application.yml の {@code spring.mail.username}（From アドレス）
 *  - application.yml の {@code app.frontend-url}（リセットリンク生成用ベース URL）
 *  - classpath:templates/email/*.html（メール本文テンプレート）
 *
 * 主要ロジックの概観:
 *  - HTML テンプレートは {@code resources/templates/email/} 配下から起動時に読み込む
 *  - テンプレート内の変数は {@code String.formatted(...)} で埋め込む
 *  - 表示時に XSS を避けるため、ユーザー入力値は {@link #escapeHtml(String)} を通す
 *  - 非同期版は {@code @Async} を付与しており、呼び出し元スレッドをブロックしない
 */
@Service
public class EmailService {

    /** Spring Boot Starter Mail が提供する SMTP 送信クライアント */
    private final JavaMailSender mailSender;

    /** 送信元アドレス（application.yml の spring.mail.username と同一） */
    @Value("${spring.mail.username}")
    private String fromAddress;

    /** フロントエンドのベース URL（パスワードリセットリンクの組み立てに使用） */
    @Value("${app.frontend-url}")
    private String frontendUrl;

    // --- テンプレート本文（起動時に classpath:templates/email/ から読み込む） ---
    private String passwordResetTemplate;
    private String scoreUpdateTemplate;
    private String supporterActivatedTemplate;
    private String resultImageTemplate;

    // --- フォールバック用の組み込みテンプレート（リソース読み込み失敗時に使用） ---
    private static final String FALLBACK_PASSWORD_RESET = """
            <!DOCTYPE html>
            <html lang="ja">
            <body style="font-family: sans-serif; background: #f8fafc; padding: 32px;">
              <div style="max-width: 480px; margin: 0 auto; background: #fff; border-radius: 16px; padding: 32px; border: 1px solid #e2e8f0;">
                <h2 style="color: #1e293b; margin-top: 0;">パスワードリセット</h2>
                <p style="color: #475569;">IIDX ID: <strong>%s</strong> のパスワードリセットが申請されました。</p>
                <p style="color: #475569;">下のボタンをクリックして新しいパスワードを設定してください。このリンクは <strong>1時間</strong> 有効です。</p>
                <a href="%s"
                   style="display: inline-block; margin: 16px 0; padding: 12px 28px; background: #2563eb; color: #fff; border-radius: 8px; text-decoration: none; font-weight: bold;">
                  パスワードをリセットする
                </a>
                <p style="color: #94a3b8; font-size: 12px;">このメールに心当たりがない場合は無視してください。</p>
                <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 24px 0;" />
                <p style="color: #94a3b8; font-size: 11px;">beat-seeker</p>
              </div>
            </body>
            </html>
            """;

    private static final String FALLBACK_SCORE_UPDATE = """
            <!DOCTYPE html>
            <html lang="ja">
            <body style="font-family:sans-serif;background:#f8fafc;padding:32px;">
              <div style="max-width:720px;margin:0 auto;background:#fff;border-radius:16px;padding:32px;border:1px solid #e2e8f0;">
                <h2 style="color:#1e293b;margin-top:0;">%sさんがスコアを更新しました</h2>
                <p style="color:#475569;">IIDX ID: <strong>%s</strong></p>
                <div style="display:flex;gap:24px;margin:16px 0;">
                  <div style="background:#f1f5f9;border-radius:8px;padding:12px 20px;text-align:center;">
                    <div style="font-size:11px;color:#64748b;margin-bottom:4px;">総BEAT-PT</div>
                    <div style="font-size:20px;font-weight:900;color:#1e293b;">%,.1f</div>
                  </div>
                  <div style="background:#f1f5f9;border-radius:8px;padding:12px 20px;text-align:center;">
                    <div style="font-size:11px;color:#64748b;margin-bottom:4px;">増加量</div>
                    <div style="font-size:20px;font-weight:900;color:#7c3aed;">+%,.1f</div>
                  </div>
                  <div style="background:#f1f5f9;border-radius:8px;padding:12px 20px;text-align:center;">
                    <div style="font-size:11px;color:#64748b;margin-bottom:4px;">更新曲数</div>
                    <div style="font-size:20px;font-weight:900;color:#2563eb;">%d</div>
                  </div>
                </div>
                %s
                <table style="width:100%%;border-collapse:collapse;margin-top:16px;font-size:13px;">
                  <thead>
                    <tr style="background:#f8fafc;border-bottom:2px solid #e2e8f0;">
                      <th style="padding:8px 10px;text-align:left;font-size:11px;color:#64748b;font-weight:700;">曲名</th>
                      <th style="padding:8px 10px;text-align:left;font-size:11px;color:#64748b;font-weight:700;">難易度</th>
                      <th style="padding:8px 10px;text-align:right;font-size:11px;color:#64748b;font-weight:700;">スコア</th>
                      <th style="padding:8px 10px;text-align:right;font-size:11px;color:#64748b;font-weight:700;">旧スコア</th>
                      <th style="padding:8px 10px;text-align:right;font-size:11px;color:#64748b;font-weight:700;">増加</th>
                      <th style="padding:8px 10px;text-align:left;font-size:11px;color:#64748b;font-weight:700;">クリア</th>
                    </tr>
                  </thead>
                  <tbody>%s</tbody>
                </table>
                <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0;" />
                <p style="color:#94a3b8;font-size:11px;">beat-seeker</p>
              </div>
            </body>
            </html>
            """;

    private static final String FALLBACK_SUPPORTER_ACTIVATED = """
            <!DOCTYPE html>
            <html lang="ja">
            <body style="font-family:sans-serif;background:#f8fafc;padding:32px;">
              <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:16px;padding:32px;border:1px solid #e2e8f0;">
                <h2 style="color:#1e293b;margin-top:0;">新しいサポーターが登録されました</h2>
                <div style="background:#fffbeb;border:1px solid #fde68a;border-radius:12px;padding:20px;margin:16px 0;text-align:center;">
                  <div style="font-size:11px;color:#92400e;margin-bottom:8px;font-weight:bold;">SUPPORTER</div>
                  <div style="font-size:24px;font-weight:900;color:#d97706;">%s</div>
                  <div style="font-size:13px;color:#92400e;margin-top:4px;">IIDX ID: %s</div>
                </div>
                <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0;" />
                <p style="color:#94a3b8;font-size:11px;">beat-seeker</p>
              </div>
            </body>
            </html>
            """;

    private static final String FALLBACK_RESULT_IMAGE = """
            <!DOCTYPE html>
            <html lang="ja">
            <body style="font-family:sans-serif;background:#f8fafc;padding:32px;">
              <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:16px;padding:32px;border:1px solid #e2e8f0;">
                <h2 style="color:#1e293b;margin-top:0;">%sさんがリザルト画像を保存しました</h2>
                <p style="color:#475569;">IIDX ID: <strong>%s</strong></p>
                <div style="background:#f1f5f9;border-radius:8px;padding:16px 20px;margin:16px 0;">
                  <div style="font-size:11px;color:#64748b;margin-bottom:4px;">対象譜面</div>
                  <div style="font-size:16px;font-weight:900;color:#1e293b;">%s</div>
                </div>
                <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0;" />
                <p style="color:#94a3b8;font-size:11px;">beat-seeker</p>
              </div>
            </body>
            </html>
            """;

    /**
     * 【コンストラクタ】 Spring から {@link JavaMailSender} を注入する。
     */
    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 【初期化】 classpath:templates/email/ 配下のテンプレートを起動時に読み込む。
     * 読み込み失敗時は stderr にログを出し、組み込みのフォールバック文字列を使用する
     * （アプリ起動を止めない）。
     */
    @PostConstruct
    void loadTemplates() {
        this.passwordResetTemplate       = loadTemplate("templates/email/password_reset.html",       FALLBACK_PASSWORD_RESET);
        this.scoreUpdateTemplate         = loadTemplate("templates/email/score_update.html",         FALLBACK_SCORE_UPDATE);
        this.supporterActivatedTemplate  = loadTemplate("templates/email/supporter_activated.html",  FALLBACK_SUPPORTER_ACTIVATED);
        this.resultImageTemplate         = loadTemplate("templates/email/result_image.html",         FALLBACK_RESULT_IMAGE);
    }

    /**
     * classpath リソースから UTF-8 でテンプレートを読み込む。CRLF は LF に正規化して、
     * 既存テキストブロックの出力と同等の改行規則に揃える。失敗時はフォールバックを返す。
     */
    private String loadTemplate(String classpath, String fallback) {
        ClassPathResource resource = new ClassPathResource(classpath);
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String raw = new String(bytes, StandardCharsets.UTF_8);
            // CRLF / CR を LF に正規化（Java テキストブロックの改行表現と一致させる）
            return raw.replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            System.err.println("Failed to load email template '" + classpath + "': " + e.getMessage()
                    + " -- using built-in fallback template.");
            return fallback;
        }
    }

    /**
     * 【メソッドの役割】 パスワードリセット用リンクを含む HTML メールを送信する。
     *
     * 処理の流れ:
     *  - 手順1: frontendUrl とワンタイムトークンを連結してリセット URL を生成
     *  - 手順2: HTML テンプレートを iidxId とリセット URL で埋め込み整形
     *  - 手順3: MimeMessageHelper で UTF-8 の HTML メールを構築し送信
     *
     * 同期呼び出しのため、送信失敗時は {@link MessagingException} が呼び出し元に伝播する。
     *
     * @param toEmail 宛先メールアドレス
     * @param iidxId  対象ユーザーの IIDX ID（メール本文に表示）
     * @param token   パスワードリセット用の一時トークン
     * @throws MessagingException MIME 組み立てや SMTP 送信に失敗した場合
     */
    public void sendPasswordResetEmail(String toEmail, String iidxId, String token) throws MessagingException {
        String resetUrl = frontendUrl + "/reset-password?token=" + token;

        String html = passwordResetTemplate.formatted(iidxId, resetUrl);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject("[beat-seeker] パスワードリセットのご案内");
        helper.setText(html, true);

        mailSender.send(message);
    }

    /**
     * 【メソッドの役割】 スコア更新通知メールを非同期で送信する。
     *
     * 処理の流れ:
     *  - 手順1: 譜面ごとの差分 List を HTML の &lt;tr&gt; 行として組み立てる
     *  - 手順2: ティアが上がった場合は「ランクアップ」表示のブロックを挿入する
     *  - 手順3: 総 BEAT-PT・増加量・更新曲数のサマリ + テーブルを埋め込んだメールを送信
     *  - 手順4: 送信失敗時は stderr にログ出力するのみ（ユーザー処理はブロックしない）
     *
     * @param toEmail        宛先メールアドレス
     * @param userName       表示用ユーザー名
     * @param iidxId         対象ユーザーの IIDX ID
     * @param diffs          譜面ごとの差分情報（title/difficulty/oldScore/newScore/clearType ほか）
     * @param totalBeatPt    現在の総 BEAT-PT
     * @param beatPtIncrease 今回の増加量
     * @param tierName       現在のティア名
     * @param prevTierName   前回のティア名（昇格判定に使用）
     */
    @Async
    public void sendScoreUpdateNotification(
            String toEmail,
            String userName,
            String iidxId,
            List<Map<String, Object>> diffs,
            double totalBeatPt,
            double beatPtIncrease,
            String tierName,
            String prevTierName) {

        // 譜面ごとの差分を HTML テーブルの行として組み立てる
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> d : diffs) {
            String title      = String.valueOf(d.getOrDefault("title", ""));
            String difficulty = String.valueOf(d.getOrDefault("difficulty", ""));
            int oldScore      = ((Number) d.getOrDefault("oldScore", 0)).intValue();
            int newScore      = ((Number) d.getOrDefault("newScore", 0)).intValue();
            int increase      = ((Number) d.getOrDefault("scoreIncrease", 0)).intValue();
            String oldClear   = String.valueOf(d.getOrDefault("oldClearType", "-"));
            String newClear   = String.valueOf(d.getOrDefault("newClearType", "-"));
            boolean clearUp   = Boolean.TRUE.equals(d.get("clearTypeImproved"));

            String increaseStr = increase > 0 ? "+" + increase : "±0";
            String clearCell = clearUp
                    ? "<span style='color:#22c55e;font-weight:bold;'>" + newClear + "</span>"
                    : "<span style='color:#64748b;'>" + newClear + "</span>";

            rows.append("<tr style='border-bottom:1px solid #e2e8f0;'>")
                .append("<td style='padding:6px 10px;font-size:13px;max-width:240px;word-break:break-all;'>").append(escapeHtml(title)).append("</td>")
                .append("<td style='padding:6px 10px;font-size:12px;color:#64748b;'>").append(escapeHtml(difficulty)).append("</td>")
                .append("<td style='padding:6px 10px;font-size:13px;text-align:right;'>").append(String.format("%,d", newScore)).append("</td>")
                .append("<td style='padding:6px 10px;font-size:12px;text-align:right;color:#64748b;'>").append(String.format("%,d", oldScore)).append("</td>")
                .append("<td style='padding:6px 10px;font-size:12px;text-align:right;color:#7c3aed;font-weight:bold;'>").append(increaseStr).append("</td>")
                .append("<td style='padding:6px 10px;font-size:12px;'>").append(clearCell).append("</td>")
                .append("</tr>");
        }

        String tierInfo = "";
        if (tierName != null && prevTierName != null && !tierName.equals(prevTierName)) {
            tierInfo = "<p style='margin:8px 0;color:#7c3aed;font-weight:bold;'>🎉 ランクアップ: " +
                    escapeHtml(prevTierName) + " → " + escapeHtml(tierName) + "</p>";
        }

        String html = scoreUpdateTemplate.formatted(
                escapeHtml(userName), escapeHtml(iidxId),
                totalBeatPt, beatPtIncrease, diffs.size(),
                tierInfo, rows.toString());

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("[beat-seeker] " + userName + "さんがスコアを更新しました");
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            // メール送信失敗はログのみ（ユーザー処理はブロックしない）
            System.err.println("Failed to send score update notification: " + e.getMessage());
        }
    }

    /**
     * 【メソッドの役割】 サポーター登録完了通知メールを運営向けに非同期送信する。
     *
     * 決済フック等から呼ばれ、stripe 支払い完了時に運営宛ての通知メールを送る。
     * 送信失敗は stderr にログ出力のみで、処理はブロックしない。
     *
     * @param toEmail  通知先メールアドレス（運営用）
     * @param userName サポーターになったユーザーの表示名
     * @param iidxId   対象ユーザーの IIDX ID
     */
    @Async
    public void sendSupporterActivatedNotification(String toEmail, String userName, String iidxId) {
        String html = supporterActivatedTemplate.formatted(escapeHtml(userName), escapeHtml(iidxId));

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("[beat-seeker] " + userName + "さんがサポーターになりました");
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            System.err.println("Failed to send supporter notification: " + e.getMessage());
        }
    }

    /**
     * 【メソッドの役割】 リザルト画像の保存通知メールを管理者向けに非同期送信する。
     *
     * スコア更新通知（{@link #sendScoreUpdateNotification}）と同様に、管理者以外のユーザーが
     * リザルト画像を登録したときに管理者へ知らせる用途。送信失敗は stderr ログのみで処理はブロックしない。
     *
     * @param toEmail         宛先メールアドレス（管理者）
     * @param userName        保存したユーザーの表示名
     * @param iidxId          保存したユーザーの IIDX ID
     * @param title           対象楽曲タイトル
     * @param difficultyName  難易度名（ANOTHER 等）
     * @param difficultyLevel 難易度レベル（null 可）
     */
    @Async
    public void sendResultImageNotification(String toEmail, String userName, String iidxId,
            String title, String difficultyName, Integer difficultyLevel) {
        String chart = escapeHtml(title) + " [" + escapeHtml(difficultyName)
                + (difficultyLevel != null ? " ☆" + difficultyLevel : "") + "]";
        String html = resultImageTemplate.formatted(escapeHtml(userName), escapeHtml(iidxId), chart);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("[beat-seeker] " + userName + "さんがリザルト画像を保存しました");
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            System.err.println("Failed to send result image notification: " + e.getMessage());
        }
    }

    /**
     * 【メソッドの役割】 TL から運営チャットに新着メッセージが届いたことを運営へ非同期通知する。
     *
     * <p>TL 専用 URL（ログイン不要）から送られるため、本文・チーム名・大会名はユーザー入力由来。
     * すべて {@link #escapeHtml(String)} を通し、本文の改行のみ {@code <br/>} に変換して表示する。
     * 送信失敗は stderr ログのみで処理はブロックしない。
     *
     * @param toEmail         宛先メールアドレス（運営）
     * @param competitionName 大会名
     * @param teamName        送信元チーム名
     * @param messageBody     メッセージ本文
     */
    @Async
    public void sendTlChatNotification(String toEmail, String competitionName, String teamName, String messageBody) {
        String safeBody = escapeHtml(messageBody).replace("\n", "<br/>");
        String html = """
                <!DOCTYPE html>
                <html lang="ja">
                <body style="font-family:sans-serif;background:#f8fafc;padding:32px;">
                  <div style="max-width:560px;margin:0 auto;background:#fff;border-radius:16px;padding:32px;border:1px solid #e2e8f0;">
                    <h2 style="color:#1e293b;margin-top:0;">大会チャットに新着メッセージ</h2>
                    <p style="color:#475569;margin:4px 0;">大会: <strong>%s</strong></p>
                    <p style="color:#475569;margin:4px 0;">チーム (TL): <strong>%s</strong></p>
                    <div style="background:#f1f5f9;border-radius:8px;padding:16px 20px;margin:16px 0;color:#1e293b;font-size:14px;line-height:1.6;">%s</div>
                    <p style="color:#94a3b8;font-size:12px;">管理画面の「運営チャット」から返信できます。</p>
                    <hr style="border:none;border-top:1px solid #e2e8f0;margin:24px 0;" />
                    <p style="color:#94a3b8;font-size:11px;">beat-seeker</p>
                  </div>
                </body>
                </html>
                """.formatted(escapeHtml(competitionName), escapeHtml(teamName), safeBody);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("[beat-seeker] 大会チャット: " + teamName + " から新着メッセージ");
            helper.setText(html, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            System.err.println("Failed to send TL chat notification: " + e.getMessage());
        }
    }

    /**
     * HTML メール本文に埋め込む前の XSS 対策用エスケープ。
     * ユーザー入力由来の文字列（タイトル、ユーザー名、難易度等）はすべてこれを通す。
     */
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
