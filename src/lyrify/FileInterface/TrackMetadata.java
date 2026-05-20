package lyrify.FileInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable metadata container for a single audio track.
 * Uses a builder for construction; null fields indicate missing data.
 */
//record for metadata
public record TrackMetadata(
        String  filepath,
        String  title,
        String  artist,
        String  album,
        String  albumArtist,
        String  trackNumber,
        String  year,
        String  genre,
        String  lyrics,
        Double  durationSeconds,
        String  mimeType,
        Long    fileSizeBytes,
        String  lastModified
) {
    //arraylist of the missing fields, creates a copy so that we dont give immutable data to
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (title  == null) missing.add("title");
        if (artist == null) missing.add("artist");
        if (album  == null) missing.add("album");
        if (year   == null) missing.add("year");
        if (genre  == null) missing.add("genre");
        if (lyrics == null) missing.add("lyrics");
        return List.copyOf(missing);
    }

    public boolean isComplete() {
        return missingFields().isEmpty();
    }

    /** Copy this record with selected fields overridden. */
    public Builder toBuilder() {
        return new Builder(filepath)
                .title(title).artist(artist).album(album).albumArtist(albumArtist)
                .trackNumber(trackNumber).year(year).genre(genre).lyrics(lyrics)
                .durationSeconds(durationSeconds).mimeType(mimeType)
                .fileSizeBytes(fileSizeBytes).lastModified(lastModified);
    }

    public static Builder builder(String filepath) {
        return new Builder(filepath);
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------
    public static final class Builder {
        private final String filepath;
        private String title, artist, album, albumArtist;
        private String trackNumber, year, genre, lyrics;
        private Double durationSeconds;
        private String mimeType, lastModified;
        private Long   fileSizeBytes;

        public Builder(String filepath) {
            if (filepath == null || filepath.isBlank())
                throw new IllegalArgumentException("filepath must not be blank");
            this.filepath = filepath;
        }

        public Builder title(String v)           { title = v;           return this; }
        public Builder artist(String v)          { artist = v;          return this; }
        public Builder album(String v)           { album = v;           return this; }
        public Builder albumArtist(String v)     { albumArtist = v;     return this; }
        public Builder trackNumber(String v)     { trackNumber = v;     return this; }
        public Builder year(String v)            { year = v;            return this; }
        public Builder genre(String v)           { genre = v;           return this; }
        public Builder lyrics(String v)          { lyrics = v;          return this; }
        public Builder durationSeconds(Double v) { durationSeconds = v; return this; }
        public Builder mimeType(String v)        { mimeType = v;        return this; }
        public Builder fileSizeBytes(Long v)     { fileSizeBytes = v;   return this; }
        public Builder lastModified(String v)    { lastModified = v;    return this; }

        public TrackMetadata build() {
            return new TrackMetadata(filepath, title, artist, album, albumArtist,
                    trackNumber, year, genre, lyrics, durationSeconds,
                    mimeType, fileSizeBytes, lastModified);
        }
    }
}
