package org.springframework.custom.service;

import org.springframework.custom.model.User;

/**
 * AOP 动态代理测试
 */
public interface AopService {

	/**
	 * 获取用户
	 *
	 * @param name
	 * @return
	 */
	User getUser(String name);

}
