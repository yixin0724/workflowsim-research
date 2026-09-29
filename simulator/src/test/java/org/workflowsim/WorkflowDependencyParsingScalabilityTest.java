package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Prepared dependency-kernel counts, plus real parser lifecycle/order controls; not XML decoder timings. */
class WorkflowDependencyParsingScalabilityTest {
    @TempDir Path directory;

    @Test
    void daxDependencyKernelDoesNotLinearlyRescanAccumulatedEdges() throws Exception {
        for (boolean fanOut : new boolean[] {true, false}) {
            int width = 512;
            Counter count = new Counter();
            WorkflowParser parser = new WorkflowParser(0);
            List<Task> nodes = nodes(width + 1, count);
            for (int i = 0; i < nodes.size(); i++) parser.mName2Task.put("t" + i, nodes.get(i));
            Method link = WorkflowParser.class.getDeclaredMethod("parseDaxDependencies", Element.class);
            link.setAccessible(true);
            List<Element> declarations = new ArrayList<>();
            if (fanOut) {
                for (int i = 1; i <= width; i++) declarations.add(child("t" + i, "t0"));
            } else {
                Element all = new Element("child").setAttribute("ref", "t0");
                for (int i = 1; i <= width; i++) all.addContent(new Element("parent").setAttribute("ref", "t" + i));
                declarations.add(all);
            }
            for (Element declaration : declarations) invoke(link, parser, declaration);
            // Repeated legal DAX declarations are deduplicated, not repeated in Task adjacency.
            for (Element declaration : declarations) invoke(link, parser, declaration);
            assertStar(nodes, fanOut, width);
            System.out.println("PARSER_MEMBERSHIP_AUDIT kind=DAX width=" + width + " fanOut=" + fanOut
                    + " comparisons=" + count.comparisons);
            assertTrue(count.comparisons <= 16L * (width + 1 + width),
                    "DAX linking repeated linear comparisons: " + count.comparisons + " fanOut=" + fanOut);
        }
    }

    @Test
    void jsonDeclaredSymmetryUsesBoundedMembershipWork() throws Exception {
        for (boolean fanOut : new boolean[] {true, false}) {
            int width = 512;
            Counter declared = new Counter();
            SpecGraph input = specs(width, fanOut, declared);
            Method validate = WfCommonsJsonParser.class.getDeclaredMethod(
                    "validateDeclaredDependencySymmetry", List.class, Map.class);
            validate.setAccessible(true);
            invoke(validate, null, input.tasks, input.byId);
            System.out.println("PARSER_MEMBERSHIP_AUDIT kind=JSON_SYMMETRY width=" + width + " fanOut=" + fanOut
                    + " comparisons=" + declared.comparisons);
            assertTrue(declared.comparisons <= 16L * (width + 1 + width),
                    "JSON declared symmetry repeated linear comparisons: " + declared.comparisons);
        }
    }

    @Test
    void jsonConnectionKernelDeduplicatesBothDirectionsWithoutQuadraticRescans() throws Exception {
        for (boolean fanOut : new boolean[] {true, false}) {
            int width = 512;
            SpecGraph input = specs(width, fanOut, new Counter());
            Counter adjacency = new Counter();
            List<Task> nodes = nodes(width + 1, adjacency);
            Map<String, Task> byId = new LinkedHashMap<>();
            for (int i = 0; i < nodes.size(); i++) byId.put("t" + i, nodes.get(i));
            Method connect = WfCommonsJsonParser.class.getDeclaredMethod(
                    "connectDependencies", List.class, Map.class, Map.class);
            connect.setAccessible(true);
            invoke(connect, null, input.tasks, input.byId, byId);
            assertStar(nodes, fanOut, width);
            System.out.println("PARSER_MEMBERSHIP_AUDIT kind=JSON_LINK width=" + width + " fanOut=" + fanOut
                    + " comparisons=" + adjacency.comparisons);
            assertTrue(adjacency.comparisons <= 16L * (width + 1 + width),
                    "JSON linking repeated linear comparisons: " + adjacency.comparisons);
        }
    }

    @Test
    void declaredSymmetryValidationDoesNotRetainAStaleMembershipSnapshot() throws Exception {
        SpecGraph input = specs(3, true, new Counter());
        Method validate = WfCommonsJsonParser.class.getDeclaredMethod(
                "validateDeclaredDependencySymmetry", List.class, Map.class);
        validate.setAccessible(true);
        invoke(validate, null, input.tasks, input.byId);
        Field children = input.tasks.get(0).getClass().getDeclaredField("children");
        children.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> values = (List<String>) children.get(input.tasks.get(0));
        values.remove("t1");
        WorkflowValidationException failure = assertThrows(WorkflowValidationException.class,
                () -> invoke(validate, null, input.tasks, input.byId));
        assertTrue(failure.getMessage().contains("does not list"), failure.getMessage());
    }

