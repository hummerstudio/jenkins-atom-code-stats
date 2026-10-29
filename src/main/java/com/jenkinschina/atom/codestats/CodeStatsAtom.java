package com.jenkinschina.atom.codestats;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lines of code as an atom: count what is in the workspace, by language, with the comment and blank split.
 *
 * <p>No external tool: cloc is a Perl script most agents do not have, and the counting rules that matter for a build
 * (which directories to skip, whether generated code counts) are the ones the caller wants to state anyway.
 */
public class CodeStatsAtom implements AtomTask {

    /** extension → language, plus the comment syntax used to tell code from comment. */
    private static final Map<String, Language> LANGUAGES = Map.ofEntries(
            Map.entry("java", new Language("Java", "//", "/*", "*/")),
            Map.entry("kt", new Language("Kotlin", "//", "/*", "*/")),
            Map.entry("groovy", new Language("Groovy", "//", "/*", "*/")),
            Map.entry("scala", new Language("Scala", "//", "/*", "*/")),
            Map.entry("c", new Language("C", "//", "/*", "*/")),
            Map.entry("h", new Language("C header", "//", "/*", "*/")),
            Map.entry("cpp", new Language("C++", "//", "/*", "*/")),
            Map.entry("cc", new Language("C++", "//", "/*", "*/")),
            Map.entry("hpp", new Language("C++ header", "//", "/*", "*/")),
            Map.entry("cs", new Language("C#", "//", "/*", "*/")),
            Map.entry("js", new Language("JavaScript", "//", "/*", "*/")),
            Map.entry("ts", new Language("TypeScript", "//", "/*", "*/")),
            Map.entry("jsx", new Language("JavaScript", "//", "/*", "*/")),
            Map.entry("tsx", new Language("TypeScript", "//", "/*", "*/")),
            Map.entry("go", new Language("Go", "//", "/*", "*/")),
            Map.entry("rs", new Language("Rust", "//", "/*", "*/")),
            Map.entry("swift", new Language("Swift", "//", "/*", "*/")),
            Map.entry("php", new Language("PHP", "//", "/*", "*/")),
            Map.entry("py", new Language("Python", "#", null, null)),
            Map.entry("rb", new Language("Ruby", "#", null, null)),
            Map.entry("sh", new Language("Shell", "#", null, null)),
            Map.entry("yml", new Language("YAML", "#", null, null)),
            Map.entry("yaml", new Language("YAML", "#", null, null)),
            Map.entry("sql", new Language("SQL", "--", "/*", "*/")),
            Map.entry("html", new Language("HTML", null, "<!--", "-->")),
            Map.entry("css", new Language("CSS", null, "/*", "*/")),
            Map.entry("scss", new Language("SCSS", "//", "/*", "*/")),
            Map.entry("xml", new Language("XML", null, "<!--", "-->")),
            Map.entry("md", new Language("Markdown", null, "<!--", "-->")));

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String includes = context.getParam("includes", "**/*.java, **/*.py, **/*.js, **/*.ts, **/*.go, **/*.rs, "
                + "**/*.c, **/*.cpp, **/*.h, **/*.kt, **/*.sh, **/*.yml, **/*.xml, **/*.md");
        String excludes = context.getParam("excludes", "**/target/**, **/build/**, **/node_modules/**, **/.git/**, "
                + "**/dist/**, **/out/**");
        String label = context.getParam("label", "Code statistics");
        String outDir = context.getParam("outputDir", "out/code-stats");
        int maxFiles = Math.max(0, context.getIntParam("maxFiles", 0));

        List<PathMatcher> include = matchers(includes);
        List<PathMatcher> exclude = matchers(excludes);
        Map<String, Tally> byLanguage = new LinkedHashMap<>();
        int files = 0;

