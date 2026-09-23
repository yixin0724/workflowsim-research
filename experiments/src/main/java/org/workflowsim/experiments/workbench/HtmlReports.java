package org.workflowsim.experiments.workbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.experiment.ExperimentArtifactValidator;

/** Offline report rendering from validated evidence; no CDN, server or executable user HTML. */
public final class HtmlReports {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private HtmlReports() { }

    public static void experiment(String name, List<Map<String, Object>> runs, Path target) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("name", name); payload.put("runs", runs);
        String template;
        try (InputStream input = HtmlReports.class.getResourceAsStream("/workbench/report.html")) {
            if (input == null) { throw new IOException("Missing workbench report template"); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) >= 0) { bytes.write(buffer, 0, length); }
            template = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        // Gson HTML escaping encodes '<', '>', '&' and quotes; also protect JS separators.
        String data = JSON.toJson(payload).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("@@(TITLE|DATA)@@").matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (marker.find()) {
            marker.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(
                    "TITLE".equals(marker.group(1)) ? escape(name) : data));
        }
        marker.appendTail(rendered);
        writeAtomic(target, rendered.toString());
    }

    public static JsonObject validatedManifest(Path path) throws IOException {
        ExperimentArtifactValidator.validate(path);
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".report-", ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
