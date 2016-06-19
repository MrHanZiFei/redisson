package org.redisson;

import java.lang.reflect.Constructor;
import java.util.Map;
import jodd.bean.BeanCopy;
import jodd.bean.BeanUtil;
//import java.util.concurrent.TimeUnit;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.FieldProxy;
import net.bytebuddy.matcher.ElementMatchers;
//import org.redisson.core.RExpirable;
//import org.redisson.core.RExpirableAsync;
//import org.redisson.core.RMap;
import org.redisson.core.RObject;
//import org.redisson.core.RObjectAsync;
import org.redisson.liveobject.CodecProvider;
import org.redisson.liveobject.LiveObjectTemplate;
import org.redisson.liveobject.RLiveObjectService;
import org.redisson.liveobject.RLiveObject;
import org.redisson.liveobject.annotation.REntity;
import org.redisson.liveobject.annotation.RId;
import org.redisson.liveobject.core.AccessorInterceptor;
//import org.redisson.liveobject.core.ExpirableInterceptor;
import org.redisson.liveobject.core.LiveObjectInterceptor;
import org.redisson.liveobject.misc.Introspectior;

public class RedissonLiveObjectService implements RLiveObjectService {

    private final Map<Class, Class> classCache;
    private final RedissonClient redisson;
//    private final ObjectMapper detachMapper;
//    private final ObjectMapper attachMapper;
    private final CodecProvider codecProvider;
//    private final SimpleModule module;

    public RedissonLiveObjectService(RedissonClient redisson, Map<Class, Class> classCache, CodecProvider codecProvider) {
        this.redisson = redisson;
        this.classCache = classCache;
        this.codecProvider = codecProvider;
    }

    //TODO: Support ID Generator
    //TODO: Add ttl renewal functionality
//    @Override
//    public <T, K> T get(Class<T> entityClass, K id, long timeToLive, TimeUnit timeUnit) {
//        T instance = get(entityClass, id);
//        RMap map = ((RLiveObject) instance).getLiveObjectLiveMap();
//        map.put("RLiveObjectDefaultTimeToLiveValue", timeToLive);
//        map.put("RLiveObjectDefaultTimeToLiveUnit", timeUnit.toString());
//        map.expire(timeToLive, timeUnit);
//        return instance;
//    }
    
    @Override
    public <T, K> T get(Class<T> entityClass, K id) {
        try {
            return instantiateLiveObject(getProxyClass(entityClass), id);
        } catch (Exception ex) {
            unregisterClass(entityClass);
            throw new RuntimeException(ex);
        }
    }

    @Override
    public <T> T attach(T detachedObject) {
        Class<T> entityClass = (Class<T>) detachedObject.getClass();
        try {
            Class<? extends T> proxyClass = getProxyClass(entityClass);
            return instantiateLiveObject(proxyClass,
                    BeanUtil.pojo.getSimpleProperty(detachedObject,
                            getRIdFieldName(detachedObject.getClass())));
        } catch (Exception ex) {
            unregisterClass(entityClass);
            throw new RuntimeException(ex);
        }
    }

    @Override
    public <T> T merge(T detachedObject) {
        T attachedObject = attach(detachedObject);
        copy(detachedObject, attachedObject);
        return attachedObject;
    }

    @Override
    public <T> T persist(T detachedObject) {
        T attachedObject = attach(detachedObject);
        if (asLiveObject(attachedObject).isPhantom()) {
            copy(detachedObject, attachedObject);
            return attachedObject;
        }
        throw new IllegalStateException("This REntity already exists.");
    }

    @Override
    public <T> T detach(T attachedObject) {
        try {
            T detached = instantiateDetachedObject((Class<T>) attachedObject.getClass().getSuperclass(), asLiveObject(attachedObject).getLiveObjectId());
            BeanCopy.beans(attachedObject, detached).declared(false, true).copy();
            return detached;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public <T> void remove(T attachedObject) {
        asLiveObject(attachedObject).delete();
    }

    @Override
    public <T, K> void remove(Class<T> entityClass, K id) {
        asLiveObject(get(entityClass, id)).delete();
    }

    @Override
    public <T> RLiveObject asLiveObject(T instance) {
        return (RLiveObject) instance;
    }

    @Override
    public <T> boolean isLiveObject(T instance) {
        return instance instanceof RLiveObject;
    }

    private <T> void copy(T detachedObject, T attachedObject) {
        String idFieldName = getRIdFieldName(detachedObject.getClass());
        BeanCopy.beans(detachedObject, attachedObject)
                .ignoreNulls(true)
                .exclude(idFieldName)
                .copy();
    }

    private String getRIdFieldName(Class cls) {
        return Introspectior.getFieldsWithAnnotation(cls, RId.class)
                .getOnly()
                .getName();
    }

    private <T, K> T instantiateLiveObject(Class<T> proxyClass, K id) throws Exception {
        T instance = instantiate(proxyClass, id);
        asLiveObject(instance).setLiveObjectId(id);
        return instance;
    }
    
    private <T, K> T instantiateDetachedObject(Class<T> cls, K id) throws Exception {
        T instance = instantiate(cls, id);
        if (BeanUtil.pojo.getSimpleProperty(instance, getRIdFieldName(cls)) == null) {
            BeanUtil.pojo.setSimpleProperty(instance, getRIdFieldName(cls), id);
        }
        return instance;
    }
    
    private <T, K> T instantiate(Class<T> cls, K id) throws Exception {
        try {
            return cls.newInstance();
        } catch (Exception exception) {
            for (Constructor<?> ctor : classCache.containsKey(cls) ? cls.getConstructors() : cls.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0].isAssignableFrom(id.getClass())) {
                    return (T) ctor.newInstance(id);
                }
            }
        }
        throw new NoSuchMethodException("Unable to find constructor matching only the RId field.");
    }

