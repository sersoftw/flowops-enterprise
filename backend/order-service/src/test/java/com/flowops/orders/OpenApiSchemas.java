package com.flowops.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

final class OpenApiSchemas {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Set<ValidationMessage> validate(String schemaName, String body) throws Exception {
        try (var input = OpenApiSchemas.class.getResourceAsStream("/static/openapi.yaml")) {
            if (input == null) throw new IllegalStateException("Published OpenAPI document is missing");
            var document = JSON.valueToTree(new Yaml().load(input));
            // Retain all component references while exposing them as JSON Schema definitions.
            var definitions = JSON.readTree(document.at("/components/schemas").toString()
                    .replace("#/components/schemas/", "#/$defs/"));
            var schema = JSON.createObjectNode();
            schema.set("$defs", definitions);
            schema.put("$ref", "#/$defs/" + schemaName);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schema).validate(JSON.readTree(body));
        }
    }
}
