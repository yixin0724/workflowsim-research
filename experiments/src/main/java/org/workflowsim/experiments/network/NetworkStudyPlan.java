package org.workflowsim.experiments.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskExecutionModel;

/** Predeclared network-limited study: a small CI matrix and an explicit full research matrix. */
public final class NetworkStudyPlan {
    /** New executions retain the parameter matrix, not the historical execution model identity. */
    public static final String PROTOCOL = "network-limited-r10-v3";
    public static final String PEFT_COMPARISON_PROTOCOL = "peft-comparison-r12-v2";
    public static final String SENSITIVITY_PROTOCOL = "sensitivity-response-r13-v2";
    /** Historical protocol identities are accepted only by read-only integrity validation. */
    public static final String HISTORICAL_PROTOCOL = "network-limited-r10-v2";
    public static final String HISTORICAL_PEFT_COMPARISON_PROTOCOL = "peft-comparison-r12-v1";
    public static final String HISTORICAL_SENSITIVITY_PROTOCOL = "sensitivity-response-r13-v1";
    public static final List<PlanningAlgorithm> PLANNERS = Collections.unmodifiableList(Arrays.asList(
            PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP,
            PlanningAlgorithm.RANDOM, PlanningAlgorithm.PSO));
    public static final List<PlanningAlgorithm> PEFT_COMPARISON_PLANNERS = Collections.unmodifiableList(Arrays.asList(
            PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP,
            PlanningAlgorithm.LOCAL_PEFT));
    public static final List<String> NETWORKS = Collections.unmodifiableList(Arrays.asList(
            "endpoint", "fat-tree-constrained", "fat-tree-wide"));
    public static final List<String> SENSITIVITY_NETWORKS = Collections.unmodifiableList(Arrays.asList(
            "endpoint", "fat-tree-constrained", "fat-tree-mid", "fat-tree-wide", "fat-tree-fast"));
    /** R13 heterogeneity axis: homogeneous baseline plus three deterministic mips-mixture patterns. */
    public static final String HOMOGENEOUS = "HOMOGENEOUS";
    public static final String HET_MILD = "HET_MILD";
    public static final String HET_STRONG = "HET_STRONG";
    public static final String HET_EXTREME = "HET_EXTREME";

    /** Study variant selector: frozen r10, S5 PEFT comparison, or R13 sensitivity response surface. */
    public enum StudyVariant { R10, PEFT_COMPARISON, SENSITIVITY_R13 }

    private final boolean full;
    private final StudyVariant variant;
    private final String protocol;
    private final List<WorkflowCase> workflows;
    private final List<Map<String, Object>> excludedInputs;

    private NetworkStudyPlan(boolean full, StudyVariant variant, List<WorkflowCase> workflows,
            List<Map<String, Object>> excludedInputs) {
        this(full, variant, workflows, excludedInputs, currentProtocol(variant));
    }

    private NetworkStudyPlan(boolean full, StudyVariant variant, List<WorkflowCase> workflows,
            List<Map<String, Object>> excludedInputs, String protocol) {
        this.full = full;
        this.variant = variant;
        this.protocol = protocol;
        this.workflows = Collections.unmodifiableList(workflows);
        this.excludedInputs = Collections.unmodifiableList(excludedInputs);
    }

    /** Maps the optional executor argument to a study variant; null means the frozen r10 protocol. */
    public static StudyVariant variantArgument(String argument) {
        if (argument == null || "r10".equals(argument)) { return StudyVariant.R10; }
        if ("peft-comparison".equals(argument)) { return StudyVariant.PEFT_COMPARISON; }
        if ("sensitivity-r13".equals(argument)) { return StudyVariant.SENSITIVITY_R13; }
        throw new IllegalArgumentException("Unknown study variant: " + argument
                + " (expected r10, peft-comparison, or sensitivity-r13)");
    }

    public static NetworkStudyPlan create(String mode, Path datasets, Path generatedInputs) throws IOException {
        return create(mode, StudyVariant.R10, datasets, generatedInputs);
    }

    public static NetworkStudyPlan create(String mode, boolean peftComparison, Path datasets, Path generatedInputs)
            throws IOException {
        return create(mode, peftComparison ? StudyVariant.PEFT_COMPARISON : StudyVariant.R10, datasets, generatedInputs);
    }

