package com.beatseeker.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendPasswordResetEmail(String toEmail, String iidxId, String token) throws MessagingException {
        String resetUrl = frontendUrl + "/reset-password?token=" + token;

        String html = """
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
                """.formatted(iidxId, resetUrl);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject("[beat-seeker] パスワードリセットのご案内");
        helper.setText(html, true);

        mailSender.send(message);
    }

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

        String html = """
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
                """.formatted(
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

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
