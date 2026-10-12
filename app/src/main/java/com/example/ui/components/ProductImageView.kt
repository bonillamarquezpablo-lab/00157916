package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.Product
import com.example.data.model.ProductCategory

@Composable
fun ProductImageView(
    product: Product,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    showAthRibbon: Boolean = true
) {
    val localDrawableRes = when (product.imageResName) {
        "prod_sony_headphones" -> R.drawable.prod_sony_headphones
        "prod_ps5_slim" -> R.drawable.prod_ps5_slim
        "prod_iphone_16" -> R.drawable.prod_iphone_16
        "prod_smart_tv" -> R.drawable.prod_smart_tv
        "prod_coffee_machine" -> R.drawable.prod_coffee_machine
        else -> null
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        if (localDrawableRes != null) {
            Image(
                painter = painterResource(id = localDrawableRes),
                contentDescription = product.title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else if (product.imageUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(product.imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = product.title,
                contentScale = contentScale,
                loading = {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                error = {
                    FallbackCategoryIcon(product.category)
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            FallbackCategoryIcon(product.category)
        }

        // ATH Ribbon
        if (showAthRibbon && product.isAtAth) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF047857), Color(0xFF10B981))
                        )
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "★ MÍNIMO",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun FallbackCategoryIcon(category: ProductCategory) {
    val (icon, gradientColors) = when (category) {
        ProductCategory.SMARTPHONES -> Pair(
            Icons.Default.PhoneAndroid,
            listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
        )
        ProductCategory.COMPUTING -> Pair(
            Icons.Default.Laptop,
            listOf(Color(0xFF6366F1), Color(0xFF4338CA))
        )
        ProductCategory.GAMING -> Pair(
            Icons.Default.SportsEsports,
            listOf(Color(0xFFEC4899), Color(0xFFBE185D))
        )
        ProductCategory.TV_AUDIO -> Pair(
            Icons.Default.Tv,
            listOf(Color(0xFF8B5CF6), Color(0xFF6D28D9))
        )
        ProductCategory.HOME_APPLIANCES -> Pair(
            Icons.Default.Kitchen,
            listOf(Color(0xFF10B981), Color(0xFF047857))
        )
        ProductCategory.PERSONAL_CARE -> Pair(
            Icons.Default.Spa,
            listOf(Color(0xFFF59E0B), Color(0xFFD97706))
        )
        ProductCategory.SPORTS -> Pair(
            Icons.Default.FitnessCenter,
            listOf(Color(0xFF06B6D4), Color(0xFF0E7490))
        )
        else -> Pair(
            Icons.Default.ShoppingBag,
            listOf(Color(0xFF64748B), Color(0xFF334155))
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(gradientColors.map { it.copy(alpha = 0.15f) })),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = gradientColors.first(),
            modifier = Modifier.size(40.dp)
        )
    }
}
