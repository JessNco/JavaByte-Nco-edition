/**
 * JavaByte's programmatic entry point. {@link dev.javabyte.Emulator#run} takes a config and returns a
 * report; everything else lives in the sub-packages. Start reading at {@code Emulator}, then
 * {@code vm.InvocationRouter}, which decides what each guest call is allowed to do.
 */
package dev.javabyte;
