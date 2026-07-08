package dev.fablemc.factions.core.integration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The single reflection toolkit every integration adapter shares (proposal-C §10.3 "reflection-only"
 * isolation strategy). No provider class is ever imported anywhere in this package; each provider
 * type is resolved by name at runtime through the helpers here, so the {@code :core} module carries
 * no compile dependency on any soft-dep and unconditionally loads even when every integration is
 * absent (ref-integrations §0.2).
 *
 * <p><b>Owning thread(s):</b> stateless static utility, callable from any thread the adapters run
 * on (mostly the boot thread and the scheduler's routed threads). <b>Mutability:</b> stateless.
 *
 * <p>Every lookup swallows {@link ReflectiveOperationException} and {@link LinkageError} and returns
 * {@code null}/{@code false}; callers treat a {@code null} as "provider not usable" and degrade to
 * their Noop path (ref-integrations §12.4 — reflection failures never break faction operations).
 */
public final class Reflect {

    private Reflect() {
    }

    /** The class for {@code fqn}, or {@code null} when it is not on the runtime classpath. */
    public static Class<?> findClass(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** {@code true} when {@code fqn} resolves on the runtime classpath. */
    public static boolean classPresent(String fqn) {
        return findClass(fqn) != null;
    }

    /** The enabled Bukkit plugin named {@code name}, or {@code null} when absent. */
    public static Plugin plugin(String name) {
        return Bukkit.getPluginManager().getPlugin(name);
    }

    /** {@code true} when a plugin named {@code name} is installed (regardless of enabled state). */
    public static boolean pluginPresent(String name) {
        return plugin(name) != null;
    }

    /** The declared {@link Method} {@code name(sig)} on {@code owner}, or {@code null}. */
    public static Method method(Class<?> owner, String name, Class<?>... sig) {
        if (owner == null) {
            return null;
        }
        try {
            Method m = owner.getMethod(name, sig);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException | LinkageError absent) {
            return null;
        }
    }

    /** Invokes no-arg {@code name} on {@code target}, returning the result or {@code null} on any failure. */
    public static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        return invoke(target, method(target.getClass(), name), NO_ARGS);
    }

    /** Invokes {@code name(sig)} on {@code target} with {@code args}, or {@code null} on any failure. */
    public static Object call(Object target, String name, Class<?>[] sig, Object... args) {
        if (target == null) {
            return null;
        }
        return invoke(target, method(target.getClass(), name, sig), args);
    }

    /** Invokes static no-arg {@code name} on {@code owner}, or {@code null} on any failure. */
    public static Object callStatic(Class<?> owner, String name) {
        return invoke(null, method(owner, name), NO_ARGS);
    }

    /** Invokes static {@code name(sig)} on {@code owner} with {@code args}, or {@code null} on failure. */
    public static Object callStatic(Class<?> owner, String name, Class<?>[] sig, Object... args) {
        return invoke(null, method(owner, name, sig), args);
    }

    /** Invokes {@code m} on {@code target}, swallowing every failure to {@code null}. */
    public static Object invoke(Object target, Method m, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            return null;
        }
    }

    /**
     * Invokes the first public 1-arg method named {@code name} on {@code target} whose parameter
     * accepts {@code arg} (assignability, not an exact descriptor). Used for provider APIs whose
     * parameter types are erased generics or version-shuffled; returns {@code null} on any miss.
     */
    public static Object call1(Object target, String name, Object arg) {
        if (target == null) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && (arg == null || params[0].isInstance(arg))) {
                m.setAccessible(true);
                return invoke(target, m, arg);
            }
        }
        return null;
    }

    /** Invokes the first public 2-arg method named {@code name} accepting {@code a}/{@code b}. */
    public static Object call2(Object target, String name, Object a, Object b) {
        if (target == null) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2 && (a == null || params[0].isInstance(a))
                    && (b == null || params[1].isInstance(b))) {
                m.setAccessible(true);
                return invoke(target, m, a, b);
            }
        }
        return null;
    }

    /** Invokes the first public static 1-arg method named {@code name} on {@code owner}. */
    public static Object callStatic1(Class<?> owner, String name, Object arg) {
        if (owner == null) {
            return null;
        }
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && (arg == null || params[0].isInstance(arg))) {
                m.setAccessible(true);
                return invoke(null, m, arg);
            }
        }
        return null;
    }

    /** Constructs {@code owner(sig)} with {@code args}, or {@code null} on any failure. */
    public static Object construct(Class<?> owner, Class<?>[] sig, Object... args) {
        if (owner == null) {
            return null;
        }
        try {
            Constructor<?> c = owner.getDeclaredConstructor(sig);
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failed) {
            return null;
        }
    }

    /** A one-element {@code Class<?>[]} signature (readability at call sites). */
    public static Class<?>[] sig(Class<?> a) {
        return new Class<?>[] {a};
    }

    /** A two-element {@code Class<?>[]} signature. */
    public static Class<?>[] sig(Class<?> a, Class<?> b) {
        return new Class<?>[] {a, b};
    }

    private static final Object[] NO_ARGS = new Object[0];
}
