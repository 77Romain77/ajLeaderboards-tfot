package us.ajg0702.leaderboards.cache.helpers;

import com.google.gson.JsonObject;
import us.ajg0702.leaderboards.boards.TimedType;
import us.ajg0702.leaderboards.cache.Cache;
import us.ajg0702.leaderboards.utils.EasyJsonObject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DbRow {

    private final UUID id;
    private final double value;

    private final Map<TimedType, Double> deltas;
    private final Map<TimedType, Double> lastTotals;
    private final Map<TimedType, Long> timestamps;
    private final Map<TimedType, Long> reachedAts;

    private final String namecache;
    private final String prefixcache;
    private final String suffixcache;
    private final String displaynamecache;



    private static final Map<String, Integer> positionCache = new ConcurrentHashMap<>();
    public DbRow(ResultSet resultSet) throws SQLException {
        this(
                UUID.fromString(resultSet.getString(getIndex(resultSet, "id"))),
                resultSet.getDouble(getIndex(resultSet, "value")),
                getTypeMaps(resultSet),
                getReachedAts(resultSet),
                resultSet.getString(getIndex(resultSet, "namecache")),
                resultSet.getString(getIndex(resultSet, "prefixcache")),
                resultSet.getString(getIndex(resultSet, "suffixcache")),
                resultSet.getString(getIndex(resultSet, "displaynamecache"))
        );
    }

    public static void clearPositionCache() {
        positionCache.clear();
    }

    private DbRow(UUID id, double value, List<Object> typeMaps, Map<TimedType, Long> reachedAts, String namecache, String prefixcache, String suffixcache, String displaynamecache) {
        //noinspection unchecked
        this(
                id,
                value,
                (Map<TimedType, Double>) typeMaps.get(0),
                (Map<TimedType, Double>) typeMaps.get(1),
                (Map<TimedType, Long>) typeMaps.get(2),
                reachedAts,
                namecache,
                prefixcache,
                suffixcache,
                displaynamecache
        );
    }

    public DbRow(UUID id, double value, Map<TimedType, Double> deltas, Map<TimedType, Double> lastTotals, Map<TimedType, Long> timestamps, Map<TimedType, Long> reachedAts, String namecache, String prefixcache, String suffixcache, String displaynamecache) {
        this.id = id;
        this.value = value;
        this.deltas = deltas;
        this.lastTotals = lastTotals;
        this.timestamps = timestamps;
        this.reachedAts = reachedAts;
        this.namecache = namecache;
        this.prefixcache = prefixcache;
        this.suffixcache = suffixcache;
        this.displaynamecache = displaynamecache;
    }

    private static List<Object> getTypeMaps(ResultSet resultSet) throws SQLException {
        final Map<TimedType, Double> deltas = new HashMap<>();
        final Map<TimedType, Double> lastTotals = new HashMap<>();
        final Map<TimedType, Long> timestamps = new HashMap<>();

        for(TimedType type : TimedType.values()) {
            if(type == TimedType.ALLTIME) continue;

            deltas.put(type, resultSet.getDouble(getIndex(resultSet, type.lowerName()+"_delta")));
            lastTotals.put(type, resultSet.getDouble(getIndex(resultSet, type.lowerName()+"_lasttotal")));
            timestamps.put(type, resultSet.getLong(getIndex(resultSet, type.lowerName()+"_timestamp")));
        }
        return Arrays.asList(deltas, lastTotals, timestamps);
    }

    private static Map<TimedType, Long> getReachedAts(ResultSet resultSet) throws SQLException {
        Map<TimedType, Long> reachedAts = new EnumMap<>(TimedType.class);
        for(TimedType type : TimedType.values()) {
            reachedAts.put(type, resultSet.getLong(getIndex(resultSet, Cache.reachedAtColumn(type))));
        }
        return reachedAts;
    }

    private static int getIndex(ResultSet rs, String name) throws SQLException {
        Integer index = positionCache.get(name);
        if (index != null) {
            return index;
        }
        try {
            int position = rs.findColumn(name);
            Integer existing = positionCache.putIfAbsent(name, position);
            return existing != null ? existing : position;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public UUID getId() {
        return id;
    }
    public double getValue() {
        return value;
    }
    public Map<TimedType, Double> getDeltas() {
        return deltas;
    }
    public Map<TimedType, Double> getLastTotals() {
        return lastTotals;
    }
    public Map<TimedType, Long> getTimestamps() {
        return timestamps;
    }
    public Map<TimedType, Long> getReachedAts() {
        return reachedAts;
    }
    public String getNamecache() {
        return namecache;
    }
    public String getPrefixcache() {
        return prefixcache;
    }
    public String getSuffixcache() {
        return suffixcache;
    }
    public String getDisplaynamecache() {
        return displaynamecache;
    }


    public JsonObject toJsonObject() {
        EasyJsonObject out = new EasyJsonObject()
                .add("id", getId().toString())
                .add("value", getValue());
        for(TimedType type : TimedType.values()) {
            out.add(Cache.reachedAtColumn(type), getReachedAts().get(type));
            if(type == TimedType.ALLTIME) continue;
            String lowerName = type.lowerName();
            out.add(lowerName+"_delta", getDeltas().get(type));
            out.add(lowerName+"_lasttotal", getLastTotals().get(type));
            out.add(lowerName+"_timestamp", getTimestamps().get(type));
        }
        out.add("namecache", getNamecache());
        out.add("prefixcache", getPrefixcache());
        out.add("suffixcache", getSuffixcache());
        out.add("displaynamecache", getDisplaynamecache() == null ? getNamecache() : getDisplaynamecache());
        return out.getHandle();
    }

    public static DbRow fromJsonObject(JsonObject object) {
        final Map<TimedType, Double> deltas = new HashMap<>();
        final Map<TimedType, Double> lastTotals = new HashMap<>();
        final Map<TimedType, Long> timestamps = new HashMap<>();
        final Map<TimedType, Long> reachedAts = new EnumMap<>(TimedType.class);
        long importTime = System.currentTimeMillis();

        for(TimedType type : TimedType.values()) {
            String reachedAtColumn = Cache.reachedAtColumn(type);
            reachedAts.put(type, object.has(reachedAtColumn) ? object.get(reachedAtColumn).getAsLong() : importTime);
            if(type == TimedType.ALLTIME) continue;

            deltas.put(type, object.get(type.lowerName()+"_delta").getAsDouble());
            lastTotals.put(type, object.get(type.lowerName()+"_lasttotal").getAsDouble());
            timestamps.put(type, object.get(type.lowerName()+"_timestamp").getAsLong());
        }

        return new DbRow(
                UUID.fromString(object.get("id").getAsString()),
                object.get("value").getAsDouble(),
                deltas, lastTotals, timestamps, reachedAts,
                object.get("namecache").getAsString(),
                object.get("prefixcache").getAsString(),
                object.get("suffixcache").getAsString(),
                object.get("displaynamecache").getAsString()
        );
    }
}
