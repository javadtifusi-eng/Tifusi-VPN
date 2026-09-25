package com.tifusi.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tifusi.vpn.ui.theme.AccentCyan
import com.tifusi.vpn.ui.theme.AccentGreen
import com.tifusi.vpn.ui.theme.PanelCard
import com.tifusi.vpn.ui.theme.PanelLine
import com.tifusi.vpn.ui.theme.TifusiTextSecondary

/** The grey rounded card of stacked rows, split by hairlines. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelCard),
        content = content,
    )
}

@Composable
fun PanelDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PanelLine))
}

/** One row: optional leading icon, a label (and hint), and whatever sits at the end. */
@Composable
fun PanelRow(
    label: String,
    icon: (@Composable () -> Unit)? = null,
    hint: String? = null,
    labelColor: Color = Color.White,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (hint != null) 11.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        icon?.let { Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f)) {
            Text(label, color = labelColor, fontSize = 16.sp, maxLines = 1)
            hint?.let { Text(it, color = TifusiTextSecondary, fontSize = 12.sp) }
        }
        trailing()
    }
}

@Composable
fun PanelIcon(icon: ImageVector, tint: Color = Color.White) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}

/** The white disc with a black glyph, used for upload and download. */
@Composable
fun DiscIcon(icon: ImageVector) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun PanelValue(text: String, onClick: (() -> Unit)? = null, trailingIcon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(text, color = AccentCyan, fontSize = 15.sp)
        trailingIcon?.let { Icon(it, contentDescription = null, tint = AccentCyan, modifier = Modifier.padding(start = 3.dp).size(18.dp)) }
    }
}

@Composable
fun PanelSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = AccentGreen,
            checkedBorderColor = AccentGreen,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF3A3A3C),
            uncheckedBorderColor = Color(0xFF3A3A3C),
        ),
    )
}

@Composable
fun SectionCaption(text: String) {
    Text(
        text.uppercase(),
        color = TifusiTextSecondary,
        fontSize = 12.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Big bold screen title, as on the Configs and Settings screens. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, modifier = modifier)
}
