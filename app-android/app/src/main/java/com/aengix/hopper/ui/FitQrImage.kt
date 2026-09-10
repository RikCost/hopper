package com.aengix.hopper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun FitQrImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val screenCap = minOf(
            config.screenWidthDp.dp - 24.dp,
            config.screenHeightDp.dp - 96.dp,
        )
        val heightCap = if (maxHeight < Dp.Infinity) maxHeight else screenCap
        val side = minOf(maxWidth, heightCap, screenCap).coerceAtLeast(96.dp)
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(side)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp),
        )
    }
}
