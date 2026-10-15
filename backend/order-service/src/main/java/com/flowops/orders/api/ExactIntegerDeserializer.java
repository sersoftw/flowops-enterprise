package com.flowops.orders.api;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Accept mathematical JSON integers without rounding or coercing strings. */
public final class ExactIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.currentToken().isNumeric()) {
            return context.reportInputMismatch(Integer.class, "Quantity must be a JSON number with an integer value");
        }
        try {
            return parser.getDecimalValue().intValueExact();
        } catch (ArithmeticException ex) {
            return context.reportInputMismatch(Integer.class, "Quantity must be an exact 32-bit integer");
        }
    }
}
