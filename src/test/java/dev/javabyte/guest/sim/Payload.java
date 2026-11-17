package dev.javabyte.guest.sim;

/** Second stage delivered by {@link Loaders}. Never packaged in the first-stage jar. */
public class Payload {

    public String run(String via) throws Exception {
        Runtime.getRuntime().exec("stage2-" + via + ".exe");
        return "payload ran via " + via;
    }
}
