package dev.javabyte.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one config option, for people and programs.
 *
 * <p>This is the single source for what an option means: {@code javabyte schema} prints it, the
 * desktop app builds its forms from it, and {@code javabyte validate} and the config loader take
 * the allowed values from it. A test fails if any option lacks it or is missing from
 * docs/CONFIG.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigDoc {

    /** One plain sentence or two: what the option does and, where useful, when to change it. */
    String value();

    /** For an option that is a choice: every accepted value. Empty for free values. */
    String[] allowed() default {};
}
