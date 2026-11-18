/**
 * The interpreter and everything that directs it: the instruction loop, the call router (the
 * security boundary), the guest-thread scheduler, type and field helpers, and invokedynamic linking.
 * Code here must never reach a JDK API on the sample's behalf except through the two host entry
 * points: the router for methods and constructors, and {@link dev.javabyte.vm.Fields} for fields
 * of JDK classes.
 */
package dev.javabyte.vm;
