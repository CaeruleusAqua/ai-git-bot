package org.remus.giteabot.ai;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigInteger;

/** Reads non-negative Integer usage counters without coercing other JSON values. */
public final class UsageCounterDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            parser.skipChildren();
            return null;
        }
        BigInteger value = parser.getBigIntegerValue();
        return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                ? value.intValue() : null;
    }

    /** Retains the existing Long range of provider cached-token counters. */
    public static final class LongCounter extends ValueDeserializer<Long> {

        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                parser.skipChildren();
                return null;
            }
            BigInteger value = parser.getBigIntegerValue();
            return value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0
                    ? value.longValue() : null;
        }
    }
}
