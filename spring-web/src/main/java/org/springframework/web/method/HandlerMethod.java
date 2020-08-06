/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.web.method;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.SynthesizingMethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Encapsulates information about a handler method consisting of a
 * {@linkplain #getMethod() method} and a {@linkplain #getBean() bean}.
 * Provides convenient access to method parameters, the method return value,
 * method annotations, etc.
 * <p>
 * 封装关于由{@linkplain #getMethod() method}和{@linkplain #getBean() bean}组成的处理器方法的信息。
 * 提供对方法参数、方法返回值、方法注释等的方便访问。
 *
 * <p>The class may be created with a bean instance or with a bean name
 * (e.g. lazy-init bean, prototype bean). Use {@link #createWithResolvedBean()}
 * to obtain a {@code HandlerMethod} instance with a bean instance resolved
 * through the associated {@link BeanFactory}.
 * <p>
 * 类可以用一个bean实例创建，也可以用一个bean名创建(例如:lazy-init bean、原型bean)。
 * 使用{@link #createWithResolvedBean()}获得一个{@code HandlerMethod}实例，
 * 该实例带有通过关联的{@link BeanFactory}解析的bean实例。
 * => 模板模型方法 org.springframework.web.servlet.handler.AbstractHandlerMethodMapping#getHandlerInternal
 * 在方法最后调用 org.springframework.web.method.HandlerMethod#createWithResolvedBean()
 * 从而确保 HandlerMethod#bean 是 Handler处理器对象，而非 Handler 处理器对象的 beanName
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 */
public class HandlerMethod {

	/**
	 * Logger that is available to subclasses.
	 */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Bean 对象
	 */
	private final Object bean;

	@Nullable
	private final BeanFactory beanFactory;

	/**
	 * Bean 的类型
	 */
	private final Class<?> beanType;

	/**
	 * 方法
	 */
	private final Method method;

	/**
	 * {@link #method} 的桥接方法
	 * <p>
	 * 详细说明
	 * <p>
	 * 1. https://www.jianshu.com/p/250030ea9b28
	 * 2. https://blog.csdn.net/mhmyqn/article/details/47342577
	 */
	private final Method bridgedMethod;

	/**
	 * 方法参数数组
	 */
	private final MethodParameter[] parameters;

	/**
	 * 响应的状态码，即 {@link ResponseStatus#code()}
	 */
	@Nullable
	private HttpStatus responseStatus;

	/**
	 * 响应的状态码原因，即 {@link ResponseStatus#reason()}
	 */
	@Nullable
	private String responseStatusReason;


	/**
	 * 解析自哪个 HandlerMethod 对象
	 * <p>
	 * 仅构造方法中传入 HandlerMethod 类型的参数适用，例如 {@link #HandlerMethod(HandlerMethod)}
	 */
	@Nullable
	private HandlerMethod resolvedFromHandlerMethod;

	/**
	 * 父接口的方法的参数注解数组
	 */
	@Nullable
	private volatile List<Annotation[][]> interfaceParameterAnnotations;


	/**
	 * Create an instance from a bean instance and a method.
	 */
	public HandlerMethod(Object bean, Method method) {
		Assert.notNull(bean, "Bean is required");
		Assert.notNull(method, "Method is required");
		// 初始化 bean
		this.bean = bean;
		// 置空 beanFactory ，因为不用
		this.beanFactory = null;
		// 初始化 beanType 属性
		this.beanType = ClassUtils.getUserClass(bean);
		// 初始化 method 和 bridgedMethod 属性
		this.method = method;
		this.bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
		// 初始化 parameters 属性
		this.parameters = initMethodParameters();
		// 初始化 responseStatus、responseStatusReason 属性
		evaluateResponseStatus();
	}

	/**
	 * Create an instance from a bean instance, method name, and parameter types.
	 *
	 * @throws NoSuchMethodException when the method cannot be found
	 */
	public HandlerMethod(Object bean, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
		Assert.notNull(bean, "Bean is required");
		Assert.notNull(methodName, "Method name is required");
		this.bean = bean;
		this.beanFactory = null;
		this.beanType = ClassUtils.getUserClass(bean);
		this.method = bean.getClass().getMethod(methodName, parameterTypes);
		this.bridgedMethod = BridgeMethodResolver.findBridgedMethod(this.method);
		this.parameters = initMethodParameters();
		evaluateResponseStatus();
	}

	/**
	 * Create an instance from a bean name, a method, and a {@code BeanFactory}.
	 * The method {@link #createWithResolvedBean()} may be used later to
	 * re-create the {@code HandlerMethod} with an initialized bean.
	 */
	public HandlerMethod(String beanName, BeanFactory beanFactory, Method method) {
		Assert.hasText(beanName, "Bean name is required");
		Assert.notNull(beanFactory, "BeanFactory is required");
		Assert.notNull(method, "Method is required");
		// <1> 将 beanName 赋值给 bean 属性，说明 beanFactory + bean 的方式，获得 handler 对象
		this.bean = beanName;
		this.beanFactory = beanFactory;
		// <2> 初始化 beanType 属性
		Class<?> beanType = beanFactory.getType(beanName);
		if (beanType == null) {
			throw new IllegalStateException("Cannot resolve bean type for bean with name '" + beanName + "'");
		}
		this.beanType = ClassUtils.getUserClass(beanType);
		// <3> 初始化 method、bridgedMethod 属性
		this.method = method;
		this.bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
		// <4> 初始化 parameters 属性
		this.parameters = initMethodParameters();
		// <5> 初始化 responseStatus、responseStatusReason 属性
		evaluateResponseStatus();
	}

	/**
	 * Copy constructor for use in subclasses.
	 */
	protected HandlerMethod(HandlerMethod handlerMethod) {
		Assert.notNull(handlerMethod, "HandlerMethod is required");
		this.bean = handlerMethod.bean;
		this.beanFactory = handlerMethod.beanFactory;
		this.beanType = handlerMethod.beanType;
		this.method = handlerMethod.method;
		this.bridgedMethod = handlerMethod.bridgedMethod;
		this.parameters = handlerMethod.parameters;
		this.responseStatus = handlerMethod.responseStatus;
		this.responseStatusReason = handlerMethod.responseStatusReason;
		this.resolvedFromHandlerMethod = handlerMethod.resolvedFromHandlerMethod;
	}

	/**
	 * Re-create HandlerMethod with the resolved handler.
	 */
	private HandlerMethod(HandlerMethod handlerMethod, Object handler) {
		Assert.notNull(handlerMethod, "HandlerMethod is required");
		Assert.notNull(handler, "Handler object is required");
		this.bean = handler;
		this.beanFactory = handlerMethod.beanFactory;
		this.beanType = handlerMethod.beanType;
		this.method = handlerMethod.method;
		this.bridgedMethod = handlerMethod.bridgedMethod;
		this.parameters = handlerMethod.parameters;
		this.responseStatus = handlerMethod.responseStatus;
		this.responseStatusReason = handlerMethod.responseStatusReason;
		this.resolvedFromHandlerMethod = handlerMethod;
	}


	private MethodParameter[] initMethodParameters() {
		int count = this.bridgedMethod.getParameterCount();
		// 创建 MethodParameter 数组
		MethodParameter[] result = new MethodParameter[count];
		// 遍历 bridgedMethod 的参数，逐个解析参数类型
		for (int i = 0; i < count; i++) {
			HandlerMethodParameter parameter = new HandlerMethodParameter(i);
			// TODO : GenericTypeResolver作用 和 MethodParameter 研究
			GenericTypeResolver.resolveParameterType(parameter, this.beanType);
			result[i] = parameter;
		}
		return result;
	}

	private void evaluateResponseStatus() {
		ResponseStatus annotation = getMethodAnnotation(ResponseStatus.class);
		if (annotation == null) {
			annotation = AnnotatedElementUtils.findMergedAnnotation(getBeanType(), ResponseStatus.class);
		}
		if (annotation != null) {
			this.responseStatus = annotation.code();
			this.responseStatusReason = annotation.reason();
		}
	}


	/**
	 * Return the bean for this handler method.
	 */
	public Object getBean() {
		return this.bean;
	}

	/**
	 * Return the method for this handler method.
	 */
	public Method getMethod() {
		return this.method;
	}

	/**
	 * This method returns the type of the handler for this handler method.
	 * <p>Note that if the bean type is a CGLIB-generated class, the original
	 * user-defined class is returned.
	 */
	public Class<?> getBeanType() {
		return this.beanType;
	}

	/**
	 * If the bean method is a bridge method, this method returns the bridged
	 * (user-defined) method. Otherwise it returns the same method as {@link #getMethod()}.
	 */
	protected Method getBridgedMethod() {
		return this.bridgedMethod;
	}

	/**
	 * Return the method parameters for this handler method.
	 */
	public MethodParameter[] getMethodParameters() {
		return this.parameters;
	}

	/**
	 * Return the specified response status, if any.
	 *
	 * @see ResponseStatus#code()
	 * @since 4.3.8
	 */
	@Nullable
	protected HttpStatus getResponseStatus() {
		return this.responseStatus;
	}

	/**
	 * Return the associated response status reason, if any.
	 *
	 * @see ResponseStatus#reason()
	 * @since 4.3.8
	 */
	@Nullable
	protected String getResponseStatusReason() {
		return this.responseStatusReason;
	}

	/**
	 * Return the HandlerMethod return type.
	 */
	public MethodParameter getReturnType() {
		return new HandlerMethodParameter(-1);
	}

	/**
	 * Return the actual return value type.
	 */
	public MethodParameter getReturnValueType(@Nullable Object returnValue) {
		return new ReturnValueMethodParameter(returnValue);
	}

	/**
	 * Return {@code true} if the method return type is void, {@code false} otherwise.
	 */
	public boolean isVoid() {
		return Void.TYPE.equals(getReturnType().getParameterType());
	}

	/**
	 * Return a single annotation on the underlying method traversing its super methods
	 * if no annotation can be found on the given method itself.
	 * <p>Also supports <em>merged</em> composed annotations with attribute
	 * overrides as of Spring Framework 4.2.2.
	 *
	 * @param annotationType the type of annotation to introspect the method for
	 * @return the annotation, or {@code null} if none found
	 * @see AnnotatedElementUtils#findMergedAnnotation
	 */
	@Nullable
	public <A extends Annotation> A getMethodAnnotation(Class<A> annotationType) {
		return AnnotatedElementUtils.findMergedAnnotation(this.method, annotationType);
	}

	/**
	 * Return whether the parameter is declared with the given annotation type.
	 *
	 * @param annotationType the annotation type to look for
	 * @see AnnotatedElementUtils#hasAnnotation
	 * @since 4.3
	 */
	public <A extends Annotation> boolean hasMethodAnnotation(Class<A> annotationType) {
		return AnnotatedElementUtils.hasAnnotation(this.method, annotationType);
	}

	/**
	 * Return the HandlerMethod from which this HandlerMethod instance was
	 * resolved via {@link #createWithResolvedBean()}.
	 */
	@Nullable
	public HandlerMethod getResolvedFromHandlerMethod() {
		return this.resolvedFromHandlerMethod;
	}

	/**
	 * If the provided instance contains a bean name rather than an object instance,
	 * the bean name is resolved before a {@link HandlerMethod} is created and returned.
	 * <p>
	 * 如果匹配到的 HandlerMethod 对应的 bean 属性是 String 类型 而非一个 Object 实例，
	 * 则在 HandlerMethod 实例被创建和返回之前 bean 属性被解析成 IOC 容器中的 bean（通过 BeanFactory）
	 */
	public HandlerMethod createWithResolvedBean() {
		Object handler = this.bean;
		// 如果是 bean 是 String类型，则获取对应的 handler 对象。例如，bean = userController 字符串，获取后，handler = UserController 对象
		if (this.bean instanceof String) {
			Assert.state(this.beanFactory != null, "Cannot resolve bean name without BeanFactory");
			String beanName = (String) this.bean;
			handler = this.beanFactory.getBean(beanName);
		}
		// 创建 HandlerMethod 对象
		return new HandlerMethod(this, handler);
	}

	/**
	 * Return a short representation of this handler method for log message purposes.
	 *
	 * @since 4.3
	 */
	public String getShortLogMessage() {
		return getBeanType().getName() + "#" + this.method.getName() +
				"[" + this.method.getParameterCount() + " args]";
	}


	private List<Annotation[][]> getInterfaceParameterAnnotations() {
		List<Annotation[][]> parameterAnnotations = this.interfaceParameterAnnotations;
		if (parameterAnnotations == null) {
			parameterAnnotations = new ArrayList<>();
			for (Class<?> ifc : this.method.getDeclaringClass().getInterfaces()) {
				for (Method candidate : ifc.getMethods()) {
					if (isOverrideFor(candidate)) {
						parameterAnnotations.add(candidate.getParameterAnnotations());
					}
				}
			}
			this.interfaceParameterAnnotations = parameterAnnotations;
		}
		return parameterAnnotations;
	}

	private boolean isOverrideFor(Method candidate) {
		if (!candidate.getName().equals(this.method.getName()) ||
				candidate.getParameterCount() != this.method.getParameterCount()) {
			return false;
		}
		Class<?>[] paramTypes = this.method.getParameterTypes();
		if (Arrays.equals(candidate.getParameterTypes(), paramTypes)) {
			return true;
		}
		for (int i = 0; i < paramTypes.length; i++) {
			if (paramTypes[i] !=
					ResolvableType.forMethodParameter(candidate, i, this.method.getDeclaringClass()).resolve()) {
				return false;
			}
		}
		return true;
	}


	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof HandlerMethod)) {
			return false;
		}
		HandlerMethod otherMethod = (HandlerMethod) other;
		return (this.bean.equals(otherMethod.bean) && this.method.equals(otherMethod.method));
	}

	@Override
	public int hashCode() {
		return (this.bean.hashCode() * 31 + this.method.hashCode());
	}

	@Override
	public String toString() {
		return this.method.toGenericString();
	}


	/**
	 * A MethodParameter with HandlerMethod-specific behavior.
	 */
	protected class HandlerMethodParameter extends SynthesizingMethodParameter {

		@Nullable
		private volatile Annotation[] combinedAnnotations;

		public HandlerMethodParameter(int index) {
			super(HandlerMethod.this.bridgedMethod, index);
		}

		protected HandlerMethodParameter(HandlerMethodParameter original) {
			super(original);
		}

		@Override
		public Class<?> getContainingClass() {
			return HandlerMethod.this.getBeanType();
		}

		@Override
		public <T extends Annotation> T getMethodAnnotation(Class<T> annotationType) {
			return HandlerMethod.this.getMethodAnnotation(annotationType);
		}

		@Override
		public <T extends Annotation> boolean hasMethodAnnotation(Class<T> annotationType) {
			return HandlerMethod.this.hasMethodAnnotation(annotationType);
		}

		@Override
		public Annotation[] getParameterAnnotations() {
			Annotation[] anns = this.combinedAnnotations;
			if (anns == null) {
				anns = super.getParameterAnnotations();
				for (Annotation[][] ifcAnns : getInterfaceParameterAnnotations()) {
					Annotation[] paramAnns = ifcAnns[getParameterIndex()];
					if (paramAnns.length > 0) {
						List<Annotation> merged = new ArrayList<>(anns.length + paramAnns.length);
						merged.addAll(Arrays.asList(anns));
						for (Annotation paramAnn : paramAnns) {
							boolean existingType = false;
							for (Annotation ann : anns) {
								if (ann.annotationType() == paramAnn.annotationType()) {
									existingType = true;
									break;
								}
							}
							if (!existingType) {
								merged.add(paramAnn);
							}
						}
						anns = merged.toArray(new Annotation[0]);
					}
				}
				this.combinedAnnotations = anns;
			}
			return anns;
		}

		@Override
		public HandlerMethodParameter clone() {
			return new HandlerMethodParameter(this);
		}
	}


	/**
	 * A MethodParameter for a HandlerMethod return type based on an actual return value.
	 */
	private class ReturnValueMethodParameter extends HandlerMethodParameter {

		@Nullable
		private final Object returnValue;

		public ReturnValueMethodParameter(@Nullable Object returnValue) {
			super(-1);
			this.returnValue = returnValue;
		}

		protected ReturnValueMethodParameter(ReturnValueMethodParameter original) {
			super(original);
			this.returnValue = original.returnValue;
		}

		@Override
		public Class<?> getParameterType() {
			return (this.returnValue != null ? this.returnValue.getClass() : super.getParameterType());
		}

		@Override
		public ReturnValueMethodParameter clone() {
			return new ReturnValueMethodParameter(this);
		}
	}

}
