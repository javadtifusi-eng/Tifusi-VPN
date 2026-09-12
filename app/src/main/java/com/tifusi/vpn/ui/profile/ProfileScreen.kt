package com.tifusi.vpn.ui.profile

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tifusi.vpn.R
import com.tifusi.vpn.ui.theme.TifusiNeonBlue

private val LanguageOptions = listOf(
    "en" to R.string.language_en,
    "fa" to R.string.language_fa,
)

@Composable
fun ProfileScreen() {
    var selectedTag by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-'))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.headlineMedium)

        LanguageOptions.forEach { (tag, labelRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedTag == tag,
                        onClick = {
                            selectedTag = tag
                            // AppCompat persists the choice and recreates the activity in that locale,
                            // which also flips layout direction to RTL for Persian.
                            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedTag == tag,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = TifusiNeonBlue),
                )
                Text(stringResource(labelRes), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}
