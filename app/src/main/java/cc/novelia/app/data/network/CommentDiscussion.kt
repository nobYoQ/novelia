package cc.novelia.app.data.network

import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumCommentInput
import cc.novelia.app.data.model.ForumPage

/** 共用讨论界面，资源路径和登录目标仍由对应 API 决定。 */
internal interface CommentDiscussion {
    val key: String
    suspend fun comments(page: Int): ForumPage<ForumComment>
    suspend fun replies(rootId: Long, page: Int): ForumPage<ForumComment>
    suspend fun replyCount(rootId: Long): Long
    suspend fun create(input: ForumCommentInput): ForumComment
    suspend fun update(id: Long, content: String): ForumComment
    suspend fun delete(id: Long)
}

internal fun ForumApi.discussion(postId: Long): CommentDiscussion = object : CommentDiscussion {
    override val key = "forum-comment:$postId"
    override suspend fun comments(page: Int) = comments(postId, page)
    override suspend fun replies(rootId: Long, page: Int) = replies(postId, rootId, page)
    override suspend fun replyCount(rootId: Long) = replyCount(postId, rootId)
    override suspend fun create(input: ForumCommentInput) = createComment(postId, input)
    override suspend fun update(id: Long, content: String) = updateComment(id, content)
    override suspend fun delete(id: Long) = deleteComment(id)
}

internal fun NovelCommentApi.discussion(subjectKey: String): CommentDiscussion = object : CommentDiscussion {
    override val key = "novel-comment:$subjectKey"
    override suspend fun comments(page: Int) = comments(subjectKey, page)
    override suspend fun replies(rootId: Long, page: Int) = replies(subjectKey, rootId, page)
    override suspend fun replyCount(rootId: Long) = replyCount(subjectKey, rootId)
    override suspend fun create(input: ForumCommentInput) = createComment(subjectKey, input)
    override suspend fun update(id: Long, content: String) = updateComment(id, content)
    override suspend fun delete(id: Long) = deleteComment(id)
}
