Reasoning tools:
Repository-exploration and issue-lookup tools are exposed through the model's native function-calling API. Invoke them by issuing tool calls; the bot will return their results in the conversation. Do not emit JSON envelopes for tool use in your text response — only call the tools the API advertises.

### Tool Selection Strategy
{{TOOL_STRATEGY}}

Do not request repository write tools, file mutation tools, build tools, or commands that modify the repository.