    public static NetworkStudyPlan create(String mode, StudyVariant variant, Path datasets, Path generatedInputs)
            throws IOException {
        if (!"full".equals(mode) && !"smoke".equals(mode)) {
            throw new IllegalArgumentException("Study mode must be smoke or full");
        }
        List<WorkflowCase> values = new ArrayList<WorkflowCase>();
        boolean full = "full".equals(mode);
        if (full) {
            add(values, datasets, "epigenomics-100", "epigenomics", "dax/epigenomics/n100/Epigenomics_100.dax");
            add(values, datasets, "epigenomics-997", "epigenomics", "dax/epigenomics/n997/Epigenomics_997.dax");
            add(values, datasets, "cybershake-100", "cybershake", "dax/cybershake/n100/CyberShake_100.dax");
            add(values, datasets, "cybershake-1000", "cybershake", "dax/cybershake/n1000/CyberShake_1000.dax");
            add(values, datasets, "inspiral-100", "inspiral", "dax/inspiral/n100/Inspiral_100.dax");
            add(values, datasets, "inspiral-1000", "inspiral", "dax/inspiral/n1000/Inspiral_1000.dax");
        } else {
            add(values, datasets, "paper-10", "paper-fixture", "dax/heft/heft-paper-example.dax");
        }
        Files.createDirectories(generatedInputs);
        for (int width : full ? new int[] {8, 32} : new int[] {4}) {
            Path path = generatedInputs.resolve("layered-" + (width * 4) + ".dax");
            Files.write(path, layeredWorkflow(width).getBytes(StandardCharsets.UTF_8));
            values.add(new WorkflowCase("layered-" + (width * 4), "layered", "SYNTHETIC", path));
        }
        List<Map<String, Object>> excluded = new ArrayList<Map<String, Object>>();
        java.util.Iterator<WorkflowCase> iterator = values.iterator();
        while (iterator.hasNext()) {
            WorkflowCase workflow = iterator.next();
            String reason = qualificationFailure(workflow.path);
            if (reason != null) {
                Map<String, Object> rejection = new LinkedHashMap<String, Object>();
                rejection.put("id", workflow.id); rejection.put("path", workflow.path.toString());
                rejection.put("reason", reason); rejection.put("scope", "EXCLUDED_FROM_ALL_PLANNERS_BEFORE_COMPARISON");
                excluded.add(rejection); iterator.remove();
            }
        }
        if (values.isEmpty()) { throw new IllegalArgumentException("No compatible study inputs"); }
        NetworkStudyPlan result = new NetworkStudyPlan(full, variant, values, excluded);
        NetworkStudyPlan registered = canonical(mode, result.getProtocol());
        if (values.size() != registered.workflows.size() || excluded.size() != registered.excludedInputs.size()) {
            throw new IllegalArgumentException("Qualified inputs differ from the registered study protocol");
        }
        for (int i = 0; i < values.size(); i++) {
            WorkflowCase actual = values.get(i), expected = registered.workflows.get(i);
            if (!actual.id.equals(expected.id) || !actual.sha256.equals(expected.sha256)) {
                throw new IllegalArgumentException("Input differs from registered protocol: " + actual.id);
            }
        }
        for (int i = 0; i < excluded.size(); i++) {
            for (String key : Arrays.asList("id", "reason", "scope")) {
                if (!registered.excludedInputs.get(i).get(key).equals(excluded.get(i).get(key))) {
                    throw new IllegalArgumentException("Input qualification differs from registered protocol: " + key);
                }
            }
        }
        return result;
    }

    /** The declared protocol revision; new executions never use a historical revision. */
    public String getProtocol() { return protocol; }

    StudyVariant getVariant() { return variant; }

    /** Whether the declaration certifies the retained historical model rather than new execution. */
    public boolean isHistoricalProtocol() {
        return HISTORICAL_PROTOCOL.equals(protocol) || HISTORICAL_PEFT_COMPARISON_PROTOCOL.equals(protocol)
                || HISTORICAL_SENSITIVITY_PROTOCOL.equals(protocol);
    }

    private static String currentProtocol(StudyVariant variant) {
        if (variant == null) { throw new IllegalArgumentException("Study variant is required"); }
        switch (variant) {
            case PEFT_COMPARISON: return PEFT_COMPARISON_PROTOCOL;
            case SENSITIVITY_R13: return SENSITIVITY_PROTOCOL;
            default: return PROTOCOL;
        }
    }

