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

package org.springframework.web.servlet.handler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.MethodIntrospector;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Abstract base class for {@link HandlerMapping} implementations that define
 * a mapping between a request and a {@link HandlerMethod}.
 *
 * <p>For each registered handler method, a unique mapping is maintained with
 * subclasses defining the details of the mapping type {@code <T>}.
 *
 * @param <T> the mapping for a {@link HandlerMethod} containing the conditions
 *            needed to match the handler method to incoming request.
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractHandlerMethodMapping<T> extends AbstractHandlerMapping implements InitializingBean {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";

	private static final HandlerMethod PREFLIGHT_AMBIGUOUS_MATCH =
			new HandlerMethod(new EmptyHandler(), ClassUtils.getMethod(EmptyHandler.class, "handle"));

	private static final CorsConfiguration ALLOW_CORS_CONFIG = new CorsConfiguration();

	static {
		ALLOW_CORS_CONFIG.addAllowedOrigin("*");
		ALLOW_CORS_CONFIG.addAllowedMethod("*");
		ALLOW_CORS_CONFIG.addAllowedHeader("*");
		ALLOW_CORS_CONFIG.setAllowCredentials(true);
	}


	/**
	 * 是否只扫描可访问的 HandlerMethod
	 */
	private boolean detectHandlerMethodsInAncestorContexts = false;

	/**
	 * Mapping命令策略
	 */
	@Nullable
	private HandlerMethodMappingNamingStrategy<T> namingStrategy;

	/**
	 * Mapping 注册表
	 */
	private final MappingRegistry mappingRegistry = new MappingRegistry();


	/**
	 * Whether to detect handler methods in beans in ancestor ApplicationContexts.
	 * <p>Default is "false": Only beans in the current ApplicationContext are
	 * considered, i.e. only in the context that this HandlerMapping itself
	 * is defined in (typically the current DispatcherServlet's context).
	 * <p>Switch this flag on to detect handler beans in ancestor contexts
	 * (typically the Spring root WebApplicationContext) as well.
	 *
	 * @see #getCandidateBeanNames()
	 */
	public void setDetectHandlerMethodsInAncestorContexts(boolean detectHandlerMethodsInAncestorContexts) {
		this.detectHandlerMethodsInAncestorContexts = detectHandlerMethodsInAncestorContexts;
	}

	/**
	 * Configure the naming strategy to use for assigning a default name to every
	 * mapped handler method.
	 * <p>The default naming strategy is based on the capital letters of the
	 * class name followed by "#" and then the method name, e.g. "TC#getFoo"
	 * for a class named TestController with method getFoo.
	 */
	public void setHandlerMethodMappingNamingStrategy(HandlerMethodMappingNamingStrategy<T> namingStrategy) {
		this.namingStrategy = namingStrategy;
	}

	/**
	 * Return the configured naming strategy or {@code null}.
	 */
	@Nullable
	public HandlerMethodMappingNamingStrategy<T> getNamingStrategy() {
		return this.namingStrategy;
	}

	/**
	 * Return a (read-only) map with all mappings and HandlerMethod's.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		this.mappingRegistry.acquireReadLock();
		try {
			return Collections.unmodifiableMap(this.mappingRegistry.getMappings());
		} finally {
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Return the handler methods for the given mapping name.
	 *
	 * @param mappingName the mapping name
	 * @return a list of matching HandlerMethod's or {@code null}; the returned
	 * list will never be modified and is safe to iterate.
	 * @see #setHandlerMethodMappingNamingStrategy
	 */
	@Nullable
	public List<HandlerMethod> getHandlerMethodsForMappingName(String mappingName) {
		return this.mappingRegistry.getHandlerMethodsByMappingName(mappingName);
	}

	/**
	 * Return the internal mapping registry. Provided for testing purposes.
	 */
	MappingRegistry getMappingRegistry() {
		return this.mappingRegistry;
	}

	/**
	 * Register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 *
	 * @param mapping the mapping for the handler method
	 * @param handler the handler
	 * @param method  the method
	 */
	public void registerMapping(T mapping, Object handler, Method method) {
		if (logger.isTraceEnabled()) {
			logger.trace("Register \"" + mapping + "\" to " + method.toGenericString());
		}
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Un-register the given mapping.
	 * <p>This method may be invoked at runtime after initialization has completed.
	 *
	 * @param mapping the mapping to unregister
	 */
	public void unregisterMapping(T mapping) {
		if (logger.isTraceEnabled()) {
			logger.trace("Unregister mapping \"" + mapping + "\"");
		}
		this.mappingRegistry.unregister(mapping);
	}


	// Handler method detection

	/**
	 * Detects handler methods at initialization.
	 * <p>
	 * AbstractHandlerMethodMapping 通过实现 InitializingBean 接口，重写初始化方法 afterPropertiesSet
	 * => 初始化控制器方法 HandlerMethod
	 *
	 * @see #initHandlerMethods
	 */
	@Override
	public void afterPropertiesSet() {
		// 初始化处理器的方法
		initHandlerMethods();
	}

	/**
	 * Scan beans in the ApplicationContext, detect and register handler methods.
	 *
	 * @see #getCandidateBeanNames()
	 * @see #processCandidateBean
	 * @see #handlerMethodsInitialized
	 */
	protected void initHandlerMethods() {
		// <1.1> 遍历 Bean ，逐个处理
		for (String beanName : getCandidateBeanNames()) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				// <1.2> 处理 Bean
				processCandidateBean(beanName);
			}
		}
		// <2> 初始化处理器的方法们。
		handlerMethodsInitialized(getHandlerMethods());
	}

	/**
	 * Determine the names of candidate beans in the application context.
	 *
	 * @see #setDetectHandlerMethodsInAncestorContexts
	 * @see BeanFactoryUtils#beanNamesForTypeIncludingAncestors
	 * @since 5.1
	 */
	protected String[] getCandidateBeanNames() {
		return (this.detectHandlerMethodsInAncestorContexts ?
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(obtainApplicationContext(), Object.class) :
				obtainApplicationContext().getBeanNamesForType(Object.class));
	}

	/**
	 * Determine the type of the specified candidate bean and call
	 * {@link #detectHandlerMethods} if identified as a handler type.
	 * <p>This implementation avoids bean creation through checking
	 * {@link org.springframework.beans.factory.BeanFactory#getType}
	 * and calling {@link #detectHandlerMethods} with the bean name.
	 *
	 * @param beanName the name of the candidate bean
	 * @see #isHandler
	 * @see #detectHandlerMethods
	 * @since 5.1
	 */
	protected void processCandidateBean(String beanName) {
		// <1> 获得 Bean 对应的类型
		Class<?> beanType = null;
		try {
			beanType = obtainApplicationContext().getType(beanName);
		} catch (Throwable ex) {
			// An unresolvable bean type, probably from a lazy bean - let's ignore it.
			if (logger.isTraceEnabled()) {
				logger.trace("Could not resolve type for bean '" + beanName + "'", ex);
			}
		}
		// <2> 判断 Bean 是否为处理器，如果是，则扫描处理器方法
		if (beanType != null && isHandler(beanType)) {
			detectHandlerMethods(beanName);
		}
	}

	/**
	 * Look for handler methods in the specified handler bean.
	 * 在指定的处理器 bean 中查找处理器方法
	 *
	 * @param handler either a bean name or an actual handler instance
	 * @see #getMappingForMethod
	 */
	protected void detectHandlerMethods(Object handler) {
		// <1> 获得处理器类型
		Class<?> handlerType = (handler instanceof String ?
				obtainApplicationContext().getType((String) handler) : handler.getClass());


		if (handlerType != null) {
			// <2> 获得真实的类。因为，handlerType 可能是代理类，例如：CGLIB 代理产生的代理子类
			Class<?> userType = ClassUtils.getUserClass(handlerType);
			// <3> 获得匹配的方法的集合
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> {
						try {
							return getMappingForMethod(	method, userType);
						} catch (Throwable ex) {
							throw new IllegalStateException("Invalid mapping on handler class [" +
									userType.getName() + "]: " + method, ex);
						}
					});
			if (logger.isTraceEnabled()) {
				logger.trace("Mapped " + methods.size() + " handler method(s) for " + userType + ": " + methods);
			}
			// <4> 遍历方法，逐个注册 HandlerMethod
			methods.forEach((method, mapping) -> {
				Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
				registerHandlerMethod(handler, invocableMethod, mapping);
			});
		}
	}

	/**
	 * Register a handler method and its unique mapping. Invoked at startup for
	 * each detected handler method.
	 * 注册 HandlerMethod 和它的独一无二的映射。该方法在每个 HandlerMethod 被检测到以后调用。
	 * <p>
	 * TODO:这里的 Mapping 到底是什么东东？
	 * => 实际上，在 RequestMappingHandlerMapping 中，就是 @RequestMapping 解析成的 RequestMappingInfo 对象。
	 *
	 * @param handler the bean name of the handler or the handler instance
	 * @param method  the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 *                               under the same mapping
	 */
	protected void registerHandlerMethod(Object handler, Method method, T mapping) {
		this.mappingRegistry.register(mapping, handler, method);
	}

	/**
	 * Create the HandlerMethod instance.
	 *
	 * @param handler either a bean name or an actual handler instance
	 * @param method  the target method
	 * @return the created HandlerMethod
	 */
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		HandlerMethod handlerMethod;
		if (handler instanceof String) {
			// <1> 如果 handler 类型为 String， 说明对应一个 Bean 对象，
			// 例如 UserController 使用 @Controller 注解后，默认 handler 为它的 beanName ，即 `userController`
			String beanName = (String) handler;
			handlerMethod = new HandlerMethod(beanName,
					obtainApplicationContext().getAutowireCapableBeanFactory(), method);
		} else {
			// <2> 如果 handler 类型非 String ，说明是一个已经是一个 handler 对象，就无需处理，直接创建 HandlerMethod 对象
			handlerMethod = new HandlerMethod(handler, method);
		}
		return handlerMethod;
	}

	/**
	 * Extract and return the CORS configuration for the mapping.
	 */
	@Nullable
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, T mapping) {
		return null;
	}

	/**
	 * Invoked after all handler methods have been detected.
	 *
	 * @param handlerMethods a read-only map with handler methods and mappings.
	 */
	protected void handlerMethodsInitialized(Map<T, HandlerMethod> handlerMethods) {
		// Total includes detected mappings + explicit registrations via registerMapping
		int total = handlerMethods.size();
		if ((logger.isTraceEnabled() && total == 0) || (logger.isDebugEnabled() && total > 0)) {
			logger.debug(total + " mappings in " + formatMappingName());
		}
	}


	// Handler method lookup

	/**
	 * Look up a handler method for the given request.
	 */
	@Override
	protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
		// <1> 获得请求的路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		// <2> 获得写锁
		this.mappingRegistry.acquireReadLock();
		try {
			// <3> 获得 HandlerMethod 对象
			HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
			// <4> 进一步，获得 HandlerMethod 对象
			return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null);
		} finally {
			// <5> 释放写锁
			this.mappingRegistry.releaseReadLock();
		}
	}

	/**
	 * Look up the best-matching handler method for the current request.
	 * If multiple matches are found, the best match is selected.
	 *
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request    the current request
	 * @return the best-matching handler method, or {@code null} if no match
	 * @see #handleMatch(Object, String, HttpServletRequest)
	 * @see #handleNoMatch(Set, String, HttpServletRequest)
	 */
	@Nullable
	protected HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
		// <1> Match 数组，存储匹配上当前请求的结果
		List<Match> matches = new ArrayList<>();
		// <1.1> 优先，基于直接 URL 的 Mapping 们，进行匹配
		List<T> directPathMatches = this.mappingRegistry.getMappingsByUrl(lookupPath);
		if (directPathMatches != null) {
			addMatchingMappings(directPathMatches, matches, request);
		}
		// <1.2> 其次，扫描注册表的 Mapping 们，进行匹配
		if (matches.isEmpty()) {
			// No choice but to go through all mappings...
			addMatchingMappings(this.mappingRegistry.getMappings().keySet(), matches, request);
		}

		// <2> 如果匹配到，则获取最佳匹配的 Match 对象的 handlerMethod 属性
		if (!matches.isEmpty()) {
			// <2.1> 创建 MatchComparator 对象，排序 matches 结果
			Comparator<Match> comparator = new MatchComparator(getMappingComparator(request));
			matches.sort(comparator);
			// <2.2> 获得首个 Match 对象
			Match bestMatch = matches.get(0);
			// <2.3> 处理存在多个 Match 对象的情况！！
			if (matches.size() > 1) {
				if (logger.isTraceEnabled()) {
					logger.trace(matches.size() + " matching mappings: " + matches);
				}
				if (CorsUtils.isPreFlightRequest(request)) {
					return PREFLIGHT_AMBIGUOUS_MATCH;
				}
				// 比较 bestMatch 和 secondBestMatch ，如果相等，说明有问题，抛出 IllegalStateException 异常
				// 因为，两个优先级一样高，说明无法判断谁更优先
				Match secondBestMatch = matches.get(1);
				if (comparator.compare(bestMatch, secondBestMatch) == 0) {
					Method m1 = bestMatch.handlerMethod.getMethod();
					Method m2 = secondBestMatch.handlerMethod.getMethod();
					String uri = request.getRequestURI();
					throw new IllegalStateException(
							"Ambiguous handler methods mapped for '" + uri + "': {" + m1 + ", " + m2 + "}");
				}
			}
			// <2.4> 处理首个 Match 对象
			handleMatch(bestMatch.mapping, lookupPath, request);
			// <2.5> 返回首个 Match 对象的 handlerMethod 属性
			return bestMatch.handlerMethod;
		} else {
			// <3> 如果匹配不到，则处理不匹配的情况
			return handleNoMatch(this.mappingRegistry.getMappings().keySet(), lookupPath, request);
		}
	}

	private void addMatchingMappings(Collection<T> mappings, List<Match> matches, HttpServletRequest request) {
		// 遍历 Mapping 数组
		for (T mapping : mappings) {
			// <1> 执行匹配
			T match = getMatchingMapping(mapping, request);
			// <2> 如果匹配，则创建 Match 对象，添加到 matches 中
			if (match != null) {
				matches.add(new Match(match, this.mappingRegistry.getMappings().get(mapping)));
			}
		}
	}

	/**
	 * Invoked when a matching mapping is found.
	 *
	 * @param mapping    the matching mapping
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request    the current request
	 */
	protected void handleMatch(T mapping, String lookupPath, HttpServletRequest request) {
		request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, lookupPath);
	}

	/**
	 * Invoked when no matching mapping is not found.
	 *
	 * @param mappings   all registered mappings
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request    the current request
	 * @throws ServletException in case of errors
	 */
	@Nullable
	protected HandlerMethod handleNoMatch(Set<T> mappings, String lookupPath, HttpServletRequest request)
			throws Exception {

		return null;
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, HttpServletRequest request) {
		CorsConfiguration corsConfig = super.getCorsConfiguration(handler, request);
		if (handler instanceof HandlerMethod) {
			HandlerMethod handlerMethod = (HandlerMethod) handler;
			if (handlerMethod.equals(PREFLIGHT_AMBIGUOUS_MATCH)) {
				return AbstractHandlerMethodMapping.ALLOW_CORS_CONFIG;
			} else {
				CorsConfiguration corsConfigFromMethod = this.mappingRegistry.getCorsConfiguration(handlerMethod);
				corsConfig = (corsConfig != null ? corsConfig.combine(corsConfigFromMethod) : corsConfigFromMethod);
			}
		}
		return corsConfig;
	}


	// Abstract template methods

	/**
	 * Whether the given type is a handler with handler methods.
	 * 判断 bean 类型是否是带处理器方法的 Handler
	 * 该方法在 RequestMappingHandlerMapping 类中实现
	 *
	 * @param beanType the type of the bean being checked
	 * @return "true" if this a handler type, "false" otherwise.
	 */
	protected abstract boolean isHandler(Class<?> beanType);

	/**
	 * Provide the mapping for a handler method. A method for which no
	 * mapping can be provided is not a handler method.
	 * 为一个 HandlerMethod 方法提供 Mapping 映射。
	 * 如果对于一个方法来说，没有映射提供，那么这个方法就不是处理器方法
	 *
	 * @param method      the method to provide a mapping for
	 * @param handlerType the handler type, possibly a sub-type of the method's
	 *                    declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Extract and return the URL paths contained in a mapping.
	 */
	protected abstract Set<String> getMappingPathPatterns(T mapping);

	/**
	 * Check if a mapping matches the current request and return a (potentially
	 * new) mapping with conditions relevant to the current request.
	 *
	 * 检查映射是否与当前请求匹配，并使用与当前请求相关的条件返回(可能是新的)映射。
	 *
	 * @param mapping the mapping to get a match for
	 * @param request the current HTTP servlet request
	 * @return the match, or {@code null} if the mapping doesn't match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, HttpServletRequest request);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 *
	 * @param request the current request
	 * @return the comparator (never {@code null})
	 */
	protected abstract Comparator<T> getMappingComparator(HttpServletRequest request);


	/**
	 * A registry that maintains all mappings to handler methods, exposing methods
	 * to perform lookups and providing concurrent access.
	 * <p>Package-private for testing purposes.
	 * <p>
	 * 一个注册表，它维护到处理器方法的所有映射，公开执行查找的方法并提供并发访问。
	 * 为了测试目的，为包级私有
	 */
	class MappingRegistry {

		/**
		 * 注册表
		 * KEY:Mapping 即 <T> 泛型
		 */
		private final Map<T, MappingRegistration<T>> registry = new HashMap<>();

		/**
		 * 注册表2
		 * KEY:Mapping 即 <T> 泛型
		 */
		private final Map<T, HandlerMethod> mappingLookup = new LinkedHashMap<>();

		/**
		 * 直接 URL 映射
		 * KEY:直接 URL
		 * VALUE: Mapping 数组
		 */
		private final MultiValueMap<String, T> urlLookup = new LinkedMultiValueMap<>();

		/**
		 * Mapping 的名字和 HandlerMethod 的映射
		 * KEY：Mapping 的名字
		 * VALUE：HandlerMethod 数组
		 */
		private final Map<String, List<HandlerMethod>> nameLookup = new ConcurrentHashMap<>();

		private final Map<HandlerMethod, CorsConfiguration> corsLookup = new ConcurrentHashMap<>();

		/**
		 * 读写锁
		 */
		private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

		/**
		 * Return all mappings and handler methods. Not thread-safe.
		 *
		 * @see #acquireReadLock()
		 */
		public Map<T, HandlerMethod> getMappings() {
			return this.mappingLookup;
		}

		/**
		 * Return matches for the given URL path. Not thread-safe.
		 *
		 * @see #acquireReadLock()
		 */
		@Nullable
		public List<T> getMappingsByUrl(String urlPath) {
			return this.urlLookup.get(urlPath);
		}

		/**
		 * Return handler methods by mapping name. Thread-safe for concurrent use.
		 */
		public List<HandlerMethod> getHandlerMethodsByMappingName(String mappingName) {
			return this.nameLookup.get(mappingName);
		}

		/**
		 * Return CORS configuration. Thread-safe for concurrent use.
		 */
		public CorsConfiguration getCorsConfiguration(HandlerMethod handlerMethod) {
			HandlerMethod original = handlerMethod.getResolvedFromHandlerMethod();
			return this.corsLookup.get(original != null ? original : handlerMethod);
		}

		/**
		 * Acquire the read lock when using getMappings and getMappingsByUrl.
		 */
		public void acquireReadLock() {
			this.readWriteLock.readLock().lock();
		}

		/**
		 * Release the read lock after using getMappings and getMappingsByUrl.
		 */
		public void releaseReadLock() {
			this.readWriteLock.readLock().unlock();
		}

		/**
		 * 注册
		 *
		 * @param mapping
		 * @param handler
		 * @param method
		 */
		public void register(T mapping, Object handler, Method method) {
			// <1> 获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				// <2.1> 创建 HandlerMethod 对象
				HandlerMethod handlerMethod = createHandlerMethod(handler, method);
				// <2.2> 校验当前 mapping 是否存在，否则如果 mapping 已存在，
				// 且 value 值与 当前 handlerMethod 不一致，则抛出 IllegalStateException 异常
				assertUniqueMethodMapping(handlerMethod, mapping);
				// <2.3> 添加 mapping + HandlerMethod 到 mappingLookup 中
				this.mappingLookup.put(mapping, handlerMethod);

				// <3.1> 获得 mapping 对应的普通 URL 数组
				List<String> directUrls = getDirectUrls(mapping);
				// <3.2> 添加到 url + mapping 到 urlLookup 集合中
				for (String url : directUrls) {
					this.urlLookup.add(url, mapping);
				}

				// <4> 初始化 nameLookup
				String name = null;
				if (getNamingStrategy() != null) {
					// <4.1> 获得 Mapping 的名字
					name = getNamingStrategy().getName(handlerMethod, mapping);
					// <4.2> 添加到 mapping 的名字 + HandlerMethod 到 nameLookup 中
					addMappingName(name, handlerMethod);
				}

				// <5> TODO
				CorsConfiguration corsConfig = initCorsConfiguration(handler, method, mapping);
				if (corsConfig != null) {
					this.corsLookup.put(handlerMethod, corsConfig);
				}

				// <6> 创建 MappingRegistration 对象，并 mapping + MappingRegistration 添加到 registry 中
				this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod, directUrls, name));
			} finally {
				// <7> 释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		private void assertUniqueMethodMapping(HandlerMethod newHandlerMethod, T mapping) {
			HandlerMethod handlerMethod = this.mappingLookup.get(mapping);
			if (handlerMethod != null && !handlerMethod.equals(newHandlerMethod)) {
				throw new IllegalStateException(
						"Ambiguous mapping. Cannot map '" + newHandlerMethod.getBean() + "' method \n" +
								newHandlerMethod + "\nto " + mapping + ": There is already '" +
								handlerMethod.getBean() + "' bean method\n" + handlerMethod + " mapped.");
			}
		}

		private List<String> getDirectUrls(T mapping) {
			List<String> urls = new ArrayList<>(1);
			// 遍历 Mapping 对应的路径
			for (String path : getMappingPathPatterns(mapping)) {
				/*
				 * 非**模式**路径
				 * 例如，@RequestMapping("/user/login") 注解对应的路径，就是直接路径。
				 * 例如，@RequestMapping("/user/${id}") 注解对应的路径，不是直接路径。
				 */
				if (!getPathMatcher().isPattern(path)) {
					urls.add(path);
				}
			}
			return urls;
		}

		private void addMappingName(String name, HandlerMethod handlerMethod) {
			// 获得 Mapping 的名字，对应的 HandlerMethod 数组
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				oldList = Collections.emptyList();
			}

			// 如果已经存在，则不用添加
			for (HandlerMethod current : oldList) {
				if (handlerMethod.equals(current)) {
					return;
				}
			}

			// 添加到 nameLookup 中
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() + 1);
			newList.addAll(oldList);
			newList.add(handlerMethod);
			this.nameLookup.put(name, newList);
		}

		/**
		 * 取消注册
		 *
		 * @param mapping
		 */
		public void unregister(T mapping) {
			// 1.获得写锁
			this.readWriteLock.writeLock().lock();
			try {
				// 2.从 registry 开始移除
				MappingRegistration<T> definition = this.registry.remove(mapping);
				if (definition == null) {
					return;
				}

				// 3.从 mappingLookup 开始移除
				this.mappingLookup.remove(definition.getMapping());

				// 4.从 urlLookup 开始移除
				for (String url : definition.getDirectUrls()) {
					List<T> list = this.urlLookup.get(url);
					if (list != null) {
						list.remove(definition.getMapping());
						if (list.isEmpty()) {
							this.urlLookup.remove(url);
						}
					}
				}

				// 5.从 nameLookup 开始移除
				removeMappingName(definition);

				// 6.从 corsLookup 开始移除
				this.corsLookup.remove(definition.getHandlerMethod());
			} finally {
				// 7.释放写锁
				this.readWriteLock.writeLock().unlock();
			}
		}

		/**
		 * 从 nameLookup 移除
		 * 注意：这里只移除 nameLookup 下 value = List<HandlerMethod> 中
		 * 元素等于传入参数 {@link MappingRegistration#getHandlerMethod } 方法返回值
		 *
		 * @param definition
		 */
		private void removeMappingName(MappingRegistration<T> definition) {
			String name = definition.getMappingName();
			if (name == null) {
				return;
			}
			HandlerMethod handlerMethod = definition.getHandlerMethod();
			List<HandlerMethod> oldList = this.nameLookup.get(name);
			if (oldList == null) {
				return;
			}
			if (oldList.size() <= 1) {
				this.nameLookup.remove(name);
				return;
			}
			List<HandlerMethod> newList = new ArrayList<>(oldList.size() - 1);
			for (HandlerMethod current : oldList) {
				if (!current.equals(handlerMethod)) {
					newList.add(current);
				}
			}
			this.nameLookup.put(name, newList);
		}
	}


	private static class MappingRegistration<T> {

		/**
		 * Mapping 对象
		 */
		private final T mapping;

		/**
		 * HandlerMethod 对象
		 */
		private final HandlerMethod handlerMethod;

		/**
		 * 直接 URL 数组
		 */
		private final List<String> directUrls;

		/**
		 * {@link #mapping } 的名字
		 */
		@Nullable
		private final String mappingName;

		public MappingRegistration(T mapping, HandlerMethod handlerMethod,
								   @Nullable List<String> directUrls, @Nullable String mappingName) {

			Assert.notNull(mapping, "Mapping must not be null");
			Assert.notNull(handlerMethod, "HandlerMethod must not be null");
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
			this.directUrls = (directUrls != null ? directUrls : Collections.emptyList());
			this.mappingName = mappingName;
		}

		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}

		public List<String> getDirectUrls() {
			return this.directUrls;
		}

		@Nullable
		public String getMappingName() {
			return this.mappingName;
		}
	}


	/**
	 * A thin wrapper around a matched HandlerMethod and its mapping, for the purpose of
	 * comparing the best match with a comparator in the context of the current request.
	 *
	 * Match 是一个匹配的 HandlerMethod 和它对应的映射的包装类，这是为了在当前请求的上下文中比较与比较器的最佳匹配
	 */
	private class Match {

		/**
		 * Mapping 对象
		 */
		private final T mapping;

		/**
		 * HandlerMethod 对象
		 */
		private final HandlerMethod handlerMethod;

		public Match(T mapping, HandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match> {

		private final Comparator<T> comparator;

		public MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}

		@Override
		public int compare(Match match1, Match match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}


	private static class EmptyHandler {

		@SuppressWarnings("unused")
		public void handle() {
			throw new UnsupportedOperationException("Not implemented");
		}
	}

}
