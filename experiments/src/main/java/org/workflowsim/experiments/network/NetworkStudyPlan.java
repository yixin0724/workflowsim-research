package org.workflowsim.experiments.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

/** Predeclared network-limited study: a small CI matrix and an explicit full research matrix. */
public final class NetworkStudyPlan {
    public static final String PROTOCOL = "network-limited-r10-v2";
    public static final List<PlanningAlgorithm> PLANNERS = Collections.unmodifiableList(Arrays.asList(
            PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP,
            PlanningAlgorithm.RANDOM, PlanningAlgorithm.PSO));
    public static final List<String> NETWORKS = Collections.unmodifiableList(Arrays.asList(
            "endpoint", "fat-tree-constrained", "fat-tree-wide"));
    private final boolean full;
    private final List<WorkflowCase> workflows;
    private final List<Map<String, Object>> excludedInputs;

    private NetworkStudyPlan(boolean full, List<WorkflowCase> workflows, List<Map<String, Object>> excludedInputs) {
        this.full = full;
        this.workflows = Collections.unmodifiableList(workflows);
        this.excludedInputs = Collections.unmodifiableList(excludedInputs);
    }

    public static NetworkStudyPlan create(String mode, Path datasets, Path generatedInputs) throws IOException {
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
        return new NetworkStudyPlan(full, values, excluded);
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
    public List<Integer> getVmCounts() { return full ? Arrays.asList(4, 16) : Collections.singletonList(4); }
    public List<Long> getRandomSeeds() { return full ? Arrays.asList(11L, 29L, 47L, 71L, 101L) : Arrays.asList(11L, 29L); }
    public List<Long> seeds(PlanningAlgorithm planner) {
        return planner == PlanningAlgorithm.RANDOM || planner == PlanningAlgorithm.PSO
                ? getRandomSeeds() : Collections.singletonList(11L);
    }
    public int getRunCount() {
        int repetitions = 0;
        for (PlanningAlgorithm planner : PLANNERS) { repetitions += seeds(planner).size(); }
        return workflows.size() * getVmCounts().size() * NETWORKS.size() * repetitions;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("protocol", PROTOCOL);
        result.put("mode", full ? "full" : "smoke");
        result.put("vmCounts", getVmCounts());
        result.put("randomSeeds", getRandomSeeds());
        result.put("deterministicSeed", 11L);
        result.put("planners", PLANNERS);
        result.put("networks", NETWORKS);
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
        result.put("inference", "DAG_PAIRED_AFTER_SEED_MEAN;SEPARATE_POPULATIONS;HOLM_THREE_PLANNERS;DESCRIPTIVE_SELECTED_CORPUS");
        result.put("transferStart", "ALL_GROUPS_START_AT_JOB_READY");
        return result;
    }

    public static DataMovementModel movement(String name) {
        if ("endpoint".equals(name)) { return DataMovementModel.preExecutionTransferDelayWithContentionV1(); }
        if ("fat-tree-constrained".equals(name) || "fat-tree-wide".equals(name)) { return DataMovementModel.fatTreeContentionV1(); }
        throw new IllegalArgumentException("Unknown network: " + name);
    }

    public static PlatformProfile platform(int count, String network) {
        movement(network);
        PlatformProfile.Builder builder = PlatformProfile.builder("network-study-" + count + "-" + network);
        for (int id = 0; id < count; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000, 1000000));
            builder.addVm(new PlatformProfile.VmSpec(id, 1000, 1, 512, 1, 10000, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(id, id);
        }
        if (!"endpoint".equals(network)) {
            builder.networkTopology(NetworkTopologySpec.fatTree(4,
                    "fat-tree-constrained".equals(network) ? 0.125 : 1.25));
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
