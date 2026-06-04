package io.github.jerryt92.j2agent.tools;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class WebTool {

    private final RestTemplate restTemplate;

    public WebTool(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Tool(name = "view_website_content", description = "访问传入的URL，返回解析出的网页内容")
    private String viewWebsiteContent(
            @ToolParam(description = "要访问的URL") String url
    ) {
        WebResult webResult = new WebResult();
        try {
            // 设置请求头伪装成浏览器
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            // 发送请求并获取返回数据
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            // 检查是否重定向
            if (responseEntity.getStatusCode().is3xxRedirection()) {
                if (responseEntity.getHeaders().getLocation() == null) {
                    return "";
                }
                String redirectUrl = responseEntity.getHeaders().getLocation().toString();
                responseEntity = restTemplate.exchange(redirectUrl, HttpMethod.GET, entity, String.class);
            }
            String response = responseEntity.getBody();
            // 使用Jsoup解析网页内容
            org.jsoup.nodes.Document doc = Jsoup.parse(response);
            webResult.title = doc.title();
            webResult.content = doc.body().text();
            log.info("webResult : " + JSONObject.toJSONString(webResult));
            return JSONObject.toJSONString(webResult);
        } catch (Exception e) {
            return "";
        }
    }

    @lombok.Data
    class WebResult {
        String title;
        String content;
    }
}