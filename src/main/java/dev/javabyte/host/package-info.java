/**
 * The boundary with the real JDK: the allow-list policy, the stub registry, the reflective bridge
 * that performs permitted calls, and the deserialization guard. Changes here affect the emulator's
 * safety, so each one should come with a test in {@code SafetyTest} or {@code EscapeTest}.
 */
package dev.javabyte.host;
