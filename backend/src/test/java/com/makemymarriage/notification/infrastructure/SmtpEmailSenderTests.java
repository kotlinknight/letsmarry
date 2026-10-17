package com.makemymarriage.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.makemymarriage.notification.EmailDelivery;
import com.makemymarriage.notification.EmailSendException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.ServerSocket;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import tools.jackson.databind.ObjectMapper;

class SmtpEmailSenderTests {
  @Test
  void temporarySmtpFailureIsRetriedWithoutExposingTheLink() {
    JavaMailSender mail = mock(JavaMailSender.class);
    when(mail.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    doThrow(new MailSendException("simulated offline server"))
        .when(mail)
        .send(any(MimeMessage.class));
    SmtpEmailSender sender = sender(mail);
    EmailDelivery delivery = delivery();

    assertThatThrownBy(() -> sender.send(delivery))
        .isInstanceOfSatisfying(
            EmailSendException.class, exception -> assertThat(exception.isPermanent()).isFalse())
        .hasMessageNotContaining("private-token");
    verify(mail).send(any(MimeMessage.class));
  }

  @Test
  void malformedPayloadAndMissingConfigurationFailSafely() {
    JavaMailSender mail = mock(JavaMailSender.class);
    assertThatThrownBy(
            () ->
                sender(mail)
                    .send(
                        new EmailDelivery(
                            UUID.randomUUID(), "a@example.com", "VERIFY_EMAIL", "{}")))
        .isInstanceOfSatisfying(
            EmailSendException.class, exception -> assertThat(exception.isPermanent()).isTrue());
    assertThatThrownBy(() -> new SmtpEmailSender(mail, new ObjectMapper(), "", "", 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("private-token");
  }

  @Test
  void resetMailUsesItsOwnSubjectAndRejectsMalformedLink() throws Exception {
    JavaMailSender mail = mock(JavaMailSender.class);
    when(mail.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    String url = "http://127.0.0.1:4200/reset-password#token=" + "A".repeat(43);
    sender(mail)
        .send(
            new EmailDelivery(
                UUID.randomUUID(),
                "a@example.com",
                "PASSWORD_RESET",
                "{\"resetUrl\":\"" + url + "\"}"));
    var message = ArgumentCaptor.forClass(MimeMessage.class);
    verify(mail).send(message.capture());
    assertThat(message.getValue().getSubject()).isEqualTo("Reset your Make My Marriage password");
    assertThatThrownBy(
            () ->
                sender(mail)
                    .send(
                        new EmailDelivery(
                            UUID.randomUUID(),
                            "a@example.com",
                            "PASSWORD_RESET",
                            "{\"resetUrl\":\"https://example.com/reset#token=bad\"}")))
        .isInstanceOfSatisfying(
            EmailSendException.class, exception -> assertThat(exception.isPermanent()).isTrue());
  }

  @Test
  void stalledSmtpGreetingTimesOutAsTemporaryFailure() throws Exception {
    try (var server = new ServerSocket(0);
        var executor = Executors.newSingleThreadExecutor()) {
      var accepted =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  Thread.sleep(1000);
                }
                return null;
              });
      var mail = new JavaMailSenderImpl();
      mail.setHost("127.0.0.1");
      mail.setPort(server.getLocalPort());
      mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "500");
      mail.getJavaMailProperties().put("mail.smtp.timeout", "200");
      assertThatThrownBy(
              () ->
                  new SmtpEmailSender(
                          mail,
                          new ObjectMapper(),
                          "hello@makemymarriage.local",
                          "127.0.0.1",
                          server.getLocalPort())
                      .send(delivery()))
          .isInstanceOfSatisfying(
              EmailSendException.class, exception -> assertThat(exception.isPermanent()).isFalse());
      accepted.get();
    }
  }

  private SmtpEmailSender sender(JavaMailSender mail) {
    return new SmtpEmailSender(
        mail, new ObjectMapper(), "hello@makemymarriage.local", "127.0.0.1", 1025);
  }

  private EmailDelivery delivery() {
    return new EmailDelivery(
        UUID.randomUUID(),
        "a@example.com",
        "VERIFY_EMAIL",
        "{\"verificationUrl\":\"http://127.0.0.1:4200/verify-email#token=private-token\"}");
  }
}