    private <T> Class<? extends T> getProxyClass(Class<T> entityClass) throws Exception {
        if (!classCache.containsKey(entityClass)) {
            validateClass(entityClass);
            registerClass(entityClass);
        }
        return classCache.get(entityClass);
    }

    private <T> void validateClass(Class<T> entityClass) throws Exception {
        if (entityClass.isAnonymousClass() || entityClass.isLocalClass()) {
            throw new IllegalArgumentException(entityClass.getName() + " is not publically accessable.");
        }
        if (!entityClass.isAnnotationPresent(REntity.class)) {
            throw new IllegalArgumentException("REntity annotation is missing from class type declaration.");
        }
        FieldList<FieldDescription.InDefinedShape> fieldsWithRIdAnnotation
                = Introspectior.getFieldsWithAnnotation(entityClass, RId.class);
        if (fieldsWithRIdAnnotation.size() == 0) {
            throw new IllegalArgumentException("RId annotation is missing from class field declaration.");
        }
        if (fieldsWithRIdAnnotation.size() > 1) {
            throw new IllegalArgumentException("Only one field with RId annotation is allowed in class field declaration.");
        }
        FieldDescription.InDefinedShape idField = fieldsWithRIdAnnotation.getOnly();
        String idFieldName = idField.getName();
        if (entityClass.getDeclaredField(idFieldName).getType().isAnnotationPresent(REntity.class)) {
            throw new IllegalArgumentException("Field with RId annotation cannot be a type of which class is annotated with REntity.");
        }
        if (entityClass.getDeclaredField(idFieldName).getType().isAssignableFrom(RObject.class)) {
            throw new IllegalArgumentException("Field with RId annotation cannot be a type of RObject");
        }
    }

    private <T> void registerClass(Class<T> entityClass) throws Exception {
        DynamicType.Builder<T> builder = new ByteBuddy()
                .subclass(entityClass);
        for (FieldDescription.InDefinedShape field
                : Introspectior.getTypeDescription(LiveObjectTemplate.class)
                .getDeclaredFields()) {
            builder = builder.define(field);
        }
        
        Class<? extends T> proxied = builder.method(ElementMatchers.isDeclaredBy(
                Introspectior.getTypeDescription(RLiveObject.class))
                .and(ElementMatchers.isGetter().or(ElementMatchers.isSetter())))
                .intercept(MethodDelegation.to(
                                new LiveObjectInterceptor(redisson, codecProvider, entityClass,
                                        getRIdFieldName(entityClass)))
                        .appendParameterBinder(FieldProxy.Binder
                                .install(LiveObjectInterceptor.Getter.class,
                                        LiveObjectInterceptor.Setter.class)))
                .implement(RLiveObject.class)
//                .method(ElementMatchers.isDeclaredBy(RExpirable.class)
//                        .or(ElementMatchers.isDeclaredBy(RExpirableAsync.class))
//                        .or(ElementMatchers.isDeclaredBy(RObject.class))
//                        .or(ElementMatchers.isDeclaredBy(RObjectAsync.class)))
//                .intercept(MethodDelegation.to(ExpirableInterceptor.class))
//                .implement(RExpirable.class)
                .method(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class))
                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(RLiveObject.class)))
//                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(RExpirable.class)))
//                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(RExpirableAsync.class)))
//                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(RObject.class)))
//                        .and(ElementMatchers.not(ElementMatchers.isDeclaredBy(RObjectAsync.class)))
                        .and(ElementMatchers.isGetter()
                                .or(ElementMatchers.isSetter()))
                        .and(ElementMatchers.isPublic()))
                .intercept(MethodDelegation.to(
                                new AccessorInterceptor(redisson, codecProvider)))
                .make().load(getClass().getClassLoader(),
                        ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();
        classCache.putIfAbsent(entityClass, proxied);
    }

    public void unregisterClass(Class cls) {
        classCache.remove(cls.isAssignableFrom(RLiveObject.class) ? cls.getSuperclass() : cls);
    }

    /**
     * @return the codecProvider
     */
    public CodecProvider getCodecProvider() {
        return codecProvider;
    }

}
