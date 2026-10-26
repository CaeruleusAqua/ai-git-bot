package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.admin.AdminUserRepository;
import org.remus.giteabot.admin.SecurityConfig;
import org.remus.giteabot.issueworkflow.IssueWorkflowRegistry;
import org.remus.giteabot.prworkflow.PrWorkflow;
import org.remus.giteabot.prworkflow.PrWorkflowCategory;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.PrWorkflowRegistry;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.remus.giteabot.prworkflow.WorkflowResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.remus.giteabot.prworkflow.WorkflowParamField.ParamType.INTEGER;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowConfigurationController.class)
@Import({SecurityConfig.class, WorkflowSelectionService.class, WorkflowParamsValidator.class,
        WorkflowTextResolver.class, PrWorkflowRegistry.class, IssueWorkflowRegistry.class,
        WorkflowIntegerRangesMvcTest.Workflows.class})
@ImportAutoConfiguration({SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class})
@ActiveProfiles("test")
class WorkflowIntegerRangesMvcTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private WorkflowConfigurationService configurationService;
    @MockitoBean private WorkflowConfigurationRepository configurationRepository;
    @MockitoBean private WorkflowSelectionRepository selectionRepository;
    @MockitoBean private AdminUserRepository adminUserRepository;

    private WorkflowSelection existing;

    @BeforeEach
    void setUp() {
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(7L);
        configuration.setName("Range test");
        when(configurationService.findById(7L)).thenReturn(Optional.of(configuration));
        when(configurationRepository.findById(7L)).thenReturn(Optional.of(configuration));
        existing = new WorkflowSelection();
        existing.setWorkflowKey("range-test");
        existing.replaceParams(Map.of("batchSize", "7", "legacyCount", "11"));
        when(selectionRepository.findByConfigurationId(7L)).thenReturn(List.of(existing));
    }

    @Test
    void form_rendersBackendBoundsAndOmitsAbsentAttributes() throws Exception {
        String html = mvc.perform(get("/system-settings/workflow-configurations/7/workflows")
                        .with(user("admin").roles("ADMIN")).locale(Locale.GERMAN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String bounded = input(html, "batchSize");
        assertTrue(bounded.contains("type=\"number\""));
        assertTrue(bounded.contains("value=\"7\""));
        assertTrue(bounded.contains("min=\"1\""));
        assertTrue(bounded.contains("max=\"9\""));
        String lowerOnly = input(html, "offset");
        assertTrue(lowerOnly.contains("min=\"-4\""));
        assertFalse(lowerOnly.contains("max="));
        String upperOnly = input(html, "ceiling");
        assertFalse(upperOnly.contains("min="));
        assertTrue(upperOnly.contains("max=\"4294967295\""));
        String unbounded = input(html, "legacyCount");
        assertTrue(unbounded.contains("value=\"11\""));
        assertFalse(unbounded.contains("min="));
        assertFalse(unbounded.contains("max="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10", "1.5", "NaN", "Infinity", "9223372036854775808", "bad"})
    void manipulatedSave_rejectsInvalidIntegersWithoutChangingSelections(String value) throws Exception {
        mvc.perform(post("/system-settings/workflow-configurations/7/workflows/save")
                        .with(user("admin").roles("ADMIN")).with(csrf()).locale(Locale.ENGLISH)
                        .param("selectedWorkflowKeys", "range-test")
                        .param("params.range-test.batchSize", value))
                .andExpect(redirectedUrl("/system-settings/workflow-configurations/7/workflows"))
                .andExpect(flash().attribute("error", containsString("Batch size")));

        verify(selectionRepository, never()).deleteByConfigurationId(any());
        verify(selectionRepository, never()).flush();
        verify(selectionRepository, never()).saveAll(any());
        assertEquals(Map.of("batchSize", "7", "legacyCount", "11"), existing.getParamsMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "9"})
    void save_acceptsBothInclusiveBoundaries(String value) throws Exception {
        mvc.perform(post("/system-settings/workflow-configurations/7/workflows/save")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("selectedWorkflowKeys", "range-test")
                        .param("params.range-test.batchSize", value))
                .andExpect(redirectedUrl("/system-settings"))
                .andExpect(flash().attributeCount(1));

        ArgumentCaptor<Iterable<WorkflowSelection>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(selectionRepository).saveAll(captor.capture());
        assertEquals(Map.of("batchSize", value, "legacyCount", "17"),
                captor.getValue().iterator().next().getParamsMap());
    }

    @Test
    void save_blankOptionalFieldsKeepDefaultsAndInheritance() throws Exception {
        mvc.perform(post("/system-settings/workflow-configurations/7/workflows/save")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .param("selectedWorkflowKeys", "range-test")
                        .param("params.range-test.batchSize", " ")
                        .param("params.range-test.offset", "")
                        .param("params.range-test.ceiling", ""))
                .andExpect(redirectedUrl("/system-settings"));

        ArgumentCaptor<Iterable<WorkflowSelection>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(selectionRepository).saveAll(captor.capture());
        assertEquals(Map.of("batchSize", "5", "legacyCount", "17"),
                captor.getValue().iterator().next().getParamsMap());
    }

    @Test
    void save_withoutCsrfCannotChangeSelections() throws Exception {
        mvc.perform(post("/system-settings/workflow-configurations/7/workflows/save")
                        .with(user("admin").roles("ADMIN"))
                        .param("selectedWorkflowKeys", "range-test")
                        .param("params.range-test.batchSize", "5"))
                .andExpect(status().isForbidden());

        verify(selectionRepository, never()).deleteByConfigurationId(any());
        verify(selectionRepository, never()).saveAll(any());
    }

    private static String input(String html, String field) {
        Matcher matcher = Pattern.compile("<input\\b[^>]*\\bname=\""
                + Pattern.quote("params.range-test." + field) + "\"[^>]*>").matcher(html);
        assertTrue(matcher.find(), "Missing rendered input for " + field);
        return matcher.group();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Workflows {
        @Bean
        PrWorkflow rangeWorkflow() {
            return new PrWorkflow() {
                @Override public String key() { return "range-test"; }
                @Override public String displayName() { return "Range test"; }
                @Override public PrWorkflowCategory category() { return PrWorkflowCategory.TESTING; }
                @Override public WorkflowResult run(PrWorkflowContext context) { return WorkflowResult.skipped("noop"); }
                @Override public WorkflowParamsSchema paramsSchema() {
                    return WorkflowParamsSchema.of(
                            new WorkflowParamField("batchSize", "Batch size", INTEGER,
                                    false, "5", null, List.of(), 1L, 9L),
                            new WorkflowParamField("offset", "Offset", INTEGER,
                                    false, null, null, List.of(), -4L, null),
                            new WorkflowParamField("ceiling", "Ceiling", INTEGER,
                                    false, null, null, List.of(), null, 4294967295L),
                            new WorkflowParamField("legacyCount", "Legacy count", INTEGER,
                                    false, "17", null, List.of()));
                }
            };
        }
    }
}
