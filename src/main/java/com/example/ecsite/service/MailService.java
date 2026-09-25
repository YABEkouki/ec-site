package com.example.ecsite.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String baseUrl;

    public MailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.base-url}") String baseUrl) {

        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.baseUrl = baseUrl;
    }

    public void sendEmailVerification(
            String toAddress,
            String rawToken) {

        String verificationUrl = baseUrl + "/email/verify?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toAddress);
        message.setSubject("メールアドレスの確認");
        message.setText("""
                ECサイトへのメールアドレス登録ありがとうございます。

                以下のURLを開いて、メールアドレスの確認を完了してください。

                %s

                このURLの有効期限は24時間です。

                このメールに心当たりがない場合は、
                このメールを破棄してください。
                """.formatted(verificationUrl));

        mailSender.send(message);
    }

    public void sendPasswordReset(
            String toAddress,
            String rawToken) {

        String resetUrl = baseUrl + "/password/reset?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(fromAddress);
        message.setTo(toAddress);
        message.setSubject("パスワード再設定");

        message.setText("""
                パスワード再設定のリクエストを受け付けました。

                以下のURLからパスワードを再設定してください。

                %s

                このURLの有効期限は1時間です。

                このメールに心当たりがない場合は、
                このメールを破棄してください。
                """.formatted(resetUrl));

        mailSender.send(message);
    }

}
