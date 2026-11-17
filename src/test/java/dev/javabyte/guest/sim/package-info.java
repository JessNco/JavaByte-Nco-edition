/**
 * The simulated malware corpus: small programs that each perform one family of malicious
 * techniques the way real Java malware does.
 *
 * <p>These classes are test data. They are compiled, packaged into jars and <b>only ever
 * interpreted by the emulator</b>; nothing in the test suite calls their {@code main} methods on
 * the real JVM. Their targets are deliberately fake (reserved {@code .test} domains and
 * documentation IP ranges), so even if one were run by mistake it could reach nothing.
 *
 * <p>Each has a scenario file under {@code src/test/resources/corpus/simulated} stating what the
 * emulator's report must show for it.
 */
package dev.javabyte.guest.sim;
