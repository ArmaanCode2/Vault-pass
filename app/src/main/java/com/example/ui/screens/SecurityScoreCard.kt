package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * The security score and what it's made of, in one card: the Vault tab shows the password counts under
 * it, the Security tab the strength bars. With no score yet ([score] null) the ring is empty and the
 * status reads "Analyzing…". The ring stays still: the score can stay null until the next recalculation,
 * and a spinner would redraw the home screen every frame meanwhile.
 */
@Composable
fun SecurityScoreCard(
    score: Int?,
    status: String?,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                // One TalkBack stop: "72, Security Score, Good".
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                CircularProgressIndicator(
                    progress = { (score ?: 0) / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    // The track is a tint of the text color: the card itself is surfaceVariant.
                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    strokeWidth = 6.dp
                )
                if (score != null) {
                    Text(
                        score.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(R.string.dashboard_security_score),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    status ?: stringResource(R.string.dashboard_analyzing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        details()
    }
}

/**
 * The Vault tab's counts, each opening what it counts. Weak and reused take their warning color only when
 * there is something to fix, so a clean vault doesn't show red.
 */
@Composable
fun ScoreCountsRow(
    total: Int,
    weak: Int,
    reused: Int,
    onTotalClick: () -> Unit,
    onWeakClick: () -> Unit,
    onReusedClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ScoreCount(total, stringResource(R.string.dashboard_stat_total), MaterialTheme.colorScheme.primary, onTotalClick, Modifier.weight(1f))
        ScoreCount(weak, stringResource(R.string.dashboard_stat_weak), MaterialTheme.colorScheme.error.takeIf { weak > 0 }, onWeakClick, Modifier.weight(1f))
        ScoreCount(reused, stringResource(R.string.dashboard_stat_reused), MaterialTheme.colorScheme.tertiary.takeIf { reused > 0 }, onReusedClick, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreCount(count: Int, label: String, color: Color?, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** The Security tab's strength distribution: one bar per strength, as a share of all passwords. */
@Composable
fun StrengthDistribution(total: Int, strong: Int, medium: Int, weak: Int) {
    val weakPct = if (total > 0) (weak.toFloat() / total) else 0f
    val mediumPct = if (total > 0) (medium.toFloat() / total) else 0f
    val strongPct = if (total > 0) (strong.toFloat() / total) else 0f

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.security_strength_distribution),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Strong
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.security_strong), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("$strong (${(strongPct * 100).toInt()}%)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { strongPct }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(12.dp))

        // Medium
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.security_medium), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("$medium (${(mediumPct * 100).toInt()}%)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { mediumPct }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(12.dp))

        // Weak
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.security_weak), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("$weak (${(weakPct * 100).toInt()}%)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(progress = { weakPct }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = MaterialTheme.colorScheme.error, trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
    }
}
