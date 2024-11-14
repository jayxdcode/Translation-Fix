package me.knighthat.component.tab.toolbar

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.util.UnstableApi
import it.fast4x.rimusic.LocalPlayerServiceBinder
import it.fast4x.rimusic.R
import it.fast4x.rimusic.models.SongEntity
import it.fast4x.rimusic.service.modern.PlayerServiceModern
import it.fast4x.rimusic.utils.asMediaItem
import kotlinx.coroutines.runBlocking

@UnstableApi
class LocateComponent private constructor(
    private val binder: PlayerServiceModern.Binder?,
    private val scrollableState: ScrollableState,
    private val positionState: MutableState<Int>,
    private val songs: () -> List<SongEntity>
): StateConditional, Descriptive {

    companion object {
        @JvmStatic
        @Composable
        fun init(
            scrollableState: ScrollableState,
            songs: () -> List<SongEntity>
        ) =
            LocateComponent(
                LocalPlayerServiceBinder.current,
                scrollableState,
                remember { mutableIntStateOf(-1) },
                songs
            )
    }

    var position: Int = positionState.value
        set(value) {
            positionState.value = value
            field = value
        }
    override var isActive: Boolean = songs().isNotEmpty() && binder?.player?.currentMediaItem != null
    override val iconId: Int = R.drawable.locate
    override val textId: Int = R.string.info_find_the_song_that_is_playing

    override fun onShortClick() {
        binder?.player
              ?.currentMediaItem
              ?.let { mediaItem ->
                  position = songs().map { it.song.asMediaItem }
                                    .indexOf( mediaItem )

                  runBlocking {
                      if( scrollableState is LazyListState )
                          scrollableState.scrollToItem( position )
                      else if( scrollableState is LazyGridState )
                          scrollableState.scrollToItem( position )
                  }
              }
    }
}