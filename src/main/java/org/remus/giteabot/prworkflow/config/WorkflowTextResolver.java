package org.remus.giteabot.prworkflow.config;

import org.remus.giteabot.prworkflow.WorkflowDescriptor;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Resolves operator-facing workflow strings from the message bundle with a
 * fallback to the descriptor's English text. The message key is derived from
 * {@link WorkflowDescriptor#key()} (e.g. {@code workflow.review.name}), so the
 * descriptor's own {@code displayName()}/{@code description()} stay English and
 * double as the fallback for logs and validation errors.
 *
 * <p>The English fallback is returned verbatim (not run through
 * {@link MessageSource#getMessage(String, Object[], String, Locale)}, whose
 * {@code defaultMessage} argument is MessageFormat-processed and would corrupt
 * apostrophes).</p>
 */
@Component
public class WorkflowTextResolver {

    private final MessageSource messageSource;

    public WorkflowTextResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String displayName(WorkflowDescriptor workflow) {
        return resolve("workflow." + workflow.key() + ".name", workflow.displayName());
    }

    public String description(WorkflowDescriptor workflow) {
        return resolve("workflow." + workflow.key() + ".description", workflow.description());
    }

    /**
     * Returns a copy of the workflow's params schema whose field labels,
     * descriptions and enum-option labels/descriptions are localized. Field
     * {@code name}/{@code type}/{@code required}/{@code defaultValue}, numeric
     * bounds and enum option {@code key} are left untouched — only operator-
     * facing text is translated.
     */
    public WorkflowParamsSchema localizedSchema(WorkflowDescriptor workflow) {
        Locale locale = LocaleContextHolder.getLocale();
        List<WorkflowParamField> fields = workflow.paramsSchema().fields().stream()
                .map(field -> localizeField(workflow.key(), field, locale))
                .toList();
        return new WorkflowParamsSchema(fields);
    }

    private WorkflowParamField localizeField(String workflowKey, WorkflowParamField field, Locale locale) {
        String prefix = "workflow." + workflowKey + ".param." + field.name();
        String label = resolve(prefix + ".label", field.label());
        String description = resolve(prefix + ".description", field.description());
        List<WorkflowParamField.EnumOption> options = field.allowedValues().stream()
                .map(opt -> new WorkflowParamField.EnumOption(
                        opt.key(),
                        resolve(prefix + ".option." + opt.key() + ".label", opt.label()),
                        resolve(prefix + ".option." + opt.key() + ".description", opt.description())))
                .toList();
        return new WorkflowParamField(field.name(), label, field.type(), field.required(),
                field.defaultValue(), description, options, field.minimum(), field.maximum());
    }

    private String resolve(String key, String fallback) {
        try {
            return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return fallback;
        }
    }
}