    /**
     * Build the registered matrix without reading files, parsing workflows, or generating inputs.
     * Paths here are logical placeholders; validators separately bind retained absolute paths.
     * The workload fingerprints and the frozen layered generator are part of the protocol,
     * not declarations supplied by the evidence being checked.
     */
    static NetworkStudyPlan canonical(String mode, String protocol) {
        if (!"full".equals(mode) && !"smoke".equals(mode)) {
            throw new IllegalArgumentException("Study mode must be smoke or full");
        }
        StudyVariant variant;
        if (PROTOCOL.equals(protocol) || HISTORICAL_PROTOCOL.equals(protocol)) { variant = StudyVariant.R10; }
        else if (PEFT_COMPARISON_PROTOCOL.equals(protocol) || HISTORICAL_PEFT_COMPARISON_PROTOCOL.equals(protocol)) {
            variant = StudyVariant.PEFT_COMPARISON;
        } else if (SENSITIVITY_PROTOCOL.equals(protocol) || HISTORICAL_SENSITIVITY_PROTOCOL.equals(protocol)) {
            variant = StudyVariant.SENSITIVITY_R13;
        } else { throw new IllegalArgumentException("Unknown protocol: " + protocol); }
        boolean full = "full".equals(mode);
        List<WorkflowCase> values = new ArrayList<WorkflowCase>();
        if (full) {
            registered(values, "epigenomics-100", "epigenomics", "dax/epigenomics/n100/Epigenomics_100.dax",
                    "374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36");
            registered(values, "epigenomics-997", "epigenomics", "dax/epigenomics/n997/Epigenomics_997.dax",
                    "2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628");
            registered(values, "cybershake-100", "cybershake", "dax/cybershake/n100/CyberShake_100.dax",
                    "183ac79c4ee80a6293adff71d8386e7538d31a9bd1b609b176d94b131b9fdb52");
            registered(values, "cybershake-1000", "cybershake", "dax/cybershake/n1000/CyberShake_1000.dax",
                    "4314ae0e6bb43c3f74818306b600151c0614b62184295438350837217f1df95b");
            registered(values, "inspiral-100", "inspiral", "dax/inspiral/n100/Inspiral_100.dax",
                    "64e2bc60893d65f8347c436986841741d7ced1bd05435f67081707d4346decfa");
        } else {
            registered(values, "paper-10", "paper-fixture", "dax/heft/heft-paper-example.dax",
                    "5e64adc375e0249dafed5cfaba75f63332d3793fe017e57ea6c8da68e9d86fbd");
        }
        for (int width : full ? new int[] {8, 32} : new int[] {4}) {
            String id = "layered-" + (width * 4);
            values.add(new WorkflowCase(id, "layered", "SYNTHETIC", Paths.get("inputs", id + ".dax"),
                    generatedSha256(width)));
        }
        List<Map<String, Object>> excluded = new ArrayList<Map<String, Object>>();
        if (full) {
            Map<String, Object> rejection = new LinkedHashMap<String, Object>();
            rejection.put("id", "inspiral-1000");
            rejection.put("path", "dax/inspiral/n1000/Inspiral_1000.dax");
            rejection.put("reason", "CONFLICTING_FILE_SIZE: H1-THINCA-782406919-2048.xml (39368.0 vs 46451.0)");
            rejection.put("scope", "EXCLUDED_FROM_ALL_PLANNERS_BEFORE_COMPARISON");
            excluded.add(rejection);
        }
        return new NetworkStudyPlan(full, variant, values, excluded, protocol);
    }

    private static void registered(List<WorkflowCase> values, String id, String family, String path, String sha256) {
        values.add(new WorkflowCase(id, family, "CLASSIC_DAX", Paths.get(path), sha256));
    }

