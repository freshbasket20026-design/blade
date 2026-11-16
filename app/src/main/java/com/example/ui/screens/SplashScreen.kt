package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import com.example.ui.theme.BladeSplashBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.94f) }

    LaunchedEffect(Unit) {
        launch {
            alphaAnim.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing))
        }
        launch {
            scaleAnim.animateTo(1f, animationSpec = tween(520, easing = FastOutSlowInEasing))
        }
        delay(1600)
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BladeSplashBackground),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.blade_splash),
            contentDescription = "Blade Splash Screen",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .scale(scaleAnim.value)
                .alpha(alphaAnim.value)
        )
    }
}
