package io.github.zcodeandroid.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.zcodeandroid.app.ui.theme.ZcodeTheme
import io.github.zcodeandroid.app.ui.theme.ZcodeThemeValues

/**
 * Previews for every shared component (owner brief: @Preview per component,
 * light + dark). Previews are the design review surface — they render from
 * tokens only, so a token change is visible here immediately.
 */

@Preview(name = "ZCodeTopBar — light", showBackground = true)
@Composable
private fun ZCodeTopBarPreview() {
    ZcodeTheme(darkTheme = false) {
        Surface {
            ZCodeTopBar(
                title = "ZCode",
                subtitle = "This device",
                statusColor = ZcodeThemeValues.statusColors.completed,
                statusLabel = "runtime connected",
                modelName = "glm-4.7",
                onModelClick = {},
            )
        }
    }
}

@Preview(name = "ZCodeTopBar — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ZCodeTopBarDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        Surface {
            ZCodeTopBar(
                title = "ZCode",
                subtitle = "This device",
                statusColor = ZcodeThemeValues.statusColors.completed,
                statusLabel = "runtime connected",
            )
        }
    }
}

@Preview(name = "Message bubbles", showBackground = true, widthDp = 380)
@Composable
private fun MessageBubblesPreview() {
    ZcodeTheme(darkTheme = false) {
        Column {
            UserMessageBubble("Refactor the parser and **run the tests**")
            AgentMessageBubble(
                "Done. Summary:\n\n## Result\n- 42 tests pass\n- coverage `+3%`\n\n```kotlin\nval x = 1\n```",
            )
        }
    }
}

@Preview(name = "Message bubbles — dark", showBackground = true, widthDp = 380, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessageBubblesDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        Column {
            UserMessageBubble("Refactor the parser and **run the tests**")
            AgentMessageBubble("Done. `-` bullets and `code` render inline.")
        }
    }
}

@Preview(name = "ToolCard", showBackground = true)
@Composable
private fun ToolCardPreview() {
    ZcodeTheme(darkTheme = false) {
        Column {
            ToolCard(name = "bash", title = "npm test -- --coverage", status = "completed")
            ToolCard(name = "read", title = "src/parser/index.ts", status = "in_progress")
            ToolCard(name = "edit", title = "", status = "failed")
        }
    }
}

@Preview(name = "ToolCard — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToolCardDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        ToolCard(name = "bash", title = "npm test -- --coverage", status = "completed")
    }
}

@Preview(name = "ChatInputBar", showBackground = true)
@Composable
private fun ChatInputBarPreview() {
    ZcodeTheme(darkTheme = false) {
        Surface {
            Column {
                ChatInputBar(
                    value = "Fix the failing test",
                    onValueChange = {},
                    running = false,
                    connected = true,
                    onSend = {},
                    onStop = {},
                    onAttach = {},
                )
                ChatInputBar(
                    value = "",
                    onValueChange = {},
                    running = true,
                    connected = true,
                    onSend = {},
                    onStop = {},
                )
            }
        }
    }
}

@Preview(name = "ChatInputBar — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatInputBarDarkPreview() {
    ZcodeTheme(darkTheme = true) {
        Surface {
            ChatInputBar(
                value = "",
                onValueChange = {},
                running = false,
                connected = true,
                onSend = {},
                onStop = {},
                onAttach = {},
            )
        }
    }
}
