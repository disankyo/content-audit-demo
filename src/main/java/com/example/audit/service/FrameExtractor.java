package com.example.audit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 动图 / 视频抽帧。
 *
 * <p>核心思路：<b>把动图和视频降维成图片</b>，然后复用图片审核能力。
 * 抽帧结果作为多条 dynamic_image 记录写入（sort_no 记帧序），
 * 这样图片、动图、视频共用一套审核逻辑。
 *
 * <p>抽帧策略（面试可讲）：
 * <ul>
 *   <li>固定间隔抽帧：实现简单，但可能漏掉中间帧的违规内容</li>
 *   <li>按场景切换点抽帧：更准，但需要解码分析，成本高</li>
 *   <li>生产常用组合：<b>首尾帧 + 固定间隔 + 关键帧</b>，
 *       对高风险内容再提高抽帧密度做二次审核</li>
 * </ul>
 *
 * <p>生产实现：ffmpeg 一行命令即可，不需要自己写解码：
 * <pre>ffmpeg -i input.mp4 -vf "fps=1/2" -frames:v 10 frame_%03d.jpg</pre>
 * 本项目为跑通流程，用模拟抽帧。
 */
@Slf4j
@Component
public class FrameExtractor {

    private static final int MAX_FRAMES = 10;

    /**
     * 模拟抽帧：返回帧的虚拟 URL 列表。
     * 真实实现替换为 ffmpeg 调用后把帧上传到对象存储，返回真实 URL。
     */
    public List<String> extract(String sourceUrl, boolean isGif) {
        int frames = isGif ? Math.min(MAX_FRAMES, 6) : MAX_FRAMES;
        List<String> urls = new ArrayList<>(frames);
        for (int i = 0; i < frames; i++) {
            urls.add(sourceUrl + (sourceUrl.contains("?") ? "&" : "?") + "frame=" + i);
        }
        log.debug("抽帧完成, source={}, frames={}", sourceUrl, frames);
        return urls;
    }
}
