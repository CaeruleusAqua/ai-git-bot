package org.remus.giteabot.agent.tools;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.config.AgentConfigProperties;
import org.remus.giteabot.systemsettings.BuiltinToolRegistry;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code execute-code} is registered before it is dispatched (the handler arrives with the
 * sandbox), so what has to be pinned down now is that the registration is complete. Each piece
 * below fails silently in production if it is missing: an unregistered kind makes the tool
 * invisible in the admin UI, and a missing role makes it unreachable from the LLM.
 */
class AgentControlToolRegistrationTest {

    private static final String TOOL = "execute-code";

    private final AgentConfigProperties agentConfig = new AgentConfigProperties();
    private final ToolCatalog catalog = new ToolCatalog(agentConfig);

    @Test
    void catalog_registersTheToolUnderItsOwnKind() {
        assertThat(catalog.kindOf(TOOL)).isEqualTo(ToolKind.AGENT_CONTROL);
        assertThat(catalog.agentControlToolNames()).containsExactly(TOOL);
    }

    @Test
    void catalog_isSilentAndBucketsTheNewKind() {
        assertThat(catalog.isSilent(TOOL)).isTrue();
        assertThat(catalog.bucketOf(TOOL)).isEqualTo(ToolCatalog.DisplayBucket.CONTEXT);
    }

    @Test
    void nativeDescriptors_areAdvertisedOnTheSurfacesThatDispatchIt() {
        for (ToolCatalog.Role role : List.of(ToolCatalog.Role.CODING, ToolCatalog.Role.WRITER)) {
            assertThat(catalog.nativeDescriptors(role, null, Set.of(TOOL)))
                    .as(role.name())
                    .extracting(ToolDescriptor::name)
                    .containsExactly(TOOL);
        }
        // Read-only review refuses it, and the PR-workflow agents run their own executors with fixed
        // tool sets: advertising it there would be a promise their dispatch does not keep.
        for (ToolCatalog.Role role : List.of(ToolCatalog.Role.REVIEW, ToolCatalog.Role.PR_WORKFLOW)) {
            assertThat(catalog.nativeDescriptors(role, null, Set.of(TOOL)))
                    .as(role.name())
                    .extracting(ToolDescriptor::name)
                    .doesNotContain(TOOL);
        }
    }

    @Test
    void nativeDescriptors_leaveItOutWhenTheBotHasNotWhitelistedIt() {
        assertThat(catalog.nativeDescriptors(ToolCatalog.Role.CODING, null, Set.of("cat")))
                .extracting(ToolDescriptor::name)
                .doesNotContain(TOOL);
    }

    @Test
    void registry_surfacesItInTheAdminToolListWithABundleDescription() {
        BuiltinToolRegistry.BuiltinTool tool = new BuiltinToolRegistry(catalog, messageSource())
                .builtinTools().stream()
                .filter(candidate -> TOOL.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        TOOL + " missing from BuiltinToolRegistry — it would never appear in the "
                                + "admin tool configuration, so no bot could enable it"));

        assertThat(tool.kind()).isEqualTo(ToolKind.AGENT_CONTROL);
        // The registry falls back to the native description when the bundle key is absent, so
        // "differs from the native text" is what proves tool.execute-code.description exists.
        assertThat(tool.description())
                .isNotBlank()
                .isNotEqualTo(catalog.describeFor(ToolCatalog.Role.CODING, TOOL).orElseThrow());
    }

    /** Real bundle, so a missing {@code tool.execute-code.description} key is detected. */
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
}
