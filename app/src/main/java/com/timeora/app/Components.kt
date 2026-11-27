package com.timevora.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Clean Homeline-inspired building blocks. Soft rounded cards and consistent shapes.

/** Rounded corner shape used for cards and rows (slightly softer than before). */
internal fun styleShape(n: Int = 16): Shape = RoundedCornerShape(n.dp)

/** Fills a surface with [base] in [shape]. [lite] (small rows) skips clipping the children. */
internal fun Modifier.styled(base: Color, shape: Shape, lite: Boolean = false): Modifier =
    if (lite) background(base, shape) else clip(shape).background(base, shape)

class CardColors(val containerColor: Color)

object CardDefaults {
    fun cardColors(containerColor: Color = Color.Unspecified): CardColors = CardColors(containerColor)
}

@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = styleShape(16),
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit
) {
    val base = if (colors.containerColor == Color.Unspecified) Color(ThemeState.palette.card) else colors.containerColor
    Column(modifier.styled(base, shape), content = content)
}

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape ?: RoundedCornerShape(12.dp),
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape ?: RoundedCornerShape(12.dp),
        colors = colors,
        border = border,
        contentPadding = contentPadding,
        content = content
    )
}
