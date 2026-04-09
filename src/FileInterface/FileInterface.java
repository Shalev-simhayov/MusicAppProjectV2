package FileInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lyrify — File Interface
 *
 * <p>All ten file-interface operations for the music metadata pipeline:
 * <ol>
 *   <li>{@link #acceptPath}         – validate and resolve a filepath</li>
 *   <li>{@link #fileSearch}         – walk a directory for audio files</li>
 *   <li>{@link #filterByMimeType}   – filter paths by MIME / extension</li>
 *   <li>{@link #scanMetadata}       – read tags from an audio file</li>
 *   <li>{@link #createMetadataTag}  – write tag updates to a file</li>
 *   <li>{@link #modifyMetadata}     – validated alias for createMetadataTag</li>
 *   <li>{@link #createLrcFile}      – write a .lrc lyrics file</li>
 *   <li>{@link #validateLrcSync}    – check LRC timestamps vs audio duration</li>
 *   <li>{@link #batchRename}        – rename files from metadata templates</li>
 *   <li>{@link #backupMetadata}     – snapshot current tags to JSON</li>
 *   <li>{@link #restoreMetadata}    – re-apply tags from a backup JSON</li>
 *   <li>{@link #scanWithCache}      – scan with mtime+size change detection</li>
 *   <li>{@link #scanDirectory}      – full pipeline entry point</li>
 * </ol>
 *
 * <p>Requires: {@code org.json} on the classpath for JSON cache/backup I/O.
 * Tag read/write requires {@code jaudiotagger} (graceful error if absent).
 */
public final class FileInterface {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav", ".wma", ".opus"
    );

    private static final Map<String, String> EXTENSION_MIME = Map.of(
            ".mp3",  "audio/mpeg",
            ".flac", "audio/flac",
            ".m4a",  "audio/mp4",
            ".aac",  "audio/aac",
            ".ogg",  "audio/ogg",
            ".wav",  "audio/wav",
            ".wma",  "audio/x-ms-wma",
            ".opus", "audio/opus"
    );

    private static final String CACHE_FILENAME = ".lyrify_cache.json";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ------------------------------------------------------------------
    // 1. Accept filepath
    // ------------------------------------------------------------------

    /**
     * Validate and resolve a filepath or directory path.
     *
     * @param path raw path string (may include {@code ~})
     * @return resolved, absolute {@link Path}
     * @throws LyrifyException if the path does not exist
     */
    public static Path acceptPath(String path) throws LyrifyException {
        if (path == null || path.isBlank())
            throw new LyrifyException("Path must not be blank.");

        // Expand leading tilde manually (Files API does not do this)
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }

        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(resolved))
            throw new LyrifyException("Path does not exist: " + resolved);

        return resolved;
    }

    // ------------------------------------------------------------------
    // 2. File search
    // ------------------------------------------------------------------

    /**
     * Walk {@code root} and return all audio files matching the supported
     * extensions, sorted by absolute path.
     *
     * @param root      file or directory to search
     * @param recursive whether to descend into subdirectories
     * @return sorted list of matching {@link Path}s
     */
    public static List<Path> fileSearch(Path root, boolean recursive) throws LyrifyException {
        return fileSearch(root, recursive, AUDIO_EXTENSIONS);
    }

    /**
     * Variant of {@link #fileSearch(Path, boolean)} with a custom extension set.
     * Extensions must be lowercase and include the leading dot (e.g. {@code ".flac"}).
     */
    public static List<Path> fileSearch(Path root, boolean recursive, Set<String> extensions)
            throws LyrifyException {

        if (Files.isRegularFile(root)) {
            String ext = extension(root);
            return extensions.contains(ext) ? List.of(root) : List.of();
        }

        if (!Files.isDirectory(root))
            throw new LyrifyException("Not a file or directory: " + root);

        List<Path> results = new ArrayList<>();
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;

        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> extensions.contains(extension(p)))
                .forEach(results::add);
        } catch (IOException e) {
            throw new LyrifyException("Error walking directory: " + root, e);
        }

        Collections.sort(results);
        return Collections.unmodifiableList(results);
    }

    // ------------------------------------------------------------------
    // 3. Filter by MIME type
    // ------------------------------------------------------------------

    /**
     * Filter a list of paths to those whose inferred MIME type starts with
     * {@code "audio/"}.  Falls back to extension-based detection.
     */
    public static List<Path> filterByMimeType(List<Path> files) {
        return filterByMimeType(files, "audio/");
    }

    /**
     * Variant allowing a custom MIME prefix (e.g. {@code "video/"}).
     */
    public static List<Path> filterByMimeType(List<Path> files, String mimePrefix) {
        return files.stream()
                .filter(p -> {
                    String mime = mimeFor(p);
                    return mime != null && mime.startsWith(mimePrefix);
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // 4. Scan metadata  (read tags)
    // ------------------------------------------------------------------

    /**
     * Read ID3/Vorbis/MP4 tags from an audio file.
     *
     * <p>Requires {@code jaudiotagger} on the classpath.  If the library is
     * absent, a minimal {@link TrackMetadata} with only filesystem attributes
     * is returned and a warning is printed to stderr.
     *
     * @param path audio file to scan
     * @return populated {@link TrackMetadata}
     */
    public static TrackMetadata scanMetadata(Path path) throws LyrifyException {
        if (!Files.isRegularFile(path))
            throw new LyrifyException("Not a regular file: " + path);

        TrackMetadata.Builder builder = TrackMetadata.builder(path.toString());

        // Filesystem attributes (always available)
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            builder.fileSizeBytes(attrs.size());
            Instant mtime = attrs.lastModifiedTime().toInstant();
            builder.lastModified(LocalDateTime.ofInstant(mtime, ZoneId.systemDefault())
                    .format(TS_FMT));
        } catch (IOException e) {
            throw new LyrifyException("Cannot read file attributes: " + path, e);
        }

        builder.mimeType(mimeFor(path));

        // Tag reading via jaudiotagger (reflective so the code compiles without it)
        try {
            Class<?> afClass   = Class.forName("org.jaudiotagger.audio.AudioFileIO");
            Class<?> tagClass  = Class.forName("org.jaudiotagger.tag.Tag");
            Class<?> fieldEnum = Class.forName("org.jaudiotagger.tag.FieldKey");

            Object af  = afClass.getMethod("read", java.io.File.class)
                                 .invoke(null, path.toFile());
            Object tag = af.getClass().getMethod("getTag").invoke(af);
            Object hdr = af.getClass().getMethod("getAudioHeader").invoke(af);

            if (tag != null) {
                builder.title(      tagStr(tag, tagClass, fieldEnum, "TITLE"));
                builder.artist(     tagStr(tag, tagClass, fieldEnum, "ARTIST"));
                builder.album(      tagStr(tag, tagClass, fieldEnum, "ALBUM"));
                builder.albumArtist(tagStr(tag, tagClass, fieldEnum, "ALBUM_ARTIST"));
                builder.trackNumber(tagStr(tag, tagClass, fieldEnum, "TRACK"));
                builder.year(       tagStr(tag, tagClass, fieldEnum, "YEAR"));
                builder.genre(      tagStr(tag, tagClass, fieldEnum, "GENRE"));
                builder.lyrics(     tagStr(tag, tagClass, fieldEnum, "LYRICS"));
            }

            if (hdr != null) {
                Object len = hdr.getClass().getMethod("getTrackLength").invoke(hdr);
                if (len instanceof Number n)
                    builder.durationSeconds((double) n.intValue());
            }

        } catch (ClassNotFoundException e) {
            System.err.println("[lyrify] jaudiotagger not found — tag reading unavailable. " +
                               "Add org.jaudiotagger to the classpath.");
        } catch (Exception e) {
            throw new LyrifyException("Failed to read tags from: " + path, e);
        }

        return builder.build();
    }

    // ------------------------------------------------------------------
    // 5. Create metadata tag  (write tags)
    // ------------------------------------------------------------------

    /**
     * Write tag fields from {@code updates} into the file at {@code path}.
     * Existing tags not present in {@code updates} are preserved.
     *
     * <p>Supported keys: {@code title, artist, album, albumArtist, trackNumber,
     * year, genre, lyrics}.
     *
     * @return refreshed {@link TrackMetadata} after writing
     */
    public static TrackMetadata createMetadataTag(Path path, Map<String, String> updates)
            throws LyrifyException {

        if (updates == null || updates.isEmpty())
            throw new LyrifyException("No updates provided to createMetadataTag.");

        try {
            Class<?> afClass    = Class.forName("org.jaudiotagger.audio.AudioFileIO");
            Class<?> tagClass   = Class.forName("org.jaudiotagger.tag.Tag");
            Class<?> fieldEnum  = Class.forName("org.jaudiotagger.tag.FieldKey");

            Object af  = afClass.getMethod("read", java.io.File.class)
                                 .invoke(null, path.toFile());
            Object tag = af.getClass().getMethod("getTagOrCreateAndSetDefault").invoke(af);

            Map<String, String> keyMap = Map.of(
                    "title",       "TITLE",
                    "artist",      "ARTIST",
                    "album",       "ALBUM",
                    "albumArtist", "ALBUM_ARTIST",
                    "trackNumber", "TRACK",
                    "year",        "YEAR",
                    "genre",       "GENRE",
                    "lyrics",      "LYRICS"
            );

            for (var entry : updates.entrySet()) {
                String fieldName = keyMap.get(entry.getKey());
                if (fieldName == null) continue;
                Object fieldKey = fieldEnum.getMethod("valueOf", String.class)
                                           .invoke(null, fieldName);
                tagClass.getMethod("setField", fieldEnum, String.class)
                        .invoke(tag, fieldKey, entry.getValue());
            }

            af.getClass().getMethod("commit").invoke(af);

        } catch (ClassNotFoundException e) {
            throw new LyrifyException("jaudiotagger not found — tag writing unavailable.", e);
        } catch (Exception e) {
            throw new LyrifyException("Failed to write tags to: " + path, e);
        }

        return scanMetadata(path);
    }

    // ------------------------------------------------------------------
    // 6. Modify metadata  (validated alias)
    // ------------------------------------------------------------------

    /**
     * Validated wrapper around {@link #createMetadataTag}.
     *
     * <p>{@code allowedFields} restricts which keys may be modified — useful
     * as a safety guard in batch operations.  Pass {@code null} to allow all
     * supported fields.
     */
    public static TrackMetadata modifyMetadata(
            Path path,
            Map<String, String> updates,
            Set<String> allowedFields) throws LyrifyException {

        Set<String> allowed = allowedFields != null ? allowedFields : Set.of(
                "title", "artist", "album", "albumArtist",
                "trackNumber", "year", "genre", "lyrics"
        );

        Map<String, String> sanitised = new LinkedHashMap<>();
        for (var entry : updates.entrySet()) {
            if (allowed.contains(entry.getKey()) && entry.getValue() != null)
                sanitised.put(entry.getKey(), entry.getValue());
        }

        if (sanitised.isEmpty())
            throw new LyrifyException("No valid/allowed fields supplied to modifyMetadata.");

        return createMetadataTag(path, sanitised);
    }

    // ------------------------------------------------------------------
    // 7. Create LRC file
    // ------------------------------------------------------------------

    /**
     * Write a {@code .lrc} file alongside (or at {@code outputPath}) the
     * audio file.  Lines are sorted by timestamp before writing.
     *
     * @param audioPath  source audio file (used to derive default output path)
     * @param lines      lyric lines — need not be pre-sorted
     * @param metadata   optional metadata for LRC header tags
     * @param outputPath explicit destination, or {@code null} to place next to audio
     * @return path of the written {@code .lrc} file
     */
    public static Path createLrcFile(
            Path audioPath,
            List<LrcLine> lines,
            TrackMetadata metadata,
            Path outputPath) throws LyrifyException {

        Path lrcPath = outputPath != null
                ? outputPath
                : audioPath.resolveSibling(
                        audioPath.getFileName().toString()
                        .replaceFirst("\\.[^.]+$", "") + ".lrc");

        List<String> lrcLines = new ArrayList<>();

        // Header
        if (metadata != null) {
            if (metadata.title()  != null) lrcLines.add("[ti:" + metadata.title()  + "]");
            if (metadata.artist() != null) lrcLines.add("[ar:" + metadata.artist() + "]");
            if (metadata.album()  != null) lrcLines.add("[al:" + metadata.album()  + "]");
        }
        lrcLines.add("[by:Lyrify]");
        lrcLines.add("[ve:1.0]");
        lrcLines.add("");

        // Body — sorted ascending
        lines.stream()
             .sorted()
             .map(LrcLine::toString)
             .forEach(lrcLines::add);

        try {
            Files.writeString(lrcPath, String.join("\n", lrcLines), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LyrifyException("Cannot write LRC file: " + lrcPath, e);
        }

        return lrcPath;
    }

    // ------------------------------------------------------------------
    // 8. Validate LRC timestamp sync
    // ------------------------------------------------------------------

    /**
     * Parse an LRC file and verify:
     * <ul>
     *   <li>Timestamps are monotonically increasing</li>
     *   <li>No timestamp exceeds audio duration (within {@code toleranceSeconds})</li>
     *   <li>No duplicate timestamps</li>
     * </ul>
     *
     * @param lrcPath          path to the {@code .lrc} file
     * @param durationSeconds  total audio duration in seconds
     * @param toleranceSecs    allowed overshoot in seconds (typically 2.0)
     * @return {@link LrcValidationReport}
     */
    public static LrcValidationReport validateLrcSync(
            Path lrcPath,
            double durationSeconds,
            double toleranceSecs) throws LyrifyException {

        String content;
        try {
            content = Files.readString(lrcPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LyrifyException("Cannot read LRC file: " + lrcPath, e);
        }

        Pattern TS = Pattern.compile("\\[(\\d{2}):(\\d{2}\\.\\d{2})](.*)");

        record Pair(int ms, String text) {}
        List<Pair> parsed = new ArrayList<>();
        for (String line : content.lines().toList()) {
            Matcher m = TS.matcher(line);
            if (m.matches()) {
                int mins   = Integer.parseInt(m.group(1));
                double sec = Double.parseDouble(m.group(2));
                int ms     = (int) (mins * 60_000 + sec * 1000);
                parsed.add(new Pair(ms, m.group(3).strip()));
            }
        }

        List<String> warnings = new ArrayList<>();
        List<String> errors   = new ArrayList<>();
        int maxMs = (int) ((durationSeconds + toleranceSecs) * 1000);

        Set<Integer> seen = new HashSet<>();
        int prevMs = -1;

        for (Pair p : parsed) {
            if (!seen.add(p.ms()))
                warnings.add("Duplicate timestamp %dms for line: '%s'".formatted(p.ms(), p.text()));

            if (p.ms() < prevMs)
                errors.add("Non-monotonic timestamp at %dms (previous: %dms)".formatted(p.ms(), prevMs));

            prevMs = p.ms();

            if (p.ms() > maxMs)
                errors.add("Timestamp %dms (%.2fs) exceeds audio duration %.2fs (tolerance ±%.1fs)"
                        .formatted(p.ms(), p.ms() / 1000.0, durationSeconds, toleranceSecs));
        }

        return new LrcValidationReport(errors.isEmpty(), parsed.size(), warnings, errors);
    }

    // ------------------------------------------------------------------
    // 9. Batch rename
    // ------------------------------------------------------------------

    /**
     * Rename audio files using a template string.
     *
     * <p>Template tokens: {@code {title}}, {@code {artist}}, {@code {album}},
     * {@code {year}}, {@code {genre}}, {@code {trackNumber}} (zero-padded to 2 digits).
     * Example: {@code "{trackNumber} - {artist} - {title}"}
     *
     * @param files     files to rename
     * @param metadatas map of absolute-path-string → TrackMetadata
     * @param template  filename template (without extension)
     * @param dryRun    if {@code true}, plan only — no filesystem changes
     * @return list of {@link RenameResult} for every input file
     */
    public static List<RenameResult> batchRename(
            List<Path> files,
            Map<String, TrackMetadata> metadatas,
            String template,
            boolean dryRun) {

        List<RenameResult> results = new ArrayList<>();

        for (Path file : files) {
            TrackMetadata meta = metadatas.get(file.toString());
            if (meta == null) {
                results.add(RenameResult.skipped(file.toString(), null, "No metadata found"));
                continue;
            }

            // Build track number with zero-padding
            int trackInt = 0;
            if (meta.trackNumber() != null) {
                try {
                    trackInt = Integer.parseInt(meta.trackNumber().split("/")[0].strip());
                } catch (NumberFormatException ignored) {}
            }

            String newStem = template
                    .replace("{title}",       sanitiseFilename(meta.title()       != null ? meta.title()       : "Unknown Title"))
                    .replace("{artist}",      sanitiseFilename(meta.artist()      != null ? meta.artist()      : "Unknown Artist"))
                    .replace("{album}",       sanitiseFilename(meta.album()       != null ? meta.album()       : "Unknown Album"))
                    .replace("{year}",        sanitiseFilename(meta.year()        != null ? meta.year()        : "0000"))
                    .replace("{genre}",       sanitiseFilename(meta.genre()       != null ? meta.genre()       : "Unknown"))
                    .replace("{trackNumber}", "%02d".formatted(trackInt));

            String ext = extension(file);
            Path newPath = file.resolveSibling(newStem + ext);

            if (newPath.equals(file)) {
                results.add(RenameResult.skipped(file.toString(), newPath.toString(), "Name unchanged"));
                continue;
            }
            if (Files.exists(newPath)) {
                results.add(RenameResult.skipped(file.toString(), newPath.toString(), "Target already exists"));
                continue;
            }

            if (!dryRun) {
                try {
                    Files.move(file, newPath, StandardCopyOption.ATOMIC_MOVE);
                    results.add(RenameResult.renamed(file.toString(), newPath.toString()));
                } catch (IOException e) {
                    results.add(RenameResult.skipped(file.toString(), newPath.toString(),
                            "Move failed: " + e.getMessage()));
                }
            } else {
                results.add(RenameResult.planned(file.toString(), newPath.toString()));
            }
        }

        return Collections.unmodifiableList(results);
    }

    // ------------------------------------------------------------------
    // 10a. Backup metadata
    // ------------------------------------------------------------------

    /**
     * Serialise current metadata for {@code files} into a timestamped JSON
     * backup file inside {@code backupDir} (defaults to the parent of the
     * first file).
     *
     * @return path to the written backup JSON file
     */
    public static Path backupMetadata(List<Path> files, Path backupDir)
            throws LyrifyException {

        if (files == null || files.isEmpty())
            throw new LyrifyException("No files provided for backup.");

        Path dir = backupDir != null ? backupDir : files.get(0).getParent();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new LyrifyException("Cannot create backup directory: " + dir, e);
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path bakPath = dir.resolve("lyrify_metadata_backup_" + ts + ".json");

        JSONArray arr = new JSONArray();
        for (Path f : files) {
            try {
                TrackMetadata meta = scanMetadata(f);
                arr.put(MetadataBackup.from(meta).toJson());
            } catch (LyrifyException e) {
                JSONObject errEntry = new JSONObject();
                errEntry.put("filepath", f.toString());
                errEntry.put("error", e.getMessage());
                arr.put(errEntry);
            }
        }

        try {
            Files.writeString(bakPath, arr.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LyrifyException("Cannot write backup file: " + bakPath, e);
        }

        return bakPath;
    }

    // ------------------------------------------------------------------
    // 10b. Restore metadata
    // ------------------------------------------------------------------

    /**
     * Re-apply tags from a backup JSON file produced by {@link #backupMetadata}.
     *
     * @return list of status maps with keys {@code filepath}, {@code status},
     *         {@code error}
     */
    public static List<Map<String, String>> restoreMetadata(Path backupPath)
            throws LyrifyException {

        String json;
        try {
            json = Files.readString(backupPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LyrifyException("Cannot read backup file: " + backupPath, e);
        }

        JSONArray arr = new JSONArray(json);
        List<Map<String, String>> results = new ArrayList<>();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String filepath = obj.optString("filepath", null);

            if (filepath == null || !Files.exists(Path.of(filepath))) {
                results.add(Map.of("filepath", filepath != null ? filepath : "?",
                                   "status", "skipped", "error", "File not found"));
                continue;
            }

            try {
                TrackMetadata meta = new MetadataBackup(obj).toMetadata();    // reuse toJson trick
                // Actually reconstruct via MetadataBackup.from(meta).toMetadata() is circular;
                // build update map directly from the JSON
                Map<String, String> updates = new LinkedHashMap<>();
                putIfPresent(updates, obj, "title");
                putIfPresent(updates, obj, "artist");
                putIfPresent(updates, obj, "album");
                putIfPresent(updates, obj, "albumArtist");
                putIfPresent(updates, obj, "trackNumber");
                putIfPresent(updates, obj, "year");
                putIfPresent(updates, obj, "genre");
                putIfPresent(updates, obj, "lyrics");

                modifyMetadata(Path.of(filepath), updates, null);
                results.add(Map.of("filepath", filepath, "status", "restored", "error", ""));
            } catch (Exception e) {
                results.add(Map.of("filepath", filepath,
                                   "status", "failed", "error", e.getMessage()));
            }
        }

        return Collections.unmodifiableList(results);
    }

    // ------------------------------------------------------------------
    // 11. Cache scan results
    // ------------------------------------------------------------------

    /**
     * Scan metadata for {@code files}, skipping any whose {@code mtime+size}
     * fingerprint matches the on-disk cache.
     *
     * <p>Cache is stored at {@code cacheDir/.lyrify_cache.json}.
     * Pass {@code null} for {@code cacheDir} to use the parent of the first file.
     */
    public static List<ScanResult> scanWithCache(List<Path> files, Path cacheDir)
            throws LyrifyException {

        if (files.isEmpty()) return List.of();

        Path dir = cacheDir != null ? cacheDir : files.get(0).getParent();
        Path cachePath = dir.resolve(CACHE_FILENAME);

        JSONObject cache = loadCache(cachePath);
        List<ScanResult> results = new ArrayList<>();
        boolean dirty = false;

        for (Path f : files) {
            String key = f.toString();
            String fp  = fingerprint(f);
            JSONObject entry = cache.optJSONObject(key);

            if (entry != null && fp.equals(entry.optString("fingerprint"))) {
                // Cache hit — reconstruct TrackMetadata from cached JSON
                JSONObject metaJson = entry.optJSONObject("metadata");
                if (metaJson != null) {
                    TrackMetadata meta = new MetadataBackup(metaJson).toMetadata();
                    results.add(ScanResult.of(key, meta, true));
                    continue;
                }
            }

            // Cache miss — full scan
            try {
                TrackMetadata meta = scanMetadata(f);
                JSONObject newEntry = new JSONObject();
                newEntry.put("fingerprint", fp);
                newEntry.put("metadata",    MetadataBackup.from(meta).toJson());
                newEntry.put("scannedAt",   LocalDateTime.now().format(TS_FMT));
                cache.put(key, newEntry);
                dirty = true;
                results.add(ScanResult.of(key, meta, false));
            } catch (LyrifyException e) {
                results.add(ScanResult.error(key, e.getMessage()));
            }
        }

        if (dirty) saveCache(cachePath, cache);
        return Collections.unmodifiableList(results);
    }

    // ------------------------------------------------------------------
    // 12. Full directory scan entry point
    // ------------------------------------------------------------------

    /**
     * High-level convenience method that chains the full file interface:
     * <ol>
     *   <li>Validate path</li>
     *   <li>Search for audio files</li>
     *   <li>Filter by MIME type</li>
     *   <li>Optionally backup existing metadata</li>
     *   <li>Scan with cache</li>
     * </ol>
     *
     * @param root      directory to scan
     * @param recursive whether to descend into subdirectories
     * @param useCache  enable mtime+size caching
     * @param backup    snapshot existing tags before scanning
     * @param backupDir destination for backup JSON (null = same as root)
     * @return scan results; backup file path is printed if created
     */
    public static List<ScanResult> scanDirectory(
            String root,
            boolean recursive,
            boolean useCache,
            boolean backup,
            Path backupDir) throws LyrifyException {

        Path rootPath = acceptPath(root);
        List<Path> files = fileSearch(rootPath, recursive);
        files = filterByMimeType(files);

        if (backup && !files.isEmpty()) {
            Path bakPath = backupMetadata(files, backupDir);
            System.out.println("[lyrify] Backup written: " + bakPath);
        }

        Path cacheDir = Files.isDirectory(rootPath) ? rootPath : rootPath.getParent();
        return useCache ? scanWithCache(files, cacheDir) : files.stream()
                .map(f -> {
                    try {
                        return ScanResult.of(f.toString(), scanMetadata(f), false);
                    } catch (LyrifyException e) {
                        return ScanResult.error(f.toString(), e.getMessage());
                    }
                }).toList();
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static String mimeFor(Path p) {
        return EXTENSION_MIME.getOrDefault(extension(p), null);
    }

    /** mtime (epoch ms) + '_' + size in bytes */
    private static String fingerprint(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return a.lastModifiedTime().toMillis() + "_" + a.size();
        } catch (IOException e) {
            return "0_0";
        }
    }

    private static JSONObject loadCache(Path cachePath) {
        if (!Files.exists(cachePath)) return new JSONObject();
        try {
            String text = Files.readString(cachePath, StandardCharsets.UTF_8);
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void saveCache(Path cachePath, JSONObject cache) {
        try {
            Files.writeString(cachePath, cache.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[lyrify] Warning: could not save cache — " + e.getMessage());
        }
    }

    /** Remove filesystem-unsafe characters and trim. */
    static String sanitiseFilename(String name) {
        // Normalise unicode
        name = Normalizer.normalize(name, Normalizer.Form.NFKD);
        // Strip OS-invalid characters
        name = name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "");
        // Trim leading/trailing dots and spaces
        name = name.strip().replaceAll("^[. ]+|[. ]+$", "");
        return name.isEmpty() ? "unknown" : name;
    }

    /** Reflectively read a tag field — returns null if missing/blank. */
    private static String tagStr(Object tag, Class<?> tagClass, Class<?> fieldEnum, String fieldName)
            throws Exception {
        Object key = fieldEnum.getMethod("valueOf", String.class).invoke(null, fieldName);
        Object val = tagClass.getMethod("getFirst", fieldEnum).invoke(tag, key);
        if (val instanceof String s && !s.isBlank()) return s.strip();
        return null;
    }

    private static void putIfPresent(Map<String, String> map, JSONObject obj, String key) {
        String v = obj.optString(key, null);
        if (v != null && !v.isBlank()) map.put(key, v);
    }

    // ------------------------------------------------------------------
    // CLI smoke-test
    // ------------------------------------------------------------------
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java -cp .:json-*.jar:jaudiotagger-*.jar lyrify.FileInterface <music_dir>");
            return;
        }

        List<ScanResult> results = scanDirectory(args[0], true, true, false, null);

        results.stream().limit(10).forEach(r -> {
            if (r.isSuccess()) {
                TrackMetadata m = r.metadata();
                System.out.printf("[%s] %s — %s (%s)%n",
                        r.fromCache() ? "cache" : "scan",
                        m.artist() != null ? m.artist() : "?",
                        m.title()  != null ? m.title()  : "?",
                        m.year()   != null ? m.year()   : "?");
                if (!m.missingFields().isEmpty())
                    System.out.println("       missing: " + m.missingFields());
            } else {
                System.out.println("[error] " + r.path() + " — " + r.error());
            }
        });

        System.out.println("\nTotal: " + results.size() + " tracks");
    }
}
