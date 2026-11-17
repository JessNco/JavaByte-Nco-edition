package dev.javabyte.corpus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One corpus entry: a sample (simulated or real), how to run it, and what the report must show.
 *
 * <p>Scenarios are data, not code, so that adding a sample to the corpus means writing a YAML
 * file. The same format serves the simulated corpus (where {@link #fixture} names a test class)
 * and the real one (where {@link #sha256} names a file fetched from MalwareBazaar).
 */
public final class Scenario {

    public String name;
    public String description;
    /** Behaviours this entry exercises; used for the coverage table in docs/TESTING.md. */
    public List<String> techniques = new ArrayList<>();

    // ---- simulated samples
    /** Main class of a test fixture. Its nested classes are packaged with it. */
    public String fixture;
    /** Further fixture classes to include in the jar. */
    public List<String> classes = new ArrayList<>();
    /** Extra jar entries: path to text. */
    public Map<String, String> resources = new LinkedHashMap<>();
    /** Name of a Java-coded step that adds generated resources (see SimulatedCorpusTest). */
    public String builder;

    // ---- real samples
    public String sha256;
    public String family;
    /** Where the sample and the expectations came from, for the next person to verify. */
    public String source;

    /** Runs in order. A later stage may take its sample from files an earlier one dropped. */
    public List<Stage> stages = new ArrayList<>();

    /** One emulation run and its expectations. */
    public static final class Stage {
        public String name = "run";
        /** Regex over artifact paths saved by the previous stage; the match becomes this stage's sample. */
        public String sampleFromArtifact;
        /** An {@code EmulatorConfig} tree, minus {@code sample}. */
        public Map<String, Object> config = new LinkedHashMap<>();
        /** If set, the stage needs the native bridge and is skipped when it is unavailable. */
        public boolean nativeBridge;
        public Expect expect = new Expect();
    }

    /** What a stage's report must contain. Every string that matches report content is a regex. */
    public static final class Expect {
        /** Outcomes that must each appear among the report's runs. */
        public List<String> outcomes = new ArrayList<>();
        /** Outcomes that must not appear. {@code internal-error} is always forbidden. */
        public List<String> forbidOutcomes = new ArrayList<>();
        /** Each must be found in some line the sample printed. */
        public List<String> stdout = new ArrayList<>();
        /** Regular expressions that no printed line may match: proof that the run stopped where expected. */
        public List<String> notPrinted = new ArrayList<>();
        public List<EventMatch> events = new ArrayList<>();
        /** Events that must not occur. */
        public List<EventMatch> absent = new ArrayList<>();
        /** Each must be found in the path of some saved artifact. */
        public List<String> artifacts = new ArrayList<>();
        /** Upper bound on {@code gap} events; null means no check. */
        public Integer maxGaps;
    }

    /** Selects events by category, action and data fields. */
    public static final class EventMatch {
        public String category;
        /** Null matches any action. */
        public String action;
        /** Data field name to regex; every entry must be found in the event's field. */
        public Map<String, String> where = new LinkedHashMap<>();
        public int min = 1;
        public Integer max;
        /** Why this expectation matters; shown when it fails. */
        public String because;
    }

    @Override
    public String toString() {
        return name;
    }
}
