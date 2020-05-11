package org.springframework.custom.aspect;

/**
 * 记录时间切面
 */
public class TimeHandlerAspect {

	public void printTime() {
		System.out.println("Spring Aop 当前时间为:" + System.currentTimeMillis());
	}

}
