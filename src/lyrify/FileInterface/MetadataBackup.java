package lyrify.FileInterface;

import org.json.JSONObject;

// Serialisable snapshot of a single track's metadata for backup/restore.
public final class MetadataBackup {

    private final JSONObject data;

    MetadataBackup(JSONObject data) {
        this.data = data;
    }

    // Serialise a TrackMetadata into a backup JSON entry.
    public static MetadataBackup from(TrackMetadata meta) {
        JSONObject obj = new JSONObject();
        obj.put("filepath",       meta.filepath());
        putOpt(obj, "title",       meta.title());
        putOpt(obj, "artist",      meta.artist());
        putOpt(obj, "album",       meta.album());
        putOpt(obj, "albumArtist", meta.albumArtist());
        putOpt(obj, "trackNumber", meta.trackNumber());
        putOpt(obj, "year",        meta.year());
        putOpt(obj, "genre",       meta.genre());
        putOpt(obj, "lyrics",      meta.lyrics());
        if (meta.durationSeconds() != null) obj.put("durationSeconds", meta.durationSeconds());
        if (meta.mimeType()        != null) obj.put("mimeType",        meta.mimeType());
        if (meta.fileSizeBytes()   != null) obj.put("fileSizeBytes",   meta.fileSizeBytes());
        if (meta.lastModified()    != null) obj.put("lastModified",    meta.lastModified());
        return new MetadataBackup(obj);
    }

    private static void putOpt(JSONObject obj, String key, String value) {
        if (value != null) obj.put(key, value);
    }

    // Deserialise back into a TrackMetadata.
    public TrackMetadata toMetadata() {
        return TrackMetadata.builder(data.getString("filepath"))
                .title(data.optString("title",       null))
                .artist(data.optString("artist",      null))
                .album(data.optString("album",        null))
                .albumArtist(data.optString("albumArtist", null))
                .trackNumber(data.optString("trackNumber", null))
                .year(data.optString("year",          null))
                .genre(data.optString("genre",        null))
                .lyrics(data.optString("lyrics",      null))
                .durationSeconds(data.has("durationSeconds") ? data.getDouble("durationSeconds") : null)
                .mimeType(data.optString("mimeType",  null))
                .fileSizeBytes(data.has("fileSizeBytes") ? data.getLong("fileSizeBytes") : null)
                .lastModified(data.optString("lastModified", null))
                .build();
    }

    public JSONObject toJson() {
        return data;
    }

    @Override
    public String toString() {
        return data.toString(2);
    }
}
