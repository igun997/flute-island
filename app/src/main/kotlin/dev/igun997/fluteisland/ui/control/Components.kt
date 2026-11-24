package dev.igun997.fluteisland.ui.control

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import dev.igun997.fluteisland.ui.Glyphs
import dev.igun997.fluteisland.ui.island.GlyphIcon

@Composable
fun FluidCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalControlColors.current
    val shape = RoundedCornerShape(28.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(c.surface)
        .let { if (onClick != null) it.pressScale().clickable(role = Role.Button, onClick = onClick) else it }
        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    Column(base.padding(18.dp), content = content)
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, icon: ImageVector? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = LocalControlColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                GlyphIcon(icon, c.accent, 20)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalControlColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

@Composable
fun NavRow(title: String, value: String? = null, subtitle: String? = null, onClick: () -> Unit) {
    val c = LocalControlColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
        if (value != null) {
            Text(value, color = c.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            Spacer(Modifier.width(6.dp))
        }
        GlyphIcon(Glyphs.ChevronRight, c.muted, 20)
    }
}


/** Segmented control whose thumb slides between options with a spring. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val c = LocalControlColors.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh).padding(4.dp)) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * index, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium), label = "thumb")
        Box(Modifier.offset(x = x).width(segment).height(38.dp).clip(RoundedCornerShape(12.dp)).background(c.accent))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                val active = i == index
                val color by animateColorAsState(if (active) c.onAccent else c.muted, label = "segText")
                Box(
                    Modifier.width(segment).height(38.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    val c = LocalControlColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueLabel, color = c.muted, fontSize = 14.sp)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceHigh, activeTickColor = c.onAccent.copy(alpha = 0.5f), inactiveTickColor = c.muted.copy(alpha = 0.4f)),
        )
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalControlColors.current
    val bg by animateColorAsState(if (enabled) c.accent else c.surfaceHigh, label = "btn")
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .let { if (enabled) it.pressScale().clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) c.onAccent else c.muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val c = LocalControlColors.current
    val tint = if (danger) c.danger else c.text
    Box(
        modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh).pressScale()
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalControlColors.current
    val bg by animateColorAsState(if (selected) c.accent else c.surfaceHigh, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onAccent else c.text, label = "chipFg")
    Box(Modifier.clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, placeholder: String? = null,
) {
    val c = LocalControlColors.current
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it, color = c.muted.copy(alpha = 0.7f)) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, focusedLabelColor = c.accent, unfocusedLabelColor = c.muted,
            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
        ),
    )
}

@Composable
fun Hint(text: String, tone: Color? = null) {
    val c = LocalControlColors.current
    Text(text, color = tone ?: c.muted, fontSize = 13.sp, lineHeight = 18.sp)
}

