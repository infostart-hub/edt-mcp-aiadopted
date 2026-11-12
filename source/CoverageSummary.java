// SPDX-License-Identifier: AGPL-3.0-or-later
import java.io.File;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageNode.CounterEntity;
import org.jacoco.core.tools.ExecFileLoader;

/** Print real JVM counters without creating an XML/HTML coverage report. */
class CoverageSummary {
    public static void main(String[] args) throws Exception {
        var execution = new ExecFileLoader();
        execution.load(new File(args[0]));
        var builder = new CoverageBuilder();
        new Analyzer(execution.getExecutionDataStore(), builder).analyzeAll(new File(args[1]));
        if (builder.getClasses().isEmpty() || !builder.getNoMatchClasses().isEmpty()) {
            throw new IllegalStateException("Missing or mismatched product classes");
        }
        var bundle = builder.getBundle(args[2]);
        ICounter lines = bundle.getCounter(CounterEntity.LINE);
        ICounter branches = bundle.getCounter(CounterEntity.BRANCH);
        if (lines.getTotalCount() == 0 || lines.getCoveredCount() == 0) {
            throw new IllegalStateException("No product lines were measured");
        }
        String branchPercent = branches.getTotalCount() == 0 ? "null"
            : String.format(java.util.Locale.ROOT, "%.4f", 100.0 * branches.getCoveredRatio());
        System.out.printf(java.util.Locale.ROOT,
            "Java source coverage totals: {\"scope\":\"%s\",\"classes\":%d,"
            + "\"covered_lines\":%d,\"total_lines\":%d,\"line_percent\":%.4f,"
            + "\"covered_branches\":%d,\"total_branches\":%d,\"branch_percent\":%s}%n",
            args[2], builder.getClasses().size(), lines.getCoveredCount(), lines.getTotalCount(),
            100.0 * lines.getCoveredRatio(), branches.getCoveredCount(), branches.getTotalCount(),
            branchPercent);
    }
}
