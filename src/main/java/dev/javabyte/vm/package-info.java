/**
 * The interpreter and everything that directs it: the instruction loop, the call router (the
 * security boundary), the guest-thread scheduler, type and field helpers, and invokedynamic linking.
 * Code here must never call a JDK API on the sample's behalf except through the router.
 */
package dev.javabyte.vm;
