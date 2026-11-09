package com.nativeauto.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativeauto.nav.IncomingLinks
import com.nativeauto.nav.LinkStatus
import com.nativeauto.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LinkStatusViewModel @Inject constructor(links: IncomingLinks) : ViewModel() {
    val status = links.status
}

/**
 * 10b: while a shared link is being read ("Opening shared place…"), or why it couldn't be. On
 * success the route preview takes over, so nothing is shown.
 */
@Composable
fun BoxScope.LinkStatusPill(viewModel: LinkStatusViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    // Keep the last text while fading out.
    val last = remember { arrayOf<LinkStatus?>(null) }
    status?.let { last[0] = it }
    AnimatedVisibility(
        visible = status != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
    ) {
        val s = last[0]
        Box(
            Modifier
                .background(AppTheme.colors.surface, RoundedCornerShape(24.dp))
                .border(1.dp, if (s is LinkStatus.Failed) AppTheme.colors.amber else AppTheme.colors.outline, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 14.dp)
                .testTag("link_status"),
        ) {
            Text(
                when (s) {
                    is LinkStatus.Failed -> s.message
                    else -> "Opening shared place…"
                },
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.colors.text,
            )
        }
    }
}
