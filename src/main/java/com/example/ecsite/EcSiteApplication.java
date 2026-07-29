package com.example.ecsite;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/* 
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
*/

@SpringBootApplication
public class EcSiteApplication {

	public static void main(String[] args) {
		SpringApplication.run(EcSiteApplication.class, args);
	}

	/* 
	@Bean
	CommandLineRunner passwordTest() {
		return args -> {
			PasswordEncoder encoder = new BCryptPasswordEncoder();

			System.out.println("=================================");
			System.out.println(encoder.encode("password"));
			System.out.println("=================================");
		};
	}
	*/

}