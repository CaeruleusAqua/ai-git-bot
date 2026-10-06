-- Add the execute-code control tool to the default tool configuration. It runs one
-- Python program that can call the tools the model itself is offered, so several reads
-- collapse into a single agent round, and a fresh installation gets it without an admin
-- opting in. Avoids duplicate inserts via NOT EXISTS guard.
--
-- Unlike V29/V37 this is not a CONTEXT tool: execute-code is ToolKind.AGENT_CONTROL, which
-- the admin UI groups separately. That has one consequence worth stating — the Default
-- configuration cannot be edited (BotToolSelectionService rejects it), so this row is
-- permanent there. A bot on its own configuration is unaffected and can still leave the
-- tool out.

INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT c.id, v.tool_name, v.tool_kind
FROM bot_tool_configurations c
CROSS JOIN (VALUES
    ('execute-code', 'AGENT_CONTROL')
) AS v(tool_name, tool_kind)
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = c.id AND s.tool_name = v.tool_name
  );
