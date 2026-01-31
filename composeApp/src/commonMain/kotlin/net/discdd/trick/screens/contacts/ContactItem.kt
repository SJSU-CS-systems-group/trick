package net.discdd.trick.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.discdd.trick.data.Contact

/**
 * A single contact item in the contacts list.
 * Displays avatar placeholder, name, last message preview, and timestamp.
 */
@Composable
fun ContactItem(
    contact: Contact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar placeholder with initials
            ContactAvatar(contact = contact)

            Spacer(modifier = Modifier.width(16.dp))

            // Name and message preview
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Display name or short ID as fallback
                Text(
                    text = contact.displayName ?: contact.shortId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Last message preview
                Text(
                    text = contact.lastMessagePreview ?: "No messages yet",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // Timestamp
            contact.lastMessageAt?.let { timestamp ->
                Text(
                    text = formatRelativeTime(timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Circular avatar placeholder with initials.
 */
@Composable
private fun ContactAvatar(
    contact: Contact,
    modifier: Modifier = Modifier
) {
    val initials = getInitials(contact.displayName ?: contact.shortId)
    
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * Get initials from a name or ID.
 * Returns up to 2 characters.
 */
private fun getInitials(name: String): String {
    val words = name.trim().split(" ").filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        words.isNotEmpty() -> words[0].take(2).uppercase()
        else -> "?"
    }
}

/**
 * Format a timestamp as relative time (e.g., "2m", "2h", "Yesterday", "5d").
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = net.discdd.trick.data.currentTimeMillis()
    val diff = now - timestamp
    
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    
    return when {
        minutes < 1 -> "Now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 2 -> "Yesterday"
        days < 7 -> "${days}d"
        else -> "${days / 7}w"
    }
}

