package com.mgtv.feed

/** Feed 流数据模型。 */
data class FeedItem(
    val id: Long,
    val title: String,
    val url: String
)

/**
 * 测试视频源。全部经 curl 实测返回 HTTP 206（支持分段请求，系统 MediaPlayer 可边下边播）。
 * 原来的 Google Cast gtv-videos-bucket 源国内访问极慢，已整体替换。
 *
 * 排序有意为之：1/2/6 是国内 CDN，首选它们验证全屏切换，避免把网络卡顿误判成"切全屏黑屏"。
 */
object FeedData {

    val items = listOf(
        FeedItem(
            1, "西瓜 360p — 火山 CDN，国内最快",
            "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-360p.mp4"
        ),
        FeedItem(
            2, "西瓜 720p — 火山 CDN，国内最快",
            "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-720p.mp4"
        ),
        FeedItem(
            3, "腾讯云 TCPlayer — 国内 CDN",
            "https://1500009007.vod2.myqcloud.com/6c9c6038vodcq1500009007/2fb02795387702305297108918/w3C7ZwlsPNYA.mp4"
        ),
        FeedItem(
            4, "Video.js oceans",
            "https://vjs.zencdn.net/v/oceans.mp4"
        ),
        FeedItem(
            5, "ArtPlayer sample",
            "https://artplayer.org/assets/sample/video.mp4"
        ),
        FeedItem(
            6, "W3C Sintel Trailer",
            "https://media.w3.org/2010/05/sintel/trailer.mp4"
        ),
        FeedItem(
            7, "BigBuckBunny 360p — 10s/1MB 小体积",
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
        ),
        FeedItem(
            8, "BigBuckBunny 720p — 10s/2MB 小体积",
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_2MB.mp4"
        ),
    )
}
