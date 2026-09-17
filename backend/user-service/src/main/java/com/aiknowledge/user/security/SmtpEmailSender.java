package com.aiknowledge.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP delivery configured by SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, SMTP_FROM and SMTP_SECURITY
 * (ssl, starttls or none). Without SMTP_HOST there is no mail server: members can still bind an address but not
 * verify it. Spring Boot's own mail auto-configuration is not used because an empty SMTP_HOST would still enable it.
 */
@Component
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSenderImpl mailer;
    private final String from;

    public SmtpEmailSender(
            @Value("${app.mail.host:}") String host,
            @Value("${app.mail.port:465}") int port,
            @Value("${app.mail.username:}") String username,
            @Value("${app.mail.password:}") String password,
            @Value("${app.mail.from:}") String from,
            @Value("${app.mail.security:ssl}") String security
    ) {
        if (host == null || host.isBlank()) {
            this.mailer = null;
            this.from = "";
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host.trim());
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        if (username != null && !username.isBlank()) {
            sender.setUsername(username.trim());
            sender.setPassword(password);
            properties.put("mail.smtp.auth", "true");
        }
        switch (security == null ? "ssl" : security.trim().toLowerCase()) {
            case "none" -> { }
            case "starttls" -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
            }
            default -> properties.put("mail.smtp.ssl.enable", "true");
        }
        this.mailer = sender;
        this.from = from == null || from.isBlank() ? (username == null ? "" : username.trim()) : from.trim();
    }

    @Override
    public boolean available() {
        return mailer != null && !from.isBlank();
    }

    @Override
    public void send(String to, String subject, String body) {
        if (!available()) throw new IllegalStateException("no mail server is configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailer.send(message);
    }
}
