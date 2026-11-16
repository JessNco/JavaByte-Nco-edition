package dev.javabyte.model;

/**
 * A resolved guest field: where it is declared and which storage slot holds it.
 *
 * @param declaring class that declares the field (may be a supertype of the class named in bytecode)
 * @param slot      index into {@link InstanceState#fields} or the class's static array
 * @param isStatic  which of the two arrays {@code slot} refers to
 */
public record FieldRef(GuestClass declaring, int slot, boolean isStatic, String name, String desc) {}
