package com.example.fleetstrike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.ui.theme.Sea

/** How much room a screen has. Tablets get the roomy layouts; phones get tighter ones. */
enum class Fit {
    /** A tablet, either way up. */
    ROOMY,

    /** A phone, or a small tablet such as a Fire HD 8 (601 dp), held upright. */
    NARROW,

    /** A phone or small tablet on its side: plenty of width, very little height. */
    SHORT,
}

/** The tablet top bar and banners need about 780 dp across, so anything narrower gets the compact layouts. */
fun fitFor(width: Dp, height: Dp): Fit = when {
    width > height && height < 560.dp -> Fit.SHORT
    width < 780.dp -> Fit.NARROW
    else -> Fit.ROOMY
}

@Composable
fun SeaBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Sea.Night, Sea.Deep)))
            .safeDrawingPadding(),
        content = content,
    )
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color = Sea.GridLine,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(Sea.Panel.copy(alpha = 0.92f), RoundedCornerShape(if (compact) 14.dp else 18.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(if (compact) 14.dp else 18.dp))
            .padding(if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        content = content,
    )
}

@Composable
fun PanelTitle(text: String, color: Color = Sea.Text) {
    Text(text, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = Sea.TextDim) {
    Text(text, color = color, fontSize = 14.sp, lineHeight = 19.sp, modifier = modifier)
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Sea.Amber,
    small: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(if (small) 12.dp else 14.dp),
        contentPadding = if (small) PaddingValues(horizontal = 14.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF1A1206)),
    ) {
        Text(
            text,
            fontSize = if (small) 15.sp else 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = if (small) 2.dp else 4.dp),
        )
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(if (small) 12.dp else 14.dp),
        contentPadding = if (small) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding,
    ) {
        Text(
            text,
            fontSize = if (small) 14.sp else 16.sp,
            color = if (enabled) Sea.Text else Sea.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
