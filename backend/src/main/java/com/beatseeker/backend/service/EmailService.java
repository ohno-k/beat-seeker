package com.beatseeker.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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
}
