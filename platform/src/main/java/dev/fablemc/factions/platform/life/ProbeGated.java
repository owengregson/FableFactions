package dev.fablemc.factions.platform.life;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code Listener} (or helper) that legitimately mentions a post-1.7.10
 * Bukkit type and is therefore registered ONLY behind a capability probe
 * (CONTRACTS §3, AM-13).
 *
 * <p>The {@code verifyDescriptorFloor} build gate reads this annotation (CLASS
 * retention — present in the bytecode, read by ASM) to exempt the class from the
 * baseline-listener descriptor-floor check, and cross-checks it against the
 * {@link ListenerGate} registration table: an annotated class that is baseline
 * (unconditionally) registered fails the build, and a baseline listener that
 * mentions a post-floor descriptor type without this annotation also fails.
 *
 * <p>{@link #capability()} names the {@code Capabilities} boolean that gates the
 * registration (e.g. {@code "blockExplode"}, {@code "armorStands"}).
 *
 * <p>Owning thread(s): none — a compile-time marker read by the build gate.
 * Mutability class: stateless annotation.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ProbeGated {

    /** The {@code Capabilities} boolean whose truth gates this class's registration. */
    String capability();
}
