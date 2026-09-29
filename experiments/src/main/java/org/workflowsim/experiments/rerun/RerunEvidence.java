package org.workflowsim.experiments.rerun;

import com.google.gson.JsonObject;
import java.nio.file.Path;

/**
 * 一次通过校验的v4证据包：三件套、可选网络账本路径/状态、事件计数与manifest。
 *
 * <p>本对象是只读快照。manifest 以 Gson {@code JsonObject} 树原样保存，后续
 * 配置重建与比对都在该树上工作；metrics/events和可选network-ledger的哈希、计数
 * 及必要内容/上下文已由 {@link RerunEvidenceReader} 委托现有验证器核对完毕。</p>
 */
public final class RerunEvidence {

    private final Path runDirectory;
    private final Path manifestPath;
    private final Path metricsPath;
    private final Path eventsPath;
    private final int eventCount;
    private final JsonObject manifest;
    private final Path networkLedgerPath;
    private final org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus;

    RerunEvidence(Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath,
            int eventCount, JsonObject manifest) {
        this(runDirectory,manifestPath,metricsPath,eventsPath,eventCount,manifest,null,null);
    }

    RerunEvidence(Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath,
            int eventCount, JsonObject manifest, Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus) {
        if (runDirectory == null || manifestPath == null || metricsPath == null
                || eventsPath == null || manifest == null) {
            throw new IllegalArgumentException("All evidence components are required");
        }
        this.runDirectory = runDirectory;
        this.manifestPath = manifestPath;
        this.metricsPath = metricsPath;
        this.eventsPath = eventsPath;
        this.eventCount = eventCount;
        boolean declared=manifest.has("configuration")&&manifest.get("configuration").isJsonObject()
                &&manifest.getAsJsonObject("configuration").has("networkEvidence");
        if(declared!=(networkLedgerPath!=null)||(networkLedgerPath==null)!=(networkCaptureStatus==null)
                ||networkCaptureStatus==org.workflowsim.data.TransferTraceSnapshot.Status.DISABLED){
            throw new IllegalArgumentException("Declared network evidence requires its validated path and capture status");
        }
        this.manifest = manifest;
        this.networkLedgerPath=networkLedgerPath;this.networkCaptureStatus=networkCaptureStatus;
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

    /** @return validated optional network ledger path, or null when OFF */
    public Path getNetworkLedgerPath() { return networkLedgerPath; }

    /** @return validated optional capture status, or null when OFF */
    public org.workflowsim.data.TransferTraceSnapshot.Status getNetworkCaptureStatus() { return networkCaptureStatus; }

    /** manifest 声明且 JSONL 实测一致的事件条数。 */
    public int getEventCount() {
        return eventCount;
    }

    /** 解析后的 manifest 树（v4 schema）。调用方不得修改。 */
    public JsonObject getManifest() {
        return manifest;
    }
}