    @Test
    void publicDaxParserKeepsFirstSeenOrderAndResetsLinkStateOnReuse() throws Exception {
        Path input = directory.resolve("repeated.dax");
        String prefix = "<adag version=\"3.3\"><job id=\"a\" runtime=\"1\"/>"
                + "<job id=\"b\" runtime=\"1\"/><job id=\"c\" runtime=\"1\"/>";
        Files.write(input, (prefix + "<child ref=\"c\"><parent ref=\"b\"/><parent ref=\"a\"/>"
                + "<parent ref=\"a\"/></child><child ref=\"c\"><parent ref=\"b\"/></child></adag>")
                .getBytes(StandardCharsets.UTF_8));
        try (SimulationSession session = SimulationSession.open(SimulationConfig.builder(input.toString(), 1).build())) {
            WorkflowParser parser = new WorkflowParser(0);
            parser.parse();
            assertEquals(Arrays.asList(2, 1), ids(parser.getTaskList().get(2).getParentList()));
            assertEquals(2, parser.getTaskList().get(2).getDepth());
            // The same parser starts a new generation with task IDs reset to one.
            Files.write(input, (prefix + "<child ref=\"c\"><parent ref=\"a\"/></child></adag>").getBytes(StandardCharsets.UTF_8));
            parser.parse();
            assertEquals(3, parser.getTaskList().size());
            assertEquals(Arrays.asList(1), ids(parser.getTaskList().get(2).getParentList()));
            assertTrue(parser.getTaskList().get(1).getChildList().isEmpty());
            assertEquals(1, parser.getInputReports().size());
        }
    }

    @Test
    void publicJsonParserKeepsTaskOrderAndDeduplicatedFirstConnectionOrder() throws Exception {
        Path input = directory.resolve("repeated.json");
        Files.write(input, ("{\"schemaVersion\":\"1.5\",\"workflow\":{\"specification\":{\"files\":[],\"tasks\":["
                + "{\"id\":\"a\",\"parents\":[],\"children\":[\"c\",\"c\"]},"
                + "{\"id\":\"b\",\"parents\":[],\"children\":[\"c\"]},"
                + "{\"id\":\"c\",\"parents\":[\"b\",\"a\",\"a\"],\"children\":[]}]},"
                + "\"execution\":{\"tasks\":[{\"id\":\"a\",\"runtimeInSeconds\":1},"
                + "{\"id\":\"b\",\"runtimeInSeconds\":1},{\"id\":\"c\",\"runtimeInSeconds\":1}]}}}")
                .getBytes(StandardCharsets.UTF_8));
        try (SimulationSession session = SimulationSession.open(SimulationConfig.builder(input.toString(), 1).build())) {
            WfCommonsJsonParser parser = new WfCommonsJsonParser();
            WfCommonsJsonParser.ParseResult result = parser.parse(input.toString(), 0, 1);
            assertEquals(Arrays.asList(1, 2, 3), ids(result.getTasks()));
            assertEquals(Arrays.asList(1, 2), ids(result.getTasks().get(2).getParentList()));
            assertEquals(2, result.getTasks().get(2).getDepth());
            assertEquals(4, result.getNextTaskId());
            assertEquals(Arrays.asList(1, 2), ids(parser.parse(input.toString(), 0, 1).getTasks().get(2).getParentList()));
        }
    }

    private static Element child(String child, String parent) {
        return new Element("child").setAttribute("ref", child)
                .addContent(new Element("parent").setAttribute("ref", parent));
    }

    private static List<Task> nodes(int size, Counter counter) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Task task = new Task(i + 1, 1000);
            task.setParentList(new CountedList<Task>(counter)); task.setChildList(new CountedList<Task>(counter));
            tasks.add(task);
        }
        return tasks;
    }

    private static void assertStar(List<Task> tasks, boolean fanOut, int width) {
        assertEquals(width, (fanOut ? tasks.get(0).getChildList() : tasks.get(0).getParentList()).size());
        for (int i = 1; i <= width; i++) {
            List<Task> back = fanOut ? tasks.get(i).getParentList() : tasks.get(i).getChildList();
            assertEquals(Arrays.asList(1), ids(back));
        }
    }

    private static List<Integer> ids(List<Task> tasks) {
        List<Integer> ids = new ArrayList<>();
        for (Task task : tasks) ids.add(task.getCloudletId());
        return ids;
    }

    private static SpecGraph specs(int width, boolean fanOut, Counter counter) throws Exception {
        Class<?> type = Class.forName("org.workflowsim.WfCommonsJsonParser$SpecTask");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        SpecGraph graph = new SpecGraph();
        for (int i = 0; i <= width; i++) {
            Object task = constructor.newInstance();
            set(type, task, "id", "t" + i);
            CountedList<String> parents = new CountedList<>(counter), children = new CountedList<>(counter);
            if (i == 0) {
                for (int leaf = 1; leaf <= width; leaf++) (fanOut ? children : parents).add("t" + leaf);
            } else {
                (fanOut ? parents : children).add("t0");
            }
            set(type, task, "parents", parents); set(type, task, "children", children);
            graph.tasks.add(task); graph.byId.put("t" + i, task);
        }
        return graph;
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static final class Counter { private long comparisons; }
    private static final class CountedList<E> extends ArrayList<E> {
        private final Counter counter;
        CountedList(Counter counter) { this.counter = counter; }
        @Override public boolean contains(Object sought) {
            for (E value : this) {
                counter.comparisons++;
                if (sought == null ? value == null : sought.equals(value)) return true;
            }
            return false;
        }
    }
    private static final class SpecGraph {
        private final List<Object> tasks = new ArrayList<>();
        private final Map<String, Object> byId = new LinkedHashMap<>();
    }
}
