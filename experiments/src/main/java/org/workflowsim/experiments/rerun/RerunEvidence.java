package org.workflowsim.experiments.rerun;

import com.google.gson.JsonObject;
import java.nio.file.Path;

/**
 * 一次通过结构校验的 v4 证据包：三件套路径、事件计数与解析后的 manifest。
 *
 * <p>本对象是只读快照。manifest 以 Gson {@code JsonObject} 树原样保存，后续
 * 配置重建与比对都在该树上工作；sidecar（metrics/events）的文件哈希与事件
 * 计数已由 {@link RerunEvidenceReader} 委托现有验证器核对完毕。</p>
 */
public final class RerunEvidence {

    private final Path runDirectory;
    private final Path manifestPath;
    private final Path metricsPath;
    private final Path eventsPath;
    private final int eventCount;
    private final JsonObject manifest;

    RerunEvidence(Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath,
            int eventCount, JsonObject manifest) {
        if (runDirectory == null || manifestPath == null || metricsPath == null
                || eventsPath == null || manifest == null) {
            throw new IllegalArgumentException("All evidence components are required");
        }
        this.runDirectory = runDirectory;
        this.manifestPath = manifestPath;
        this.metricsPath = metricsPath;
        this.eventsPath = eventsPath;
        this.eventCount = eventCount;
        this.manifest = manifest;
    }

    public Path getRunDirectory() {
        return runDirectory;
    }

    public Path getManifestPath() {
        return manifestPath;
    }

    public Path getMetricsPath() {
        return metricsPath;
    }

    public Path getEventsPath() {
        return eventsPath;
    }

    /** manifest 声明且 JSONL 实测一致的事件条数。 */
    public int getEventCount() {
        return eventCount;
    }

    /** 解析后的 manifest 树（v4 schema）。调用方不得修改。 */
    public JsonObject getManifest() {
        return manifest;
    }
}
