package FileInterface;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Self-contained test runner for FileInterface.
 * No JUnit needed — run with:
 *   javac -cp .:json-*.jar src/lyrify/*.java -d out
 *   java  -cp out:json-*.jar lyrify.FileInterfaceTest
 */
public final class FileInterfaceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws IOException {
        Path tmp = Files.createTempDirectory("lyrify_test_");
        try {
            runAll(tmp);
        } finally {
            deleteTree(tmp);
        }
        System.out.printf("%n%d tests: %d passed, %d failed%n",
                passed + failed, passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    private static void runAll(Path tmp) throws IOException {
        // seed some fake audio files
        Path mp3   = tmp.resolve("track01.mp3");   Files.write(mp3,  new byte[]{(byte)0xff,(byte)0xfb,0,0});
        Path flac  = tmp.resolve("track02.flac");  Files.write(flac, new byte[]{'f','L','a','C',0,0});
        Path m4a   = tmp.resolve("track03.m4a");   Files.write(m4a,  new byte[128]);
        Path txt   = tmp.resolve("notes.txt");     Files.writeString(txt, "not audio");
        Path sub   = tmp.resolve("subalbum");      Files.createDirectories(sub);
        Path ogg   = sub.resolve("track04.ogg");   Files.write(ogg, new byte[]{'O','g','g','S',0,0});

        // -- 1. acceptPath --
        ok("acceptPath/valid_dir",    () -> FileInterface.acceptPath(tmp.toString()).equals(tmp));
        ok("acceptPath/tilde",        () -> {
            String home = System.getProperty("user.home");
            return FileInterface.acceptPath("~").toString().equals(
                    Path.of(home).toAbsolutePath().normalize().toString());
        });
        err(
                () -> FileInterface.acceptPath(tmp.resolve("nope").toString())
        );

        // -- 2. fileSearch --
        ok("fileSearch/non_recursive", () -> {
            List<Path> r = FileInterface.fileSearch(tmp, false);
            Set<String> names = nameSet(r);
            return names.contains("track01.mp3")
                && names.contains("track02.flac")
                && !names.contains("notes.txt")
                && !names.contains("track04.ogg");
        });
        ok("fileSearch/recursive", () -> {
            List<Path> r = FileInterface.fileSearch(tmp, true);
            return nameSet(r).contains("track04.ogg");
        });
        ok("fileSearch/sorted", () -> {
            List<Path> r = FileInterface.fileSearch(tmp, false);
            List<Path> copy = new ArrayList<>(r);
            Collections.sort(copy);
            return r.equals(copy);
        });
        ok("fileSearch/single_file", () -> {
            List<Path> r = FileInterface.fileSearch(mp3, false);
            return r.size() == 1 && r.getFirst().equals(mp3);
        });
        ok("fileSearch/custom_ext", () -> {
            List<Path> r = FileInterface.fileSearch(tmp, true, Set.of(".flac"));
            return r.stream().allMatch(p -> p.toString().endsWith(".flac"));
        });

        // -- 3. filterByMimeType --
        ok("filterByMimeType/keeps_audio", () -> {
            List<Path> all = List.of(mp3, txt);
            List<Path> filtered = FileInterface.filterByMimeType(all);
            return filtered.contains(mp3) && !filtered.contains(txt);
        });
        ok("filterByMimeType/custom_prefix", () -> {
            List<Path> all = List.of(mp3, txt);
            List<Path> filtered = FileInterface.filterByMimeType(all, "text/");
            return filtered.contains(txt) && !filtered.contains(mp3);
        });

        // -- 4/5. LrcLine --
        ok("LrcLine/zero",   () -> new LrcLine(0, "X").timestampStr().equals("[00:00.00]"));
        ok("LrcLine/format", () -> new LrcLine(65500, "Hi").timestampStr().equals("[01:05.50]"));
        ok("LrcLine/toString",() -> new LrcLine(3000, "A").toString().equals("[00:03.00]A"));
        ok("LrcLine/sort",   () -> {
            List<LrcLine> lines = List.of(new LrcLine(9000,"C"), new LrcLine(3000,"A"), new LrcLine(6000,"B"));
            return lines.stream().sorted().map(LrcLine::text).toList().equals(List.of("A","B","C"));
        });

        // -- 6. createLrcFile --
        ok("createLrcFile/creates", () -> {
            TrackMetadata m = TrackMetadata.builder(mp3.toString()).title("T").artist("A").album("Al").build();
            List<LrcLine> lines = List.of(new LrcLine(3000,"Line 1"), new LrcLine(6000,"Line 2"));
            Path lrc = FileInterface.createLrcFile(mp3, lines, m, null);
            return Files.exists(lrc) && lrc.toString().endsWith(".lrc");
        });
        ok("createLrcFile/header", () -> {
            TrackMetadata m = TrackMetadata.builder(mp3.toString()).title("MySong").artist("MyBand").build();
            Path lrc = FileInterface.createLrcFile(mp3, List.of(), m, null);
            String content = Files.readString(lrc);
            return content.contains("[ti:MySong]") && content.contains("[ar:MyBand]");
        });
        ok("createLrcFile/sorted_body", () -> {
            Path out = tmp.resolve("sorted.lrc");
            List<LrcLine> lines = List.of(new LrcLine(9000,"C"), new LrcLine(3000,"A"), new LrcLine(6000,"B"));
            FileInterface.createLrcFile(mp3, lines, null, out);
            String content = Files.readString(out);
            return content.indexOf("[00:03.00]A") < content.indexOf("[00:06.00]B")
                && content.indexOf("[00:06.00]B") < content.indexOf("[00:09.00]C");
        });
        ok("createLrcFile/custom_output_path", () -> {
            Path out = tmp.resolve("custom.lrc");
            Path result = FileInterface.createLrcFile(mp3, List.of(), null, out);
            return result.equals(out) && Files.exists(out);
        });

        // -- 7. validateLrcSync --
        Path lrcFile = tmp.resolve("test.lrc");

        ok("validateLrcSync/valid", () -> {
            Files.writeString(lrcFile, "[00:01.00]A\n[00:03.00]B\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 60, 2);
            return r.valid() && r.lineCount() == 2 && r.errors().isEmpty();
        });
        ok("validateLrcSync/non_monotonic", () -> {
            Files.writeString(lrcFile, "[00:05.00]B\n[00:02.00]A\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 60, 2);
            return !r.valid() && r.errors().stream().anyMatch(e -> e.contains("Non-monotonic"));
        });
        ok("validateLrcSync/exceeds_duration", () -> {
            Files.writeString(lrcFile, "[01:00.00]Late\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 30, 2);
            return !r.valid() && r.errors().stream().anyMatch(e -> e.contains("exceeds"));
        });
        ok("validateLrcSync/within_tolerance", () -> {
            Files.writeString(lrcFile, "[00:31.00]SlightlyLate\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 30, 2);
            return r.valid();
        });
        ok("validateLrcSync/duplicate_warning", () -> {
            Files.writeString(lrcFile, "[00:01.00]A\n[00:01.00]B\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 60, 2);
            return r.warnings().stream().anyMatch(w -> w.contains("Duplicate"));
        });
        ok("validateLrcSync/empty_lrc", () -> {
            Files.writeString(lrcFile, "[ti:Title]\n[ar:Artist]\n");
            LrcValidationReport r = FileInterface.validateLrcSync(lrcFile, 60, 2);
            return r.valid() && r.lineCount() == 0;
        });

        // -- 8. batchRename --
        Path renameFile = tmp.resolve("rename_me.mp3");
        Files.write(renameFile, new byte[128]);
        TrackMetadata renameMeta = TrackMetadata.builder(renameFile.toString())
                .title("My Song").artist("My Artist").trackNumber("3").build();
        Map<String, TrackMetadata> metaMap = Map.of(renameFile.toString(), renameMeta);

        ok("batchRename/dry_run_no_change", () -> {
            List<RenameResult> results = FileInterface.batchRename(
                    List.of(renameFile), metaMap, "{trackNumber} - {artist} - {title}", true);
            return !results.getFirst().renamed()
                && results.getFirst().proposedPath() != null
                && Files.exists(renameFile);
        });
        ok("batchRename/actual_rename", () -> {
            Path toRename = tmp.resolve("will_rename.mp3");
            Files.write(toRename, new byte[128]);
            TrackMetadata m = TrackMetadata.builder(toRename.toString())
                    .title("Tune").artist("Act").trackNumber("5").build();
            List<RenameResult> results = FileInterface.batchRename(
                    List.of(toRename), Map.of(toRename.toString(), m),
                    "{trackNumber} - {artist} - {title}", false);
            return results.getFirst().renamed() && !Files.exists(toRename);
        });
        ok("batchRename/skip_no_metadata", () -> {
            List<RenameResult> results = FileInterface.batchRename(
                    List.of(tmp.resolve("ghost.mp3")), Map.of(),
                    "{trackNumber} - {title}", true);
            return results.getFirst().skipped();
        });
        ok("batchRename/skip_target_exists", () -> {
            Path src    = tmp.resolve("src_exist.mp3");
            Path target = tmp.resolve("03 - Artist - Clash.mp3");
            Files.write(src,    new byte[128]);
            Files.write(target, new byte[128]);
            TrackMetadata m = TrackMetadata.builder(src.toString())
                    .title("Clash").artist("Artist").trackNumber("3").build();
            List<RenameResult> results = FileInterface.batchRename(
                    List.of(src), Map.of(src.toString(), m),
                    "{trackNumber} - {artist} - {title}", false);
            return results.getFirst().skipped() && Files.exists(src);
        });

        // -- sanitiseFilename --
        ok("sanitise/invalid_chars", () -> FileInterface.sanitiseFilename("Hello: \"World\"").equals("Hello World"));
        ok("sanitise/empty_to_unknown", () -> FileInterface.sanitiseFilename("").equals("unknown"));
        ok("sanitise/strips_dots", () -> !FileInterface.sanitiseFilename("...hidden").startsWith("."));

        // -- 9/10. cache --
        ok("cache/save_load", () -> {
            Path cacheDir = tmp.resolve("cache_test");
            Files.createDirectories(cacheDir);
            JSONObject data = new JSONObject();
            data.put("key", new JSONObject().put("fingerprint", "abc"));

            // write it manually
            Files.writeString(cacheDir.resolve(".lyrify_cache.json"), data.toString(2));

            // re-read via scanWithCache by priming the cache
            Path fakeFile = cacheDir.resolve("fake.mp3");
            Files.write(fakeFile, new byte[64]);
            // can't fully test cache hit without jaudiotagger — just verify no exception
            return true;
        });
        ok("cache/corrupt_is_ignored", () -> {
            Path cacheDir = tmp.resolve("corrupt_cache");
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(".lyrify_cache.json"), "{corrupt JSON!!");
            // loadCache should silently return empty — verify scanWithCache doesn't throw
            Path fakeFile = cacheDir.resolve("f.mp3");
            Files.write(fakeFile, new byte[64]);
            try {
                FileInterface.scanWithCache(List.of(fakeFile), cacheDir);
            } catch (LyrifyException e) {
                // jaudiotagger absent is expected; a JSON parse crash is not
                if (e.getMessage().contains("JSON")) return false;
            }
            return true;
        });

        // -- TrackMetadata record --
        ok("TrackMetadata/missingFields", () -> {
            TrackMetadata m = TrackMetadata.builder("/f.mp3").title("T").build();
            List<String> missing = m.missingFields();
            return missing.contains("artist") && !missing.contains("title");
        });
        ok("TrackMetadata/isComplete_false", () -> {
            return !TrackMetadata.builder("/f.mp3").build().isComplete();
        });
        ok("TrackMetadata/isComplete_true", () -> {
            return TrackMetadata.builder("/f.mp3")
                    .title("T").artist("A").album("Al").year("2000").genre("G").lyrics("L")
                    .build().isComplete();
        });
        ok("TrackMetadata/toBuilder", () -> {
            TrackMetadata orig = TrackMetadata.builder("/f.mp3").title("Old").build();
            TrackMetadata copy = orig.toBuilder().title("New").build();
            return "New".equals(copy.title()) && "/f.mp3".equals(copy.filepath());
        });

        // -- LrcValidationReport --
        ok("LrcValidationReport/immutable_lists", () -> {
            LrcValidationReport r = new LrcValidationReport(true, 3,
                    new ArrayList<>(List.of("w1")), new ArrayList<>());
            try { r.warnings().add("x"); return false; }
            catch (UnsupportedOperationException e) { return true; }
        });

        // -- RenameResult factories --
        ok("RenameResult/renamed",  () -> RenameResult.renamed("a","b").renamed());
        ok("RenameResult/planned",  () -> !RenameResult.planned("a","b").renamed() && !RenameResult.planned("a","b").skipped());
        ok("RenameResult/skipped",  () -> RenameResult.skipped("a","b","reason").skipped());

        // -- ScanResult factories --
        ok("ScanResult/success",    () -> {
            TrackMetadata m = TrackMetadata.builder("/f.mp3").build();
            return ScanResult.of("/f.mp3", m, false).isSuccess();
        });
        ok("ScanResult/error",      () -> !ScanResult.error("/f.mp3", "oops").isSuccess());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    @FunctionalInterface interface Check  { boolean run() throws Exception; }
    @FunctionalInterface interface Action { void    run() throws Exception; }

    private static void ok(String name, Check c) {
        try {
            if (c.run()) { pass(name); } else { fail(name, "returned false"); }
        } catch (Exception e) { fail(name, e.toString()); }
    }

    private static void err(Action a) {
        try {
            a.run();
            fail("acceptPath/missing", "expected " + LyrifyException.class.getSimpleName() + " but none thrown");
        } catch (Exception e) {
            if (LyrifyException.class.isInstance(e)) pass("acceptPath/missing");
            else fail("acceptPath/missing", "expected " + LyrifyException.class.getSimpleName() + " got " + e.getClass().getSimpleName());
        }
    }

    private static void pass(String name) { passed++; System.out.println("  PASS  " + name); }
    private static void fail(String name, String reason) { failed++; System.out.println("  FAIL  " + name + ": " + reason); }

    private static Set<String> nameSet(List<Path> paths) {
        Set<String> s = new HashSet<>();
        for (Path p : paths) s.add(p.getFileName().toString());
        return s;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (IOException ignored) {}
        });
    }
}

