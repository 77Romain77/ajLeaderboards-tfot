package us.ajg0702.leaderboards.cache.helpers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Requires a changed score snapshot to remain stable for a minimum amount of time before it is persisted.
 */
public final class ScoreChangeValidator {
    private final long validationDelayMillis;
    private final ConcurrentMap<Key, PendingChange> pendingChanges = new ConcurrentHashMap<>();

    public ScoreChangeValidator(long validationDelayMillis) {
        if(validationDelayMillis < 0) throw new IllegalArgumentException("validationDelayMillis must not be negative");
        this.validationDelayMillis = validationDelayMillis;
    }

    public ValidationResult validate(
            String board,
            UUID playerId,
            Map<String, Double> storedScores,
            Map<String, Double> observedScores,
            long observedAt
    ) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(observedScores, "observedScores");

        Key key = new Key(board, playerId);
        if(storedScores == null || storedScores.equals(observedScores)) {
            pendingChanges.remove(key);
            return ValidationResult.accepted(observedAt);
        }

        Map<String, Double> snapshot = Collections.unmodifiableMap(new HashMap<>(observedScores));
        AtomicReference<ValidationResult> result = new AtomicReference<>();
        pendingChanges.compute(key, (ignored, pending) -> {
            if(pending == null || !pending.scores.equals(snapshot)) {
                result.set(ValidationResult.rejected());
                return new PendingChange(snapshot, observedAt);
            }
            if(observedAt - pending.firstObservedAt < validationDelayMillis) {
                result.set(ValidationResult.rejected());
                return pending;
            }
            result.set(ValidationResult.accepted(pending.firstObservedAt));
            return null;
        });
        return result.get();
    }

    public void clear(String board, UUID playerId) {
        pendingChanges.remove(new Key(board, playerId));
    }

    public void clearPlayer(UUID playerId) {
        pendingChanges.keySet().removeIf(key -> key.playerId.equals(playerId));
    }

    public static final class ValidationResult {
        private static final ValidationResult REJECTED = new ValidationResult(false, 0);
        private final boolean accepted;
        private final long reachedAt;

        private ValidationResult(boolean accepted, long reachedAt) {
            this.accepted = accepted;
            this.reachedAt = reachedAt;
        }

        private static ValidationResult accepted(long reachedAt) {
            return new ValidationResult(true, reachedAt);
        }

        private static ValidationResult rejected() {
            return REJECTED;
        }

        public boolean isAccepted() {
            return accepted;
        }

        public long getReachedAt() {
            if(!accepted) throw new IllegalStateException("A rejected score change has no achievement timestamp");
            return reachedAt;
        }
    }

    private static final class PendingChange {
        private final Map<String, Double> scores;
        private final long firstObservedAt;

        private PendingChange(Map<String, Double> scores, long firstObservedAt) {
            this.scores = scores;
            this.firstObservedAt = firstObservedAt;
        }
    }

    private static final class Key {
        private final String board;
        private final UUID playerId;

        private Key(String board, UUID playerId) {
            this.board = board;
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object other) {
            if(this == other) return true;
            if(!(other instanceof Key)) return false;
            Key key = (Key) other;
            return board.equals(key.board) && playerId.equals(key.playerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(board, playerId);
        }
    }
}
