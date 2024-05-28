package com.udacity.webcrawler.profiler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * A method interceptor that checks whether {@link Method}s are annotated with the {@link Profiled}
 * annotation. If they are, the method interceptor records how long the method invocation took.
 */
final class ProfilingMethodInterceptor implements InvocationHandler {

    private final Clock clock;
    private final ProfilingState profilingState;
    private final Object object;

    ProfilingMethodInterceptor(Clock clock, ProfilingState profilingState, Object object) {
        this.clock = Objects.requireNonNull(clock);
        this.profilingState = profilingState;
        this.object = object;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final Runnable recordState;
        if (method.getAnnotation(Profiled.class) != null) {
            final ZonedDateTime startTime = ZonedDateTime.now(this.clock);
            recordState = () -> this.profilingState.record(object.getClass(), method, Duration.between(startTime, ZonedDateTime.now(this.clock)));
        } else {
            recordState = () -> {};
        }
       return getObject(method, args, recordState);
    }

    public Object getObject(Method method, Object[] args, Runnable recordState) throws Throwable {
        try {
            return method.invoke(object, args);
        } catch (InvocationTargetException ite) {
            throw ite.getTargetException();
        } catch (IllegalAccessException | UndeclaredThrowableException e) {
            throw new RuntimeException(e);
        } finally {
            recordState.run();
        }
    }
}
