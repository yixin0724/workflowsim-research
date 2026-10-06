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
    private final Path fileLifecyclePath;
    private final org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus;

    RerunEvidence(Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath,
            int eventCount, JsonObject manifest) {
        this(runDirectory,manifestPath,metricsPath,eventsPath,eventCount,manifest,null,null);
    }

    RerunEvidence(Path runDirectory, Path manifestPath, Path metricsPath, Path eventsPath,
            int eventCount, JsonObject manifest, Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus) {
        this(runDirectory,manifestPath,metricsPath,eventsPath,eventCount,manifest,networkLedgerPath,networkCaptureStatus,null,null);
    }

    RerunEvidence(Path runDirectory,Path manifestPath,Path metricsPath,Path eventsPath,int eventCount,JsonObject manifest,Path networkLedgerPath,
            org.workflowsim.data.TransferTraceSnapshot.Status networkCaptureStatus,Path fileLifecyclePath,org.workflowsim.data.v2.FileLifecycleEvidence.Status fileLifecycleCaptureStatus){
        if (runDirectory == null || manifestPath == null || metricsPath == null
                || eventsPath == null || manifest == null) {
            throw new IllegalArgumentException("All evidence components are required");
        }
        this.runDirectory = runDirectory;
        this.manifestPath = manifestPath;
        this.metricsPath = metricsPath;
        this.eventsPath = eventsPath;
        this.eventCount = eventCount;
        org.workflowsim.data.NetworkEvidenceConfig option=org.workflowsim.data.NetworkEvidenceConfig.off();
        if(manifest.has("configuration")&&manifest.get("configuration").isJsonObject()&&manifest.getAsJsonObject("configuration").has("networkEvidence"))option=org.workflowsim.data.NetworkEvidenceConfigCodec.decodeConfig(manifest.getAsJsonObject("configuration").get("networkEvidence"));
        boolean group=option.getMode()==org.workflowsim.data.NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,file=option.getMode()==org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2;
        if(group!=(networkLedgerPath!=null)||(networkLedgerPath==null)!=(networkCaptureStatus==null)||networkCaptureStatus==org.workflowsim.data.TransferTraceSnapshot.Status.DISABLED
                ||file!=(fileLifecyclePath!=null)||(fileLifecyclePath==null)!=(fileLifecycleCaptureStatus==null)||(fileLifecycleCaptureStatus!=null&&fileLifecycleCaptureStatus!=org.workflowsim.data.v2.FileLifecycleEvidence.Status.COMPLETE)){
            throw new IllegalArgumentException("Declared network evidence requires its version-specific validated path and capture status");
        }
        this.manifest = manifest;
        this.networkLedgerPath=networkLedgerPath;this.networkCaptureStatus=networkCaptureStatus;this.fileLifecyclePath=fileLifecyclePath;this.fileLifecycleCaptureStatus=fileLifecycleCaptureStatus;
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
    /** @return validated V2 sidecar, null for OFF/V1 */ public Path getFileLifecyclePath(){return fileLifecyclePath;}
    /** @return validated complete V2 capture, null for OFF/V1 */ public org.workflowsim.data.v2.FileLifecycleEvidence.Status getFileLifecycleCaptureStatus(){return fileLifecycleCaptureStatus;}

    /** manifest 声明且 JSONL 实测一致的事件条数。 */
    public int getEventCount() {
        return eventCount;
    }

    /** 解析后的 manifest 树（v4 schema）。调用方不得修改。 */
    public JsonObject getManifest() {
        return manifest;
    }
}