    private static String generatedSha256(int width) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(layeredWorkflow(width).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) { hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /** The planner set for this study variant. */
    public List<PlanningAlgorithm> getPlanners() {
        return variant == StudyVariant.R10 ? PLANNERS : PEFT_COMPARISON_PLANNERS;
    }

    /** The network list for this study variant. */
    public List<String> getNetworks() {
        return variant == StudyVariant.SENSITIVITY_R13 ? SENSITIVITY_NETWORKS : NETWORKS;
    }

    /** One declared platform/network cell; the matrix is workflows × conditions × planners × seeds. */
    public static final class Condition {
        public final int vmCount;
        public final String network;
        public final String heterogeneity;
        Condition(int vmCount, String network, String heterogeneity) {
            this.vmCount = vmCount; this.network = network; this.heterogeneity = heterogeneity;
        }
    }

    /** Declared study conditions in execution order. */
    public List<Condition> getConditions() {
        List<Condition> conditions = new ArrayList<Condition>();
        if (variant != StudyVariant.SENSITIVITY_R13) {
            for (Integer vmCount : getVmCounts()) {
                for (String network : NETWORKS) { conditions.add(new Condition(vmCount, network, HOMOGENEOUS)); }
            }
            return Collections.unmodifiableList(conditions);
        }
        List<Integer> mainCounts = getVmCounts();
        // smoke 只覆盖两个代表性网络，保持 CI 成本恒定；full 覆盖全部五个带宽档位
        List<String> mainNetworks = full ? SENSITIVITY_NETWORKS : Arrays.asList("endpoint", "fat-tree-constrained");
        for (Integer vmCount : mainCounts) {
            for (String network : mainNetworks) { conditions.add(new Condition(vmCount, network, HOMOGENEOUS)); }
        }
        List<Integer> heterogeneousCounts = full ? Arrays.asList(8, 16) : Collections.singletonList(4);
        for (Integer vmCount : heterogeneousCounts) {
            for (String level : Arrays.asList(HET_MILD, HET_STRONG, HET_EXTREME)) {
                conditions.add(new Condition(vmCount, "fat-tree-constrained", level));
            }
        }
        return Collections.unmodifiableList(conditions);
    }

    /** Qualification is structural only: no algorithm performance is observed or selected. */
    static String qualificationFailure(Path input) {
        boolean disabled = org.cloudbus.cloudsim.Log.isDisabled();
        org.cloudbus.cloudsim.Log.disable();
        try (org.workflowsim.utils.SimulationSession session = org.workflowsim.utils.SimulationSession.open(
                org.workflowsim.utils.SimulationConfig.builder(input.toString(), 1).build())) {
            org.workflowsim.WorkflowParser parser = new org.workflowsim.WorkflowParser(0);
            parser.parse();
            Map<String, Double> sizes = new LinkedHashMap<String, Double>();
            for (org.workflowsim.Task task : parser.getTaskList()) {
                for (org.workflowsim.FileItem file : task.getFileList()) {
                    Double previous = sizes.put(file.getName(), file.getSize());
                    if (previous != null && Double.compare(previous, file.getSize()) != 0) {
                        return "CONFLICTING_FILE_SIZE: " + file.getName() + " (" + previous + " vs " + file.getSize() + ")";
                    }
                }
            }
            return null;
        } finally { org.cloudbus.cloudsim.Log.setDisabled(disabled); }
    }

    private static void add(List<WorkflowCase> values, Path datasets, String id, String family, String relative) {
        Path path = datasets.resolve(relative).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) { throw new IllegalArgumentException("Missing study input: " + path); }
        values.add(new WorkflowCase(id, family, "CLASSIC_DAX", path));
    }

