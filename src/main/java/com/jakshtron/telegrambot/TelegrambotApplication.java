package com.jakshtron.telegrambot;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TelegrambotApplication implements CommandLineRunner {

	private final TelegramAuthStateHandler authHandler;

	public TelegrambotApplication(TelegramAuthStateHandler authHandler) {
		this.authHandler = authHandler;
	}

	public static void main(String[] args) {
		SpringApplication.run(TelegrambotApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println(">>> Jakshtron Telegram Bot is starting...");

		// Start the TDLib auth flow
		authHandler.startAuthentication();

		System.out.println(">>> System is active. Monitoring for signals...");

		// This is the most efficient way to keep a non-web Spring Boot app alive
		// It puts the main thread to sleep without consuming CPU cycles
		Thread.currentThread().join();
	}
}