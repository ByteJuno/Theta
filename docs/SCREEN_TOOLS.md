# Screen tools

Theta registers `screen_capture`, `screen_ui_tree`, `app_list`, `screen_tap`,
`screen_long_press`, `screen_swipe`, `screen_key`, `app_launch` and
`screen_input_text` alongside the unchanged `screen_info` tool. Existing host
tool permission rules apply. Every tool shares the `screen` resource across
sessions. Reads use READ; actions use DEVICE_CONTROL. Discovery does not run su
or prompt for Accessibility. Enable **Theta screen control** in Android's
Accessibility settings, or explicitly authorize a Root tool invocation first.

An already confirmed Root environment is preferred. Accessibility handles
unavailable reads and actions positively known not to have started. Failed or
interrupted writes report UNKNOWN and are never automatically replayed on the
other backend. Root process-group receipts and Accessibility completion callbacks
keep the worker and screen lease alive until actual exit, independently of the
host's logical timeout/cancellation. A missing callback retains the lease and
uses the existing restart/recovery path; it does not imply successful termination.

Coordinates are **original display pixels**, not `screen_info` physical-mode
dimensions or scaled image pixels. Capture metadata contains original and output
dimensions. Images are JPEG, at most 1600 pixels on the long edge and 768 KiB.
The host stores them in the calling session's BlobStore. The outbound model
request keeps each screenshot inside its original tool result, with the original
call ID and message order. Anthropic and OpenAI Responses can send image content
inside tool results. Chat Completions and adapters without tool-media support
keep explicit text placeholders, independently of their user-image capability.
The final request, including all supported tool images and user attachments, must
fit the existing image memory budget before encoding; otherwise the run pauses
or uses the existing checkpoint path. Other sessions cannot read the image.

Trees retain visible text/description/actionable nodes, bounds, center, class and
foreground package. Password text/descriptions are omitted. Traversal is capped
at 2000 nodes; output at 160 nodes and 24 KiB, with a truncation flag. Application
lists are limited to enabled launcher activities, 200 entries and 24 KiB.

Unicode input uses ACTION_SET_TEXT for the focused editable Accessibility node
(replacing its contents). Root passes UTF-8 in a private temporary file to an
app_process clipboard helper, marks the clipboard sensitive, verifies readback,
then sends KEYCODE_PASTE at the current selection. It replaces the clipboard and
does not restore it. Text is never interpolated into shell commands or diagnostic
output. Tool arguments still follow the existing private invocation-ledger policy.
Unsupported clipboard Binder signatures fail before a write and allow the
Accessibility route; failures after the clipboard write was attempted remain
UNKNOWN. ROM clipboard and secure-window restrictions can prevent operation.

`ScreenRuntime.captureVisibilityHook` supplies paired before/after hooks for a
future overlay. There is no overlay, permission-enabling automation, device test,
or claim that secure screens/ROM-specific Root interfaces work on every device.
Native bitmap tests run on supported Linux Robolectric hosts; Windows reports
the native-graphics test as skipped rather than substituting a fake pixel pass.