    public List<WorkflowCase> getWorkflows() { return workflows; }
    public List<Integer> getVmCounts() {
        if (variant == StudyVariant.SENSITIVITY_R13) {
            return full ? Arrays.asList(4, 8, 16, 32) : Collections.singletonList(4);
        }
        return full ? Arrays.asList(4, 16) : Collections.singletonList(4);
    }
    public List<Long> getRandomSeeds() { return full ? Arrays.asList(11L, 29L, 47L, 71L, 101L) : Arrays.asList(11L, 29L); }
    public List<Long> seeds(PlanningAlgorithm planner) {
        return planner == PlanningAlgorithm.RANDOM || planner == PlanningAlgorithm.PSO
                ? getRandomSeeds() : Collections.singletonList(11L);
    }
    public int getRunCount() {
        int repetitions = 0;
        for (PlanningAlgorithm planner : getPlanners()) { repetitions += seeds(planner).size(); }
        return workflows.size() * getConditions().size() * repetitions;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocol", getProtocol());
        result.put("mode", full ? "full" : "smoke");
        result.put("vmCounts", getVmCounts());
        result.put("randomSeeds", getRandomSeeds());
        result.put("deterministicSeed", 11L);
        result.put("planners", getPlanners());
        result.put("networks", getNetworks());
        result.put("runCount", getRunCount());
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        for (WorkflowCase workflow : workflows) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("id", workflow.id); entry.put("family", workflow.family);
            entry.put("population", workflow.population); entry.put("path", workflow.path.toString());
            entry.put("sha256", workflow.sha256);
            cases.add(entry);
        }
        result.put("workflows", cases);
        result.put("excludedInputs", excludedInputs);
        result.put("vmMips", 1000); result.put("endpointMbPerSecond", 1);
        result.put("fatTreeK", 4); result.put("constrainedLinkMbPerSecond", 0.125);
        result.put("wideLinkMbPerSecond", 1.25);
        if (variant == StudyVariant.SENSITIVITY_R13) {
            result.put("midLinkMbPerSecond", 0.5);
            result.put("fastLinkMbPerSecond", 5.0);
            result.put("fatTreeKRule", "k=4 for vmCount<=16; k=8 for vmCount=32");
            Map<String, Object> patterns = new LinkedHashMap<String, Object>();
            patterns.put(HOMOGENEOUS, Collections.singletonList(1000));
            patterns.put(HET_MILD, Arrays.asList(1000, 500));
            patterns.put(HET_STRONG, Arrays.asList(2000, 1000, 500));
            patterns.put(HET_EXTREME, Arrays.asList(2000, 500));
            result.put("heterogeneityMipsPatterns", patterns);
            List<Map<String, Object>> conditions = new ArrayList<Map<String, Object>>();
            for (Condition condition : getConditions()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("vmCount", condition.vmCount);
                entry.put("network", condition.network);
                entry.put("heterogeneity", condition.heterogeneity);
                conditions.add(entry);
            }
            result.put("conditions", conditions);
        }
        result.put("inference", variant == StudyVariant.R10
                ? "DAG_PAIRED_AFTER_SEED_MEAN;SEPARATE_POPULATIONS;HOLM_THREE_PLANNERS;DESCRIPTIVE_SELECTED_CORPUS"
                : "DAG_PAIRED_AFTER_SEED_MEAN;SEPARATE_POPULATIONS;HOLM_TWO_PLANNERS;DESCRIPTIVE_SELECTED_CORPUS");
        result.put("transferStart", "ALL_GROUPS_START_AT_JOB_READY");
        if (!isHistoricalProtocol()) { result.put("executionSemantics", TaskExecutionModel.EXECUTION_SEMANTICS); }
        return result;
    }

    public static DataMovementModel movement(String name) {
        if ("endpoint".equals(name)) { return DataMovementModel.preExecutionTransferDelayWithContentionV1(); }
        if (name != null && name.startsWith("fat-tree-")) { return DataMovementModel.fatTreeContentionV1(); }
        throw new IllegalArgumentException("Unknown network: " + name);
    }

    /** Declared fat-tree link bandwidth (MB/s) for a response-surface network name. */
    public static double fatTreeLinkMbPerSecond(String network) {
        if ("fat-tree-constrained".equals(network)) { return 0.125; }
        if ("fat-tree-mid".equals(network)) { return 0.5; }
        if ("fat-tree-wide".equals(network)) { return 1.25; }
        if ("fat-tree-fast".equals(network)) { return 5.0; }
        throw new IllegalArgumentException("Unknown fat-tree network: " + network);
    }

    /** Fat-tree pod order grows with the platform so the size axis stays feasible; declared per condition. */
    public static int fatTreeK(int vmCount) { return vmCount <= 16 ? 4 : 8; }

    /** Deterministic per-VM MIPS for a declared heterogeneity level, indexed in platform order. */
    public static double vmMips(int vmIndex, String heterogeneity) {
        if (HOMOGENEOUS.equals(heterogeneity)) { return 1000.0; }
        if (HET_MILD.equals(heterogeneity)) { return vmIndex % 2 == 0 ? 1000.0 : 500.0; }
        if (HET_STRONG.equals(heterogeneity)) { return new double[] {2000.0, 1000.0, 500.0}[vmIndex % 3]; }
        if (HET_EXTREME.equals(heterogeneity)) { return vmIndex % 2 == 0 ? 2000.0 : 500.0; }
        throw new IllegalArgumentException("Unknown heterogeneity level: " + heterogeneity);
    }

    /** Shared fixed simulation declaration for execution and read-only protocol validation. */
    static SimulationConfig configuration(String input, int count, String network, PlanningAlgorithm planner, long seed) {
        return SimulationConfig.builder(input, count)
                .planningAlgorithm(planner).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(seed)
                .dataMovementModel(movement(network)).runtimeScale(1.0).runtimeReferenceMips(1000.0)
                .cloudSimMinEventIntervalSeconds(0.1).deadline(0).costModel(Parameters.CostModel.DATACENTER)
                .build();
    }

    public static PlatformProfile platform(int count, String network) {
        return platform(count, network, HOMOGENEOUS);
    }

    public static PlatformProfile platform(int count, String network, String heterogeneity) {
        movement(network);
        String name = "network-study-" + count + "-" + network
                + (HOMOGENEOUS.equals(heterogeneity) ? "" : "-" + heterogeneity);
        PlatformProfile.Builder builder = PlatformProfile.builder(name)
                .storage(new PlatformProfile.StorageSpec(1000000000000L, 15))
                .costs(new PlatformProfile.CostSpec(3.0, 0.05, 0.1, 0.1));
        for (int id = 0; id < count; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000, 1000000));
            builder.addVm(new PlatformProfile.VmSpec(id, vmMips(id, heterogeneity), 1, 512, 1, 10000, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(id, id);
        }
        if (!"endpoint".equals(network)) {
            builder.networkTopology(NetworkTopologySpec.fatTree(fatTreeK(count), fatTreeLinkMbPerSecond(network)));
        }
        return builder.build();
    }

    /** Four layers, two unique outgoing files per task, no filename ambiguity or global shared input. */
    static String layeredWorkflow(int width) {
        if (width < 2 || width > 256) { throw new IllegalArgumentException("Layer width must be 2..256"); }
        StringBuilder xml = new StringBuilder("<adag version=\"2.1\">\n");
        for (int layer = 0; layer < 4; layer++) {
            for (int column = 0; column < width; column++) {
                int id = layer * width + column;
                xml.append("<job id=\"t").append(id).append("\" name=\"layered\" runtime=\"")
                        .append(1 + column % 5).append("\">");
                if (layer > 0) {
                    file(xml, "e" + ((layer - 1) * width + column) + "a", "input");
                    file(xml, "e" + ((layer - 1) * width + (column + width - 1) % width) + "b", "input");
                }
                if (layer < 3) { file(xml, "e" + id + "a", "output"); file(xml, "e" + id + "b", "output"); }
                xml.append("</job>\n");
            }
        }
        for (int layer = 1; layer < 4; layer++) {
            for (int column = 0; column < width; column++) {
                xml.append("<child ref=\"t").append(layer * width + column)
                        .append("\"><parent ref=\"t").append((layer - 1) * width + column)
                        .append("\"/><parent ref=\"t").append((layer - 1) * width + (column + width - 1) % width)
                        .append("\"/></child>\n");
            }
        }
        return xml.append("</adag>\n").toString();
    }

    private static void file(StringBuilder xml, String name, String direction) {
        xml.append("<uses file=\"").append(name).append("\" link=\"").append(direction)
                .append("\" size=\"32000000\"/>");
    }

    public static final class WorkflowCase {
        public final String id;
        public final String family;
        public final String population;
        public final Path path;
        public final String sha256;

        /** Metadata-only constructor for the registered, filesystem-independent declaration. */
        private WorkflowCase(String id, String family, String population, Path path, String sha256) {
            this.id = id; this.family = family; this.population = population;
            this.path = path; this.sha256 = sha256;
        }

        WorkflowCase(String id, String family, String population, Path path) {
            this.id = id; this.family = family; this.population = population;
            this.path = path.toAbsolutePath().normalize();
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream input = Files.newInputStream(this.path)) {
                    byte[] buffer = new byte[8192]; int length;
                    while ((length = input.read(buffer)) != -1) { digest.update(buffer, 0, length); }
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) { hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); }
                this.sha256 = hex.toString();
            } catch (IOException | java.security.NoSuchAlgorithmException failure) {
                throw new IllegalStateException("Cannot fingerprint study input " + path, failure);
            }
        }
    }
}
