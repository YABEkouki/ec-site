package com.example.ecsite.service.payment;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

class PaymentDiscrepancyAuditNotificationMailClientTest {
    static PaymentDiscrepancyAuditNotificationProperties properties() {
        return new PaymentDiscrepancyAuditNotificationProperties(true,List.of("a@example.com","b@example.com"),Duration.ofMinutes(1),
            Duration.ofMinutes(15),Duration.ofMinutes(5),3,Duration.ofMinutes(3),
            new PaymentDiscrepancyAuditNotificationProperties.Smtp(Duration.ofSeconds(5),Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ofMinutes(2)));
    }
    @Test void copiesSenderWithoutMutatingSharedConfigurationAndUsesUtf8Bcc() throws Exception {
        var original=new JavaMailSenderImpl();original.setHost("localhost");original.setPort(1025);
        original.setUsername("smtp-user");original.setPassword("secret");original.setProtocol("smtp");
        original.getJavaMailProperties().put("mail.smtp.auth","true");original.getJavaMailProperties().put("mail.debug","true");
        var client=new PaymentDiscrepancyAuditNotificationMailClient(original,properties());
        var message=client.createMessage(new PaymentAuditNotificationMail("from@example.com",List.of("a@example.com","b@example.com"),"PAY.JP監査の警告","日本語本文"));
        assertThat(message.getSubject()).isEqualTo("PAY.JP監査の警告");
        assertThat(message.getContent()).isEqualTo("日本語本文");
        assertThat(message.getRecipients(Message.RecipientType.TO)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
        assertThat(message.getRecipients(Message.RecipientType.BCC)).extracting(Object::toString).containsExactly("a@example.com","b@example.com");
        message.saveChanges();assertThat(message.getContentType().toLowerCase(Locale.ROOT)).contains("charset=utf-8");
        var field=PaymentDiscrepancyAuditNotificationMailClient.class.getDeclaredField("sender");field.setAccessible(true);
        var independent=(JavaMailSenderImpl)field.get(client);
        assertThat(independent).isNotSameAs(original);assertThat(independent.getHost()).isEqualTo("localhost");
        assertThat(independent.getPort()).isEqualTo(1025);assertThat(independent.getUsername()).isEqualTo("smtp-user");
        assertThat(independent.getJavaMailProperties()).containsEntry("mail.smtp.connectiontimeout","5000")
            .containsEntry("mail.smtp.timeout","10000").containsEntry("mail.smtp.writetimeout","10000")
            .containsEntry("mail.smtp.auth","true").containsEntry("mail.debug","false");
        original.getJavaMailProperties().put("mail.smtp.auth","false");
        assertThat(independent.getJavaMailProperties()).containsEntry("mail.smtp.auth","true");
        assertThat(original.getJavaMailProperties()).doesNotContainKey("mail.smtp.timeout");
    }

    @Test void sendsBccEnvelopeAndJapaneseMimeToLoopbackSmtpOnly() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()) {
            var envelope=new java.util.concurrent.CopyOnWriteArrayList<String>();
            var received=pool.submit(()->{
                try(var socket=server.accept()) {
                    socket.setSoTimeout(3000);
                    var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);
                    out.print("220 localhost test\r\n");out.flush();var data=new StringBuilder();
                    for(String line;(line=in.readLine())!=null;) {
                        if(line.startsWith("EHLO")||line.startsWith("HELO"))out.print("250 localhost\r\n");
                        else if(line.startsWith("MAIL FROM:"))out.print("250 OK\r\n");
                        else if(line.startsWith("RCPT TO:")){envelope.add(line);out.print("250 OK\r\n");}
                        else if(line.equals("DATA")) {
                            out.print("354 Send data\r\n");out.flush();
                            while(!(line=in.readLine()).equals("."))data.append(line.startsWith("..")?line.substring(1):line).append("\r\n");
                            out.print("250 Accepted\r\n");
                        } else if(line.equals("QUIT")){out.print("221 Bye\r\n");out.flush();break;}
                        else out.print("250 OK\r\n");out.flush();
                    }
                    return data.toString();
                }
            });
            var original=new JavaMailSenderImpl();original.setHost("127.0.0.1");original.setPort(server.getLocalPort());
            var client=new PaymentDiscrepancyAuditNotificationMailClient(original,properties());
            client.send(new PaymentAuditNotificationMail("from@example.com",List.of("a@example.com","b@example.com"),"監査の警告","日本語の本文"));
            String data=received.get(5,TimeUnit.SECONDS);
            var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
            assertThat(envelope).containsExactly("RCPT TO:<a@example.com>","RCPT TO:<b@example.com>");
            assertThat(data).doesNotContain("Bcc:");assertThat(message.getSubject()).isEqualTo("監査の警告");
            assertThat(message.getContent().toString()).contains("日本語の本文");
        }
    }
    @Test void smtpReadTimeoutBoundsServerThatNeverSendsGreeting() throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var pool=Executors.newSingleThreadExecutor()) {
            var release=new CountDownLatch(1);
            var holding=pool.submit(()->{try(var socket=server.accept()){release.await(5,TimeUnit.SECONDS);}return null;});
            var original=new JavaMailSenderImpl();original.setHost("127.0.0.1");original.setPort(server.getLocalPort());
            var p=properties();
            var bounded=new PaymentDiscrepancyAuditNotificationProperties(true,p.recipients(),p.fixedDelay(),p.noHistoryGracePeriod(),p.retryDelay(),3,p.claimLease(),
                new PaymentDiscrepancyAuditNotificationProperties.Smtp(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(30)));
            var client=new PaymentDiscrepancyAuditNotificationMailClient(original,bounded);
            long started=System.nanoTime();
            try {
                assertThatThrownBy(()->client.send(new PaymentAuditNotificationMail("from@example.com",List.of("a@example.com"),"警告","本文"))).isInstanceOf(MailException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
            } finally {release.countDown();holding.get(5,TimeUnit.SECONDS);}
        }
    }
}
