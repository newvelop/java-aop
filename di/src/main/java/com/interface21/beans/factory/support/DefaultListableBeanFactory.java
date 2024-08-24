package com.interface21.beans.factory.support;

import com.interface21.beans.BeanUtils;
import com.interface21.beans.factory.ConfigurableListableBeanFactory;
import com.interface21.beans.factory.config.BeanDefinition;
import com.interface21.beans.factory.config.BeanPostProcessor;
import com.interface21.context.annotation.AnnotatedBeanDefinition;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class DefaultListableBeanFactory implements BeanDefinitionRegistry, ConfigurableListableBeanFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultListableBeanFactory.class);

    private final Map<Class<?>, Object> beans = new HashMap<>();

    private final Map<Class<?>, BeanDefinition> beanDefinitions = new HashMap<>();

    private final List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();

    @Override
    public void preInstantiateSingletons() {
        for (Class<?> clazz : getBeanClasses()) {
            getBean(clazz);
        }
    }

    @Override
    public Set<Class<?>> getBeanClasses() {
        return beanDefinitions.keySet();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> clazz) {
        Object bean = beans.get(clazz);
        if (bean != null) {
            return (T) bean;
        }

        BeanDefinition beanDefinition = beanDefinitions.get(clazz);
        if (beanDefinition instanceof AnnotatedBeanDefinition) {
            return (T) createAnnotatedBean(beanDefinition)
                    .map(b -> initializeBean(clazz, b))
                    .orElse(null);
        }

        Optional<Class<?>> concreteClazz = BeanFactoryUtils.findConcreteClass(clazz, getBeanClasses());
        if (concreteClazz.isEmpty()) {
            return null;
        }

        beanDefinition = beanDefinitions.get(concreteClazz.get());
        log.debug("BeanDefinition : {}", beanDefinition);
        bean = inject(beanDefinition);
        return (T) initializeBean(concreteClazz.get(), bean);
    }

    private Object initializeBean(Class<?> clazz, Object bean) {
        Object processedBean = postProcess(bean);
        beans.put(clazz, processedBean);
        initialize(processedBean, clazz);
        return processedBean;
    }

    private Object postProcess(Object bean) {
        List<BeanPostProcessor> selectedBeanPostProcessor = beanPostProcessors.stream()
                .filter(beanPostProcessor -> beanPostProcessor.accept(bean))
                .toList();
        if (selectedBeanPostProcessor.isEmpty()) {
            return bean;
        }
        if (selectedBeanPostProcessor.size() == 1) {
            return selectedBeanPostProcessor.get(0)
                    .postInitialization(bean);
        }
        throw new IllegalStateException("PostProcess 처리는 한개만 가능합니다.");
    }

    private void initialize(Object bean, Class<?> beanClass) {
        Set<Method> initializeMethods = BeanFactoryUtils.getBeanMethods(beanClass, PostConstruct.class);
        if (initializeMethods.isEmpty()) {
            return;
        }
        for (Method initializeMethod : initializeMethods) {
            log.debug("@PostConstruct Initialize Method : {}", initializeMethod);
            BeanFactoryUtils.invokeMethod(initializeMethod, bean,
                    populateArguments(initializeMethod.getParameterTypes()));
        }
    }

    private Optional<Object> createAnnotatedBean(BeanDefinition beanDefinition) {
        final var annotatedBeanDefinition = (AnnotatedBeanDefinition) beanDefinition;
        final var method = annotatedBeanDefinition.getMethod();
        final var bean = getBean(method.getDeclaringClass());
        final var args = populateArguments(method.getParameterTypes());
        return BeanFactoryUtils.invokeMethod(method, bean, args);
    }

    private Object[] populateArguments(Class<?>[] paramTypes) {
        List<Object> args = new ArrayList<>();
        for (Class<?> param : paramTypes) {
            Object bean = getBean(param);
            if (bean == null) {
                throw new NullPointerException(param + "에 해당하는 Bean이 존재하지 않습니다.");
            }
            args.add(getBean(param));
        }
        return args.toArray();
    }

    private Object inject(BeanDefinition beanDefinition) {
        if (beanDefinition.getResolvedInjectMode() == InjectType.INJECT_NO) {
            return BeanUtils.instantiate(beanDefinition.getBeanClass());
        } else if (beanDefinition.getResolvedInjectMode() == InjectType.INJECT_FIELD) {
            return injectFields(beanDefinition);
        } else {
            return injectConstructor(beanDefinition);
        }
    }

    private Object injectFields(BeanDefinition beanDefinition) {
        Object bean = BeanUtils.instantiate(beanDefinition.getBeanClass());
        Set<Field> injectFields = beanDefinition.getInjectFields();
        for (Field field : injectFields) {
            injectField(bean, field);
        }
        return bean;
    }

    private void injectField(Object bean, Field field) {
        log.debug("Inject Bean : {}, Field : {}", bean, field);
        try {
            field.setAccessible(true);
            field.set(bean, getBean(field.getType()));
        } catch (IllegalAccessException | IllegalArgumentException e) {
            log.error(e.getMessage());
        }
    }

    private Object injectConstructor(BeanDefinition beanDefinition) {
        Constructor<?> constructor = beanDefinition.getInjectConstructor();
        Object[] args = populateArguments(constructor.getParameterTypes());
        return BeanUtils.instantiateClass(constructor, args);
    }

    @Override
    public void clear() {
        beanDefinitions.clear();
        beans.clear();
    }

    @Override
    public void registerBeanDefinition(Class<?> clazz, BeanDefinition beanDefinition) {
        log.debug("register bean : {}", clazz);
        beanDefinitions.put(clazz, beanDefinition);
    }

    public void registerBeanPostProcessor(BeanPostProcessor postProcessor) {
        beanPostProcessors.add(postProcessor);
    }
}
