package org.workflowsim.experiments.rerun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * rerun 流程的机器可读失败：携带 {@link RerunVerdict} 与可选细节列表。
 *
 * <p>契约要求每一步失败都 fail fast 并给出原因；调用方把 verdict 映射到退出码，
 * 把 message 与 details 写入报告。本异常不代表程序 bug，而是契约定义的合法
 * 失败路径之一（包括 {@code RECONSTRUCTION_REJECTED} 这类信息性结论）。</p>
 */
public final class RerunFailureException extends Exception {

    private static final long serialVersionUID = 1L;

    private final RerunVerdict verdict;
    private final List<String> details;

    public RerunFailureException(RerunVerdict verdict, String message) {
        this(verdict, message, Collections.<String>emptyList(), null);
    }

    public RerunFailureException(RerunVerdict verdict, String message, List<String> details) {
        this(verdict, message, details, null);
    }

    public RerunFailureException(RerunVerdict verdict, String message, Throwable cause) {
        this(verdict, message, Collections.<String>emptyList(), cause);
    }

    public RerunFailureException(RerunVerdict verdict, String message, List<String> details,
            Throwable cause) {
        super(message, cause);
        if (verdict == null || message == null || details == null) {
            throw new IllegalArgumentException("Verdict, message and details are required");
        }
        this.verdict = verdict;
        this.details = Collections.unmodifiableList(new ArrayList<String>(details));
    }

    /** 本失败对应的契约结论。 */
    public RerunVerdict getVerdict() {
        return verdict;
    }

    /** 附加细节（如尝试过的候选路径、被拒绝的具体原因）；可为空列表，不可为 null。 */
    public List<String> getDetails() {
        return details;
    }
}
