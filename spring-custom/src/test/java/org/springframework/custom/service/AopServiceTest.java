package org.springframework.custom.service;

import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;


public class AopServiceTest {

	@Test
	public void getUser() {
		ApplicationContext applicationContext = new ClassPathXmlApplicationContext("spring/aop.xml");
		AopService aopService = applicationContext.getBean(AopService.class);
		aopService.getUser("djy");
	}

}