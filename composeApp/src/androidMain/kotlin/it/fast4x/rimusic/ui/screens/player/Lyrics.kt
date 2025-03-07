package it.fast4x.rimusic.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import it.fast4x.environment.Environment
import it.fast4x.environment.models.bodies.NextBody
import it.fast4x.kugou.KuGou
import it.fast4x.lrclib.LrcLib
import it.fast4x.lrclib.models.Track
import it.fast4x.rimusic.*
import it.fast4x.rimusic.enums.*
import it.fast4x.rimusic.models.Lyrics
import it.fast4x.rimusic.ui.components.LocalMenuState
import it.fast4x.rimusic.ui.components.themed.*
import it.fast4x.rimusic.ui.styling.*
import it.fast4x.rimusic.utils.*
import kotlinx.coroutines.*
import me.bush.translator.Language
import me.bush.translator.Translator
import org.jsoup.Jsoup
import timber.log.Timber
import java.net.URLEncoder

val client = HttpClient(CIO)

suspend fun httpGet(url: String) = client.get(url).bodyAsText()
suspend fun httpPost(url: String, data: Map<String, String>) = client.post(url) { setBody(data) }.bodyAsText()

@UnstableApi
@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    size: Dp,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    modifier: Modifier = Modifier,
    clickLyricsText: Boolean,
    trailingContent: (@Composable () -> Unit)? = null,
    isLandscape: Boolean,
) {
    AnimatedVisibility(
        visible = isDisplayed,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        val menuState = LocalMenuState.current
        val currentView = LocalView.current
        val binder = LocalPlayerServiceBinder.current

        // State variables and preferences
        var showlyricsthumbnail by rememberPreference(showlyricsthumbnailKey, false)
        var isShowingSynchronizedLyrics by rememberPreference(isShowingSynchronizedLyricsKey, false)
        var invalidLrc by remember(mediaId, isShowingSynchronizedLyrics) { mutableStateOf(false) }
        var isPicking by remember(mediaId, isShowingSynchronizedLyrics) { mutableStateOf(false) }
        var lyricsColor by rememberPreference(lyricsColorKey, LyricsColor.Thememode)
        var lyricsOutline by rememberPreference(lyricsOutlineKey, LyricsOutline.None)
        val playerBackgroundColors by rememberPreference(playerBackgroundColorsKey, PlayerBackgroundColors.BlurredCoverColor)
        var lyricsFontSize by rememberPreference(lyricsFontSizeKey, LyricsFontSize.Medium)
        val thumbnailSize = Dimensions.thumbnails.player.song
        val colorPaletteMode by rememberPreference(colorPaletteModeKey, ColorPaletteMode.Dark)
        var isEditing by remember(mediaId, isShowingSynchronizedLyrics) { mutableStateOf(false) }
        var showPlaceholder by remember { mutableStateOf(false) }
        var lyrics by remember { mutableStateOf<Lyrics?>(null) }
        val text = if (isShowingSynchronizedLyrics) lyrics?.synced else lyrics?.fixed
        var textTranslated by remember { mutableStateOf("") }
        var isError by remember(mediaId, isShowingSynchronizedLyrics) { mutableStateOf(false) }
        var isErrorSync by remember(mediaId, isShowingSynchronizedLyrics) { mutableStateOf(false) }
        var showLanguagesList by remember { mutableStateOf(false) }
        var translateEnabled by remember { mutableStateOf(false) }
        var romanization by rememberPreference(romanizationKey, Romanization.Off)
        var showSecondLine by rememberPreference(showSecondLineKey, false)
        var otherLanguageApp by rememberPreference(otherLanguageAppKey, Languages.English)
        var lyricsBackground by rememberPreference(lyricsBackgroundKey, LyricsBackground.Black)

        if (showLanguagesList) {
            translateEnabled = false
            menuState.display {
                Menu {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TitleSection(title = stringResource(R.string.languages))
                    }

                    MenuEntry(
                        icon = R.drawable.translate,
                        text = stringResource(R.string.do_not_translate),
                        secondaryText = "",
                        onClick = {
                            menuState.hide()
                            showLanguagesList = false
                            translateEnabled = false
                        }
                    )
                    MenuEntry(
                        icon = R.drawable.translate,
                        text = stringResource(R.string._default),
                        secondaryText = languageDestinationName(otherLanguageApp),
                        onClick = {
                            menuState.hide()
                            showLanguagesList = false
                            translateEnabled = true
                        }
                    )

                    Languages.entries.forEach {
                        if (it != Languages.System)
                            MenuEntry(
                                icon = R.drawable.translate,
                                text = languageDestinationName(it),
                                secondaryText = "",
                                onClick = {
                                    menuState.hide()
                                    otherLanguageApp = it
                                    showLanguagesList = false
                                    translateEnabled = true
                                }
                            )
                    }
                }
            }
        }

        var languageDestination = languageDestination(otherLanguageApp)

        val translator = Translator(getHttpClient())

        var copyToClipboard by remember { mutableStateOf(false) }

        if (copyToClipboard) text?.let {
            textCopyToClipboard(it, context)
            copyToClipboard = false
        }

        var copyTranslatedToClipboard by remember { mutableStateOf(false) }

        if (copyTranslatedToClipboard) textTranslated.let {
            textCopyToClipboard(it, context)
            copyTranslatedToClipboard = false
        }

        var fontSize by rememberPreference(lyricsFontSizeKey, LyricsFontSize.Medium)
        val showBackgroundLyrics by rememberPreference(showBackgroundLyricsKey, false)
        val playerEnableLyricsPopupMessage by rememberPreference(playerEnableLyricsPopupMessageKey, true)
        var expandedplayer by rememberPreference(expandedplayerKey, false)
        var checkedLyricsLrc by remember { mutableStateOf(false) }
        var checkedLyricsKugou by remember { mutableStateOf(false) }
        var checkedLyricsInnertube by remember { mutableStateOf(false) }
        var checkLyrics by remember { mutableStateOf(false) }
        var lyricsHighlight by rememberPreference(lyricsHighlightKey, LyricsHighlight.None)
        var lyricsAlignment by rememberPreference(lyricsAlignmentKey, LyricsAlignment.Center)
        var lyricsSizeAnimate by rememberPreference(lyricsSizeAnimateKey, false)
        val mediaMetadata = mediaMetadataProvider()
        var artistName by rememberSaveable { mutableStateOf(mediaMetadata.artist?.toString().orEmpty())}
        var title by rememberSaveable { mutableStateOf(cleanPrefix(mediaMetadata.title?.toString().orEmpty()))}
        var lyricsSize by rememberPreference(lyricsSizeKey, 20f)
        var lyricsSizeL by rememberPreference(lyricsSizeLKey, 20f)
        var customSize = if (isLandscape) lyricsSizeL else lyricsSize
        var showLyricsSizeDialog by rememberSaveable { mutableStateOf(false) }
        val lightTheme = colorPaletteMode == ColorPaletteMode.Light || (colorPaletteMode == ColorPaletteMode.System && (!isSystemInDarkTheme()))
        val effectRotationEnabled by rememberPreference(effectRotationKey, true)
        var landscapeControls by rememberPreference(landscapeControlsKey, true)
        var jumpPrevious by rememberPreference(jumpPreviousKey,"3")
        var isRotated by rememberSaveable { mutableStateOf(false) }
        val rotationAngle by animateFloatAsState(targetValue = if (isRotated) 360F else 0f, animationSpec = tween(durationMillis = 200), label = "")
        val colorPaletteName by rememberPreference(colorPaletteNameKey, ColorPaletteName.Dynamic)

        if (showLyricsSizeDialog) {
            LyricsSizeDialog(
                onDismiss = { showLyricsSizeDialog = false},
                sizeValue = { lyricsSize = it },
                sizeValueL = { lyricsSizeL = it}
            )
        }

        LaunchedEffect(mediaMetadata.title, mediaMetadata.artist) {
            artistName = mediaMetadata.artist?.toString().orEmpty()
            title = cleanPrefix(mediaMetadata.title?.toString().orEmpty())
        }

        suspend fun deepL(untranslatedLrc: String, srcLang: String, targLang: String): String? {
            return withContext(Dispatchers.IO) {
                try {
                    val baseUrl = "https://www.deepl.com/en/translator#$srcLang/$targLang/"
                    val cleanInput = URLEncoder.encode(untranslatedLrc, "UTF-8")
                    val finalUrl = "$baseUrl$cleanInput%0A"
                    val response = httpGet(finalUrl)
                    val document = Jsoup.parse(response)
                    val targetTextarea = document.selectFirst("textarea[dl-test=translator-target-input]")
                    targetTextarea?.text()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }
        suspend fun libreTranslate(untranslatedLrc: String, srcLang: String, targLang: String): String? {
            return withContext(Dispatchers.IO) {
                try {
                    val url = "https://libretranslate.com/translate"
                    val params = mapOf(
                        "q" to untranslatedLrc,
                        "source" to srcLang,
                        "target" to targLang,
                        "format" to "text"
                    )
                    val response = httpPost(url, params)
                    // Assuming response is a JSON object
                    val json = response.jsonObject
                    json.getString("translatedText")
                } catch (e: Exception) {
                    Timber.e("LibreTranslate error: ${e.stackTraceToString()}")
                    null
                }
            }
        }

        fun detectLanguage(text: String): String {
            // A placeholder function to detect language, replace with actual implementation
            return "auto"
        }

        val deeplSourceLanguages = setOf(
            "AR", "BG", "CS", "DA", "DE", "EL", "EN", "ES", "ET", "FI", "FR", 
            "HU", "ID", "IT", "JA", "KO", "LT", "LV", "NB", "NL", "PL", "PT", 
            "RO", "RU", "SK", "SL", "SV", "TR", "UK", "ZH"
        )

        @Composable
        fun translateLyricsWithRomanization(output: MutableState<String>, textToTranslate: String, isSync: Boolean, destinationLanguage: Language = Language.AUTO) {
            LaunchedEffect(showSecondLine, romanization, textToTranslate, destinationLanguage) {
                var destLanguage = destinationLanguage
                val result = withContext(Dispatchers.IO) {
                    try {
                        val sourceLanguage = detectLanguage(textToTranslate)
                        val translationService = if (sourceLanguage in deeplSourceLanguages && textToTranslate.length <= 1500) {
                            ::deepL
                        } else {
                            ::libreTranslate
                        }
                        val translation = translationService(textToTranslate, sourceLanguage, destinationLanguage.code)
                        translation ?: ""
                    } catch (e: Exception) {
                        if (isSync) {
                            Timber.e("Lyrics sync translation ${e.stackTraceToString()}")
                        } else {
                            Timber.e("Lyrics not sync translation ${e.stackTraceToString()}")
                        }
                        ""
                    }
                }
                val translatedText = if (result.toString() == "kotlin.Unit") "" else result.toString()
                showPlaceholder = false
                output.value = translatedText

                textTranslated = translatedText
            }
        }

        LaunchedEffect(mediaId, isShowingSynchronizedLyrics, checkLyrics) {
            withContext(Dispatchers.IO) {
                Database.lyrics(mediaId).collect { currentLyrics ->
                    if (isShowingSynchronizedLyrics && currentLyrics?.synced == null) {
                        lyrics = null
                        var duration = withContext(Dispatchers.Main) {
                            durationProvider()
                        }

                        while (duration == C.TIME_UNSET) {
                            delay(100)
                            duration = withContext(Dispatchers.Main) {
                                durationProvider()
                            }
                        }

                        kotlin.runCatching {
                            LrcLib.lyrics(
                                artist = artistName ?: "",
                                title = title ?: "",
                                duration = duration.milliseconds,
                                album = mediaMetadata.albumTitle?.toString()
                            )?.onSuccess {
                                if ((it?.text?.isNotEmpty() == true || it?.sentences?.isNotEmpty() == true)
                                    && playerEnableLyricsPopupMessage
                                )
                                    coroutineScope.launch {
                                        SmartMessage(
                                            context.resources.getString(R.string.info_lyrics_found_on_s)
                                                .format("LrcLib.net"),
                                            type = PopupType.Success, context = context
                                        )
                                    }
                                else
                                    if (playerEnableLyricsPopupMessage)
                                        coroutineScope.launch {
                                            SmartMessage(
                                                context.resources.getString(R.string.info_lyrics_not_found_on_s).format("LrcLib.net"),
                                                type = PopupType.Error,
                                                durationLong = true,
                                                context = context
                                            )
                                        }

                                isError = false
                                Database.upsert(
                                    Lyrics(
                                        songId = mediaId,
                                        fixed = currentLyrics?.fixed,
                                        synced = it?.text.orEmpty()
                                    )
                                )
                                checkedLyricsLrc = true
                            }?.onFailure {
                                if (playerEnableLyricsPopupMessage)
                                    coroutineScope.launch {
                                        SmartMessage(
                                            context.resources.getString(R.string.info_lyrics_not_found_on_s_try_on_s)
                                                .format("LrcLib.net", "KuGou.com"),
                                            type = PopupType.Error,
                                            durationLong = true, context = context
                                        )
                                    }

                                checkedLyricsLrc = true

                                kotlin.runCatching {
                                    KuGou.lyrics(
                                        artist = mediaMetadata.artist?.toString() ?: "",
                                        title = cleanPrefix(mediaMetadata.title?.toString() ?: ""),
                                        duration = duration / 1000
                                    )?.onSuccess {
                                        if ((it?.value?.isNotEmpty() == true || it?.sentences?.isNotEmpty() == true)
                                            && playerEnableLyricsPopupMessage
                                        )
                                            coroutineScope.launch {
                                                SmartMessage(
                                                    context.resources.getString(R.string.info_lyrics_found_on_s)
                                                        .format("KuGou.com"),
                                                    type = PopupType.Success, context = context
                                                )
                                            }
                                        else
                                            if (playerEnableLyricsPopupMessage)
                                                coroutineScope.launch {
                                                    SmartMessage(
                                                        context.resources.getString(R.string.info_lyrics_not_found_on_s)
                                                            .format("KuGou.com"),
                                                        type = PopupType.Error,
                                                        durationLong = true, context = context
                                                    )
                                                }

                                        isError = false
                                        Database.upsert(
                                            Lyrics(
                                                songId = mediaId,
                                                fixed = currentLyrics?.fixed,
                                                synced = it?.value.orEmpty()
                                            )
                                        )
                                        checkedLyricsKugou = true
                                    }?.onFailure {
                                        if (playerEnableLyricsPopupMessage)
                                            coroutineScope.launch {
                                                SmartMessage(
                                                    context.resources.getString(R.string.info_lyrics_not_found_on_s)
                                                        .format("KuGou.com"),
                                                    type = PopupType.Error,
                                                    durationLong = true, context = context
                                                )
                                            }

                                        isError = true
                                    }
                                }.onFailure {
                                    Timber.e("Lyrics Kugou get error ${it.stackTraceToString()}")
                                }
                            }
                        }.onFailure {
                            Timber.e("Lyrics get error ${it.stackTraceToString()}")
                        }

                    } else if (!isShowingSynchronizedLyrics && currentLyrics?.fixed == null) {
                        isError = false
                        lyrics = null
                        kotlin.runCatching {
                            Environment.lyrics(NextBody(videoId = mediaId))
                                ?.onSuccess { fixedLyrics ->
                                    Database.upsert(
                                        Lyrics(
                                            songId = mediaId,
                                            fixed = fixedLyrics ?: "",
                                            synced = currentLyrics?.synced
                                        )
                                    )
                                }?.onFailure {
                                isError = true
                            }
                        }.onFailure {
                            Timber.e("Lyrics Innertube get error ${it.stackTraceToString()}")
                        }
                        checkedLyricsInnertube = true
                    } else {
                        lyrics = currentLyrics
                    }
                }
            }
        }

        if (isEditing) {
            InputTextDialog(
                onDismiss = { isEditing = false },
                setValueRequireNotNull = false,
                title = stringResource(R.string.enter_the_lyrics),
                value = text ?: "",
                placeholder = stringResource(R.string.enter_the_lyrics),
                setValue = {
                    Database.asyncTransaction {
                        ensureSongInserted()
                        upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = if (isShowingSynchronizedLyrics) lyrics?.fixed else it,
                                synced = if (isShowingSynchronizedLyrics) it else lyrics?.synced,
                            )
                        )
                    }
                }
            )
        }

        @Composable
        fun SelectLyricFromTrack(
            tracks: List<Track>,
            mediaId: String,
            lyrics: Lyrics?
        ) {
            menuState.display {
                Menu {
                    MenuEntry(
                        icon = R.drawable.chevron_back,
                        text = stringResource(R.string.cancel),
                        onClick = { menuState.hide() }
                    )
                    Row {
                        TextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            colors = TextFieldDefaults.textFieldColors(textColor = colorPalette().text, unfocusedIndicatorColor = colorPalette().text),
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .weight(1f)
                        )
                        TextField(
                            value = artistName,
                            onValueChange = { artistName = it },
                            singleLine = true,
                            colors = TextFieldDefaults.textFieldColors(textColor = colorPalette().text, unfocusedIndicatorColor = colorPalette().text),
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .weight(1f)
                        )
                        IconButton(
                            icon = R.drawable.search,
                            color = Color.Black,
                            onClick = {
                                isPicking = false
                                menuState.hide()
                                isPicking = true
                            },
                            modifier = Modifier
                                .background(shape = RoundedCornerShape(4.dp), color = Color.White)
                                .padding(all = 4.dp)
                                .size(24.dp)
                                .align(Alignment.CenterVertically)
                                .weight(0.2f)
                        )
                    }
                    tracks.forEach {
                        MenuEntry(
                            icon = R.drawable.text,
                            text = "${it.artistName} - ${it.trackName}",
                            secondaryText = "(${stringResource(R.string.sort_duration)} ${
                                it.duration.seconds.toComponents { minutes, seconds, _ ->
                                    "$minutes:${seconds.toString().padStart(2, '0')}"
                                }
                            } ${stringResource(R.string.id)} ${it.id}) ",
                            onClick = {
                                menuState.hide()
                                Database.asyncTransaction {
                                    upsert(
                                        Lyrics(
                                            songId = mediaId,
                                            fixed = lyrics?.fixed,
                                            synced = it.syncedLyrics.orEmpty()
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
            }
            isPicking = false
        }

        if (isPicking && isShowingSynchronizedLyrics) {
            var loading by remember { mutableStateOf(true) }
            val tracks = remember { mutableStateListOf<Track>() }
            var error by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                kotlin.runCatching {
                    LrcLib.lyrics(
                        artist = artistName,
                        title = title
                    )?.onSuccess {
                        if (it.isNotEmpty() && playerEnableLyricsPopupMessage)
                            coroutineScope.launch {
                                SmartMessage(
                                    context.resources.getString(R.string.info_lyrics_tracks_found_on_s)
                                        .format("LrcLib.net"),
                                    type = PopupType.Success, context = context
                                )
                            }
                        else if (playerEnableLyricsPopupMessage)
                            coroutineScope.launch {
                                SmartMessage(
                                    context.resources.getString(R.string.info_lyrics_tracks_not_found_on_s)
                                        .format("LrcLib.net"),
                                    type = PopupType.Error,
                                    durationLong = true, context = context
                                )
                            }

                        tracks.clear()
                        tracks.addAll(it)
                        loading = false
                        error = false
                    }?.onFailure {
                        if (playerEnableLyricsPopupMessage)
                            coroutineScope.launch {
                                SmartMessage(
                                    context.resources.getString(R.string.an_error_has_occurred_while_fetching_the_lyrics)
                                        .format("LrcLib.net"),
                                    type = PopupType.Error,
                                    durationLong = true, context = context
                                )
                            }

                        loading = false
                        error = true
                    } ?: run { loading = false }
                }.onFailure {
                    Timber.e("Lyrics get error 1 ${it.stackTraceToString()}")
                }
            }

            if (loading)
                DefaultDialog(
                    onDismiss = { isPicking = false }
                ) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

            if (tracks.isNotEmpty()) {
                SelectLyricFromTrack(tracks = tracks, mediaId = mediaId, lyrics = lyrics)
            }
        }

        if (isShowingSynchronizedLyrics) {
            DisposableEffect(Unit) {
                currentView.keepScreenOn = true
                onDispose { currentView.keepScreenOn = false }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() }
                    )
                }
                .fillMaxSize()
                .background(if (!showlyricsthumbnail) Color.Transparent else Color.Black.copy(0.8f))
                .clip(thumbnailShape())
        ) {
            AnimatedVisibility(
                visible = (isError && text == null) || (invalidLrc && isShowingSynchronizedLyrics),
                enter = slideInVertically { -it },
                exit = slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                BasicText(
                    text = stringResource(R.string.an_error_has_occurred_while_fetching_the_lyrics),
                    style = typography().xs.center.medium.color(PureBlackColorPalette.text),
                    modifier = Modifier
                       .background(if (!showlyricsthumbnail) Color.Transparent else Color.Black.copy(0.4f))
                        .padding(all = 8.dp)
                        .fillMaxWidth()
                )
            }

            if (text?.isEmpty() == true && !checkedLyricsLrc && !checkedLyricsKugou && !checkedLyricsInnertube)
                checkLyrics = !checkLyrics

            if (text?.isNotEmpty() == true) {
                if (isShowingSynchronizedLyrics) {
                    val density = LocalDensity.current
                    val player = LocalPlayerServiceBinder.current?.player ?: return@AnimatedVisibility

                    val synchronizedLyrics = remember(text) {
                        val sentences = LrcLib.Lyrics(text).sentences

                        run {
                            invalidLrc = false
                            SynchronizedLyrics(sentences) {
                                player.currentPosition + 50L //- (lyrics?.startTime ?: 0L)
                            }
                        }
                    }

                    val lazyListState = rememberLazyListState()

                    LaunchedEffect(synchronizedLyrics, density) {
                        val centerOffset = with(density) {
                            (-thumbnailSize.div(if (!showlyricsthumbnail && !isLandscape) if (trailingContent == null) 2 else 1
                                                else if (trailingContent == null) 3 else 2))
                                .roundToPx()
                        }

                        lazyListState.animateScrollToItem(
                            index = synchronizedLyrics.index + 1,
                            scrollOffset = centerOffset
                        )

                        while (isActive) {
                            delay(50)
                            if (!synchronizedLyrics.update()) continue

                            lazyListState.animateScrollToItem(
                                index = synchronizedLyrics.index + 1,
                                scrollOffset = centerOffset
                            )
                        }
                    }

                    var modifierBG = Modifier.verticalFadingEdge()
                    if (showBackgroundLyrics && showlyricsthumbnail) modifierBG = modifierBG.background(colorPalette().accent)

                    LazyColumn(
                        state = lazyListState,
                        userScrollEnabled = true,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = modifierBG.background(
                            if (isDisplayed && !showlyricsthumbnail) if (lyricsBackground == LyricsBackground.Black) Color.Black.copy(0.6f)
                            else if (lyricsBackground == LyricsBackground.White) Color.White.copy(0.4f)
                            else Color.Transparent else Color.Transparent
                        )
                    ) {
                        item(key = "header", contentType = 0) {
                            // Additional items can be added here
                        }
                        itemsIndexed(synchronizedLyrics.sentences) { index, sentence ->
                            BasicText(
                                text = sentence.text,
                                style = typography().xs.center.medium.color(PureBlackColorPalette.text),
                                modifier = Modifier.padding(all = 8.dp)
                            )
                        }
                    }
                } else {
                    BasicText(
                        text = text ?: "",
                        style = typography().xs.center.medium.color(PureBlackColorPalette.text),
                        modifier = Modifier.padding(all = 8.dp)
                    )
                }
            }
        }
    }
}

