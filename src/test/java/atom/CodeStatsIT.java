package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The code-stats atom, started the way the runner starts it.
 *
 * <p>The checks the old {@code tools/smoke.sh} made: exact line counts on a known workspace, one block comment spanning
 * two lines, {@code target/} excluded, the file budget, and an empty workspace.
 */
class CodeStatsIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** Three source files with known counts, plus one under target/ that must not be counted. */
    private Atom workspace() {
        return atom.file("src/A.java", """
                        package demo;

                        // a line comment
                        public class A {
                            /*
                             * block
                             */
                            int x = 1;
                        }
                        """)
                .file("src/b.py", "def f():\n    # comment\n    return 1\n\n")
                .file("src/c.py", "x = 1\n")
                .file("target/Gen.java", "public class Gen { }\n");
    }

    @Test
    @DisplayName("files/code/comment/blank counts are exact, and target/ is not counted")
    void countsLinesExactly() throws Exception {
        AtomResult run = workspace().input("{\"label\": \"Code statistics\"}").run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("3", run.field("files"), "a file under target/ must not be counted");
        assertEquals("7", run.field("code"));
        assertEquals("5", run.field("comments"));
        assertEquals("2", run.field("blanks"));
    }

    @Test
    @DisplayName("the report is written and names the languages it found")
    void writesTheReport() throws Exception {
        AtomResult run = workspace().input("{\"label\": \"Code statistics\"}").run();

        run.assertOk();
        String report = run.file("out/code-stats/index.html");
        assertNotNull(report, "no report");
        assertTrue(report.contains("Python"), "report misses a language");
    }

    @Test
    @DisplayName("a budget on the file count trips the atom, and the message names the limit")
    void refusesOverTheFileBudget() throws Exception {
        AtomResult run = atom.file("one.py", "x = 1\n")
                .file("two.py", "y = 2\n")
                .input("{\"maxFiles\": \"1\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("the limit is 1"), run.file("output.json"));
    }

    @Test
    @DisplayName("a workspace with nothing to count is a clear error")
    void refusesAnEmptyWorkspace() throws Exception {
        AtomResult run = atom.input("{}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no source file matched"), run.file("output.json"));
    }
}
