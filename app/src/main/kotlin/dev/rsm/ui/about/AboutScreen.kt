package dev.rsm.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rsm.BuildConfig
import dev.rsm.R
import kotlinx.coroutines.launch

/** Where the two outbound links go. Kept here so the copy in strings.xml and the target agree. */
private const val GITHUB_URL = "https://github.com/garrymann"
private const val DONATE_URL = "https://paypal.me/garyfromhungary"

/**
 * Version, links and licence attribution.
 *
 * ### On FR-804
 *
 * FR-804 requires a *complete* third-party licence and source-offer screen, and this is not yet
 * that: it names the licence of every dependency the app currently ships but carries neither the
 * full licence texts nor the written source offer. `80-licensing.md` records that "complete" is
 * undefined in the spec, so this is a starting point rather than a claim to satisfy FR-804 — the
 * remaining work is the texts, the source offer, and keeping the list generated rather than
 * hand-written. The libsu line is listed unconditionally because it is true of the build that
 * contains root support and harmless in the one that does not; naming it per flavour would need a
 * flavour-specific source set (FR-823 forbids a runtime branch) for one line of attribution.
 *
 * The links are plain `ACTION_VIEW` intents, so the app still declares no `INTERNET` permission and
 * FR-607 holds: nothing here is a core function, and the browser does the fetching.
 */
// TopAppBar is @ExperimentalMaterial3Api in material3 1.4.0 — see the note in VolumeListScreen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkFailed = stringResource(R.string.about_link_failed)

    fun openLink(url: String) {
        // FR-602's no-generic-failure rule applies to this surface too: a device with no browser is
        // a specific, nameable situation, so it gets a specific message instead of a silent no-op.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(linkFailed) }
        }
    }

    Scaffold(
        modifier = modifier.testTag("about-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppIdentity()
            LinksCard(onGithub = { openLink(GITHUB_URL) }, onDonate = { openLink(DONATE_URL) })
            LicencesCard()
        }
    }
}

@Composable
private fun AppIdentity() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Rounded.SdStorage,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            // BuildConfig.APP_VERSION_* are set from git in app/build.gradle.kts, so this line is
            // never a hand-maintained literal that can drift from what was actually built.
            text = stringResource(
                R.string.about_version,
                BuildConfig.APP_VERSION_NAME,
                BuildConfig.APP_VERSION_CODE,
                BuildConfig.DISTRIBUTION,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("about-version"),
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LinksCard(onGithub: () -> Unit, onDonate: () -> Unit) {
    SectionCard(title = stringResource(R.string.about_links_header)) {
        LinkRow(
            icon = Icons.Rounded.Code,
            title = stringResource(R.string.about_github),
            subtitle = stringResource(R.string.about_github_subtitle),
            testTag = "about-github",
            onClick = onGithub,
        )
        HorizontalDivider()
        LinkRow(
            icon = Icons.Rounded.Favorite,
            title = stringResource(R.string.about_donate),
            subtitle = stringResource(R.string.about_donate_subtitle),
            testTag = "about-donate",
            onClick = onDonate,
        )
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    ListItem(
        // The whole row is one clickable node, so TalkBack announces "title, subtitle" once and
        // activating it anywhere follows the link — rather than the icon, text and trailing glyph
        // each being separately focusable and only one of them doing anything.
        modifier = Modifier.clickable(onClick = onClick).testTag(testTag),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
        },
        // Transparent, not `surface`: this row sits inside a Card, whose container is a tinted
        // surface. Painting `surface` here would show as a lighter patch inside the card.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun LicencesCard() {
    SectionCard(title = stringResource(R.string.about_licences_header)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.about_licence_androidx), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_licence_kotlin), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_licence_libsu), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.about_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}