/*@Composable
fun SelectLyricFromTrack(
    tracks: List<Track>,
    mediaId: String,
    lyrics: Lyrics?
) {
    val menuState = LocalMenuState.current

    menuState.display {
        Menu {
            MenuEntry(
                icon = R.drawable.chevron_back,
                text = stringResource(R.string.cancel),
                onClick = { menuState.hide() }
            )
            tracks.forEach {
                MenuEntry(
                    icon = R.drawable.text,
                    text = "${it.artistName} - ${it.trackName}",
                    secondaryText = "(${stringResource(R.string.sort_duration)} ${
                        it.duration.seconds.toComponents { minutes, seconds, _ ->
                            "$minutes:${seconds.toString().padStart(2, '0')}"
                        }
                    } ${stringResource(R.string.id)} ${it.id}) ",
                    onClick = {
                        menuState.hide()
                        Database.asyncTransaction {
                            upsert(
                                Lyrics(
                                    songId = mediaId,
                                    fixed = lyrics?.fixed,
                                    synced = it.syncedLyrics.orEmpty()
                                )
                            )
                        }
                    }
                )
            }
            MenuEntry(
                icon = R.drawable.chevron_back,
                text = stringResource(R.string.cancel),
                onClick = { menuState.hide() }
            )
        }
    }
}*/
