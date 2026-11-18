import MBFoundation
import SwiftUI

struct CommentsView: View {
    @State private var model: InteractionModel
    @State private var draft = ""
    @Environment(\.dismiss) private var dismiss
    init(source: any InteractionSource, track: Track) { _model = State(initialValue: InteractionModel(source: source, track: track)) }
    var body: some View {
        NavigationStack {
            List {
                if model.capabilities.likes {
                    Section {
                    Button {
                        Task { await model.toggleLike() }
                    } label: {
                        Label(LocalizedStringKey(model.liked ? "已点赞" : "点赞视频"), systemImage: model.liked ? "hand.thumbsup.fill" : "hand.thumbsup")
                    }.disabled(model.isChangingLike)
                    if let error = model.likeError { Text(LocalizedStringKey(error)).font(.caption).foregroundStyle(.red) }
                }
                }
                if model.capabilities.comments {
                    Section("评论 · \(model.total)") {
                    ForEach(model.comments) { comment in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(comment.author).font(.subheadline.bold())
                                Spacer()
                                if model.commentLikes {
                                    Button { Task { await model.toggleCommentLike(comment) } } label: { Label("\(comment.likes)", systemImage: comment.liked ? "hand.thumbsup.fill" : "hand.thumbsup") }
                                        .buttonStyle(.borderless).disabled(model.changingComments.contains(comment.id)).accessibilityLabel("点赞评论 \(comment.author)")
                                } else { Text("\(comment.likes) 赞").font(.caption).foregroundStyle(.secondary) }
                            }
                            Text(comment.text).textSelection(.enabled)
                            Text(comment.date, style: .date).font(.caption2).foregroundStyle(.secondary)
                        }.padding(.vertical, 6)
                    }
                    if model.isLoading { ProgressView().frame(maxWidth: .infinity) }
                    else if model.comments.isEmpty && model.errorMessage == nil { Text("暂无评论").foregroundStyle(.secondary) }
                    if model.hasMore { Button("加载更多评论") { Task { await model.loadMore() } }.disabled(model.isLoading) }
                }
                }
                if let message = model.errorMessage {
                    Text(LocalizedStringKey(message)).foregroundStyle(.red)
                    Button("重试加载评论") { Task { await model.refresh() } }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if model.capabilities.posting { HStack(alignment: .bottom) {
                    TextField("发送评论，需要登录", text: $draft, axis: .vertical).lineLimit(1...5).textFieldStyle(.roundedBorder)
                    Button("发送") { let text = draft; Task { if await model.post(text) { draft = "" } } }
                        .disabled(model.isPosting || model.isLoading || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding().background(.bar) }
            }
            .navigationTitle("评论与点赞")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .task { await model.refresh(); await model.loadLike() }
            .refreshable { await model.refresh() }
        }
    }
}
