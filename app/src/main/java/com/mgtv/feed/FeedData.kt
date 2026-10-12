package com.mgtv.feed

/** Feed 流数据模型。 */
data class FeedItem(
    val id: Long,
    val title: String,
    val url: String
)

/**
 * 测试视频源。全部经 curl 实测（2026-10-12）：
 * - 返回 HTTP 206，支持 Range 分段请求，系统 MediaPlayer 可边下边播；
 * - 全部国内 CDN，首 MB 下载速度 0.75MB/s ~ 2.2MB/s；
 * - 内容各不相同（新闻短片、演示片），按速度从高到低排序。
 */
object FeedData {

    /** 频道列表（抖音式多频道）。 */
    val channels = listOf("关注", "推荐", "朋友", "同城")

    /** 每个频道给不同的视频顺序，模拟不同内容池。 */
    fun itemsForChannel(channel: Int): List<FeedItem> {
        if (channel <= 0) return items
        val shift = channel % items.size
        return items.drop(shift) + items.take(shift)
    }

    val items = listOf(
        FeedItem(
            1, "西瓜 360p — 火山 CDN",
            "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-360p.mp4"
        ),
        FeedItem(
            2, "齐鲁新闻 — 齐鲁网 CDN (2.2MB/s)",
            "https://stream7.iqilu.com/10339/upload_transcode/202002/16/20200216050645YIMfjPq5Nw.mp4"
        ),
        FeedItem(
            3, "齐鲁新闻 — 齐鲁网 CDN (2.1MB/s)",
            "https://stream7.iqilu.com/10339/article/202002/18/2fca1c77730e54c7b500573c2437003f.mp4"
        ),
        FeedItem(
            4, "齐鲁新闻 — 齐鲁网 CDN (1.9MB/s)",
            "https://stream7.iqilu.com/10339/upload_transcode/202002/17/20200217101826WjyFCbUXQ2.mp4"
        ),
        FeedItem(
            5, "齐鲁新闻 — 齐鲁网 CDN (1.9MB/s)",
            "https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209104902N3v5Vpxuvb.mp4"
        ),
        FeedItem(
            6, "腾讯云 TCPlayer — 腾讯 CDN (1.7MB/s)",
            "https://1500009007.vod2.myqcloud.com/6c9c6038vodcq1500009007/2fb02795387702305297108918/w3C7ZwlsPNYA.mp4"
        ),
        FeedItem(
            7, "齐鲁新闻 — 齐鲁网 CDN (1.8MB/s)",
            "https://stream7.iqilu.com/10339/article/202002/18/02319a81c80afed90d9a2b9dc47f85b9.mp4"
        ),
        FeedItem(
            8, "齐鲁新闻 — 齐鲁网 CDN (1.6MB/s)",
            "https://stream7.iqilu.com/10339/article/202002/17/778c5884fa97f460dac8d90493c451de.mp4"
        ),
    )
}
