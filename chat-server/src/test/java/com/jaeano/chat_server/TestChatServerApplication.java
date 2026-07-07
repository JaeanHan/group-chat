package com.jaeano.chat_server;

import org.springframework.boot.SpringApplication;

public class TestChatServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(ChatServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
