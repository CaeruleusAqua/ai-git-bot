import {expect, Locator, Page, test} from '@playwright/test';



const SETTINGS_URL = '/system-settings';

const PATTERN_VALUE = '*.yaml';



// Stable ids rendered by system-settings/workflow-configurations/workflows.html.

const REVIEW_CHECKBOX = '#wf-review';

const REVIEW_COLLAPSE = '#wf-collapse-review';

const REVIEW_COLLAPSE_TOGGLE = `[data-bs-target="${REVIEW_COLLAPSE}"]`;

const EXCLUDED_PATTERNS_FIELD = '#param-review-excludedFilePatterns';



/**

 * Resolve the workflows URL without hardcoding a configuration id: open the

 * system-settings page and read the "Workflows" link from the table inside

 * #section-workflow-configurations. Prefers the row flagged as "Default",

 * falling back to the first configuration.

 */

async function resolveWorkflowsUrl(page: Page): Promise<string> {

    await page.goto(SETTINGS_URL);

    await page.waitForLoadState('networkidle');



    const section = page.locator('#section-workflow-configurations');

    await section.waitFor({state: 'attached', timeout: 5000});



    const workflowsLinks = section.locator('a[href*="/workflows"]');

    await workflowsLinks.first().waitFor({state: 'attached', timeout: 5000});



    // Prefer the row marked as Default (uniquely identified by the primary

    // badge), falling back to the first configuration.

    const defaultRow = section.locator('tr', {has: section.locator('span.badge.bg-primary')});

    const preferred = (await defaultRow.count()) > 0

        ? defaultRow.first().locator('a[href*="/workflows"]').first()

        : workflowsLinks.first();



    const href = await preferred.getAttribute('href');

    if (!href) {

        throw new Error('Could not resolve a workflows URL from #section-workflow-configurations.');

    }

    return href;

}



/**

 * The first-visit balloon-help tour dims the page behind a click-blocking

 * overlay. Dismissing it stores a version cookie, so it reappears only once

 * per browser context (and again after an application upgrade).

 */

async function dismissTour(page: Page): Promise<void> {

    const dismiss = page.getByRole('button', {name: 'Dismiss'}).first();

    if (await dismiss.isVisible().catch(() => false)) {

        await dismiss.click();

    }

    await page.locator('.giteabot-tour-overlay')

        .waitFor({state: 'detached', timeout: 5000})

        .catch(() => undefined);

}



async function openWorkflowsPage(page: Page, workflowsUrl: string): Promise<void> {

    await page.goto(workflowsUrl);

    await page.waitForLoadState('networkidle');

    await dismissTour(page);

}



/**

 * Expand the review workflow's parameter panel via its Bootstrap collapse

 * toggle - the panel starts expanded, so this only clicks when a stored

 * collapse state hid it.

 *

 * The panel must be addressed by id: matching the workflow name as text hits

 * the "REVIEW" category badge of the first REVIEW-category row, and clicking

 * that badge's label toggles that row's selection checkbox instead of

 * expanding anything.

 */

async function expandReviewSection(page: Page): Promise<void> {

    const collapse = page.locator(REVIEW_COLLAPSE);

    await collapse.waitFor({state: 'attached', timeout: 5000});

    if (!(await collapse.isVisible())) {

        await page.locator(REVIEW_COLLAPSE_TOGGLE).first().click();

        await expect(collapse).toBeVisible();

    }

}



/**

 * Parameters are persisted only for workflows selected on the configuration -

 * WorkflowSelectionService.extractWorkflowParams drops the params of anything

 * that is not ticked - so the review workflow has to be enabled before its

 * params can round-trip.

 *

 * @returns the previous checked state so the cleanup can restore it.

 */

async function enableReviewWorkflow(page: Page): Promise<boolean> {

    const checkbox = page.locator(REVIEW_CHECKBOX);

    const wasChecked = await checkbox.isChecked();

    if (!wasChecked) {

        await checkbox.check();

        await expect(checkbox).toBeChecked();

    }

    return wasChecked;

}



/**

 * Submit the selection form. On success the controller redirects to the

 * system-settings overview; on a validation error it renders the workflows

 * page again with an .alert-danger flash instead.

 */

async function saveSelection(page: Page): Promise<void> {

    await page.getByRole('button', {name: /save selection/i}).first().click();

    await page.waitForURL((url) => url.pathname === SETTINGS_URL, {timeout: 10000});

    await expect(page.locator('.alert-danger')).toHaveCount(0);

}



/**

 * Restore the review workflow's value and selection state so the test stays

 * idempotent. Best effort - never masks the original failure.

 */

async function restoreState(page: Page, workflowsUrl: string, originalValue: string,

                            reviewWasEnabled: boolean | null): Promise<void> {

    if (reviewWasEnabled === null) {

        // Failed before touching the configuration; nothing to undo.

        return;

    }

    try {

        await openWorkflowsPage(page, workflowsUrl || await resolveWorkflowsUrl(page));

        await expandReviewSection(page);



        const checkbox = page.locator(REVIEW_CHECKBOX);

        if (reviewWasEnabled) {

            // Value only survives while the workflow stays enabled: unticking it

            // drops the params together with the selection.

            await page.locator(EXCLUDED_PATTERNS_FIELD).fill(originalValue);

            await checkbox.check();

        } else {

            await checkbox.uncheck();

        }

        await saveSelection(page);

    } catch {

        // Best-effort cleanup; do not mask original test failure.

    }

}



test.describe('Review workflow excluded file patterns persistence', () => {

    test('excludedFilePatterns value persists after saving and reloading', async ({page}) => {

        let workflowsUrl = '';

        let originalValue = '';

        let reviewWasEnabled: boolean | null = null;



        try {

            // 1. Resolve the workflows URL from the settings page (no hardcoded id).

            workflowsUrl = await resolveWorkflowsUrl(page);

            await openWorkflowsPage(page, workflowsUrl);



            // 2. Expand the 'review' workflow section and make sure the workflow

            //    is enabled, otherwise its params are discarded on save.

            await expandReviewSection(page);

            reviewWasEnabled = await enableReviewWorkflow(page);



            // 3. Capture the original value so we can restore it.

            const input: Locator = page.locator(EXCLUDED_PATTERNS_FIELD);

            await expect(input).toBeVisible();

            originalValue = await input.inputValue();



            // 4. Enter the new value and persist it.

            await input.fill(PATTERN_VALUE);

            await expect(input).toHaveValue(PATTERN_VALUE);

            await saveSelection(page);



            // 5. Reload from scratch: only server-side state can survive this.

            await openWorkflowsPage(page, workflowsUrl);

            await expandReviewSection(page);

            await expect(page.locator(EXCLUDED_PATTERNS_FIELD)).toHaveValue(PATTERN_VALUE);

        } finally {

            await restoreState(page, workflowsUrl, originalValue, reviewWasEnabled);

        }

    });

});

