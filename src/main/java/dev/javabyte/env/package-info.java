/**
 * The simulated machine the sample sees: virtual disk, network, processes, clock and executors.
 * Classes named {@code Sim*} extend or imitate a JDK type and must override every public method of
 * it; {@code SafetyTest} enforces that, which is what makes a JDK upgrade safe.
 */
package dev.javabyte.env;
