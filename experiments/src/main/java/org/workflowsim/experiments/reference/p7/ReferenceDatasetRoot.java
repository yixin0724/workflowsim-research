package org.workflowsim.experiments.reference.p7;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** P7/reference 运行入口共享的显式数据集根目录契约。 */
final class ReferenceDatasetRoot {

    private ReferenceDatasetRoot() {
    }

    /**
     * 校验并规范化一个显式、绝对的数据集目录。
     *
     * @param datasetRoot 调用者提供的 {@code datasets} 目录
     * @return 规范化后的绝对目录
     * @throws IllegalArgumentException 当目录为空、相对、不存在或不是目录时抛出
     */
    static Path require(Path datasetRoot) {
        if (datasetRoot == null) {
            throw new IllegalArgumentException("Dataset root is required");
        }
        if (!datasetRoot.isAbsolute()) {
            throw new IllegalArgumentException("Dataset root must be absolute: " + datasetRoot);
        }
        Path normalized = datasetRoot.normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Dataset root is not a directory: " + normalized);
        }
        return normalized;
    }

    /**
     * 在已验证的数据集根内解析一个声明的相对输入文件。
     *
     * @param datasetRoot 显式、绝对的数据集根
     * @param relativePath 由冻结场景声明的相对文件名
     * @return 已验证的规范化绝对常规文件
     * @throws IllegalArgumentException 当路径逃逸数据集根、无效或不存在时抛出
     */
    static Path resolveFile(Path datasetRoot, String relativePath) {
        Path normalizedRoot = require(datasetRoot);
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset-relative input path is required");
        }
        Path relative;
        try {
            relative = Paths.get(relativePath);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Invalid dataset-relative input path: " + relativePath,
                    exception);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Dataset input path must be relative: " + relativePath);
        }
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Dataset input path escapes dataset root: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Dataset input file does not exist: " + resolved);
        }
        return resolved;
    }
}
