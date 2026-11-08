package com.example.fleetstrike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fleetstrike.ui.theme.Sea

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
fun Panel(modifier: Modifier = Modifier, accent: Color = Sea.GridLine, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Sea.Panel.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Sea.Amber) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF1A1206)),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Text(text, fontSize = 16.sp, color = if (enabled) Sea.Text else Sea.TextDim, modifier = Modifier.padding(vertical = 2.dp))
    }
}
