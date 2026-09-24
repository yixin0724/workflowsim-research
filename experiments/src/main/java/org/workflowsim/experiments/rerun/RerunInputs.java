package org.workflowsim.experiments.rerun;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阶段 2 产物：manifest 中全部输入在本机的定位结果（只读）。
 *
 * <p>每个 {@link ResolvedInput} 记录原路径、本机实际路径、核对通过的 sha256 与
 * 命中的定位层级，供配置重建与报告使用；定位过程绝不静默替换输入。</p>
 */
public final class RerunInputs {

    private final List<ResolvedInput> resolved;

    RerunInputs(List<ResolvedInput> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            throw new IllegalArgumentException("At least one resolved input is required");
        }
        this.resolved = Collections.unmodifiableList(new ArrayList<ResolvedInput>(resolved));
    }

    public List<ResolvedInput> getResolved() {
        return resolved;
    }

    /** 按 manifest inputs[] 原始顺序返回第 i 个定位结果。 */
    public ResolvedInput get(int index) {
        return resolved.get(index);
    }

    public int size() {
        return resolved.size();
    }

    /** 单个输入的已核对定位结果。 */
    public static final class ResolvedInput {

        private final String recordedPath;
        private final Path resolvedPath;
        private final String sha256;
        private final long sizeBytes;
        private final int tier;

        ResolvedInput(String recordedPath, Path resolvedPath, String sha256, long sizeBytes,
                int tier) {
            if (recordedPath == null || resolvedPath == null || sha256 == null) {
                throw new IllegalArgumentException("All resolved input fields are required");
            }
            this.recordedPath = recordedPath;
            this.resolvedPath = resolvedPath;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.tier = tier;
        }

        /** manifest 记录的原机器绝对路径。 */
        public String getRecordedPath() {
            return recordedPath;
        }

        /** 本机实际使用且哈希核对通过的文件。 */
        public Path getResolvedPath() {
            return resolvedPath;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        /**
         * 命中的定位层级：1 = study inputs/ 目录，2 = 记录的绝对路径原样，
         * 3 = 按记录的 workingDirectory 相对化后重定位。
         */
        public int getTier() {
            return tier;
        }
    }
}
