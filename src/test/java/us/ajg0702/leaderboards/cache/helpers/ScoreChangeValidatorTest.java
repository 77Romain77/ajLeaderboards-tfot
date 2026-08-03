package us.ajg0702.leaderboards.cache.helpers;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScoreChangeValidatorTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BOARD = "objective_score_{unlockedSuccess}";

    @Test
    public void discardsTemporaryChangeWhenScoreReturnsToStoredValue() {
        ScoreChangeValidator validator = new ScoreChangeValidator(5_000L);

        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(127), 1_000L).isAccepted());
        assertTrue(validator.validate(BOARD, PLAYER, score(128), score(128), 2_000L).isAccepted());
        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(127), 7_000L).isAccepted());
    }

    @Test
    public void acceptsStableChangeAfterDelayWithFirstObservationTime() {
        ScoreChangeValidator validator = new ScoreChangeValidator(5_000L);

        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(129), 1_000L).isAccepted());
        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(129), 5_999L).isAccepted());

        ScoreChangeValidator.ValidationResult result =
                validator.validate(BOARD, PLAYER, score(128), score(129), 6_000L);

        assertTrue(result.isAccepted());
        assertEquals(1_000L, result.getReachedAt());
    }

    @Test
    public void changingCandidateRestartsValidationDelay() {
        ScoreChangeValidator validator = new ScoreChangeValidator(5_000L);

        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(129), 1_000L).isAccepted());
        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(130), 6_000L).isAccepted());
        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(130), 10_999L).isAccepted());

        ScoreChangeValidator.ValidationResult result =
                validator.validate(BOARD, PLAYER, score(128), score(130), 11_000L);

        assertTrue(result.isAccepted());
        assertEquals(6_000L, result.getReachedAt());
    }

    @Test
    public void clearingPlayerDropsPendingChanges() {
        ScoreChangeValidator validator = new ScoreChangeValidator(5_000L);

        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(129), 1_000L).isAccepted());
        validator.clearPlayer(PLAYER);
        assertFalse(validator.validate(BOARD, PLAYER, score(128), score(129), 7_000L).isAccepted());
    }

    @Test
    public void acceptsFirstStoredValueImmediately() {
        ScoreChangeValidator validator = new ScoreChangeValidator(5_000L);
        ScoreChangeValidator.ValidationResult result =
                validator.validate(BOARD, PLAYER, null, score(128), 1_000L);

        assertTrue(result.isAccepted());
        assertEquals(1_000L, result.getReachedAt());
    }

    private static Map<String, Double> score(double value) {
        return Collections.singletonMap("ALLTIME", value);
    }
}
