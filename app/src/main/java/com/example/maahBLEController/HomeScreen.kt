package com.example.maahBLEController

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    context: Context,
    refreshKey: Int = 0,
    onLayoutSelected: (String) -> Unit
) {
    val layouts = remember(refreshKey) {
        val assetFiles = context.assets.list("")?.filter { it.endsWith(".layout") } ?: emptyList()
        val internalFiles = context.filesDir.list()?.filter { it.endsWith(".layout")} ?: emptyList()
        (assetFiles + internalFiles).distinct().sorted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
        )
        Text(
            text = "Select a Controller Layout",
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        LazyColumn{
            items(layouts){ layout ->
                Button(
                    onClick = { onLayoutSelected(layout) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ){
                    Text(text = layout)
                }

            }
        }
    }
}