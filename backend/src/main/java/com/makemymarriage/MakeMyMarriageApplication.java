package com.makemymarriage;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MakeMyMarriageApplication {

  public static void main(String[] args) {
    // Keep JDBC startup and server timestamps independent of host timezone aliases.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(MakeMyMarriageApplication.class, args);
  }
}
