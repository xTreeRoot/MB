package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.CommentPage
import com.xtreeroot.mb.music.InteractionCapabilities
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.TrackComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class CommentPageState(val page: CommentPage = CommentPage(emptyList(), 0, null),
                                     val capabilities: InteractionCapabilities? = null, val loading: Boolean = false,
                                     val appending: Boolean = false, val error: String? = null)

internal class CommentPages(private val source: InteractionSource, private val trackId: String) {
    private val mutable = MutableStateFlow(CommentPageState())
    private var failedCursor: Int? = null
    val state = mutable.asStateFlow()

    suspend fun refresh() = load(null)

    suspend fun retry() {
        if (mutable.value.error != null) load(failedCursor)
    }

    suspend fun loadMore() {
        mutable.value.page.nextCursor?.let { load(it) }
    }

    private suspend fun load(cursor: Int?) {
        val previous = mutable.value
        if (previous.loading) return
        val context = currentCoroutineContext()
        mutable.value = previous.copy(loading = true, appending = cursor != null, error = null)
        try {
            val capabilities = if (cursor == null || previous.capabilities == null) source.interactionCapabilities() else previous.capabilities
            context.ensureActive()
            mutable.update { it.copy(capabilities = capabilities) }
            if (capabilities.comments) {
                val next = source.comments(trackId, cursor)
                context.ensureActive()
                val existing = previous.page.comments.takeIf { cursor != null }.orEmpty()
                mutable.update {
                    it.copy(page = next.copy(comments = (existing + next.comments).distinctBy(TrackComment::id),
                        nextCursor = next.nextCursor?.takeUnless { it == cursor }))
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            context.ensureActive()
            failedCursor = cursor
            mutable.update { it.copy(error = failure.message ?: "评论加载失败") }
        } finally {
            mutable.update { it.copy(loading = false) }
        }
    }

    fun recordPosted(comment: TrackComment) {
        mutable.update { current ->
            if (current.page.comments.any { it.id == comment.id }) current else current.copy(
                page = current.page.copy(comments = listOf(comment) + current.page.comments, total = current.page.total + 1))
        }
    }

    fun updateLike(commentId: String, enabled: Boolean) {
        mutable.update { current ->
            current.copy(page = current.page.copy(comments = current.page.comments.map { comment ->
                if (comment.id != commentId || comment.liked == enabled) comment else comment.copy(liked = enabled,
                    likes = (comment.likes + if (enabled) 1 else -1).coerceAtLeast(0))
            }))
        }
    }
}
