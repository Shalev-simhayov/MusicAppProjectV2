# Lyrify — File Interface (Java 21)

Standalone Java 21 implementation of all 10 file-interface functions
for the Lyrify music metadata corrector pipeline.

## Source files

| File | Purpose |
|---|---|
| `TrackMetadata.java`     | Immutable record for a track's metadata (builder pattern) |
| `LrcLine.java`           | Timestamped lyric line record with LRC formatting |
| `ScanResult.java`        | Result wrapper for a single file scan |
| `RenameResult.java`      | Outcome of a single batch-rename operation |
| `LrcValidationReport.java` | Report from LRC timestamp validation |
| `LyrifyException.java`   | Checked domain exception |
| `MetadataBackup.java`    | JSON serialisation/deserialisation for backup/restore |
| `FileInterface.java`     | All 10 interface functions + CLI entry point |
| `FileInterfaceTest.java` | Self-contained test runner (no JUnit needed) |

## Dependencies

| Library | Use | Required? |
|---|---|---|
| `org.json` (json-20240303.jar) | Cache + backup JSON I/O | **Required** |
| `jaudiotagger` (jaudiotagger-3.0.1.jar) | Read/write audio tags | Optional — degrades gracefully |

Download from Maven Central or Releases:
- https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
- https://repo1.maven.org/maven2/org/jaudiotagger/jaudiotagger/3.0.1/jaudiotagger-3.0.1.jar

## Compile & run

```bash
# Compile
javac --release 21 -cp .:json-20240303.jar \
      src/lyrify/*.java -d out

# Run tests
java -cp out:json-20240303.jar lyrify.FileInterfaceTest

# CLI scan
java -cp out:json-20240303.jar:jaudiotagger-3.0.1.jar \
     lyrify.FileInterface /path/to/music
```

## Quick-start (code)

```java
import lyrify.*;
import java.nio.file.Path;
import java.util.*;

// 1. Scan a directory (with cache + backup)
List<ScanResult> results = FileInterface.scanDirectory(
    "/path/to/music", true, true, true, null
);

// 2. Inspect results
for (ScanResult r : results) {
    if (r.isSuccess()) {
        TrackMetadata m = r.metadata();
        System.out.println(m.artist() + " — " + m.title());
        System.out.println("Missing: " + m.missingFields());
    }
}

// 3. Write corrected tags
FileInterface.modifyMetadata(
    Path.of("/path/to/song.mp3"),
    Map.of("title", "Bohemian Rhapsody", "artist", "Queen", "year", "1975"),
    null // allow all fields
);

// 4. Create an LRC file
List<LrcLine> lines = List.of(
    new LrcLine(4200,  "Is this the real life?"),
    new LrcLine(8400,  "Is this just fantasy?"),
    new LrcLine(12800, "Caught in a landslide")
);
Path lrc = FileInterface.createLrcFile(
    Path.of("/path/to/song.mp3"), lines, metadata, null
);

// 5. Validate the LRC
LrcValidationReport report = FileInterface.validateLrcSync(lrc, 354.0, 2.0);
System.out.println(report); // LrcValidationReport{valid=true, lines=3, ...}

// 6. Batch rename (dry run first)
List<RenameResult> plan = FileInterface.batchRename(
    files, metadataMap,
    "{trackNumber} - {artist} - {title}",
    true  // dry run
);
plan.forEach(r -> System.out.println(r.originalPath() + " -> " + r.proposedPath()));
```

## Batch rename templates

| Template | Example output |
|---|---|
| `{trackNumber} - {artist} - {title}` | `01 - Queen - Bohemian Rhapsody.mp3` |
| `{year} - {album} - {title}` | `1975 - A Night at the Opera - Bohemian Rhapsody.mp3` |
| `{artist} - {title}` | `Queen - Bohemian Rhapsody.mp3` |

## Cache

A `.lyrify_cache.json` file is written to the scanned directory storing
`mtime + size` fingerprints. Unchanged files are skipped on re-scan.
Delete the file to force a full re-scan.

## Backup & restore

`backupMetadata()` writes a timestamped JSON file
(`lyrify_metadata_backup_YYYYMMDD_HHmmss.json`).
`restoreMetadata(path)` re-applies all stored tags.
Always back up before bulk modify or rename operations.
