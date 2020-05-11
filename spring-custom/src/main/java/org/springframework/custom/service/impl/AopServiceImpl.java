package org.springframework.custom.service.impl;

import org.springframework.custom.model.User;
import org.springframework.custom.service.AopService;
import org.springframework.stereotype.Service;

@Service
public class AopServiceImpl implements AopService {

	@Override
	public User getUser(String name) {
		System.out.println("Spring Aop Jdk Proxy");
		return new User(name);
	}

}
