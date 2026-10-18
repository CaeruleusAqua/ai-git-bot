package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamName;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.remus.giteabot.prworkflow.WorkflowParamField.ParamType.INTEGER;
import static org.remus.giteabot.prworkflow.WorkflowParamField.ParamType.STRING;

class WorkflowParamsValidatorTest {

    private final WorkflowParamsValidator validator = new WorkflowParamsValidator();

    @ParameterizedTest
    @ValueSource(strings = {"-2", "0", "3"})
    void validate_acceptsInclusiveIntegerBounds(String value) {
        WorkflowParamsSchema schema = schema(-2L, 3L, null);

        Map<String, String> canonical = validator.validate(Map.of("count", value), schema);

        assertEquals(Map.of("count", value), canonical);
        assertEquals(Map.of("count", Long.parseLong(value)), validator.typed(canonical, schema));
    }

    @ParameterizedTest
    @CsvSource({"-3, at least -2", "4, at most 3"})
    void validate_rejectsValuesOutsideBoundsWithoutClamping(String value, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("count", value), schema(-2L, 3L, null)));

        assertTrue(error.getMessage().contains("Count"));
        assertTrue(error.getMessage().contains(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0", "NaN", "Infinity", "-Infinity", "1e2", "0x10", "bad",
            "9223372036854775808", "-9223372036854775809"})
    void validate_rejectsMalformedOrUnrepresentableIntegers(String value) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("count", value), schema(null, null, null)));

        assertEquals("Parameter 'Count' must be an integer", error.getMessage());
    }

    @Test
    void validate_supportsOneSidedAndEqualBoundsAcrossTheLongRange() {
        assertEquals(Map.of("count", "9223372036854775807"), validator.validate(
                Map.of("count", "9223372036854775807"), schema(2147483648L, null, null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                Map.of("count", "2147483647"), schema(2147483648L, null, null)));
        assertEquals(Map.of("count", "-9223372036854775808"), validator.validate(
                Map.of("count", "-9223372036854775808"), schema(null, -2147483649L, null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                Map.of("count", "-2147483648"), schema(null, -2147483649L, null)));
        assertEquals(Map.of("count", "7"), validator.validate(
                Map.of("count", "7"), schema(7L, 7L, null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                Map.of("count", "6"), schema(7L, 7L, null)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                Map.of("count", "8"), schema(7L, 7L, null)));
    }

    @Test
    void validate_missingAndBlankOptionalValuesStillInheritOrUseValidatedDefaults() {
        for (Map<String, String> raw : List.of(Map.<String, String>of(), Map.of("count", "  "))) {
            assertEquals(Map.of(), validator.validate(raw, schema(1L, 9L, null)));
            assertEquals(Map.of("count", "5"), validator.validate(raw, schema(1L, 9L, "5")));
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(raw, schema(1L, 9L, "10")));
            assertThrows(IllegalArgumentException.class,
                    () -> validator.validate(raw, schema(1L, 9L, "1.5")));
        }
        assertEquals(Map.of(), validator.validate(null, schema(1L, 9L, null)));
        assertEquals(Map.of("count", "1"), validator.validate(
                Map.of("count", " +001 "), schema(1L, 9L, null)));
    }

    @Test
    void validate_aggregatesRangeErrorsWithExistingRequiredErrors() {
        WorkflowParamsSchema schema = WorkflowParamsSchema.of(
                new WorkflowParamField("count", "Count", INTEGER, false, null, null, List.of(), 1L, 9L),
                new WorkflowParamField("command", "Command", STRING, true, null, null, List.of()));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("count", "0"), schema));

        assertEquals("Parameter 'Count' must be at least 1; Parameter 'Command' is required", error.getMessage());
    }

    @Test
    void field_rejectsReversedBoundsAndBoundsOnNonIntegers() {
        assertThrows(IllegalArgumentException.class, () -> schema(9L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowParamField(
                "command", "Command", STRING, false, null, null, List.of(), 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowParamField(
                "command", "Command", STRING, false, null, null, List.of(), null, 9L));
    }

    @Test
    void field_oldConstructorsRemainUnboundedAndKeepExistingValidation() {
        WorkflowParamName name = () -> "count";
        WorkflowParamField legacy = new WorkflowParamField("count", "Count", INTEGER,
                false, "4", null, List.of());
        WorkflowParamField named = new WorkflowParamField(name, "Count", INTEGER, false, "4", null);
        WorkflowParamField choice = new WorkflowParamField(() -> "mode", "Mode", false, "a", null,
                List.of(new WorkflowParamField.EnumOption("a", "Alpha", null)));

        for (WorkflowParamField field : List.of(legacy, named, choice)) {
            assertNull(field.minimum());
            assertNull(field.maximum());
        }
        assertEquals(legacy, named);
        WorkflowParamsSchema schema = WorkflowParamsSchema.of(legacy, choice);
        assertEquals(Map.of("count", "-9223372036854775808", "mode", "a"), validator.validate(
                Map.of("count", "-9223372036854775808", "mode", "A", "unknown", "ignored"), schema));
        assertEquals(Map.of("count", "9223372036854775807", "mode", "a"), validator.validate(
                Map.of("count", "9223372036854775807"), schema));
        assertEquals(Map.of("count", "4", "mode", "a"), validator.validate(null, schema));
    }

    @Test
    void field_namedIntegerConstructorCarriesBoundsIntoValidation() {
        WorkflowParamField field = new WorkflowParamField(() -> "count", "Count", INTEGER,
                false, null, null, 2147483648L, 4294967295L);
        WorkflowParamsSchema schema = WorkflowParamsSchema.of(field);

        assertEquals(Map.of("count", "4294967295"),
                validator.validate(Map.of("count", "4294967295"), schema));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(Map.of("count", "4294967296"), schema));
    }

    private WorkflowParamsSchema schema(Long minimum, Long maximum, String defaultValue) {
        return WorkflowParamsSchema.of(new WorkflowParamField("count", "Count", INTEGER,
                false, defaultValue, null, List.of(), minimum, maximum));
    }
}