        try (var walk = Files.walk(workspace, 20)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (files >= 20_000) {
                    break;
                }
                Path relative = workspace.relativize(path);
                if (include.stream().noneMatch(m -> m.matches(relative) || m.matches(java.nio.file.Path.of("_").resolve(relative)) || m.matches(path.getFileName()))
                        || exclude.stream().anyMatch(m -> m.matches(relative) || m.matches(java.nio.file.Path.of("_").resolve(relative)))) {
                    continue;
                }
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                Language language = dot < 0 ? null : LANGUAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
                if (language == null) {
                    continue;
                }
                Tally tally = byLanguage.computeIfAbsent(language.name(), key -> new Tally());
                count(path, language, tally);
                tally.files++;
                files++;
            }
        } catch (IOException e) {
            return AtomResult.error(ErrorType.SYSTEM, 40001, "could not walk the workspace: " + e.getMessage());
        }

        if (files == 0) {
            return AtomResult.error(ErrorType.USER, 40002, "no source file matched " + includes);
        }

        long lines = 0;
        long code = 0;
        long comments = 0;
        long blanks = 0;
        for (Tally tally : byLanguage.values()) {
            lines += tally.lines;
            code += tally.code;
            comments += tally.comments;
            blanks += tally.blank;
        }

        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"),
                render(label, files, byLanguage, lines, code, comments, blanks), StandardCharsets.UTF_8);

        context.log(String.format(Locale.ROOT, "[code-stats] %d file(s), %d lines (%d code, %d comment, %d blank)",
                files, lines, code, comments, blanks));

        AtomResult result = AtomResult.success()
                .data("files", String.valueOf(files))
                .data("lines", String.valueOf(lines))
                .data("code", String.valueOf(code))
                .data("comments", String.valueOf(comments))
                .data("blanks", String.valueOf(blanks))
                .report("report", label, dir.toString(), "index.html");

        if (maxFiles > 0 && files > maxFiles) {
            return AtomResult.failure(files + " source file(s), the limit is " + maxFiles)
                    .data("files", String.valueOf(files))
                    .data("lines", String.valueOf(lines))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    /** A line inside a block comment is not code; the state is per file, which is why this is a streaming loop. */
    private static void count(Path file, Language language, Tally tally) throws IOException {
        boolean inBlock = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            tally.lines++;
            if (line.isEmpty()) {
                tally.blank++;
                continue;
            }
            if (inBlock) {
                tally.comments++;
                if (language.blockEnd() != null && line.contains(language.blockEnd())) {
                    inBlock = false;
                }
                continue;
            }
            if (language.blockStart() != null && line.startsWith(language.blockStart())) {
                tally.comments++;
                inBlock = language.blockEnd() == null || !line.substring(language.blockStart().length())
                        .contains(language.blockEnd());
                continue;
            }
            if (language.lineComment() != null && line.startsWith(language.lineComment())) {
                tally.comments++;
                continue;
            }
            tally.code++;
        }
    }

    private static List<PathMatcher> matchers(String spec) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + trimmed));
            }
        }
        return matchers;
    }

    private static String render(String label, int files, Map<String, Tally> byLanguage,
                                 long lines, long code, long comments, long blanks) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 18px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:12px 16px;min-width:110px}")
                .append(".card b{display:block;font-size:22px}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb;font-size:13px}")
                .append("th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #eef0f3}")
                .append("th{background:#fafbfc;color:#6b7280}td.n{text-align:right;font-variant-numeric:tabular-nums}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">").append(files)
                .append(" file(s)</p><div class=\"cards\">");
        card(html, "lines", lines);
        card(html, "code", code);
        card(html, "comment", comments);
        card(html, "blank", blanks);
        html.append("</div><table><tr><th>Language</th><th class=\"n\">Files</th><th class=\"n\">Lines</th>")
                .append("<th class=\"n\">Code</th><th class=\"n\">Comment</th></tr>");
        byLanguage.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Tally> e) -> e.getValue().code).reversed())
                .forEach(entry -> html.append("<tr><td>").append(escape(entry.getKey()))
                        .append("</td><td class=\"n\">").append(entry.getValue().files)
                        .append("</td><td class=\"n\">").append(entry.getValue().lines)
                        .append("</td><td class=\"n\">").append(entry.getValue().code)
                        .append("</td><td class=\"n\">").append(entry.getValue().comments).append("</td></tr>"));
        return html.append("</table></body></html>").toString();
    }

    private static void card(StringBuilder html, String label, long value) {
        html.append("<div class=\"card\"><b>").append(value).append("</b><span>").append(label).append("</span></div>");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Language(String name, String lineComment, String blockStart, String blockEnd) {}

    private static final class Tally {
        int files;
        long lines;
        long code;
        long comments;
        long blank;

        // one Tally per language; the file count is added by count() callers through files++ above
        {
            this.files = 0;
        }
    }
}
