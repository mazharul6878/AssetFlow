package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.NotificationRecord
import com.example.ui.theme.NovaBorderLight
import com.example.ui.theme.NovaPrimary
import com.example.ui.theme.NovaSurfaceWhite
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextNavy
import com.example.ui.theme.StatusBlueBg
import com.example.ui.theme.StatusBlueFg
import com.example.ui.theme.StatusExceptionBg
import com.example.ui.theme.StatusExceptionFg
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg

/**
 * End User Panel: Notifications (Section 4.C.9)
 * - In-app notifications generated from real backend events
 * - Admin remarks and correction requests
 * - Submission status changes
 * - Review request updates
 * - Approval & Publication events
 */
@Composable
fun NotificationsScreen(
  notifications: List<NotificationRecord>,
  onNotificationClick: (NotificationRecord) -> Unit
) {
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("notifications_screen"),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Column {
        Text(
          text = "Notifications",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = NovaTextNavy
        )
        Text(
          text = "Real-time updates on Admin reviews, remarks, approvals, and publication status.",
          style = MaterialTheme.typography.bodyMedium,
          color = NovaTextMuted
        )
      }
    }

    if (notifications.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Icon(Icons.Default.NotificationsNone, contentDescription = null, modifier = Modifier.size(48.dp), tint = NovaPrimary)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "No Notifications",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = NovaTextNavy
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "You're all caught up. When Admin reviews or sends remarks on your counts, they will appear here.",
              style = MaterialTheme.typography.bodyMedium,
              color = NovaTextMuted,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      }
    } else {
      items(notifications, key = { it.id }) { notif ->
        val (icon, iconBg, iconFg) = when (notif.type) {
          "REMARKS" -> Triple(Icons.Default.Feedback, StatusExceptionBg, StatusExceptionFg)
          "PUBLISHED", "APPROVAL" -> Triple(Icons.Default.CheckCircle, StatusVerifiedBg, StatusVerifiedFg)
          "REVIEW_REQUEST" -> Triple(Icons.AutoMirrored.Filled.Send, StatusBlueBg, StatusBlueFg)
          else -> Triple(Icons.Default.Notifications, StatusBlueBg, StatusBlueFg)
        }

        Card(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { onNotificationClick(notif) }
            .testTag("notif_item_${notif.id}"),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = NovaSurfaceWhite),
          border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NovaBorderLight))
        ) {
          Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
          ) {
            Surface(
              shape = CircleShape,
              color = iconBg,
              modifier = Modifier.size(36.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(18.dp))
              }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = notif.title,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold,
                  color = NovaTextNavy
                )
                if (!notif.isRead) {
                  Surface(
                    shape = CircleShape,
                    color = NovaPrimary,
                    modifier = Modifier.size(8.dp)
                  ) {}
                }
              }

              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = notif.message,
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextNavy
              )

              if (notif.timestampIso.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = notif.timestampIso.take(19).replace("T", " "),
                  style = MaterialTheme.typography.labelSmall,
                  color = NovaTextMuted
                )
              }
            }
          }
        }
      }
    }
  }
}
