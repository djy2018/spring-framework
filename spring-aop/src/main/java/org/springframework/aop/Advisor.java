/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop;

import org.aopalliance.aop.Advice;

/**
 * Base interface holding AOP <b>advice</b> (action to take at a joinpoint)
 * and a filter determining the applicability of the advice (such as
 * a pointcut). <i>This interface is not for use by Spring users, but to
 * allow for commonality in support for different types of advice.</i>
 *
 * 包含AOP通知(在连接点采取的动作)的基本接口和决定通知适用性的过滤器(例如切入点)。
 * 这个接口不是供Spring用户使用的，而是允许在支持不同类型的通知方面具有共性。
 *
 * <p>Spring AOP is based around <b>around advice</b> delivered via method
 * <b>interception</b>, compliant with the AOP Alliance interception API.
 * The Advisor interface allows support for different types of advice,
 * such as <b>before</b> and <b>after</b> advice, which need not be
 * implemented using interception.
 *
 * Spring AOP是基于通过方法拦截交付的环绕通知，与AOP联盟拦截API兼容。
 * Advisor接口允许支持不同类型的通知，比如before和after通知，这些通知不需要使用拦截来实现。
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 */
public interface Advisor {

	/**
	 * Common placeholder for an empty {@code Advice} to be returned from
	 * {@link #getAdvice()} if no proper advice has been configured (yet).
	 * @since 5.0
	 */
	Advice EMPTY_ADVICE = new Advice() {};


	/**
	 * Return the advice part of this aspect. An advice may be an
	 * interceptor, a before advice, a throws advice, etc.
	 * @return the advice that should apply if the pointcut matches
	 * @see org.aopalliance.intercept.MethodInterceptor
	 * @see BeforeAdvice
	 * @see ThrowsAdvice
	 * @see AfterReturningAdvice
	 */
	Advice getAdvice();

	/**
	 * Return whether this advice is associated with a particular instance
	 * (for example, creating a mixin) or shared with all instances of
	 * the advised class obtained from the same Spring bean factory.
	 * <p><b>Note that this method is not currently used by the framework.</b>
	 * Typical Advisor implementations always return {@code true}.
	 * Use singleton/prototype bean definitions or appropriate programmatic
	 * proxy creation to ensure that Advisors have the correct lifecycle model.
	 * @return whether this advice is associated with a particular target instance
	 */
	boolean isPerInstance();

}
