package com.niki914.zafiro.app.ui.chat.composable.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2BubbleShape
import com.niki914.uikit.infra.shape.G2FieldShape

/** User 气泡在连续用户消息组内的位置（渲染层分组：组边界 = 相邻 UserBubble 之间存在任何内容，见 userBubblePosition）。 */
enum class UserBubblePosition {
    /** 独立单条：右上圆角、右下直角 + 尾巴 */
    Single,

    /** 组首：右上圆角、右下直角 */
    GroupFirst,

    /** 组中：右上、右下均直角 */
    GroupMid,

    /** 组末：右上、右下均直角 + 尾巴 */
    GroupLast,
}

/** 接缝侧小圆角（组内 User 气泡相接角）。 */
private val UserBubbleInnerCorner = 6.dp

/** User 气泡普通角半径（G2 连续平滑椭圆大圆角）。 */
internal val UserBubbleCornerRadius = 26.dp

@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    position: UserBubblePosition = UserBubblePosition.Single,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleBg = colorScheme.primary.copy(alpha = 0.20f)
    val shadowColor = colorScheme.primary.copy(alpha = 0.25f)
    val bubbleShape = when (position) {
        UserBubblePosition.Single -> G2BubbleShape(UserBubbleCornerRadius)

        UserBubblePosition.GroupFirst -> G2FieldShape(
            topStart = UserBubbleCornerRadius,
            topEnd = UserBubbleCornerRadius,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = UserBubbleCornerRadius,
        )

        UserBubblePosition.GroupMid -> G2FieldShape(
            topStart = UserBubbleCornerRadius,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = UserBubbleCornerRadius,
        )

        UserBubblePosition.GroupLast -> G2FieldShape(
            topStart = UserBubbleCornerRadius,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = UserBubbleCornerRadius,
            bottomStart = UserBubbleCornerRadius,
        )
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth * 0.88f)
                .shadow(
                    elevation = 4.dp,
                    shape = bubbleShape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .clip(bubbleShape)
                .background(bubbleBg, bubbleShape)
                .padding(horizontal = 20.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
