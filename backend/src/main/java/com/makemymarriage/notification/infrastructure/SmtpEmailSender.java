package com.makemymarriage.notification.infrastructure;

import com.makemymarriage.notification.EmailDelivery;
import com.makemymarriage.notification.EmailSendException;
import com.makemymarriage.notification.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("(local | test) & !production")
@ConditionalOnProperty(name = "app.email-outbox.worker.enabled", havingValue = "true")
class SmtpEmailSender implements EmailSender {
  private final JavaMailSender mail;
  private final ObjectMapper mapper;
  private final String from;

  SmtpEmailSender(
      JavaMailSender mail,
      ObjectMapper mapper,
      @Value("${app.mail.from:}") String from,
      @Value("${spring.mail.host:}") String host,
      @Value("${spring.mail.port:0}") int port) {
    if (host.isBlank() || port < 1 || port > 65535 || from.isBlank()) {
      throw new IllegalStateException("Email worker needs mail host, port, and from address");
    }
    try {
      new InternetAddress(from, true).validate();
    } catch (Exception exception) {
      throw new IllegalStateException("Email worker has an invalid from address");
    }
    this.mail = mail;
    this.mapper = mapper;
    this.from = from;
  }

  @Override
  public SendReceipt send(EmailDelivery delivery) {
    if (!"VERIFY_EMAIL".equals(delivery.templateCode())) {
      throw new EmailSendException(true);
    }
    String url;
    try {
      var payload = mapper.readTree(delivery.payloadJson());
      url = payload.path("verificationUrl").asText();
      URI parsed = URI.create(url);
      if (!"token"
              .equals(parsed.getFragment() == null ? null : parsed.getFragment().split("=", 2)[0])
          || !("https".equals(parsed.getScheme())
              || ("http".equals(parsed.getScheme())
                  && ("localhost".equals(parsed.getHost())
                      || "127.0.0.1".equals(parsed.getHost()))))) {
        throw new IllegalArgumentException();
      }
      new InternetAddress(delivery.recipientEmail(), true).validate();
    } catch (Exception exception) {
      throw new EmailSendException(true);
    }

    String safeUrl =
        url.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    String plain =
        "Verify your Make My Marriage email address\n\n"
            + "Open this link, then select Confirm email address:\n"
            + url
            + "\n\n"
            + "This link expires in 24 hours. If you did not request this, ignore this email.\n";
    String html =
        """
        <!doctype html><html lang="en"><body style="font-family:Arial,sans-serif;color:#292335">
        <div style="max-width:560px;margin:auto;padding:24px">
        <h1 style="color:#db665e">Make My Marriage</h1>
        <h2>Verify your email address</h2>
        <p>Open the link below, then select <strong>Confirm email address</strong>.</p>
        <p><a style="display:inline-block;background:#db665e;color:#fff;padding:12px 20px;border-radius:8px;text-decoration:none"
        href="%s">Verify email</a></p>
        <p>This link expires in 24 hours. If you did not request this, ignore this email.</p>
        </div></body></html>
        """
            .formatted(safeUrl);
    try {
      var message = mail.createMimeMessage();
      var helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(delivery.recipientEmail());
      helper.setSubject("Verify your Make My Marriage email");
      helper.setText(plain, html);
      message.setHeader("X-Delivery-ID", delivery.deliveryId().toString());
      mail.send(message);
      return new SendReceipt(message.getMessageID());
    } catch (MessagingException exception) {
      throw new EmailSendException(true);
    } catch (MailException exception) {
      throw new EmailSendException(false);
    }
  }
}
