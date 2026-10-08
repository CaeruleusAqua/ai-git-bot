package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.AgentConfigProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from a native tool call's JSON arguments to the positional vector the executors read.
 * It has to be derived from the tool's schema: a hand-written list of property names falls behind
 * silently, and for {@code execute-code} it fell behind into a valid Python dict literal — the
 * program ran, printed nothing and exited 0, so an empty result looked deliberate.
 */
class ToolArgumentsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCatalog catalog = new ToolCatalog(new AgentConfigProperties());

    @Test
    void executeCodeKeepsItsProgram() {
        ObjectNode args = JSON.createObjectNode().put("code", "print(1)");

        assertThat(ToolArguments.toPositional("execute-code", args, schema("execute-code")))
                .containsExactly("print(1)");
    }

    /**
     * The invariant behind the regression: no declared property may be dropped or reordered on the
     * way to the executor, which reads positionally — a missing property either shifts the ones
     * after it or silently falls back to a default.
     */
    @Test
    void everyDeclaredPropertyReachesTheExecutorInSchemaOrder() {
        List<String> tools = new ArrayList<>();
        for (ToolCatalog.Role role : ToolCatalog.Role.values()) {
            tools.addAll(catalog.builtinToolNames(role));
        }
        assertThat(tools).as("tools the catalog declares").isNotEmpty();

        List<String> violations = new ArrayList<>();
        int inspected = 0;
        for (String tool : tools) {
            JsonNode schema = schema(tool);
            JsonNode properties = schema == null ? null : schema.get("properties");
            if (properties == null || !properties.isObject()) {
                continue;
            }
            inspected++;
            List<String> declared = new ArrayList<>();
            ObjectNode args = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                declared.add(property.getKey());
                args.put(property.getKey(), property.getKey());
            }
            List<String> mapped = ToolArguments.toPositional(tool, args, schema);
            if (!declared.equals(mapped)) {
                violations.add(tool + ": declared " + declared + " but mapped " + mapped);
            }
        }

        assertThat(inspected).as("tools declaring a schema").isGreaterThan(10);
        assertThat(violations).isEmpty();
    }

    @Test
    void optionalPropertiesAreNotDropped() {
        ObjectNode args = JSON.createObjectNode().put("path", "src/Foo.java").put("limit", 25);

        assertThat(ToolArguments.toPositional("ctags-signatures", args, schema("ctags-signatures")))
                .containsExactly("src/Foo.java", "25");
    }

    @Test
    void aNullForAnOptionalPropertyIsNotAnArgument() {
        ObjectNode args = JSON.createObjectNode().put("path", "README.md");
        args.putNull("startLine");
        args.putNull("endLine");

        assertThat(ToolArguments.toPositional("cat", args, schema("cat")))
                .containsExactly("README.md");
    }

    @Test
    void varargsArrayStaysAFlatArgumentVector() {
        ObjectNode args = JSON.createObjectNode();
        args.putArray("args").add("--glob").add("*.md");

        assertThat(ToolArguments.toPositional("rg", args, schema("rg")))
                .containsExactly("--glob", "*.md");
    }

    @Test
    void aDeclaredToolWithAnUnknownShapeCarriesNoArgument() {
        // execute-code declares `code`; a call naming something else matches no property, so
        // nothing reaches the executor — which must then report the missing argument instead of
        // running the raw object (a valid Python dict literal that would exit 0 silently).
        ObjectNode args = JSON.createObjectNode().put("program", "print(1)");

        assertThat(ToolArguments.toPositional("execute-code", args, schema("execute-code")))
                .isEmpty();
    }

    @Test
    void aToolWithoutASchemaStillCarriesItsRawObject() {
        // Validation tools (and unknown names) declare no schema, so the raw object is the only
        // thing there is to pass.
        ObjectNode args = JSON.createObjectNode().put("custom", "x");

        assertThat(ToolArguments.toPositional("mvn", args, null))
                .containsExactly(args.toString());
    }

    @Test
    void aSkippedOptionalPropertyKeepsThePositionOfTheOnesAfterIt() {
        ObjectNode args = JSON.createObjectNode().put("path", "README.md").put("endLine", 50);

        // cat reads startLine and endLine by position: without the hole this arrives as startLine=50.
        assertThat(ToolArguments.toPositional("cat", args, schema("cat")))
                .containsExactly("README.md", "", "50");
    }

    private JsonNode schema(String tool) {
        return catalog.schemaOf(tool).orElse(null);
    }
}
